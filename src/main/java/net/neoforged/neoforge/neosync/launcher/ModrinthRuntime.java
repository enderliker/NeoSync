/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.launcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipFile;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ManagedPaths;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

/** Verifies the standalone installer's readiness record without downloading launch resources. */
final class ModrinthRuntime {
    private static final int DESCRIPTOR_LIMIT = 4 * 1024 * 1024;
    private static final int INDEX_LIMIT = 8 * 1024 * 1024;
    private static final long FILE_LIMIT = 512L * 1024 * 1024;
    private static final long TOTAL_LIMIT = 8L * 1024 * 1024 * 1024;
    private static final Set<String> FILE_FIELDS = Set.of("path", "size", "sha256");

    private ModrinthRuntime() {}

    record Verified(Path client, long size, String sha256, int protocolVersion) {}

    static Verified verify(Path root, String versionId, JsonObject metadata, Path libraryDirectory, DiscoveryCancellation token) throws IOException {
        try {
            return verifyRecord(root, versionId, metadata, libraryDirectory, token);
        } catch (IllegalArgumentException | ArithmeticException | NullPointerException failure) {
            throw new IOException("The Modrinth runtime readiness record is malformed. Run the NeoSync installer again.", failure);
        }
    }

    private static Verified verifyRecord(Path root, String versionId, JsonObject metadata, Path libraryDirectory, DiscoveryCancellation token) throws IOException {
        Path meta = ManagedPaths.directory(root.resolve("meta"), false);
        Path version = ManagedPaths.directory(meta.resolve("versions").resolve(versionId), false);
        var descriptor = SyncJson.object(SyncJson.parse(read(version.resolve("neosync-runtime.json"), DESCRIPTOR_LIMIT), DESCRIPTOR_LIMIT),
                Set.of("schemaVersion", "versionId", "minecraftVersion", "platform", "architecture", "protocolVersion", "metadataSha256",
                        "libraryDirectory", "client", "assetIndex", "resources", "localLibraries"),
                Set.of());
        if (SyncJson.number(descriptor.get("schemaVersion"), 1, 1) != 1
                || !versionId.equals(SyncJson.string(descriptor.get("versionId"), 256))
                || !"1.21.1".equals(SyncJson.string(descriptor.get("minecraftVersion"), 32))
                || !platform().equals(SyncJson.string(descriptor.get("platform"), 32))
                || !architecture().equals(SyncJson.string(descriptor.get("architecture"), 32)))
            throw incomplete();
        Path recordedLibraries = Path.of(SyncJson.string(descriptor.get("libraryDirectory"), 4096));
        Path expectedLibraries = root.resolve("neosync/runtime").resolve(versionId.substring("1.21.1-".length())).resolve("libraries");
        if (!recordedLibraries.isAbsolute() || !recordedLibraries.equals(recordedLibraries.normalize())
                || !recordedLibraries.equals(libraryDirectory) || !recordedLibraries.equals(expectedLibraries))
            throw incomplete();
        ManagedPaths.directory(recordedLibraries, false);
        String expectedMetadata = SyncJson.matching(descriptor.get("metadataSha256"), 64, SyncManifest.HASH_PATTERN);
        if (!expectedMetadata.equals(SyncManifest.sha256(read(version.resolve(versionId + ".json"), SyncManifest.MAX_BYTES))))
            throw incomplete();
        int protocol = (int) SyncJson.number(descriptor.get("protocolVersion"), 767, 767);
        var budget = new Budget();
        var resources = verifyFiles(meta, descriptor.get("resources"), budget, token);
        var local = verifyFiles(recordedLibraries, descriptor.get("localLibraries"), budget, token);
        if (local.isEmpty()) throw incomplete();
        var client = file(meta, descriptor.get("client"));
        if (!client.path().equals("versions/" + versionId + "/" + versionId + ".jar")) throw incomplete();
        verifyFile(client, budget, token);
        var downloads = SyncJson.object(metadata.get("downloads"), Set.of("client"), Set.of("client_mappings", "server", "server_mappings"));
        verifyOfficial(client, downloads.get("client"), token);
        try (var jar = new ZipFile(client.absolute().toFile())) {
            var entry = jar.getEntry("version.json");
            if (entry == null || entry.getSize() > 8192) throw incomplete();
            try (var input = jar.getInputStream(entry)) {
                var gameVersion = SyncJson.parse(input.readNBytes(8193), 8192);
                if (!gameVersion.isJsonObject() || SyncJson.number(gameVersion.getAsJsonObject().get("protocol_version"), 767, 767) != protocol)
                    throw incomplete();
            }
        }
        for (var element : SyncJson.array(metadata.get("libraries"), 0, 4096)) {
            if (!element.isJsonObject()) throw incomplete();
            var library = element.getAsJsonObject();
            if (!applies(library.get("rules"))) continue;
            if (!library.has("downloads") || !library.get("downloads").isJsonObject()) throw incomplete();
            var artifact = library.getAsJsonObject("downloads").get("artifact");
            if (artifact == null || !artifact.isJsonObject()) throw incomplete();
            String path = "libraries/" + SyncJson.string(artifact.getAsJsonObject().get("path"), 4096);
            var resource = resources.get(path);
            if (resource == null) throw incomplete();
            verifyOfficial(resource, artifact, token);
        }
        String loader = gameArgument(metadata, "--fml.neoForgeVersion");
        for (String suffix : java.util.List.of("client", "universal"))
            if (!local.containsKey("net/neoforged/neoforge/" + loader + "/neoforge-" + loader + "-" + suffix + ".jar")) throw incomplete();
        var index = file(meta, descriptor.get("assetIndex"));
        var indexMetadata = metadata.getAsJsonObject("assetIndex");
        if (indexMetadata == null || !index.path().equals("assets/indexes/" + SyncJson.string(indexMetadata.get("id"), 128) + ".json")) throw incomplete();
        if (index.size() > INDEX_LIMIT) throw incomplete();
        verifyFile(index, budget, token);
        verifyOfficial(index, indexMetadata, token);
        var assetDocument = SyncJson.object(SyncJson.parse(read(index.absolute(), INDEX_LIMIT), INDEX_LIMIT), Set.of("objects"), Set.of("virtual", "map_to_resources"));
        if ((assetDocument.has("virtual") && SyncJson.bool(assetDocument.get("virtual")))
                || (assetDocument.has("map_to_resources") && SyncJson.bool(assetDocument.get("map_to_resources"))))
            throw incomplete();
        var assetObjects = assetDocument.getAsJsonObject("objects");
        if (assetObjects.size() > 65536) throw incomplete();
        var assets = new HashMap<String, Long>();
        for (var element : assetObjects.entrySet()) {
            var asset = SyncJson.object(element.getValue(), Set.of("hash", "size"), Set.of());
            String sha1 = SyncJson.matching(asset.get("hash"), 40, "[0-9a-f]{40}");
            long size = SyncJson.number(asset.get("size"), 0, FILE_LIMIT);
            Long previous = assets.putIfAbsent(sha1, size);
            if (previous != null && previous != size) throw incomplete();
            if (previous == null) {
                Path path = resolve(meta, "assets/objects/" + sha1.substring(0, 2) + "/" + sha1);
                budget.add(path, size);
                if (Files.size(path) != size || !sha1.equals(hash(path, "SHA-1", size, token))) throw incomplete();
            }
        }
        if (metadata.has("logging")) {
            var logging = metadata.getAsJsonObject("logging").getAsJsonObject("client");
            if (logging != null) {
                var log = logging.getAsJsonObject("file");
                var resource = resources.get("log_configs/" + SyncJson.string(log.get("id"), 256));
                if (resource == null) throw incomplete();
                verifyOfficial(resource, log, token);
            }
        }
        ManagedPaths.directory(meta.resolve("natives").resolve(versionId), false);
        token.check();
        return new Verified(client.absolute(), client.size(), client.sha256(), protocol);
    }

    static void materialize(Verified verified, Path target, String targetId, Path natives, DiscoveryCancellation token) throws IOException {
        Path client = target.resolve(targetId + ".jar");
        if (Files.exists(client, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(client) != verified.size() || !verified.sha256().equals(hash(client, "SHA-256", verified.size(), token))) throw incomplete();
        } else {
            if (Files.getFileStore(target).getUsableSpace() < verified.size() + 64L * 1024 * 1024)
                throw new IOException("Not enough free disk space to prepare this Modrinth instance.");
            Path stage = target.resolve(".neosync-" + UUID.randomUUID());
            try {
                try (var input = Files.newInputStream(verified.client(), LinkOption.NOFOLLOW_LINKS);
                        var output = Files.newOutputStream(stage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    long total = 0;
                    while ((read = input.read(buffer)) != -1) {
                        token.check();
                        total += read;
                        if (total > verified.size()) throw incomplete();
                        output.write(buffer, 0, read);
                    }
                }
                if (!verified.sha256().equals(hash(stage, "SHA-256", verified.size(), token))) throw incomplete();
                token.check();
                Files.createLink(client, stage);
            } finally {
                Files.deleteIfExists(stage);
            }
        }
        ManagedPaths.directory(natives, true);
    }

    static String gameDirectoryArgument(Path instance, Path game) throws IOException {
        if (!game.isAbsolute() || !game.equals(game.normalize())) throw incomplete();
        Path relative = instance.relativize(game);
        String suffix = relative.toString().replace('\\', '/');
        if (suffix.isEmpty() || suffix.contains("${") || suffix.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || !instance.resolve(relative).normalize().equals(game))
            throw new IOException("The prepared game directory cannot be represented by Modrinth App. Use a NeoSync profile inside this launcher installation.");
        return "${game_directory}/" + suffix;
    }

    private record FileRecord(String path, Path absolute, long size, String sha256) {}

    private static Map<String, FileRecord> verifyFiles(Path root, JsonElement value, Budget budget, DiscoveryCancellation token) throws IOException {
        var files = new HashMap<String, FileRecord>();
        for (var entry : SyncJson.array(value, 0, 4096)) {
            var file = file(root, entry);
            if (files.putIfAbsent(file.path(), file) != null) throw incomplete();
            verifyFile(file, budget, token);
        }
        return files;
    }

    private static FileRecord file(Path root, JsonElement value) throws IOException {
        var record = SyncJson.object(value, FILE_FIELDS, Set.of());
        String path = SyncJson.string(record.get("path"), 4096);
        return new FileRecord(path, resolve(root, path), SyncJson.number(record.get("size"), 0, FILE_LIMIT),
                SyncJson.matching(record.get("sha256"), 64, SyncManifest.HASH_PATTERN));
    }

    private static Path resolve(Path root, String value) throws IOException {
        if (value.contains("\\") || value.contains(":") || value.contains("${") || value.startsWith("/")
                || value.codePoints().anyMatch(Character::isISOControl))
            throw incomplete();
        for (String part : value.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw incomplete();
        Path path = root.resolve(value);
        if (!path.normalize().startsWith(root)) throw incomplete();
        ManagedPaths.directory(path.getParent(), false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw incomplete();
        return path;
    }

    private static void verifyFile(FileRecord file, Budget budget, DiscoveryCancellation token) throws IOException {
        budget.add(file.absolute(), file.size());
        if (Files.size(file.absolute()) != file.size() || !file.sha256().equals(hash(file.absolute(), "SHA-256", file.size(), token))) throw incomplete();
    }

    private static void verifyOfficial(FileRecord file, JsonElement value, DiscoveryCancellation token) throws IOException {
        if (value == null || !value.isJsonObject()) throw incomplete();
        var object = value.getAsJsonObject();
        long size = SyncJson.number(object.get("size"), 0, FILE_LIMIT);
        String sha1 = SyncJson.matching(object.get("sha1"), 40, "[0-9a-f]{40}");
        if (size != file.size() || !sha1.equals(hash(file.absolute(), "SHA-1", size, token))) throw incomplete();
    }

    private static String hash(Path path, String algorithm, long expectedSize, DiscoveryCancellation token) throws IOException {
        ManagedPaths.directory(path.getParent(), false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != expectedSize || expectedSize > FILE_LIMIT) throw incomplete();
        try {
            var digest = MessageDigest.getInstance(algorithm);
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    token.check();
                    total += read;
                    if (total > expectedSize) throw incomplete();
                    digest.update(buffer, 0, read);
                }
                if (total != expectedSize) throw incomplete();
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] read(Path path, int limit) throws IOException {
        ManagedPaths.directory(path.getParent(), false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) throw incomplete();
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw incomplete();
            return bytes;
        }
    }

    private static boolean applies(JsonElement value) throws IOException {
        if (value == null) return true;
        boolean allowed = false;
        for (var element : SyncJson.array(value, 0, 32)) {
            var rule = SyncJson.object(element, Set.of("action"), Set.of("os", "features"));
            String action = SyncJson.matching(rule.get("action"), 16, "allow|disallow");
            boolean matches = true;
            if (rule.has("features")) throw incomplete();
            if (rule.has("os")) {
                var os = SyncJson.object(rule.get("os"), Set.of(), Set.of("name", "arch", "version"));
                if (os.has("name")) matches &= platform().equals(SyncJson.string(os.get("name"), 32));
                if (os.has("arch")) {
                    String arch = SyncJson.string(os.get("arch"), 32);
                    matches &= arch.equals(System.getProperty("os.arch")) || arch.equals(architecture());
                }
            }
            if (matches) allowed = action.equals("allow");
        }
        return allowed;
    }

    private static String gameArgument(JsonObject metadata, String name) throws IOException {
        var arguments = metadata.getAsJsonObject("arguments");
        if (arguments == null) throw incomplete();
        var game = SyncJson.array(arguments.get("game"), 0, 1024);
        for (int i = 0; i + 1 < game.size(); i++)
            if (game.get(i).isJsonPrimitive() && game.get(i).getAsString().equals(name)) return SyncJson.string(game.get(i + 1), 256);
        throw incomplete();
    }

    private static String platform() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) return "windows";
        if (os.contains("linux")) return "linux";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        throw incomplete();
    }

    private static String architecture() throws IOException {
        return switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "arm", "arm32" -> "arm";
            default -> throw incomplete();
        };
    }

    private static IOException incomplete() {
        return new IOException("The Modrinth runtime is missing or changed. Run the NeoSync installer again before preparing this profile.");
    }

    private static final class Budget {
        private long total;
        private final Map<Path, Long> files = new HashMap<>();

        void add(Path path, long size) throws IOException {
            Long previous = files.putIfAbsent(path, size);
            if (previous != null) {
                if (previous != size) throw incomplete();
                return;
            }
            total = Math.addExact(total, size);
            if (total > TOTAL_LIMIT) throw incomplete();
        }
    }
}
