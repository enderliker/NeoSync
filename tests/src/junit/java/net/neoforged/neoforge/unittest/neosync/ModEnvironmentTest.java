/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.JarMetadata;
import net.neoforged.neoforge.neosync.protocol.ModEnvironment;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforge.neosync.provider.CurseForgeProvider;
import net.neoforged.neoforge.neosync.provider.ModrinthProvider;
import net.neoforged.neoforge.neosync.provider.ProviderArtifact;
import net.neoforged.neoforge.neosync.provider.ProviderHttpClient;
import net.neoforged.neoforge.neosync.server.AdminSelection;
import net.neoforged.neoforge.neosync.server.ModEnvironmentDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModEnvironmentTest {
    @Test
    void requiresExplicitWholeFileDeclarations(@TempDir Path directory) throws Exception {
        for (var side : ModEnvironment.values()) {
            String declaration = side == ModEnvironment.UNKNOWN ? "" : "\n[modproperties.test_mod]\nneosyncSide=\"" + side.name() + "\"\n";
            Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML + declaration, Map.of());
            assertEquals(side, JarMetadata.environment(jar, new DiscoveryCancellation()));
        }
        Path mixed = JarMetadataTest.jar(directory, JarMetadataTest.TOML + "\n[[mods]]\nmodId=\"other_mod\"\nversion=\"1.0\"\n[modproperties.test_mod]\nneosyncSide=\"CLIENT\"\n", Map.of());
        assertEquals(ModEnvironment.UNKNOWN, JarMetadata.environment(mixed, new DiscoveryCancellation()));
        Path invalid = JarMetadataTest.jar(directory, JarMetadataTest.TOML + "\n[modproperties.test_mod]\nneosyncSide=\"guess\"\n", Map.of());
        assertThrows(IOException.class, () -> JarMetadata.environment(invalid, new DiscoveryCancellation()));
        Path dependency = JarMetadataTest.jar(directory, JarMetadataTest.TOML + "\n[[dependencies.test_mod]]\nmodId=\"minecraft\"\nside=\"BOTH\"\n", Map.of());
        assertEquals(ModEnvironment.UNKNOWN, JarMetadata.environment(dependency, new DiscoveryCancellation()));
    }

    @Test
    void mapsModrinthSidesWithoutGuessing() throws Exception {
        for (String client : List.of("required", "optional", "unsupported", "unknown")) {
            for (String server : List.of("required", "unsupported", "unknown")) {
                var provider = new ModrinthProvider((service, path, body, token) -> bytes("[{\"id\":\"abcdefgh\",\"client_side\":\"" + client + "\",\"server_side\":\"" + server + "\"}]"));
                var expected = client.equals("unknown") || server.equals("unknown") ? ModEnvironment.UNKNOWN
                        : client.equals("unsupported") ? (server.equals("unsupported") ? ModEnvironment.UNKNOWN : ModEnvironment.SERVER)
                                : server.equals("unsupported") ? ModEnvironment.CLIENT : ModEnvironment.BOTH;
                assertEquals(expected, provider.environments(List.of("abcdefgh"), new DiscoveryCancellation()).get("abcdefgh"));
            }
        }
    }

    @Test
    void keepsManualExclusionsAndSuggestsNewFiles(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var candidate = new AdminSelection.Candidate(jar, "Test mod", ModEnvironment.BOTH);
        Path config = directory.resolve("config/neosync-server.json");
        var selection = new AdminSelection(config, List.of(candidate), 25565);
        assertTrue(selection.state().getAsJsonArray("files").get(0).getAsJsonObject().get("selected").getAsBoolean());
        assertFalse(selection.state().get("enabled").getAsBoolean());
        var request = AdminSelectionTest.request(selection, "automatic");
        request.getAsJsonArray("files").remove(0);
        selection.save(bytes(request.toString()));
        byte[] saved = Files.readAllBytes(config);
        Path added = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var restarted = new AdminSelection(config, List.of(candidate, new AdminSelection.Candidate(added, "Added mod", ModEnvironment.CLIENT)), 25565);
        var files = restarted.state().getAsJsonArray("files");
        assertFalse(files.get(0).getAsJsonObject().get("selected").getAsBoolean());
        assertTrue(files.get(1).getAsJsonObject().get("selected").getAsBoolean());
        assertArrayEquals(saved, Files.readAllBytes(config));
    }

    @Test
    void inspectsLocalDeclarationsWithoutProviderRequestsAndFailsClosed(@TempDir Path directory) throws Exception {
        Path declared = JarMetadataTest.jar(directory, JarMetadataTest.TOML + "\n[modproperties.test_mod]\nneosyncSide=\"SERVER\"\n", Map.of());
        var local = ModEnvironmentDetector.detect(List.of(new AdminSelection.Candidate(declared, "Declared")), (service, path, body, token) -> {
            throw new AssertionError("Local declarations must not query providers.");
        });
        assertEquals(ModEnvironment.SERVER, local.getFirst().environment());
        Path unknown = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var failed = ModEnvironmentDetector.detect(List.of(new AdminSelection.Candidate(unknown, "Unknown")), (service, path, body, token) -> {
            throw new IOException("Unavailable");
        });
        assertEquals(ModEnvironment.UNKNOWN, failed.getFirst().environment());
    }

    @ParameterizedTest
    @ValueSource(strings = { "modrinth", "curseforge" })
    void detectsExactProviderFilesAndPersistsOnlyLocalSelection(String provider, @TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        byte[] content = Files.readAllBytes(jar);
        String sha512 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(content));
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        String curseForgeFile = "{\"id\":456789,\"modId\":123,\"gameId\":432,\"isAvailable\":true,\"gameVersions\":[\"1.21.1\",\"NeoForge\"],\"fileLength\":" + content.length
                + ",\"downloadUrl\":\"https://mediafilez.forgecdn.net/files/456/789/mod.jar\",\"hashes\":[{\"algo\":1,\"value\":\"" + sha1 + "\"}],\"fileFingerprint\":" + CurseForgeProvider.fingerprint(content)
                + ",\"sortableGameVersions\":[{\"gameVersionTypeId\":99,\"gameVersionName\":\"Both\"}]}";
        var calls = new ArrayList<String>();
        var detected = ModEnvironmentDetector.detect(List.of(new AdminSelection.Candidate(jar, "Test mod")), (service, path, body, token) -> {
            calls.add(path);
            if (service == ProviderHttpClient.Service.MODRINTH) {
                if (provider.equals("curseforge")) return bytes("{}");
                if (path.startsWith("/v2/projects")) return bytes("[{\"id\":\"abcdefgh\",\"client_side\":\"required\",\"server_side\":\"required\"}]");
                return bytes("{\"" + sha512 + "\":{\"id\":\"ijklmnop\",\"project_id\":\"abcdefgh\",\"game_versions\":[\"1.21.1\"],\"loaders\":[\"neoforge\"],\"files\":[{\"url\":\"https://cdn.modrinth.com/data/abcdefgh/versions/ijklmnop/mod.jar\",\"size\":" + content.length + ",\"hashes\":{\"sha512\":\"" + sha512 + "\"}}]}}");
            }
            assertEquals("curseforge", provider);
            if (path.endsWith("version-types")) return bytes("{\"data\":[{\"id\":99,\"gameId\":432,\"name\":\"Environment\"}]}");
            if (path.contains("fingerprints")) return bytes("{\"data\":{\"exactMatches\":[{\"file\":" + curseForgeFile + "}]}}");
            return bytes("{\"data\":" + curseForgeFile + "}");
        });
        assertEquals(ModEnvironment.BOTH, detected.getFirst().environment());
        Path config = directory.resolve("config/neosync-server.json");
        var selection = new AdminSelection(config, detected, 25565);
        selection.save(bytes(AdminSelectionTest.request(selection, "automatic").toString()));
        String saved = Files.readString(config);
        for (String forbidden : List.of("456789", "modId", "project_id", "downloadUrl", "forgecdn.net", sha1, sha512, "Environment")) assertFalse(saved.contains(forbidden));
        assertTrue(saved.contains(jar.getFileName().toString()));
        assertTrue(saved.contains("\"resolveProviders\":true"));
        assertTrue(calls.contains("/v2/version_files"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "Client", "Server", "Both", "Unspecified" })
    void readsFreshCurseForgeEnvironmentEachTimeWithoutRetainingApiData(String environment) throws Exception {
        byte[] content = { 1, 2, 3 };
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        var expected = new ProviderArtifact(new SyncManifest.ProviderHint("curseforge", "123", "456789"),
                java.net.URI.create("https://mediafilez.forgecdn.net/files/456/789/mod.jar"), 3, "SHA-1", hash, false);
        var calls = new ArrayList<String>();
        var provider = new CurseForgeProvider((service, path, body, token) -> {
            assertEquals(ProviderHttpClient.Service.CURSEFORGE, service);
            calls.add(path);
            if (path.endsWith("version-types")) return bytes("{\"data\":[{\"id\":99,\"gameId\":432,\"name\":\"Environment\"}]}");
            return bytes("{\"data\":{\"id\":456789,\"modId\":123,\"gameId\":432,\"isAvailable\":true,\"gameVersions\":[\"1.21.1\",\"NeoForge\"],\"fileLength\":3,\"downloadUrl\":\"https://mediafilez.forgecdn.net/files/456/789/mod.jar\",\"hashes\":[{\"algo\":1,\"value\":\"" + hash + "\"}],\"sortableGameVersions\":[{\"gameVersionTypeId\":99,\"gameVersionName\":\"" + environment + "\"}]}}");
        });
        var detected = switch (environment) {
            case "Client" -> ModEnvironment.CLIENT;
            case "Server" -> ModEnvironment.SERVER;
            case "Both" -> ModEnvironment.BOTH;
            default -> ModEnvironment.UNKNOWN;
        };
        assertEquals(detected, provider.environment(expected, new DiscoveryCancellation()));
        assertEquals(detected, provider.environment(expected, new DiscoveryCancellation()));
        assertEquals(List.of("/v1/mods/123/files/456789", "/v1/games/432/version-types", "/v1/mods/123/files/456789", "/v1/games/432/version-types"), calls);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
