/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

public final class CurseForgeProvider {
    private final ProviderTransport transport;

    public CurseForgeProvider(ProviderTransport transport) {
        this.transport = transport;
    }

    public Map<SyncManifest.ProviderHint, ProviderArtifact> files(List<SyncManifest.ProviderHint> hints, DiscoveryCancellation token) throws IOException {
        var unique = hints.stream().distinct().toList();
        if (unique.size() > 2048) throw new IOException("Too many CurseForge files.");
        for (var hint : unique) {
            if (!hint.id().equals("curseforge")) throw new IOException("Unexpected provider hint.");
            identifier(hint.projectId());
            identifier(hint.fileId());
        }
        var projects = batch("/v1/mods", "modIds", unique.stream().map(SyncManifest.ProviderHint::projectId).distinct().toList(), token);
        var files = batch("/v1/mods/files", "fileIds", unique.stream().map(SyncManifest.ProviderHint::fileId).distinct().toList(), token);
        var result = new HashMap<SyncManifest.ProviderHint, ProviderArtifact>();
        for (var hint : unique) {
            token.check();
            var project = projects.get(hint.projectId());
            var file = files.get(hint.fileId());
            if (project == null || file == null) throw new IOException("The exact CurseForge project or file was not found.");
            if (SyncJson.number(project.get("gameId"), 1, Integer.MAX_VALUE) != 432 || SyncJson.number(project.get("classId"), 1, Integer.MAX_VALUE) != 6
                    || SyncJson.number(file.get("gameId"), 1, Integer.MAX_VALUE) != 432 || !numberId(file, "modId").equals(hint.projectId())
                    || !SyncJson.bool(file.get("isAvailable")))
                throw new IOException("CurseForge did not return the requested available Minecraft mod file.");
            var versions = SyncJson.array(file.get("gameVersions"), 1, 512);
            boolean game = false;
            boolean loader = false;
            for (var entry : versions) {
                String version = SyncJson.string(entry, 128);
                if (version.equals("1.21.1")) game = true;
                if (version.equals("NeoForge")) loader = true;
            }
            if (!game || !loader) throw new IOException("The CurseForge file is not listed for Minecraft 1.21.1 and NeoForge.");
            String sha1 = null;
            for (var entry : SyncJson.array(file.get("hashes"), 1, 8)) {
                var hash = ProviderJson.object(entry);
                if (SyncJson.number(hash.get("algo"), 1, 2) == 1) {
                    if (sha1 != null) throw new IOException("Duplicate CurseForge SHA-1 identity.");
                    sha1 = SyncJson.matching(hash.get("value"), 40, "[0-9a-fA-F]{40}").toLowerCase(java.util.Locale.ROOT);
                }
            }
            if (sha1 == null) throw new IOException("CurseForge did not supply the file's SHA-1 identity.");
            var distribution = project.get("allowModDistribution");
            if (distribution == null || distribution.isJsonNull()) throw new IOException("CurseForge has not established whether automatic downloads are permitted for this project.");
            boolean manual = !SyncJson.bool(distribution);
            URI source;
            if (manual) {
                String slug = SyncJson.matching(project.get("slug"), 128, "[a-z0-9][a-z0-9-]*");
                source = URI.create("https://www.curseforge.com/minecraft/mc-mods/" + slug + "/files/" + hint.fileId());
            } else {
                if (!file.has("downloadUrl") || file.get("downloadUrl").isJsonNull())
                    throw new IOException("CurseForge did not provide a permitted download URL. This does not establish an author restriction.");
                source = ProviderJson.uri(file.get("downloadUrl"));
            }
            var resolved = new ProviderArtifact(hint, source, SyncJson.number(file.get("fileLength"), 1, SyncManifest.MAX_FILE_BYTES), "SHA-1", sha1, manual);
            resolved.validate();
            result.put(hint, resolved);
        }
        return Map.copyOf(result);
    }

    private Map<String, JsonObject> batch(String path, String key, List<String> ids, DiscoveryCancellation token) throws IOException {
        var result = new HashMap<String, JsonObject>();
        for (int start = 0; start < ids.size(); start += 32) {
            token.check();
            var batch = ids.subList(start, Math.min(ids.size(), start + 32));
            var values = new JsonArray();
            for (String id : batch) values.add(identifier(id));
            var body = new JsonObject();
            body.add(key, values);
            byte[] bytes = transport.request(ProviderHttpClient.Service.CURSEFORGE, path, body.toString(), token);
            if (bytes.length == 0) continue;
            var response = ProviderJson.object(SyncJson.parse(bytes, ProviderHttpClient.MAX_BYTES));
            for (var entry : SyncJson.array(response.get("data"), 0, 32)) {
                var value = ProviderJson.object(entry);
                String id = numberId(value, "id");
                if (!batch.contains(id) || result.putIfAbsent(id, value) != null) throw new IOException("CurseForge returned unexpected or duplicate identities.");
            }
        }
        return Map.copyOf(result);
    }

    private static String numberId(JsonObject object, String key) throws IOException {
        return Long.toString(SyncJson.number(object.get(key), 1, Integer.MAX_VALUE));
    }

    private static int identifier(String id) throws IOException {
        try {
            if (!id.matches("[1-9][0-9]{0,9}")) throw new NumberFormatException();
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new IOException("CurseForge requires a numeric project and exact file ID.");
        }
    }
}
