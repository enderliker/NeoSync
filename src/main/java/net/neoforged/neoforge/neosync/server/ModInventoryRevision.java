/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ManagedPaths;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforge.neosync.protocol.SyncRevision;

public record ModInventoryRevision(String digest, Instant changedAt, List<SyncRevision.Jar> files, List<String> added, List<String> replaced, List<String> removed) {
    private record File(String name, String hash, long size, Instant firstSeenAt) {}

    public ModInventoryRevision {
        files = List.copyOf(files);
        added = List.copyOf(added);
        replaced = List.copyOf(replaced);
        removed = List.copyOf(removed);
    }

    public static ModInventoryRevision update(Path modsDirectory, List<String> selectedFiles, Path record, Instant detectedAt, DiscoveryCancellation token) throws IOException {
        token.check();
        if (selectedFiles.size() > 2048 || new HashSet<>(selectedFiles).size() != selectedFiles.size()) throw new IOException("Invalid client inventory selection.");
        ManagedPaths.directory(modsDirectory, false);
        ManagedPaths.directory(record.getParent(), true);
        var previous = new HashMap<String, File>();
        String previousDigest = "";
        Instant previousChange = detectedAt;
        List<String> previousAdded = List.of();
        List<String> previousReplaced = List.of();
        List<String> previousRemoved = List.of();
        if (Files.exists(record, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS) || Files.size(record) > SyncManifest.MAX_BYTES)
                throw new IOException("The mod inventory record is unsafe or oversized.");
            byte[] bytes;
            try (var input = Files.newInputStream(record, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(SyncManifest.MAX_BYTES + 1);
            }
            var object = SyncJson.object(SyncJson.parse(bytes, SyncManifest.MAX_BYTES),
                    Set.of("schemaVersion", "scope", "inventorySha256", "changedAt", "files", "added", "replaced", "removed"), Set.of());
            SyncJson.number(object.get("schemaVersion"), 1, 1);
            if (!SyncJson.string(object.get("scope"), 32).equals("client-selection")) throw new IOException("Unsupported mod inventory scope.");
            previousDigest = SyncJson.matching(object.get("inventorySha256"), 64, SyncManifest.HASH_PATTERN);
            previousChange = timestamp(object.get("changedAt"));
            for (var entry : SyncJson.array(object.get("files"), 0, 2048)) {
                var file = SyncJson.object(entry, Set.of("fileName", "sha256", "size", "firstSeenAt"), Set.of());
                String name = SyncJson.matching(file.get("fileName"), 128, SyncManifest.FILE_PATTERN);
                var parsed = new File(name, SyncJson.matching(file.get("sha256"), 64, SyncManifest.HASH_PATTERN),
                        SyncJson.number(file.get("size"), 1, SyncManifest.MAX_FILE_BYTES), timestamp(file.get("firstSeenAt")));
                if (previous.putIfAbsent(name, parsed) != null) throw new IOException("Duplicate mod inventory file.");
            }
            if (!previousDigest.equals(digest(previous))) throw new IOException("The mod inventory record digest is inconsistent.");
            var changes = new HashSet<String>();
            previousAdded = changeNames(object.get("added"), previous.keySet(), changes, false);
            previousReplaced = changeNames(object.get("replaced"), previous.keySet(), changes, false);
            previousRemoved = changeNames(object.get("removed"), previous.keySet(), changes, true);
        }
        var current = new HashMap<String, File>();
        long total = 0;
        for (String name : selectedFiles) {
            token.check();
            if (!name.matches(SyncManifest.FILE_PATTERN) || name.length() > 128) throw new IOException("Unsupported mod inventory file name.");
            Path path = modsDirectory.resolve(name);
            var fingerprint = ArtifactFiles.fingerprint(path, token);
            total += fingerprint.size();
            if (current.size() >= 2048 || total > SyncManifest.MAX_TOTAL_BYTES) throw new IOException("The mod inventory exceeds its limits.");
            var old = previous.get(name);
            boolean unchanged = old != null && old.hash().equals(fingerprint.sha256()) && old.size() == fingerprint.size();
            current.put(name, new File(name, fingerprint.sha256(), fingerprint.size(), unchanged ? old.firstSeenAt() : detectedAt));
        }
        String digest = digest(current);
        var added = new ArrayList<String>();
        var replaced = new ArrayList<String>();
        var removed = new ArrayList<String>();
        current.keySet().stream().sorted().forEach(name -> {
            var old = previous.get(name);
            if (old == null) added.add(name);
            else if (!old.hash().equals(current.get(name).hash()) || old.size() != current.get(name).size()) replaced.add(name);
        });
        previous.keySet().stream().filter(name -> !current.containsKey(name)).sorted().forEach(removed::add);
        var snapshot = current.values().stream().sorted(java.util.Comparator.comparing(File::name))
                .map(file -> new SyncRevision.Jar(file.name(), file.hash(), file.size(), file.firstSeenAt())).toList();
        if (digest.equals(previousDigest)) return new ModInventoryRevision(digest, previousChange, snapshot, previousAdded, previousReplaced, previousRemoved);
        var output = new JsonObject();
        output.addProperty("schemaVersion", 1);
        output.addProperty("scope", "client-selection");
        output.addProperty("inventorySha256", digest);
        output.addProperty("changedAt", detectedAt.toString());
        var files = new JsonArray();
        current.values().stream().sorted(java.util.Comparator.comparing(File::name)).forEach(file -> {
            var entry = new JsonObject();
            entry.addProperty("fileName", file.name());
            entry.addProperty("sha256", file.hash());
            entry.addProperty("size", file.size());
            entry.addProperty("firstSeenAt", file.firstSeenAt().toString());
            files.add(entry);
        });
        output.add("files", files);
        output.add("added", names(added));
        output.add("replaced", names(replaced));
        output.add("removed", names(removed));
        byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > SyncManifest.MAX_BYTES) throw new IOException("The mod inventory record exceeds its size limit.");
        Path temporary = record.resolveSibling("neosync-inventory-" + java.util.UUID.randomUUID() + ".tmp");
        try {
            try (var file = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            token.check();
            if (Files.isSymbolicLink(record)) throw new IOException("The mod inventory record must not be a symbolic link.");
            Files.move(temporary, record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new ModInventoryRevision(digest, detectedAt, snapshot, added, replaced, removed);
    }

    private static String digest(Map<String, File> files) {
        return SyncRevision.inventoryDigest(files.values().stream().map(file -> new SyncRevision.Jar(file.name(), file.hash(), file.size(), file.firstSeenAt())).toList());
    }

    private static List<String> changeNames(com.google.gson.JsonElement value, Set<String> files, Set<String> changes, boolean removed) throws IOException {
        var result = new ArrayList<String>();
        for (var entry : SyncJson.array(value, 0, 2048)) {
            String name = SyncJson.matching(entry, 128, SyncManifest.FILE_PATTERN);
            if (!changes.add(name) || files.contains(name) == removed) throw new IOException("Invalid mod inventory change list.");
            result.add(name);
        }
        return List.copyOf(result);
    }

    private static JsonArray names(List<String> names) {
        var result = new JsonArray();
        names.forEach(result::add);
        return result;
    }

    private static Instant timestamp(com.google.gson.JsonElement value) throws IOException {
        try {
            return Instant.parse(SyncJson.string(value, 64));
        } catch (java.time.DateTimeException failure) {
            throw new IOException("Invalid mod inventory timestamp.", failure);
        }
    }
}
