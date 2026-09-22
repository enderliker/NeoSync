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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforge.neosync.provider.CurseForgeProvider;
import net.neoforged.neoforge.neosync.provider.ProviderHttpClient;
import net.neoforged.neoforge.neosync.provider.ProviderTransport;
import net.neoforged.neoforge.neosync.provider.SourceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurseForgeProviderTest {
    static final SyncManifest.ProviderHint HINT = new SyncManifest.ProviderHint("curseforge", "123", "456");
    static final String PAGE = "https://www.curseforge.com/minecraft/mc-mods/test-mod/files/456";
    static final String CDN = "https://edge.forgecdn.net/files/0/456/test.jar";

    static ProviderTransport transport(String distribution, String url, long size, String sha1) {
        return (service, path, body, token) -> {
            token.check();
            assertEquals(ProviderHttpClient.Service.CURSEFORGE, service);
            if (path.equals("/v1/mods")) {
                assertEquals("{\"modIds\":[123]}", body);
                return ("{\"data\":[{\"id\":123,\"gameId\":432,\"classId\":6,\"slug\":\"test-mod\",\"allowModDistribution\":" + distribution + "}]}").getBytes(StandardCharsets.UTF_8);
            }
            assertEquals("/v1/mods/files", path);
            assertEquals("{\"fileIds\":[456]}", body);
            return ("{\"data\":[{\"id\":456,\"modId\":123,\"gameId\":432,\"isAvailable\":true,\"gameVersions\":[\"1.21.1\",\"NeoForge\"],\"fileLength\":"
                    + size + ",\"hashes\":[{\"algo\":1,\"value\":\"" + sha1 + "\"}],\"downloadUrl\":" + (url == null ? "null" : "\"" + url + "\"") + "}]}").getBytes(StandardCharsets.UTF_8);
        };
    }

    static byte[] manifest(Path jar) throws Exception {
        var artifact = JarMetadataTest.artifact(jar);
        var root = JsonParser.parseString(new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        var file = root.getAsJsonArray("files").get(0).getAsJsonObject();
        file.addProperty("size", artifact.size());
        file.addProperty("sha256", artifact.sha256());
        var source = file.getAsJsonArray("sources").get(0).getAsJsonObject();
        source.addProperty("url", CDN);
        source.add("provider", JsonParser.parseString("{\"id\":\"curseforge\",\"projectId\":\"123\",\"fileId\":\"456\"}"));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void selectsPermittedDownloadsAndRestrictedExactPagesWithoutCdnBypass() throws Exception {
        var allowed = new CurseForgeProvider(transport("true", CDN, 12, "a".repeat(40))).files(List.of(HINT, HINT), new DiscoveryCancellation()).get(HINT);
        assertFalse(allowed.manual());
        assertEquals(CDN, allowed.source().toString());
        var restricted = new CurseForgeProvider(transport("false", "https://attacker.example/bypass.jar", 12, "a".repeat(40)))
                .files(List.of(HINT), new DiscoveryCancellation()).get(HINT);
        assertTrue(restricted.manual());
        assertEquals(PAGE, restricted.source().toString());
        assertEquals("SHA-1", restricted.algorithm());
    }

    @Test
    void neverInfersAuthorRestrictionsFromNullPermissionMissingUrlOrHttpErrors() {
        assertThrows(IOException.class, () -> new CurseForgeProvider(transport("null", null, 12, "a".repeat(40))).files(List.of(HINT), new DiscoveryCancellation()));
        assertThrows(IOException.class, () -> new CurseForgeProvider(transport("true", null, 12, "a".repeat(40))).files(List.of(HINT), new DiscoveryCancellation()));
        assertThrows(IOException.class, () -> new CurseForgeProvider(transport("true", "https://127.0.0.1/mod.jar", 12, "a".repeat(40))).files(List.of(HINT), new DiscoveryCancellation()));
        var failure = new CurseForgeProvider((service, path, body, token) -> {
            throw new IOException("HTTP 403");
        });
        assertEquals("HTTP 403", assertThrows(IOException.class, () -> failure.files(List.of(HINT), new DiscoveryCancellation())).getMessage());
    }

    @Test
    void rejectsWrongProviderIdentitiesAndMetadata() {
        for (var replacement : Map.of("\"modId\":123", "\"modId\":321", "\"gameId\":432", "\"gameId\":1", "\"NeoForge\"", "\"Fabric\"", "\"isAvailable\":true", "\"isAvailable\":false", "\"id\":456", "\"id\":789").entrySet()) {
            var original = transport("true", CDN, 12, "a".repeat(40));
            var provider = new CurseForgeProvider((service, path, body, token) -> new String(original.request(service, path, body, token), StandardCharsets.UTF_8)
                    .replace(replacement.getKey(), replacement.getValue()).getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> provider.files(List.of(HINT), new DiscoveryCancellation()));
        }
    }

    @Test
    void bindsManualPagesAndChangedProviderEvidenceToFreshConsent(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        byte[] bytes = manifest(jar);
        var manifest = SyncManifest.parse(bytes);
        var resolver = new SourceResolver(transport("false", null, manifest.files().getFirst().size(), "a".repeat(40)));
        var sources = resolver.resolve(manifest, new DiscoveryCancellation());
        var plan = InstallationPlan.create(InstallationPlanTest.endpoint(bytes), bytes, Set.of(), null, "0.1.0-dev", "21.1.251", null, sources);
        assertTrue(plan.hasManualDownloads());
        assertTrue(plan.reviewLines().contains("This mod requires a manual download because its author disabled automatic downloads — your browser will open."));
        assertTrue(plan.warningLines().stream().anyMatch(line -> line.contains(PAGE)));
        assertThrows(IOException.class, () -> plan.accept(true, false));
        assertThrows(IOException.class, () -> plan.accept(true, true).require(InstallationPlanTest.plan(bytes)));
        assertEquals(PAGE, plan.files().getFirst().source().toString());
    }
}
