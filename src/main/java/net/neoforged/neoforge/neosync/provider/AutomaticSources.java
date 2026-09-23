/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

public final class AutomaticSources {
    private AutomaticSources() {}

    public static void requireConfiguredAccess(List<SyncManifest.Source> sources, ProviderTransport transport) throws IOException {
        for (var source : sources) {
            if (source.provider() == null || !source.provider().id().equals("curseforge")) continue;
            if (source.evidence() == null)
                throw new IOException("Configured CurseForge hints need exact server-reported evidence. Use resolveProviders with the administrator's own API key.");
            if (!transport.available(ProviderHttpClient.Service.CURSEFORGE))
                throw new IOException("The server administrator must set their own NEOSYNC_CURSEFORGE_API_KEY before advertising CurseForge files.");
        }
    }

    public static Map<Path, ProviderArtifact> resolve(Map<Path, ArtifactFiles.Fingerprint> files, ProviderTransport transport, DiscoveryCancellation token) throws IOException {
        return resolve(files, Map.of(), transport, token);
    }

    public static Map<Path, ProviderArtifact> resolve(Map<Path, ArtifactFiles.Fingerprint> files, Map<Path, SyncManifest.ProviderHint> curseHints,
            ProviderTransport transport, DiscoveryCancellation token) throws IOException {
        var hashes = new HashMap<Path, String>();
        var sha1s = new HashMap<Path, String>();
        long total = 0;
        for (var entry : files.entrySet()) {
            total += entry.getValue().size();
            if (files.size() > 2048 || total > SyncManifest.MAX_TOTAL_BYTES) throw new IOException("Provider lookup inventory exceeds the limit.");
            var digest = ProviderArtifact.digest("SHA-512");
            var sha256 = SyncManifest.sha256Digest();
            var sha1 = ProviderArtifact.digest("SHA-1");
            long size = 0;
            try (var input = Files.newInputStream(entry.getKey(), LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    token.check();
                    size += count;
                    if (size > entry.getValue().size()) throw new IOException("The selected provider artifact grew during inspection.");
                    digest.update(buffer, 0, count);
                    sha256.update(buffer, 0, count);
                    sha1.update(buffer, 0, count);
                }
            }
            if (size != entry.getValue().size() || !HexFormat.of().formatHex(sha256.digest()).equals(entry.getValue().sha256()))
                throw new IOException("The selected provider artifact changed during inspection.");
            hashes.put(entry.getKey(), HexFormat.of().formatHex(digest.digest()));
            sha1s.put(entry.getKey(), HexFormat.of().formatHex(sha1.digest()));
        }
        var matches = new ModrinthProvider(transport).findHashes(hashes.values().stream().toList(), token);
        var needed = hashes.entrySet().stream().filter(entry -> !matches.containsKey(entry.getValue())).map(Map.Entry::getKey).toList();
        for (var path : needed) if (!curseHints.containsKey(path))
            throw new IOException("Modrinth has no exact match for " + path.getFileName() + ". Supply exact CurseForge project/file IDs for lookup. Hosting is not a third-party fallback.");
        if (!needed.isEmpty() && !transport.available(ProviderHttpClient.Service.CURSEFORGE))
            throw new IOException("Modrinth has no exact match for a selected file. The server administrator must set their own NEOSYNC_CURSEFORGE_API_KEY or configure a permitted direct HTTPS source; hosting is not a third-party fallback.");
        var curseFiles = new CurseForgeProvider(transport).files(needed.stream().map(curseHints::get).toList(), token);
        var result = new HashMap<Path, ProviderArtifact>();
        for (var entry : hashes.entrySet()) {
            var match = matches.get(entry.getValue());
            if (match == null) {
                match = curseFiles.get(curseHints.get(entry.getKey()));
                if (match == null || !match.hash().equals(sha1s.get(entry.getKey()))) throw new IOException("CurseForge did not match the exact selected server bytes.");
            }
            if (match.size() != files.get(entry.getKey()).size()) throw new IOException("The provider file size does not match the selected artifact.");
            result.put(entry.getKey(), match);
        }
        return Map.copyOf(result);
    }

    public static JsonArray sources(ProviderArtifact artifact) {
        var hint = new JsonObject();
        hint.addProperty("id", artifact.identity().id());
        hint.addProperty("projectId", artifact.identity().projectId());
        hint.addProperty("fileId", artifact.identity().fileId());
        if (artifact.identity().id().equals("curseforge")) {
            hint.addProperty("sha1", artifact.hash());
            hint.addProperty("manual", artifact.manual());
        }
        var source = new JsonObject();
        source.addProperty("type", "external");
        source.addProperty("url", artifact.source().toASCIIString());
        source.add("provider", hint);
        var result = new JsonArray();
        result.add(source);
        return result;
    }
}
