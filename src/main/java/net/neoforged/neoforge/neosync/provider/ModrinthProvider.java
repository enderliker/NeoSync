/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

public final class ModrinthProvider {
    private final ProviderTransport transport;

    public ModrinthProvider(ProviderTransport transport) {
        this.transport = transport;
    }

    public Map<String, List<ProviderArtifact>> versions(List<SyncManifest.ProviderHint> hints, DiscoveryCancellation token) throws IOException {
        var ids = hints.stream().map(SyncManifest.ProviderHint::fileId).distinct().toList();
        if (ids.size() > 2048) throw new IOException("Too many provider versions.");
        var result = new HashMap<String, List<ProviderArtifact>>();
        for (int start = 0; start < ids.size(); start += 32) {
            token.check();
            var batch = ids.subList(start, Math.min(ids.size(), start + 32));
            var array = new JsonArray();
            for (String id : batch) {
                if (!id.matches("[A-Za-z0-9]{8}")) throw new IOException("Modrinth requires an exact version ID.");
                array.add(id);
            }
            byte[] bytes = transport.request(ProviderHttpClient.Service.MODRINTH, "/v2/versions?ids=" + URLEncoder.encode(array.toString(), StandardCharsets.UTF_8), "", token);
            if (bytes.length == 0) continue;
            for (var entry : SyncJson.array(SyncJson.parse(bytes, ProviderHttpClient.MAX_BYTES), 0, 32)) {
                var files = parseVersion(ProviderJson.object(entry));
                String id = files.getFirst().identity().fileId();
                if (!batch.contains(id) || result.putIfAbsent(id, files) != null) throw new IOException("Modrinth returned unexpected or duplicate versions.");
            }
        }
        return Map.copyOf(result);
    }

    /** The hashes must be computed from existing administrator-selected files, never from an unconsented client download. */
    public Map<String, ProviderArtifact> findHashes(List<String> sha512s, DiscoveryCancellation token) throws IOException {
        var hashes = sha512s.stream().distinct().toList();
        if (hashes.size() > 2048) throw new IOException("Too many provider hashes.");
        var result = new HashMap<String, ProviderArtifact>();
        for (int start = 0; start < hashes.size(); start += 32) {
            var batch = hashes.subList(start, Math.min(start + 32, hashes.size()));
            var array = new JsonArray();
            for (String hash : batch) {
                if (!hash.matches("[0-9a-f]{128}")) throw new IOException("Invalid SHA-512 lookup hash.");
                array.add(hash);
            }
            var request = new JsonObject();
            request.add("hashes", array);
            request.addProperty("algorithm", "sha512");
            byte[] bytes = transport.request(ProviderHttpClient.Service.MODRINTH, "/v2/version_files", request.toString(), token);
            if (bytes.length == 0) continue;
            var response = ProviderJson.object(SyncJson.parse(bytes, ProviderHttpClient.MAX_BYTES));
            if (!batch.containsAll(response.keySet())) throw new IOException("Modrinth returned an unexpected hash.");
            for (var entry : response.entrySet()) {
                var matching = parseVersion(ProviderJson.object(entry.getValue())).stream().filter(file -> file.hash().equals(entry.getKey())).toList();
                if (matching.size() != 1) throw new IOException("Modrinth did not identify one exact file for the lookup hash.");
                result.put(entry.getKey(), matching.getFirst());
            }
        }
        return Map.copyOf(result);
    }

    private static List<ProviderArtifact> parseVersion(JsonObject version) throws IOException {
        String project = SyncJson.matching(version.get("project_id"), 8, "[A-Za-z0-9]{8}");
        String id = SyncJson.matching(version.get("id"), 8, "[A-Za-z0-9]{8}");
        var games = SyncJson.array(version.get("game_versions"), 1, 512);
        var loaders = SyncJson.array(version.get("loaders"), 1, 64);
        boolean game = false;
        boolean loader = false;
        for (var entry : games) if (SyncJson.string(entry, 128).equals("1.21.1")) game = true;
        for (var entry : loaders) if (SyncJson.string(entry, 64).equals("neoforge")) loader = true;
        if (!game || !loader) throw new IOException("The Modrinth version is not listed for Minecraft 1.21.1 and NeoForge.");
        var result = new ArrayList<ProviderArtifact>();
        var urls = new java.util.HashSet<java.net.URI>();
        for (var entry : SyncJson.array(version.get("files"), 1, 64)) {
            var file = ProviderJson.object(entry);
            var hashes = ProviderJson.object(file.get("hashes"));
            var source = new ProviderArtifact(new SyncManifest.ProviderHint("modrinth", project, id), ProviderJson.uri(file.get("url")),
                    SyncJson.number(file.get("size"), 1, SyncManifest.MAX_FILE_BYTES), "SHA-512", SyncJson.matching(hashes.get("sha512"), 128, "[0-9a-f]{128}"), false);
            source.validate();
            if (!urls.add(source.source())) throw new IOException("Modrinth returned duplicate file URLs.");
            result.add(source);
        }
        return List.copyOf(result);
    }
}
