/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neosync.installer;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/** Stages a complete launcher runtime before exposing its metadata or inventory entry. */
final class ModrinthRuntimeSetup implements AutoCloseable {
    private static final long TOTAL_LIMIT = 8L * 1024 * 1024 * 1024;
    private static final int INDEX_LIMIT = 8 * 1024 * 1024;
    private static final Set<String> HOSTS = Set.of("libraries.minecraft.net", "resources.download.minecraft.net", "piston-data.mojang.com", "piston-meta.mojang.com", "launcher.mojang.com", "launchermeta.mojang.com");

    @FunctionalInterface
    interface Downloader {
        void download(URI uri, Path target, long size) throws IOException;
    }

    private record Resource(Path path, Path source, String sha1, long size, URI uri) {}

    private final Path metadata;
    private final Path stage;
    private final Downloader downloader;
    private final Consumer<String> progress;
    private final Map<Path, Resource> resources = new LinkedHashMap<>();
    private final List<Path> published = new ArrayList<>();
    private final List<Path> directories = new ArrayList<>();
    private final String versionId;
    private final JsonObject descriptor;
    private final byte[] versionBytes;
    private long total;
    private boolean committed;

    private ModrinthRuntimeSetup(Path root, String versionId, Downloader downloader, byte[] versionBytes, Consumer<String> progress) throws IOException {
        this.metadata = root.resolve("meta");
        this.versionId = versionId;
        this.downloader = downloader;
        this.progress = progress;
        this.versionBytes = versionBytes;
        this.descriptor = new JsonObject();
        this.stage = root.resolve(".neosync-runtime-" + UUID.randomUUID());
        InstallerFiles.directory(stage, true);
    }

    static ModrinthRuntimeSetup prepare(Path root, Path installation, String versionId, JsonObject merged, JsonObject vanilla, Map<Path, Path> libraries, Downloader downloader, Consumer<String> progress) throws Exception {
        Path ownedRuntime = root.resolve("neosync/runtime").resolve(versionId.substring("1.21.1-".length())).toAbsolutePath().normalize();
        if (!installation.toAbsolutePath().normalize().equals(ownedRuntime)) throw new IOException("The Modrinth runtime must belong to this NeoSync installation.");
        byte[] versionBytes = InstallerFiles.encode(merged);
        var setup = new ModrinthRuntimeSetup(root, versionId, downloader, versionBytes, progress);
        try {
            Path clientPath = Path.of("versions", versionId, versionId + ".jar");
            Resource client = setup.add(clientPath, installation.resolve("versions/1.21.1/1.21.1.jar"), vanilla.getAsJsonObject("downloads").getAsJsonObject("client"));
            String indexId = vanilla.getAsJsonObject("assetIndex").get("id").getAsString();
            if (!indexId.matches("[A-Za-z0-9._-]{1,128}")) throw new IOException("Invalid Minecraft asset index identity.");
            Path indexPath = Path.of("assets/indexes", indexId + ".json");
            Resource index = setup.add(indexPath, installation.resolve(indexPath), vanilla.getAsJsonObject("assetIndex"));
            if (index.size > INDEX_LIMIT) throw new IOException("The Minecraft asset index exceeds its limit.");
            JsonArray required = new JsonArray();
            JsonArray local = new JsonArray();
            if (merged.getAsJsonArray("libraries").size() > 4096) throw new IOException("The Minecraft library count exceeds its limit.");
            for (var value : merged.getAsJsonArray("libraries")) {
                var library = value.getAsJsonObject();
                if (!applies(library)) continue;
                if (library.has("natives")) throw new IOException("Legacy extracted Minecraft natives are unsupported for this runtime.");
                var artifact = library.getAsJsonObject("downloads").getAsJsonObject("artifact");
                Path path = InstallerFiles.relativeJar(artifact.get("path").getAsString());
                setup.add(Path.of("libraries").resolve(path), installation.resolve("libraries").resolve(path), artifact);
            }
            for (var entry : libraries.entrySet()) {
                Path path = Path.of("libraries").resolve(entry.getKey());
                if (!setup.resources.containsKey(path)) setup.addLocal(path, entry.getValue());
                local.add(identity(entry.getKey(), entry.getValue()));
            }
            var logging = vanilla.getAsJsonObject("logging").getAsJsonObject("client").getAsJsonObject("file");
            String logId = logging.get("id").getAsString();
            if (!logId.matches("[A-Za-z0-9._-]{1,128}")) throw new IOException("Invalid Minecraft logging configuration identity.");
            setup.add(Path.of("log_configs", logId), installation.resolve("assets/log_configs").resolve(logId), logging);
            setup.materialize(List.copyOf(setup.resources.values()));
            JsonObject assetDocument = parse(setup.available(index), INDEX_LIMIT);
            JsonObject assets = assetDocument.getAsJsonObject("objects");
            if (assets == null || assets.size() > 65536) throw new IOException("The Minecraft asset count exceeds its limit.");
            if ((assetDocument.has("virtual") && assetDocument.get("virtual").getAsBoolean()) || (assetDocument.has("map_to_resources") && assetDocument.get("map_to_resources").getAsBoolean()))
                throw new IOException("Legacy Minecraft asset layouts are unsupported for this runtime.");
            for (Resource resource : setup.resources.values()) if (!resource.path.equals(clientPath) && !resource.path.equals(indexPath)) required.add(identity(resource.path, setup.available(resource)));
            List<Resource> objects = new ArrayList<>();
            for (var entry : assets.entrySet()) {
                JsonObject object = entry.getValue().getAsJsonObject();
                String hash = object.get("hash").getAsString();
                if (!hash.matches("[0-9a-f]{40}")) throw new IOException("Invalid Minecraft asset hash.");
                Path path = Path.of("assets/objects", hash.substring(0, 2), hash);
                JsonObject artifact = new JsonObject();
                artifact.addProperty("sha1", hash);
                artifact.addProperty("size", object.get("size").getAsLong());
                artifact.addProperty("url", "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash);
                if (!setup.resources.containsKey(path)) objects.add(setup.add(path, installation.resolve(path), artifact));
                else if (setup.resources.get(path).size != artifact.get("size").getAsLong()) throw new IOException("Conflicting Minecraft asset identities.");
            }
            setup.materialize(objects);
            int protocol = protocol(setup.available(client));
            if (protocol != 767) throw new IOException("The Minecraft runtime has an unexpected protocol version.");
            setup.descriptor.addProperty("schemaVersion", 1);
            setup.descriptor.addProperty("versionId", versionId);
            setup.descriptor.addProperty("minecraftVersion", "1.21.1");
            setup.descriptor.addProperty("platform", platform());
            setup.descriptor.addProperty("architecture", architecture());
            setup.descriptor.addProperty("protocolVersion", protocol);
            Path stagedMetadata = setup.stage.resolve("version.json");
            Files.write(stagedMetadata, versionBytes, StandardOpenOption.CREATE_NEW);
            setup.descriptor.addProperty("metadataSha256", InstallerFiles.hash(stagedMetadata, "SHA-256"));
            setup.descriptor.addProperty("libraryDirectory", installation.resolve("libraries").toAbsolutePath().normalize().toString());
            setup.descriptor.add("client", identity(clientPath, setup.available(client)));
            setup.descriptor.add("assetIndex", identity(indexPath, setup.available(index)));
            setup.descriptor.add("resources", required);
            setup.descriptor.add("localLibraries", local);
            return setup;
        } catch (Exception failure) {
            setup.close();
            throw failure;
        }
    }

    int protocol() {
        return descriptor.get("protocolVersion").getAsInt();
    }

    void publish() throws IOException {
        progress.accept("Publishing the verified Modrinth runtime...");
        for (Resource resource : resources.values()) {
            Path target = metadata.resolve(resource.path);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) verify(target, resource);
            else {
                directory(target.getParent());
                Files.createLink(target, stage.resolve(resource.path));
                published.add(target);
            }
        }
        directory(metadata.resolve("natives").resolve(versionId));
        publishRecord(metadata.resolve("versions").resolve(versionId).resolve(versionId + ".json"), versionBytes, InstallerFiles.JSON_LIMIT);
        byte[] descriptorBytes = (new GsonBuilder().setPrettyPrinting().create().toJson(descriptor) + "\n").getBytes(StandardCharsets.UTF_8);
        publishRecord(metadata.resolve("versions").resolve(versionId).resolve("neosync-runtime.json"), descriptorBytes, 4 * 1024 * 1024);
    }

    void commit() {
        committed = true;
    }

    private void publishRecord(Path target, byte[] bytes, int limit) throws IOException {
        if (bytes.length > limit) throw new IOException("The runtime readiness record exceeds its limit.");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!java.util.Arrays.equals(InstallerFiles.read(target, limit), bytes)) throw new IOException("The existing NeoSync runtime was edited. It was not overwritten.");
        } else {
            directory(target.getParent());
            InstallerFiles.publish(target, bytes);
            published.add(target);
        }
    }

    private void directory(Path path) throws IOException {
        List<Path> missing = new ArrayList<>();
        for (Path next = path; next != null && !Files.exists(next, LinkOption.NOFOLLOW_LINKS); next = next.getParent()) missing.add(next);
        InstallerFiles.directory(path, true);
        for (int i = missing.size() - 1; i >= 0; i--) directories.add(missing.get(i));
    }

    private Resource add(Path path, Path source, JsonObject artifact) throws IOException {
        if (path.isAbsolute() || !path.equals(path.normalize())) throw new IOException("Invalid Minecraft resource path.");
        for (Path segment : path) if (segment.toString().equals("..") || segment.toString().equals(".") || segment.toString().isEmpty()) throw new IOException("Invalid Minecraft resource path.");
        String sha1 = artifact.get("sha1").getAsString();
        long size = artifact.get("size").getAsLong();
        if (!sha1.matches("[0-9a-f]{40}") || size < 0 || size > InstallerFiles.LIBRARY_LIMIT) throw new IOException("Invalid Minecraft resource identity.");
        URI uri = null;
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) && artifact.has("url") && !artifact.get("url").getAsString().isEmpty()) uri = approved(artifact.get("url").getAsString());
        Resource resource = new Resource(path, source, sha1, size, uri);
        Resource previous = resources.putIfAbsent(path, resource);
        if (previous != null) {
            if (previous.size != size || !previous.sha1.equals(sha1)) throw new IOException("Conflicting Minecraft resource identities.");
            return previous;
        }
        total += size;
        if (total > TOTAL_LIMIT) throw new IOException("The complete Minecraft runtime exceeds its limit.");
        return resource;
    }

    private void addLocal(Path path, Path source) throws IOException {
        JsonObject artifact = new JsonObject();
        artifact.addProperty("sha1", InstallerFiles.hash(source, "SHA-1"));
        artifact.addProperty("size", Files.size(source));
        add(path, source, artifact);
    }

    private void materialize(List<Resource> files) throws Exception {
        long required = 64L * 1024 * 1024;
        for (Resource resource : files) if (!Files.exists(metadata.resolve(resource.path), LinkOption.NOFOLLOW_LINKS)) required += resource.size;
        if (Files.getFileStore(stage).getUsableSpace() < required) throw new IOException("There is not enough free space to stage the complete Minecraft runtime.");
        progress.accept("Preparing " + files.size() + " Minecraft runtime files for Modrinth App...");
        var completed = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var completion = new ExecutorCompletionService<Void>(executor);
            Set<Future<Void>> active = new java.util.HashSet<>();
            var remaining = files.iterator();
            try {
                while (remaining.hasNext() || !active.isEmpty()) {
                    while (remaining.hasNext() && active.size() < 8) {
                        Resource resource = remaining.next();
                        active.add(completion.submit(() -> {
                            materialize(resource);
                            return null;
                        }));
                    }
                    var result = completion.take();
                    active.remove(result);
                    result.get();
                    int count = completed.incrementAndGet();
                    if (count == files.size() || count % 100 == 0) progress.accept("Verified " + count + " of " + files.size() + " Minecraft runtime files.");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Minecraft runtime preparation was interrupted.", failure);
            } catch (java.util.concurrent.ExecutionException failure) {
                if (failure.getCause() instanceof IOException error) throw error;
                throw new IOException("Minecraft runtime preparation failed.", failure.getCause());
            } finally {
                for (var future : active) future.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    private void materialize(Resource resource) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Minecraft runtime preparation was interrupted.");
        Path target = metadata.resolve(resource.path);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) verify(target, resource);
        else {
            Path staged = stage.resolve(resource.path);
            InstallerFiles.directory(staged.getParent(), true);
            if (Files.exists(resource.source, LinkOption.NOFOLLOW_LINKS)) {
                verify(resource.source, resource);
                Files.copy(resource.source, staged);
            } else {
                if (resource.uri == null) throw new IOException("A required local Minecraft runtime resource is missing.");
                downloader.download(resource.uri, staged, resource.size);
            }
            verify(staged, resource);
        }
    }

    private Path available(Resource resource) {
        Path target = metadata.resolve(resource.path);
        return Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? target : stage.resolve(resource.path);
    }

    private static void verify(Path file, Resource resource) throws IOException {
        if (!resource.sha1.equals(InstallerFiles.hash(file, "SHA-1")) || Files.size(file) != resource.size)
            throw new IOException("A Minecraft runtime resource failed verification: " + file.getFileName());
    }

    private static JsonObject identity(Path path, Path file) throws IOException {
        JsonObject identity = new JsonObject();
        identity.addProperty("path", path.toString().replace('\\', '/'));
        identity.addProperty("size", Files.size(file));
        identity.addProperty("sha256", InstallerFiles.hash(file, "SHA-256"));
        return identity;
    }

    private static JsonObject parse(Path file, int limit) throws IOException {
        try {
            return JsonParser.parseString(new String(InstallerFiles.read(file, limit), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Invalid Minecraft runtime metadata.", error);
        }
    }

    private static int protocol(Path client) throws IOException {
        try (var jar = new JarFile(client.toFile())) {
            var entry = jar.getJarEntry("version.json");
            if (entry == null || entry.getSize() > 8192) throw new IOException("The Minecraft client has no bounded version identity.");
            try (var input = jar.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(8193);
                if (bytes.length > 8192) throw new IOException("The Minecraft client version identity exceeds its limit.");
                return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().get("protocol_version").getAsInt();
            }
        } catch (RuntimeException error) {
            throw new IOException("Invalid Minecraft client version identity.", error);
        }
    }

    static boolean applies(JsonObject library) throws IOException {
        if (!library.has("rules")) return true;
        boolean allowed = false;
        for (var entry : library.getAsJsonArray("rules")) {
            JsonObject rule = entry.getAsJsonObject();
            if (rule.has("features")) throw new IOException("Unsupported Minecraft library feature rule.");
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name")) matches &= platform().equals(os.get("name").getAsString());
                if (os.has("arch")) matches &= architecture().equals(os.get("arch").getAsString()) || System.getProperty("os.arch").equals(os.get("arch").getAsString());
            }
            String action = rule.get("action").getAsString();
            if (!action.equals("allow") && !action.equals("disallow")) throw new IOException("Invalid Minecraft library rule.");
            if (matches) allowed = action.equals("allow");
        }
        return allowed;
    }

    static String platform() throws IOException {
        String platform = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (platform.contains("mac") || platform.contains("darwin")) return "osx";
        if (platform.contains("win")) return "windows";
        if (platform.contains("linux")) return "linux";
        throw new IOException("Unsupported Minecraft runtime platform.");
    }

    static String architecture() throws IOException {
        return switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "arm64", "aarch64" -> "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "arm", "arm32" -> "arm";
            default -> throw new IOException("Unsupported Minecraft runtime architecture.");
        };
    }

    private static URI approved(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getFragment() != null || uri.getQuery() != null)
                throw new IOException("Unapproved Minecraft resource URL.");
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid Minecraft resource URL.", error);
        }
    }

    static void download(URI uri, Path target, long size) throws IOException {
        if (size < 0 || size > InstallerFiles.LIBRARY_LIMIT) throw new IOException("An official Minecraft resource has an invalid size.");
        approved(uri.toString());
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        if (addresses.length == 0) throw new IOException("The official Minecraft resource host could not be resolved.");
        for (InetAddress address : addresses) if (!publicAddress(address)) throw new IOException("The official Minecraft resource host resolved to a non-public address.");
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "NeoSync installer (https://github.com/enderliker/NeoSync)");
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(3).toNanos();
        try {
            if (connection.getResponseCode() != 200) throw new IOException("An official Minecraft resource download failed.");
            if (connection.getContentLengthLong() != -1 && connection.getContentLengthLong() != size) throw new IOException("An official Minecraft resource has an unexpected size.");
            try (var input = connection.getInputStream(); var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536];
                long written = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (written > size || System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) throw new IOException("An official Minecraft resource exceeded its download limits.");
                    output.write(buffer, 0, count);
                }
                if (written != size) throw new IOException("An official Minecraft resource download was incomplete.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            int third = bytes[2] & 255;
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && (second == 168 || second == 0 || (second == 2)))
                    && !(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    && !(first == 203 && second == 0 && third == 113);
        }
        return bytes.length == 16 && (bytes[0] & 0xe0) == 0x20
                && !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x01 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8);
    }

    @Override
    public void close() throws IOException {
        if (!committed) {
            for (int i = published.size() - 1; i >= 0; i--) Files.deleteIfExists(published.get(i));
            for (int i = directories.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(directories.get(i));
                } catch (java.nio.file.DirectoryNotEmptyException ignored) {}
            }
        }
        if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(stage)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
