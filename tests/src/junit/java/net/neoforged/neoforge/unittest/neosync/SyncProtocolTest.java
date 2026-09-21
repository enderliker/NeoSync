/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.RequirementReport;
import net.neoforged.neoforge.neosync.protocol.SyncCapability;
import net.neoforged.neoforge.neosync.protocol.SyncEndpoint;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SyncProtocolTest {
    static final String HASH = "a".repeat(64);

    static byte[] manifest() {
        return ("""
                {"schemaVersion":1,"serverId":"95d92dfd-680a-4a1c-b983-3d7a7fa8b14b",
                 "revision":"test","displayName":"Test Server","minecraftVersion":"1.21.1",
                 "loader":{"id":"neosync","version":"0.1.0-dev","neoForgeVersion":"21.1.251"},
                 "files":[{"sha256":"%s","size":12,"fileName":"test-mod.jar","required":true,
                   "mods":[{"id":"test_mod","version":"1.0","displayName":"Test Mod","dependencies":[]}],
                   "sources":[{"type":"server"}]}]}
                """).formatted(HASH).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject editableManifest() {
        return JsonParser.parseString(new String(manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void reject(JsonObject value) {
        assertThrows(IOException.class, () -> SyncManifest.parse(value.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesRequiredArtifactAndEmptyInventory() throws Exception {
        assertEquals("test_mod", SyncManifest.parse(manifest()).files().getFirst().mods().getFirst().id());
        var empty = editableManifest();
        empty.getAsJsonArray("files").remove(0);
        assertTrue(SyncManifest.parse(empty.toString().getBytes(StandardCharsets.UTF_8)).files().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"x\":1,\"x\":2}", "{\"x\":1}{}", "{\"x\":1}//comment", "{\"x\":NaN}", "{\"x\":1e999999999999999999999999999999999999999}", "{'x':1}" })
    void rejectsAmbiguousOrNonstandardJson(String text) {
        assertThrows(IOException.class, () -> SyncJson.parse(text.getBytes(StandardCharsets.UTF_8), 1024));
    }

    @Test
    void boundsBytesDepthAndUtf8() {
        assertThrows(IOException.class, () -> SyncJson.parse(new byte[1025], 1024));
        assertThrows(IOException.class, () -> SyncJson.parse(("[".repeat(18) + "0" + "]".repeat(18)).getBytes(StandardCharsets.UTF_8), 1024));
        assertThrows(IOException.class, () -> SyncJson.parse(new byte[] { '"', (byte) 0xC3, '"' }, 1024));
    }

    @Test
    void rejectsUnknownFieldsAndTrustClaims() {
        var root = editableManifest();
        root.addProperty("command", "run");
        reject(root);
        root = editableManifest();
        root.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("verified", true);
        reject(root);
    }

    @ParameterizedTest
    @ValueSource(strings = { "../outside.jar", "/outside.jar", "nested/mod.jar", "bad\\mod.jar" })
    void rejectsFilenamePaths(String fileName) {
        var root = editableManifest();
        root.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("fileName", fileName);
        reject(root);
    }

    @Test
    void rejectsDuplicateFilesAndReservedModIds() {
        var root = editableManifest();
        var files = root.getAsJsonArray("files");
        files.add(files.get(0).deepCopy());
        reject(root);
        root = editableManifest();
        root.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonArray("mods").get(0).getAsJsonObject().addProperty("id", "neoforge");
        reject(root);
    }

    @Test
    void rejectsUnsatisfiedDependencies() {
        var root = editableManifest();
        root.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonArray("mods").get(0).getAsJsonObject()
                .getAsJsonArray("dependencies").add(JsonParser.parseString("{\"id\":\"missing_library\",\"versionRange\":\"[1.0,2.0)\",\"type\":\"required\"}"));
        reject(root);
    }

    @Test
    void rejectsOversizedArtifactAndTotal() {
        var root = editableManifest();
        root.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("size", SyncManifest.MAX_FILE_BYTES + 1);
        reject(root);
        root = editableManifest();
        var files = root.getAsJsonArray("files");
        var template = files.remove(0).getAsJsonObject();
        template.getAsJsonArray("mods").remove(0);
        template.addProperty("size", SyncManifest.MAX_FILE_BYTES);
        for (int i = 0; i < 9; i++) {
            var file = template.deepCopy();
            file.addProperty("sha256", String.format("%064x", i));
            files.add(file);
        }
        reject(root);
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://example.org/mod.jar", "file:///tmp/mod.jar", "https://user:password@example.org/mod.jar", "https://example.org:22/mod.jar", "https://example.org/mod.jar#fragment" })
    void rejectsUnsafeSourceSyntax(String url) {
        var root = editableManifest();
        var source = root.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonArray("sources").get(0).getAsJsonObject();
        source.addProperty("type", "external");
        source.addProperty("url", url);
        reject(root);
    }

    @Test
    void treatsProviderHintsAsUnverified() throws Exception {
        var root = editableManifest();
        var source = root.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonArray("sources").get(0).getAsJsonObject();
        source.addProperty("type", "external");
        source.addProperty("url", "https://example.org/mod.jar");
        source.add("provider", JsonParser.parseString("{\"id\":\"modrinth\",\"projectId\":\"example\",\"fileId\":\"version\"}"));
        var manifest = SyncManifest.parse(root.toString().getBytes(StandardCharsets.UTF_8));
        var report = RequirementReport.compare(manifest, Set.of(), Map.of(), "0.1.0-dev", "21.1.251");
        assertTrue(report.lines().contains("Source: example.org (unverified)"));
    }

    @Test
    void requiresBothFileIdentityAndLoadedVersion() throws Exception {
        var manifest = SyncManifest.parse(manifest());
        assertTrue(RequirementReport.compare(manifest, Set.of(HASH), Map.of("test_mod", "1.0"), "0.1.0-dev", "21.1.251").ready());
        assertFalse(RequirementReport.compare(manifest, Set.of(), Map.of("test_mod", "1.0"), "0.1.0-dev", "21.1.251").ready());
        assertFalse(RequirementReport.compare(manifest, Set.of(HASH), Map.of("test_mod", "2.0"), "0.1.0-dev", "21.1.251").ready());
        assertFalse(RequirementReport.compare(manifest, Set.of(HASH), Map.of("test_mod", "1.0"), "0.2.0", "21.1.251").ready());
    }

    @Test
    void reportsConflictingExtraClientMods() throws Exception {
        var root = editableManifest();
        root.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonArray("mods").get(0).getAsJsonObject()
                .getAsJsonArray("dependencies").add(JsonParser.parseString("{\"id\":\"extra_mod\",\"versionRange\":\"[1.0,2.0)\",\"type\":\"incompatible\"}"));
        var manifest = SyncManifest.parse(root.toString().getBytes(StandardCharsets.UTF_8));
        var report = RequirementReport.compare(manifest, Set.of(HASH), Map.of("test_mod", "1.0", "extra_mod", "1.5"), "0.1.0-dev", "21.1.251");
        assertFalse(report.ready());
        assertEquals(0, report.missingFiles());
        assertTrue(report.lines().stream().anyMatch(line -> line.contains("Conflicting installed mod: extra_mod 1.5")));
        assertTrue(RequirementReport.compare(manifest, Set.of(HASH), Map.of("test_mod", "1.0", "extra_mod", "2.0"), "0.1.0-dev", "21.1.251").ready());
    }

    @Test
    void validatesCapabilityAndDerivesUrlLocally() throws Exception {
        var capability = new SyncCapability(8443, HASH);
        assertEquals(capability, SyncCapability.parse(capability.toJson()));
        var endpoint = SyncEndpoint.create("EXAMPLE.org.", 25565, capability);
        assertEquals("https://example.org:8443/.well-known/neosync/v1/servers/25565/manifests/" + HASH + ".json", endpoint.manifestUri().toString());
        assertEquals("::1", SyncEndpoint.normalizeHost("[::1]"));
        assertThrows(IOException.class, () -> SyncEndpoint.create("example.org", 25565, new SyncCapability(22, HASH)));
        var unsupported = capability.toJson();
        unsupported.getAsJsonArray("protocols").set(0, new com.google.gson.JsonPrimitive(2));
        assertThrows(IOException.class, () -> SyncCapability.parse(unsupported));
    }

    @ParameterizedTest
    @ValueSource(strings = { "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "192.0.2.1", "198.18.0.1", "203.0.113.1", "::1", "::", "fc00::1", "fe80::1", "2001:db8::1", "2002:7f00:1::", "::ffff:127.0.0.1" })
    void blocksNonPublicDestinations(String address) throws Exception {
        assertFalse(SyncEndpoint.isPublic(InetAddress.getByName(address)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "2001:4860:4860::8888" })
    void allowsPublicDestinations(String address) throws Exception {
        assertTrue(SyncEndpoint.isPublic(InetAddress.getByName(address)));
    }

    @Test
    void distinguishesLocalApprovalFromBlockedSpecialAddresses() throws Exception {
        assertTrue(SyncEndpoint.isLocal(InetAddress.getByName("fd12:3456::1")));
        assertFalse(SyncEndpoint.isPublic(InetAddress.getByName("fd12:3456::1")));
        assertFalse(SyncEndpoint.isLocal(InetAddress.getByName("169.254.169.254")));
        assertFalse(SyncEndpoint.isPublic(InetAddress.getByName("3fff::1")));
    }

    @Test
    void fingerprintsWithoutFollowingFileSymlinks(@TempDir Path directory) throws Exception {
        Path file = Files.writeString(directory.resolve("mod.jar"), "test content");
        var fingerprint = ArtifactFiles.fingerprint(file, new DiscoveryCancellation());
        assertEquals(SyncManifest.sha256("test content".getBytes(StandardCharsets.UTF_8)), fingerprint.sha256());
        Path link = Files.createSymbolicLink(directory.resolve("link.jar"), file);
        assertThrows(IOException.class, () -> ArtifactFiles.fingerprint(link, new DiscoveryCancellation()));
        var cancelled = new DiscoveryCancellation();
        cancelled.close();
        assertThrows(IOException.class, () -> ArtifactFiles.fingerprint(file, cancelled));
    }
}
