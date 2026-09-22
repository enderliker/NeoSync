/*
 * Copyright (c) NeoSync contributors
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforge.neosync.provider.AutomaticSources;
import net.neoforged.neoforge.neosync.provider.ModrinthProvider;
import net.neoforged.neoforge.neosync.provider.ProviderHttpClient;
import net.neoforged.neoforge.neosync.provider.ProviderTransport;
import net.neoforged.neoforge.neosync.provider.SourceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModrinthProviderTest {
    private static final String URL = "https://cdn.modrinth.com/data/abcdefgh/versions/ijklmnop/mod.jar";
    private static final SyncManifest.ProviderHint HINT = new SyncManifest.ProviderHint("modrinth", "abcdefgh", "ijklmnop");

    private static String version(long size, String hash) {
        return """
                {"id":"ijklmnop","project_id":"abcdefgh","game_versions":["1.21.1"],"loaders":["neoforge"],
                 "files":[{"url":"%s","size":%d,"hashes":{"sha512":"%s"}}]}
                """.formatted(URL, size, hash);
    }

    private static byte[] manifest(long size, String hash) {
        var root = JsonParser.parseString(new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        var file = root.getAsJsonArray("files").get(0).getAsJsonObject();
        file.addProperty("size", size);
        file.addProperty("sha256", hash);
        var source = file.getAsJsonArray("sources").get(0).getAsJsonObject();
        source.addProperty("url", URL);
        source.add("provider", JsonParser.parseString("{\"id\":\"modrinth\",\"projectId\":\"abcdefgh\",\"fileId\":\"ijklmnop\"}"));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ProviderTransport transport(String response) {
        return (service, path, body, token) -> {
            token.check();
            assertEquals(ProviderHttpClient.Service.MODRINTH, service);
            return response.getBytes(StandardCharsets.UTF_8);
        };
    }

    @Test
    void batchesAndDeduplicatesVersionHintsWithoutDownloading() throws Exception {
        var requests = new AtomicInteger();
        var provider = new ModrinthProvider((service, path, body, token) -> {
            requests.incrementAndGet();
            assertTrue(path.startsWith("/v2/versions?ids="));
            assertFalse(path.contains("cdn"));
            assertEquals("", body);
            return ("[" + version(12, "a".repeat(128)) + "]").getBytes(StandardCharsets.UTF_8);
        });
        assertEquals(1, provider.versions(List.of(HINT, HINT), new DiscoveryCancellation()).size());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://cdn.modrinth.com/data/abcdefgh/versions/x/a.jar", "https://127.0.0.1/a.jar", "https://cdn.modrinth.com.attacker.example/a.jar",
            "https://cdn.modrinth.com:8443/data/abcdefgh/versions/x/a.jar", "https://cdn.modrinth.com/data/othermod/versions/x/a.jar", "https://user@cdn.modrinth.com/a.jar",
            "https://cdn.modrinth.com/data/abcdefgh/versions/x/a.jar?token=secret" })
    void rejectsUnapprovedProviderSources(String source) {
        var provider = new ModrinthProvider(transport("[" + version(12, "a".repeat(128)).replace(URL, source) + "]"));
        assertThrows(IOException.class, () -> provider.versions(List.of(HINT), new DiscoveryCancellation()));
    }

    @Test
    void distinguishesAbsenceMismatchMalformedAndProviderFailure() throws Exception {
        var manifest = SyncManifest.parse(manifest(12, SyncProtocolTest.HASH));
        assertTrue(new ModrinthProvider(transport("[]")).versions(List.of(HINT), new DiscoveryCancellation()).isEmpty());
        assertThrows(IOException.class, () -> new SourceResolver(transport("[]")).resolve(manifest, new DiscoveryCancellation()));
        for (String response : List.of("[" + version(13, "a".repeat(128)) + "]", "[" + version(12, "a".repeat(128)).replace("abcdefgh", "different") + "]", "{", "[" + version(12, "bad") + "]",
                "[" + version(12, "a".repeat(128)).replace("neoforge", "fabric") + "]", "[" + version(12, "a".repeat(128)) + "," + version(12, "a".repeat(128)) + "]", " ".repeat(ProviderHttpClient.MAX_BYTES + 1))) {
            assertThrows(IOException.class, () -> new SourceResolver(transport(response)).resolve(manifest, new DiscoveryCancellation()));
        }
        var failure = new SourceResolver((service, path, body, token) -> {
            throw new IOException("HTTP 429");
        });
        assertEquals("HTTP 429", assertThrows(IOException.class, () -> failure.resolve(manifest, new DiscoveryCancellation())).getMessage());
        var token = new DiscoveryCancellation();
        token.close();
        assertThrows(IOException.class, () -> new SourceResolver(transport("[]")).resolve(manifest, token));
    }

    @Test
    void identifiesExistingServerBytesAndVerifiesBothHashesBeforePublishing(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        byte[] content = Files.readAllBytes(jar);
        String sha512 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(content));
        var fingerprint = ArtifactFiles.fingerprint(jar, new DiscoveryCancellation());
        var found = AutomaticSources.resolve(Map.of(jar, fingerprint), transport("{\"" + sha512 + "\":" + version(content.length, sha512) + "}"), new DiscoveryCancellation());
        assertEquals(HINT, found.get(jar).identity());
        byte[] bytes = manifest(content.length, fingerprint.sha256());
        var sources = new SourceResolver(transport("[" + version(content.length, sha512) + "]")).resolve(SyncManifest.parse(bytes), new DiscoveryCancellation());
        var plan = InstallationPlan.create(InstallationPlanTest.endpoint(bytes), bytes, Set.of(fingerprint.sha256()), null, "0.1.0-dev", "21.1.251", null, sources);
        assertThrows(IOException.class, () -> plan.accept(false, true));
        assertThrows(IOException.class, () -> plan.accept(true, false));
        assertTrue(plan.reviewLines().stream().anyMatch(line -> line.contains("provider metadata matched") && line.contains("not a safety guarantee")));
        var store = ProfileStore.open(Files.createDirectory(directory.resolve("game")));
        var prepared = store.prepare(plan, plan.accept(true, true), Map.of(fingerprint.sha256(), jar), "4.0.44", new DiscoveryCancellation(), (a, b, c) -> {});
        var reopened = ProfileStore.open(prepared.gameDirectory());
        reopened.verify(prepared, "4.0.44", new DiscoveryCancellation());
        assertEquals(prepared, reopened.active().orElseThrow());
        var wrong = new SourceResolver(transport("[" + version(content.length, "b".repeat(128)) + "]")).resolve(SyncManifest.parse(bytes), new DiscoveryCancellation());
        var changed = InstallationPlan.create(InstallationPlanTest.endpoint(bytes), bytes, Set.of(fingerprint.sha256()), prepared.manifest(), "0.1.0-dev", "21.1.251", null, wrong);
        assertThrows(IOException.class, () -> plan.accept(true, true).require(changed));
        assertThrows(IOException.class, () -> store.prepare(changed, changed.accept(true, true), Map.of(fingerprint.sha256(), jar), "4.0.44", new DiscoveryCancellation(), (a, b, c) -> {}));
        assertEquals(prepared, store.prepared(plan.identity()).orElseThrow());
        assertEquals(fingerprint, ArtifactFiles.fingerprint(jar, new DiscoveryCancellation()));
        Path audit = prepared.gameDirectory().getParent().resolve("consent.json");
        Files.writeString(audit, Files.readString(audit).replace(sha512, "c".repeat(128)));
        assertThrows(IOException.class, () -> store.verify(prepared, "4.0.44", new DiscoveryCancellation()));
    }

    @Test
    void preservesProviderRetryInstructionsAndRejectsArbitraryApiPaths() throws Exception {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        assertEquals(now.plusSeconds(90), ProviderHttpClient.retryAfter("90", null, now));
        assertEquals(now.plusSeconds(120), ProviderHttpClient.retryAfter("Tue, 22 Sep 2026 00:02:00 GMT", null, now));
        assertEquals(now.plusSeconds(30), ProviderHttpClient.retryAfter(null, "30", now));
        assertEquals(now.plusSeconds(60), ProviderHttpClient.retryAfter("malformed", null, now));
        var client = new ProviderHttpClient();
        assertThrows(IOException.class, () -> client.request(ProviderHttpClient.Service.MODRINTH, "https://attacker.example", "", new DiscoveryCancellation()));
        assertThrows(IOException.class, () -> client.request(ProviderHttpClient.Service.CURSEFORGE, "/v1/mods/123", "", new DiscoveryCancellation()));
    }
}
