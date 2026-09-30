/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record SyncRevision(UUID serverId, String manifestSha256, String inventorySha256, Instant changedAt,
        List<Jar> files, List<String> added, List<String> replaced, List<String> removed) {

    public static final int MAX_BYTES = SyncManifest.MAX_BYTES;
    public record Jar(String fileName, String sha256, long size, Instant firstSeenAt) {}

    public SyncRevision {
        files = List.copyOf(files);
        added = List.copyOf(added);
        replaced = List.copyOf(replaced);
        removed = List.copyOf(removed);
    }

    public static SyncRevision parse(byte[] bytes) throws IOException {
        var object = SyncJson.object(SyncJson.parse(bytes, MAX_BYTES),
                Set.of("schemaVersion", "serverId", "manifestSha256", "inventorySha256", "changedAt", "files", "added", "replaced", "removed"), Set.of());
        SyncJson.number(object.get("schemaVersion"), 1, 1);
        try {
            var files = new ArrayList<Jar>();
            var names = new HashSet<String>();
            long total = 0;
            for (var entry : SyncJson.array(object.get("files"), 0, 2048)) {
                var file = SyncJson.object(entry, Set.of("fileName", "sha256", "size", "firstSeenAt"), Set.of());
                String name = SyncJson.matching(file.get("fileName"), 128, SyncManifest.FILE_PATTERN);
                if (!names.add(name)) throw new IOException("Duplicate client revision file.");
                long size = SyncJson.number(file.get("size"), 1, SyncManifest.MAX_FILE_BYTES);
                total += size;
                if (total > SyncManifest.MAX_TOTAL_BYTES) throw new IOException("The client revision inventory exceeds its byte limit.");
                files.add(new Jar(name, SyncJson.matching(file.get("sha256"), 64, SyncManifest.HASH_PATTERN), size,
                        Instant.parse(SyncJson.string(file.get("firstSeenAt"), 64))));
            }
            String inventory = SyncJson.matching(object.get("inventorySha256"), 64, SyncManifest.HASH_PATTERN);
            if (!inventory.equals(inventoryDigest(files))) throw new IOException("The client revision inventory digest is inconsistent.");
            var changes = new HashSet<String>();
            var added = changeNames(object.get("added"), names, changes, false);
            var replaced = changeNames(object.get("replaced"), names, changes, false);
            var removed = changeNames(object.get("removed"), names, changes, true);
            return new SyncRevision(UUID.fromString(SyncJson.matching(object.get("serverId"), 36, "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
                    SyncJson.matching(object.get("manifestSha256"), 64, SyncManifest.HASH_PATTERN),
                    inventory, Instant.parse(SyncJson.string(object.get("changedAt"), 64)), files, added, replaced, removed);
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw new IOException("Invalid synchronization revision.", failure);
        }
    }

    public byte[] bytes() {
        var object = new JsonObject();
        object.addProperty("schemaVersion", 1);
        object.addProperty("serverId", serverId.toString());
        object.addProperty("manifestSha256", manifestSha256);
        object.addProperty("inventorySha256", inventorySha256);
        object.addProperty("changedAt", changedAt.toString());
        var entries = new com.google.gson.JsonArray();
        for (var file : files) {
            var entry = new JsonObject();
            entry.addProperty("fileName", file.fileName());
            entry.addProperty("sha256", file.sha256());
            entry.addProperty("size", file.size());
            entry.addProperty("firstSeenAt", file.firstSeenAt().toString());
            entries.add(entry);
        }
        object.add("files", entries);
        object.add("added", names(added));
        object.add("replaced", names(replaced));
        object.add("removed", names(removed));
        return object.toString().getBytes(StandardCharsets.UTF_8);
    }

    public boolean matchesManifest(SyncManifest manifest) {
        var expected = manifest.files().stream().map(file -> new Jar(file.fileName(), file.sha256(), file.size(), Instant.EPOCH)).toList();
        return serverId.equals(manifest.serverId()) && files.size() == expected.size() && inventorySha256.equals(inventoryDigest(expected));
    }

    public static String inventoryDigest(List<Jar> files) {
        var entries = new com.google.gson.JsonArray();
        files.stream().sorted(java.util.Comparator.comparing(Jar::fileName)).forEach(file -> entries.add(file.fileName() + "|" + file.sha256() + "|" + file.size()));
        return SyncManifest.sha256(entries.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> changeNames(com.google.gson.JsonElement value, Set<String> files, Set<String> changes, boolean removed) throws IOException {
        var result = new ArrayList<String>();
        for (var entry : SyncJson.array(value, 0, 2048)) {
            String name = SyncJson.matching(entry, 128, SyncManifest.FILE_PATTERN);
            if (!changes.add(name) || files.contains(name) == removed) throw new IOException("Invalid or duplicate client revision change.");
            result.add(name);
        }
        return List.copyOf(result);
    }

    private static com.google.gson.JsonArray names(List<String> names) {
        var result = new com.google.gson.JsonArray();
        names.forEach(result::add);
        return result;
    }
}
