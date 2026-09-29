/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforge.neosync.provider.AutomaticSources;
import net.neoforged.neoforge.neosync.provider.CurseForgeProvider;
import net.neoforged.neoforge.neosync.provider.ModrinthMetadataCache;
import net.neoforged.neoforge.neosync.provider.ProviderHttpClient;
import net.neoforged.neoforge.neosync.provider.SourceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CurseForgeProviderTest {
    private static final SyncManifest.ProviderHint HINT = new SyncManifest.ProviderHint("curseforge", "123", "456789");
    private static final String URL = "https://mediafilez.forgecdn.net/files/456/789/mod.jar";

    private static String file(byte[] bytes) throws Exception {
        return """
                {"id":456789,"modId":123,"gameId":432,"isAvailable":true,"gameVersions":["1.21.1","NeoForge"],
                "fileLength":%d,"downloadUrl":"%s","hashes":[{"algo":1,"value":"%s"}],"fileFingerprint":%d}
                """.formatted(bytes.length, URL, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)), CurseForgeProvider.fingerprint(bytes));
    }

    @Test
    void usesFreshExactFileMetadataAndNeverTheModrinthCache() throws Exception {
        var calls = new ArrayList<String>();
        var provider = new CurseForgeProvider((service, path, body, token) -> {
            assertEquals(ProviderHttpClient.Service.CURSEFORGE, service);
            assertEquals("/v1/mods/123/files/456789", path);
            assertEquals("", body);
            calls.add(path);
            try {
                return ("{\"data\":" + file(new byte[] { 1, 2, 3 }) + "}").getBytes(StandardCharsets.UTF_8);
            } catch (Exception failure) {
                throw new IOException(failure);
            }
        });
        assertEquals(HINT, provider.file(HINT, new DiscoveryCancellation()).identity());
        provider.file(HINT, new DiscoveryCancellation());
        assertEquals(2, calls.size());
        var cache = new ModrinthMetadataCache();
        cache.put(ProviderHttpClient.Service.CURSEFORGE, "/v1/mods/123/files/456789", "", new byte[] { 1 });
        assertEquals(null, cache.get(ProviderHttpClient.Service.CURSEFORGE, "/v1/mods/123/files/456789", ""));
    }

    @Test
    void resolvesOnlyMissingModrinthFilesAndVerifiesBothHashes(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        byte[] bytes = Files.readAllBytes(jar);
        String metadata = file(bytes);
        var calls = new ArrayList<ProviderHttpClient.Service>();
        var result = AutomaticSources.resolve(Map.of(jar, ArtifactFiles.fingerprint(jar, new DiscoveryCancellation())), (service, path, body, token) -> {
            calls.add(service);
            return (service == ProviderHttpClient.Service.MODRINTH ? "{}" : "{\"data\":{\"exactMatches\":[{\"file\":" + metadata + "}]}}")
                    .getBytes(StandardCharsets.UTF_8);
        }, new DiscoveryCancellation());
        assertEquals(List.of(ProviderHttpClient.Service.MODRINTH, ProviderHttpClient.Service.CURSEFORGE), calls);
        assertEquals(HINT, result.get(jar).identity());
        var root = JsonParser.parseString(new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        var artifact = root.getAsJsonArray("files").get(0).getAsJsonObject();
        artifact.addProperty("sha256", SyncManifest.sha256(bytes));
        artifact.addProperty("size", bytes.length);
        artifact.add("sources", CurseForgeProvider.sources(jar, ArtifactFiles.fingerprint(jar, new DiscoveryCancellation()), new DiscoveryCancellation()));
        byte[] manifest = root.toString().getBytes(StandardCharsets.UTF_8);
        var requirements = net.neoforged.neoforge.neosync.protocol.RequirementReport.compare(SyncManifest.parse(manifest), Set.of(), Map.of(), "0.1.0-dev", "21.1.251");
        assertTrue(requirements.lines().contains("Source: CurseForge (fresh exact lookup required; not a safety guarantee)"));
        assertFalse(requirements.ready());
        var resolver = new SourceResolver((service, path, body, token) -> ("{\"data\":{\"exactMatches\":[{\"file\":" + metadata + "}]}}").getBytes(StandardCharsets.UTF_8));
        var resolved = resolver.resolve(SyncManifest.parse(manifest), new DiscoveryCancellation());
        var plan = InstallationPlan.create(InstallationPlanTest.endpoint(manifest), manifest, Set.of(SyncManifest.sha256(bytes)), null, "0.1.0-dev", "21.1.251", null, resolved);
        var store = ProfileStore.open(directory);
        var prepared = store.prepare(plan, plan.accept(true, true), Map.of(SyncManifest.sha256(bytes), jar), "4.0.44", new DiscoveryCancellation(), (action, completed, total) -> {});
        String audit = Files.readString(prepared.gameDirectory().getParent().resolve("consent.json"));
        assertFalse(audit.contains("SHA-1"));
        assertFalse(audit.contains("\"provider\""));
        assertFalse(audit.contains("forgecdn"));
        assertFalse(Files.readString(prepared.gameDirectory().getParent().resolve("manifest.json")).contains("456789"));
        assertFalse(Files.readString(prepared.gameDirectory().getParent().resolve("manifest.json")).contains("forgecdn"));
        store.verify(prepared, "4.0.44", new DiscoveryCancellation());
        result.get(jar).verify(jar, prepared.manifest().files().getFirst(), new DiscoveryCancellation());
        Files.writeString(jar, "corrupted");
        assertThrows(IOException.class, () -> result.get(jar).verify(jar, prepared.manifest().files().getFirst(), new DiscoveryCancellation()));
    }

    @Test
    void hostsOnlyExplicitlyEligibleFilesAfterBothProvidersMiss(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var inventory = Map.of(jar, ArtifactFiles.fingerprint(jar, new DiscoveryCancellation()));
        net.neoforged.neoforge.neosync.provider.ProviderTransport missing = (service, path, body, token) -> (service == ProviderHttpClient.Service.MODRINTH ? "{}" : "{\"data\":{\"exactMatches\":[]}}").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> AutomaticSources.resolve(inventory, missing, new DiscoveryCancellation()));
        assertTrue(AutomaticSources.resolve(inventory, missing, new DiscoveryCancellation(), Set.of(jar)).isEmpty());
        assertThrows(IOException.class, () -> AutomaticSources.resolve(inventory, (service, path, body, token) -> {
            if (service == ProviderHttpClient.Service.CURSEFORGE) throw new IOException("HTTP 429");
            return "{}".getBytes(StandardCharsets.UTF_8);
        }, new DiscoveryCancellation(), Set.of(jar)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "null", "\"http://mediafilez.forgecdn.net/files/456/789/mod.jar\"", "\"https://127.0.0.1/mod.jar\"",
            "\"https://mediafilez.forgecdn.net.attacker.example/files/456/789/mod.jar\"", "\"https://mediafilez.forgecdn.net/files/999/789/mod.jar\"" })
    void rejectsRestrictedOrUnapprovedDownloads(String url) throws Exception {
        String metadata = file(new byte[] { 1, 2, 3 }).replace("\"" + URL + "\"", url);
        var provider = new CurseForgeProvider((service, path, body, token) -> ("{\"data\":" + metadata + "}").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> provider.file(HINT, new DiscoveryCancellation()));
    }

    @Test
    void fingerprintIgnoresOnlyCurseForgeWhitespace() {
        assertEquals(CurseForgeProvider.fingerprint(new byte[] { 1, 2, 3, 4, 5 }), CurseForgeProvider.fingerprint(new byte[] { 1, 9, 2, 10, 3, 13, 4, 32, 5 }));
        assertEquals(1540447798L, CurseForgeProvider.fingerprint(new byte[0]));
    }

    @Test
    void permitsOnlyDisclosedCurseForgeCdnRedirectsForTheSameFile() throws Exception {
        String metadata = file(new byte[] { 1, 2, 3 });
        var provider = new CurseForgeProvider((service, path, body, token) -> ("{\"data\":" + metadata + "}").getBytes(StandardCharsets.UTF_8)).file(HINT, new DiscoveryCancellation());
        assertThrows(IllegalStateException.class, provider::audit);
        assertEquals(java.net.URI.create(URL.replace("mediafilez", "edge")), provider.redirect(java.net.URI.create(URL.replace("mediafilez", "edge"))));
        assertThrows(IOException.class, () -> provider.redirect(java.net.URI.create(URL.replace("/mod.jar", "/other.jar"))));
        assertThrows(IOException.class, () -> provider.redirect(java.net.URI.create(URL.replace("mediafilez.forgecdn.net", "attacker.example"))));
        assertThrows(IOException.class, () -> provider.redirect(java.net.URI.create(URL.replace("https:", "http:"))));
    }
}
