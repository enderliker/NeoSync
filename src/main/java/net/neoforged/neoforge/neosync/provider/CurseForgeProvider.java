/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ModEnvironment;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

public final class CurseForgeProvider {
    private final ProviderTransport transport;

    public CurseForgeProvider(ProviderTransport transport) {
        this.transport = transport;
    }

    public ModEnvironment environment(ProviderArtifact expected, DiscoveryCancellation token) throws IOException {
        var hint = expected.identity();
        if (!hint.id().equals("curseforge")) throw new IOException("A CurseForge file identity is required.");
        expected.validate();
        if (!transport.available(ProviderHttpClient.Service.CURSEFORGE)) throw new IOException("CurseForge API access is unavailable in this build.");
        byte[] bytes = transport.request(ProviderHttpClient.Service.CURSEFORGE, "/v1/mods/" + hint.projectId() + "/files/" + hint.fileId(), "", token);
        if (bytes.length == 0) return ModEnvironment.UNKNOWN;
        var file = ProviderJson.object(ProviderJson.object(SyncJson.parse(bytes, ProviderHttpClient.MAX_BYTES)).get("data"));
        var actual = parse(file);
        if (!actual.identity().equals(hint) || actual.size() != expected.size() || !actual.hash().equals(expected.hash()))
            throw new IOException("The CurseForge environment lookup returned a different file.");
        byte[] types = transport.request(ProviderHttpClient.Service.CURSEFORGE, "/v1/games/432/version-types", "", token);
        if (types.length == 0) return ModEnvironment.UNKNOWN;
        var environmentTypes = new java.util.HashSet<Long>();
        for (var entry : SyncJson.array(ProviderJson.object(SyncJson.parse(types, ProviderHttpClient.MAX_BYTES)).get("data"), 0, 512)) {
            var type = ProviderJson.object(entry);
            if (SyncJson.number(type.get("gameId"), 1, Integer.MAX_VALUE) != 432) throw new IOException("Unexpected CurseForge game version type.");
            if (SyncJson.string(type.get("name"), 128).equalsIgnoreCase("Environment"))
                environmentTypes.add(SyncJson.number(type.get("id"), 1, Integer.MAX_VALUE));
        }
        if (environmentTypes.isEmpty() || !file.has("sortableGameVersions")) return ModEnvironment.UNKNOWN;
        boolean client = false;
        boolean server = false;
        for (var entry : SyncJson.array(file.get("sortableGameVersions"), 0, 512)) {
            var version = ProviderJson.object(entry);
            if (!version.has("gameVersionTypeId") || version.get("gameVersionTypeId").isJsonNull()
                    || !environmentTypes.contains(SyncJson.number(version.get("gameVersionTypeId"), 1, Integer.MAX_VALUE)))
                continue;
            String name = SyncJson.string(version.get("gameVersionName"), 128);
            switch (name) {
                case "Client" -> client = true;
                case "Server" -> server = true;
                case "Client and Server", "Both" -> {
                    client = true;
                    server = true;
                }
                default -> {
                    return ModEnvironment.UNKNOWN;
                }
            }
        }
        return client ? (server ? ModEnvironment.BOTH : ModEnvironment.CLIENT) : (server ? ModEnvironment.SERVER : ModEnvironment.UNKNOWN);
    }

    public ProviderArtifact file(SyncManifest.ProviderHint hint, DiscoveryCancellation token) throws IOException {
        if (!hint.id().equals("curseforge") || !hint.projectId().matches("[1-9][0-9]{0,9}") || !hint.fileId().matches("[1-9][0-9]{0,9}"))
            throw new IOException("CurseForge requires exact numeric project and file identifiers.");
        if (!transport.available(ProviderHttpClient.Service.CURSEFORGE)) throw new IOException("CurseForge API access is unavailable in this build.");
        byte[] bytes = transport.request(ProviderHttpClient.Service.CURSEFORGE, "/v1/mods/" + hint.projectId() + "/files/" + hint.fileId(), "", token);
        if (bytes.length == 0) throw new IOException("The approved CurseForge file is unavailable. No alternate source was selected.");
        var result = parse(ProviderJson.object(ProviderJson.object(SyncJson.parse(bytes, ProviderHttpClient.MAX_BYTES)).get("data")));
        if (!result.identity().equals(hint)) throw new IOException("CurseForge returned an unexpected file identity.");
        return result;
    }

    public Map<Path, ProviderArtifact> findFiles(Map<Path, ArtifactFiles.Fingerprint> files, DiscoveryCancellation token) throws IOException {
        if (files.isEmpty()) return Map.of();
        if (!transport.available(ProviderHttpClient.Service.CURSEFORGE)) throw new IOException("CurseForge API access is unavailable in this build.");
        if (files.size() > 2048) throw new IOException("Too many CurseForge fingerprints.");
        var inspections = new HashMap<Path, Lookup>();
        long total = 0;
        for (var entry : files.entrySet()) {
            total = Math.addExact(total, entry.getValue().size());
            if (total > SyncManifest.MAX_TOTAL_BYTES || entry.getValue().size() < 1 || entry.getValue().size() > SyncManifest.MAX_FILE_BYTES)
                throw new IOException("The CurseForge lookup inventory exceeds the byte limit.");
            var inspection = inspect(entry.getKey(), entry.getValue(), token);
            inspections.put(entry.getKey(), new Lookup(inspection.fingerprint(), inspection.sha1(), entry.getValue().size()));
        }
        var resolved = findFingerprints(inspections.values().stream().distinct().toList(), token);
        var result = new HashMap<Path, ProviderArtifact>();
        for (var entry : inspections.entrySet()) {
            var match = resolved.get(entry.getValue());
            if (match != null) result.put(entry.getKey(), match);
        }
        return Map.copyOf(result);
    }

    public record Lookup(long fingerprint, String sha1, long size) {}

    public Map<Lookup, ProviderArtifact> findFingerprints(List<Lookup> lookups, DiscoveryCancellation token) throws IOException {
        if (lookups.isEmpty()) return Map.of();
        if (!transport.available(ProviderHttpClient.Service.CURSEFORGE)) throw new IOException("CurseForge API access is unavailable in this build.");
        if (lookups.size() > 2048) throw new IOException("Too many CurseForge fingerprints.");
        for (var lookup : lookups) {
            if (lookup.fingerprint() < 0 || lookup.fingerprint() > 0xffffffffL || !lookup.sha1().matches("[0-9a-f]{40}")
                    || lookup.size() < 1 || lookup.size() > SyncManifest.MAX_FILE_BYTES)
                throw new IOException("Invalid CurseForge lookup evidence.");
        }
        var unique = lookups.stream().map(Lookup::fingerprint).distinct().toList();
        var result = new HashMap<Lookup, ProviderArtifact>();
        for (int start = 0; start < unique.size(); start += 32) {
            var batch = unique.subList(start, Math.min(start + 32, unique.size()));
            var array = new JsonArray();
            batch.forEach(array::add);
            var request = new JsonObject();
            request.add("fingerprints", array);
            byte[] bytes = transport.request(ProviderHttpClient.Service.CURSEFORGE, "/v1/fingerprints/432", request.toString(), token);
            if (bytes.length == 0) continue;
            var data = ProviderJson.object(ProviderJson.object(SyncJson.parse(bytes, ProviderHttpClient.MAX_BYTES)).get("data"));
            for (var match : SyncJson.array(data.get("exactMatches"), 0, 2048)) {
                var file = ProviderJson.object(ProviderJson.object(match).get("file"));
                long fingerprint = SyncJson.number(file.get("fileFingerprint"), 0, 0xffffffffL);
                if (!batch.contains(fingerprint)) throw new IOException("CurseForge returned an unexpected fingerprint.");
                var candidate = parse(file);
                for (var lookup : lookups) {
                    if (lookup.fingerprint() != fingerprint || !lookup.sha1().equals(candidate.hash()) || lookup.size() != candidate.size()) continue;
                    if (result.putIfAbsent(lookup, candidate) != null) throw new IOException("CurseForge returned ambiguous exact files.");
                }
            }
        }
        return Map.copyOf(result);
    }

    public static JsonArray sources(Path path, ArtifactFiles.Fingerprint expected, DiscoveryCancellation token) throws IOException {
        var inspection = inspect(path, expected, token);
        var source = new JsonObject();
        source.addProperty("type", "curseforge");
        source.addProperty("fingerprint", inspection.fingerprint());
        source.addProperty("sha1", inspection.sha1());
        var result = new JsonArray();
        result.add(source);
        return result;
    }

    private static ProviderArtifact parse(JsonObject file) throws IOException {
        String project = Long.toString(SyncJson.number(file.get("modId"), 1, Integer.MAX_VALUE));
        String id = Long.toString(SyncJson.number(file.get("id"), 1, Integer.MAX_VALUE));
        if (SyncJson.number(file.get("gameId"), 1, Integer.MAX_VALUE) != 432 || !SyncJson.bool(file.get("isAvailable")))
            throw new IOException("The CurseForge artifact is unavailable or is not a Minecraft file.");
        if (file.has("isServerPack") && !file.get("isServerPack").isJsonNull() && SyncJson.bool(file.get("isServerPack")))
            throw new IOException("CurseForge server packs cannot be installed as client mods.");
        var versions = SyncJson.array(file.get("gameVersions"), 1, 512);
        var names = new java.util.HashSet<String>();
        for (var version : versions) names.add(SyncJson.string(version, 128));
        if (!names.containsAll(List.of("1.21.1", "NeoForge"))) throw new IOException("The CurseForge file is not listed for Minecraft 1.21.1 and NeoForge.");
        String sha1 = null;
        for (var entry : SyncJson.array(file.get("hashes"), 1, 8)) {
            var hash = ProviderJson.object(entry);
            if (SyncJson.number(hash.get("algo"), 1, 2) != 1) continue;
            if (sha1 != null) throw new IOException("CurseForge returned duplicate SHA-1 evidence.");
            sha1 = SyncJson.matching(hash.get("value"), 40, "[0-9a-f]{40}");
        }
        if (sha1 == null) throw new IOException("CurseForge did not provide independent SHA-1 evidence.");
        if (!file.has("downloadUrl") || file.get("downloadUrl").isJsonNull())
            throw new IOException("CurseForge did not authorize a direct download. No URL was fabricated and no hosting fallback was selected.");
        var result = new ProviderArtifact(new SyncManifest.ProviderHint("curseforge", project, id), ProviderJson.uri(file.get("downloadUrl")),
                SyncJson.number(file.get("fileLength"), 1, SyncManifest.MAX_FILE_BYTES), "SHA-1", sha1, false);
        result.validate();
        return result;
    }

    public static long fingerprint(byte[] bytes) {
        int length = 0;
        for (byte value : bytes) if (value != 9 && value != 10 && value != 13 && value != 32) length++;
        var normalized = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        for (byte value : bytes) if (value != 9 && value != 10 && value != 13 && value != 32) normalized.put(value);
        normalized.flip();
        int hash = 1 ^ length;
        while (normalized.remaining() >= 4) {
            int block = normalized.getInt() * 0x5bd1e995;
            block ^= block >>> 24;
            hash = hash * 0x5bd1e995 ^ block * 0x5bd1e995;
        }
        int tail = 0;
        int shift = 0;
        while (normalized.hasRemaining()) {
            tail |= Byte.toUnsignedInt(normalized.get()) << shift;
            shift += 8;
        }
        if (shift != 0) hash = (hash ^ tail) * 0x5bd1e995;
        hash ^= hash >>> 13;
        hash *= 0x5bd1e995;
        return Integer.toUnsignedLong(hash ^ hash >>> 15);
    }

    private record Inspection(long fingerprint, String sha1) {}

    private static Inspection inspect(Path path, ArtifactFiles.Fingerprint expected, DiscoveryCancellation token) throws IOException {
        token.check();
        if (expected.size() < 1 || expected.size() > SyncManifest.MAX_FILE_BYTES || !expected.sha256().matches("[0-9a-f]{64}"))
            throw new IOException("Invalid local CurseForge lookup artifact.");
        net.neoforged.neoforge.neosync.protocol.ManagedPaths.directory(path.toAbsolutePath().getParent(), false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("The selected provider artifact is not a regular file.");
        var sha1 = ProviderArtifact.digest("SHA-1");
        var sha256 = SyncManifest.sha256Digest();
        int normalizedLength = 0;
        long size = 0;
        byte[] buffer = new byte[65536];
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                token.check();
                size += count;
                if (size > expected.size()) throw new IOException("The selected artifact grew during provider inspection.");
                sha1.update(buffer, 0, count);
                sha256.update(buffer, 0, count);
                for (int index = 0; index < count; index++) {
                    byte value = buffer[index];
                    if (value != 9 && value != 10 && value != 13 && value != 32) normalizedLength++;
                }
            }
        }
        if (size != expected.size() || !HexFormat.of().formatHex(sha256.digest()).equals(expected.sha256()))
            throw new IOException("The selected artifact changed during provider inspection.");
        int hash = 1 ^ normalizedLength;
        int block = 0;
        int shift = 0;
        size = 0;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                token.check();
                size += count;
                if (size > expected.size()) throw new IOException("The selected artifact grew during fingerprint inspection.");
                sha256.update(buffer, 0, count);
                for (int index = 0; index < count; index++) {
                    byte value = buffer[index];
                    if (value == 9 || value == 10 || value == 13 || value == 32) continue;
                    block |= Byte.toUnsignedInt(value) << shift;
                    shift += 8;
                    if (shift == 32) {
                        block *= 0x5bd1e995;
                        block ^= block >>> 24;
                        hash = hash * 0x5bd1e995 ^ block * 0x5bd1e995;
                        block = 0;
                        shift = 0;
                    }
                }
            }
        }
        if (size != expected.size() || !HexFormat.of().formatHex(sha256.digest()).equals(expected.sha256()))
            throw new IOException("The selected artifact changed during fingerprint inspection.");
        if (shift != 0) hash = (hash ^ block) * 0x5bd1e995;
        hash ^= hash >>> 13;
        hash *= 0x5bd1e995;
        return new Inspection(Integer.toUnsignedLong(hash ^ hash >>> 15), HexFormat.of().formatHex(sha1.digest()));
    }
}
