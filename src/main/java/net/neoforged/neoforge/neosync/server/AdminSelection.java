/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ManagedPaths;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

/** Saves a reviewed selection for the next server start; it never changes loaded mods. */
public final class AdminSelection {
    public record Candidate(Path path, String description) {}

    private record Entry(Candidate candidate, ArtifactFiles.Fingerprint fingerprint) {}

    private final Path configPath;
    private final Map<String, Entry> inventory = new LinkedHashMap<>();
    private final JsonObject defaults;
    private final String startupDigest;

    public AdminSelection(Path configPath, List<Candidate> candidates, int gamePort) throws IOException {
        ManagedPaths.directory(configPath.getParent(), true);
        this.configPath = configPath;
        if (candidates.size() > 2048) throw new IOException("The administrator inventory exceeds the file limit.");
        long total = 0;
        for (var candidate : candidates) {
            String name = candidate.path().getFileName().toString();
            if (!name.matches(SyncManifest.FILE_PATTERN) || name.length() > 128) continue;
            var fingerprint = ArtifactFiles.fingerprint(candidate.path(), new DiscoveryCancellation());
            total += fingerprint.size();
            if (total > SyncManifest.MAX_TOTAL_BYTES) throw new IOException("The administrator inventory exceeds the byte limit.");
            if (inventory.putIfAbsent(name, new Entry(candidate, fingerprint)) != null) throw new IOException("Duplicate administrator inventory file.");
        }
        defaults = new JsonObject();
        defaults.addProperty("enabled", false);
        defaults.addProperty("displayName", "NeoSync server");
        defaults.addProperty("mode", "managed-https");
        defaults.addProperty("adminTransport", "https");
        defaults.addProperty("adminPort", 6742);
        defaults.addProperty("bindAddress", "0.0.0.0");
        defaults.addProperty("port", 8443);
        defaults.addProperty("httpsPort", 8443);
        defaults.addProperty("gamePort", gamePort);
        defaults.add("files", new JsonArray());
        createDefaultConfig();
        startupDigest = SyncManifest.sha256(read());
    }

    private void createDefaultConfig() throws IOException {
        try {
            Files.writeString(configPath, new GsonBuilder().setPrettyPrinting().create().toJson(defaults) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException ignored) {
            // An existing administrator configuration must retain its exact bytes.
        }
    }

    public synchronized ServerTransport transport() throws IOException {
        return ServerTransport.parse(parse(read()));
    }

    public synchronized JsonObject state() throws IOException {
        byte[] bytes = read();
        var config = parse(bytes);
        var selected = selections(config);
        var transport = ServerTransport.parse(config);
        var result = new JsonObject();
        result.addProperty("revision", SyncManifest.sha256(bytes));
        result.addProperty("restartRequired", !startupDigest.equals(SyncManifest.sha256(bytes)));
        result.addProperty("enabled", SyncJson.bool(config.get("enabled")));
        result.addProperty("transport", transport.insecure() ? "http" : "https");
        result.addProperty("adminTransport", transport.adminTransport());
        result.addProperty("adminPort", transport.adminPort());
        result.addProperty("manifestPort", transport.advertisedPort());
        result.addProperty("displayName", config.has("displayName") ? SyncJson.string(config.get("displayName"), 128) : "NeoSync server");
        var files = new JsonArray();
        for (var entry : inventory.entrySet()) {
            var file = new JsonObject();
            file.addProperty("fileName", entry.getKey());
            file.addProperty("description", entry.getValue().candidate().description());
            file.addProperty("sha256", entry.getValue().fingerprint().sha256());
            file.addProperty("size", entry.getValue().fingerprint().size());
            var selection = selected.remove(entry.getKey());
            file.addProperty("selected", selection != null);
            String source = "modrinth";
            if (selection != null && selection.has("sources")) {
                source = SyncManifest.parseSources(selection.get("sources")).stream().anyMatch(s -> s.type().equals("server")) ? "server" : "configured";
            }
            file.addProperty("source", source);
            files.add(file);
        }
        result.add("files", files);
        var missing = new JsonArray();
        selected.keySet().stream().sorted().forEach(missing::add);
        result.add("missingSelections", missing);
        return result;
    }

    public synchronized void save(byte[] request) throws IOException {
        var input = SyncJson.object(SyncJson.parse(request, SyncManifest.MAX_BYTES), Set.of("revision", "enabled", "displayName", "files"), Set.of("transport", "adminTransport"));
        String expected = SyncJson.matching(input.get("revision"), 64, SyncManifest.HASH_PATTERN);
        byte[] original = read();
        if (!SyncManifest.sha256(original).equals(expected)) throw new IOException("The configuration changed. Reload the panel before saving.");
        var config = parse(original);
        var previous = selections(config);
        var transport = ServerTransport.parse(config);
        if (input.has("transport")) {
            String requested = ServerTransport.protocol(SyncJson.string(input.get("transport"), 8));
            if (requested.equals("http") != transport.insecure()) {
                boolean http = requested.equals("http");
                config.addProperty("mode", http ? "http" : "managed-https");
                config.addProperty("port", http ? 8080 : 8443);
                config.remove(http ? "httpsPort" : "httpPort");
                config.addProperty(http ? "httpPort" : "httpsPort", http ? 8080 : 8443);
            }
        }
        if (input.has("adminTransport"))
            config.addProperty("adminTransport", ServerTransport.protocol(SyncJson.string(input.get("adminTransport"), 8)));
        var files = new JsonArray();
        var names = new HashSet<String>();
        boolean hosting = false;
        for (var item : SyncJson.array(input.get("files"), 0, 2048)) {
            var selected = SyncJson.object(item, Set.of("fileName", "sha256", "source"), Set.of("authoredByAdministrator", "exclusiveToServer", "distributionRights"));
            String name = SyncJson.matching(selected.get("fileName"), 128, SyncManifest.FILE_PATTERN);
            var entry = inventory.get(name);
            if (entry == null || !names.add(name)) throw new IOException("Select each file once from the loaded server inventory.");
            String hash = SyncJson.matching(selected.get("sha256"), 64, SyncManifest.HASH_PATTERN);
            if (!hash.equals(entry.fingerprint().sha256()) || !entry.fingerprint().equals(ArtifactFiles.fingerprint(entry.candidate().path(), new DiscoveryCancellation())))
                throw new IOException("A selected file changed. Restart the server and review the new inventory.");
            String source = SyncJson.string(selected.get("source"), 16);
            var selection = new JsonObject();
            selection.addProperty("fileName", name);
            switch (source) {
                case "modrinth" -> selection.addProperty("resolveProviders", true);
                case "server" -> {
                    var sources = new JsonArray();
                    var provided = new JsonObject();
                    provided.addProperty("type", "server");
                    sources.add(provided);
                    selection.add("sources", sources);
                    var declaration = new JsonObject();
                    for (String key : List.of("authoredByAdministrator", "exclusiveToServer", "distributionRights"))
                        declaration.addProperty(key, SyncJson.bool(selected.get(key)));
                    declaration.addProperty("sha256", hash);
                    selection.add("hosting", declaration);
                    new HostingPolicy(true, SyncManifest.MAX_TOTAL_BYTES, 8, 8 * 1024 * 1024, 120).validateSelection(selection, hash);
                    hosting = true;
                }
                case "configured" -> {
                    var existing = previous.get(name);
                    if (existing == null || !existing.has("sources") || SyncManifest.parseSources(existing.get("sources")).stream().anyMatch(s -> s.type().equals("server")))
                        throw new IOException("No existing external source is configured for that file.");
                    selection.add("sources", existing.get("sources").deepCopy());
                }
                default -> throw new IOException("Select Modrinth, an existing external source, or eligible server hosting.");
            }
            files.add(selection);
        }
        config.add("files", files);
        config.addProperty("enabled", SyncJson.bool(input.get("enabled")));
        config.addProperty("displayName", SyncJson.string(input.get("displayName"), 128));
        for (var entry : defaults.entrySet()) {
            if (entry.getKey().equals("httpsPort") && config.has("mode") && config.get("mode").getAsString().equals("http")) continue;
            if (!config.has(entry.getKey())) {
                if (entry.getKey().equals("port") && config.has("mode") && config.get("mode").getAsString().equals("http")) config.addProperty("port", 8080);
                else config.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        ServerTransport.parse(config);
        var policy = config.has("hosting") ? config.getAsJsonObject("hosting").deepCopy() : new JsonObject();
        policy.addProperty("enabled", hosting);
        HostingPolicy.parse(policy);
        config.add("hosting", policy);
        byte[] output = config.toString().getBytes(StandardCharsets.UTF_8);
        if (output.length > SyncManifest.MAX_BYTES) throw new IOException("The selection exceeds the configuration limit.");
        Path temporary = configPath.resolveSibling("neosync-selection-" + AdminSecrets.randomToken() + ".tmp");
        try (var lockFile = FileChannel.open(configPath.resolveSibling("neosync-selection.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var lock = lockFile.tryLock()) {
            if (lock == null || !SyncManifest.sha256(read()).equals(expected)) throw new IOException("The configuration changed. Reload the panel before saving.");
            try (var file = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = java.nio.ByteBuffer.wrap(output);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            if (Files.isSymbolicLink(configPath)) throw new IOException("The configuration must not be a symbolic link.");
            Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] read() throws IOException {
        ManagedPaths.directory(configPath.getParent(), false);
        if (!Files.exists(configPath, LinkOption.NOFOLLOW_LINKS)) return defaults.toString().getBytes(StandardCharsets.UTF_8);
        if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS) || Files.size(configPath) > SyncManifest.MAX_BYTES)
            throw new IOException("The server configuration path is unsafe or oversized.");
        try (var input = Files.newInputStream(configPath, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(SyncManifest.MAX_BYTES + 1);
            if (bytes.length > SyncManifest.MAX_BYTES) throw new IOException("The server configuration exceeds its size limit.");
            return bytes;
        }
    }

    private static JsonObject parse(byte[] bytes) throws IOException {
        return SyncJson.object(SyncJson.parse(bytes, SyncManifest.MAX_BYTES), Set.of("enabled"),
                Set.of("mode", "bindAddress", "port", "httpsPort", "httpPort", "adminTransport", "adminPort", "gamePort", "displayName", "files", "keyStore", "passwordEnvironment", "hosting"));
    }

    private static Map<String, JsonObject> selections(JsonObject config) throws IOException {
        var result = new HashMap<String, JsonObject>();
        if (!config.has("files")) return result;
        for (var item : SyncJson.array(config.get("files"), 0, 2048)) {
            var file = SyncJson.object(item, Set.of("fileName"), Set.of("sources", "resolveProviders", "hosting"));
            String name = SyncJson.matching(file.get("fileName"), 128, SyncManifest.FILE_PATTERN);
            if (result.putIfAbsent(name, file) != null) throw new IOException("Duplicate configured client file.");
        }
        return result;
    }
}
