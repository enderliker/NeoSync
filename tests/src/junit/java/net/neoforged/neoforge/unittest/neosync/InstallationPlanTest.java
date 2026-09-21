/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.SyncCapability;
import net.neoforged.neoforge.neosync.protocol.SyncEndpoint;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstallationPlanTest {
    static byte[] manifest() {
        return new String(SyncProtocolTest.manifest(), StandardCharsets.UTF_8).replace("{\"type\":\"server\"}",
                "{\"type\":\"external\",\"url\":\"https://example.org/test.jar\"}").getBytes(StandardCharsets.UTF_8);
    }

    static SyncEndpoint endpoint(byte[] bytes) throws IOException {
        return SyncEndpoint.create("EXAMPLE.org.", 25565, new SyncCapability(8443, SyncManifest.sha256(bytes)));
    }

    static InstallationPlan plan(byte[] bytes) throws IOException {
        return InstallationPlan.create(endpoint(bytes), bytes, Set.of(), null, "0.1.0-dev", "21.1.251");
    }

    @Test
    void snapshotsBytesAndSeparatesAvailableFilesFromActivation() throws Exception {
        byte[] bytes = manifest();
        var plan = InstallationPlan.create(endpoint(bytes), bytes, Set.of(SyncProtocolTest.HASH), null, "0.1.0-dev", "21.1.251");
        bytes[0] = 0;
        plan.manifestBytes()[0] = 0;
        assertEquals('{', plan.manifestBytes()[0]);
        assertEquals(12, plan.totalBytes());
        assertEquals(0, plan.downloadBytes());
        assertEquals("example.org", plan.identity().host());
        assertTrue(plan.reviewLines().stream().anyMatch(line -> line.contains("1 mod in 1 file; 1 file available; 0 files to download")));
        assertTrue(plan.reviewLines().stream().anyMatch(line -> line.contains("available for verified copying")));
    }

    @Test
    void requiresBothDecisionsAndNeverReusesPersistedConsent() throws Exception {
        var plan = plan(manifest());
        assertThrows(IOException.class, () -> plan.accept(false, false));
        assertThrows(IOException.class, () -> plan.accept(true, false));
        assertThrows(IOException.class, () -> plan.accept(false, true));
        var consent = plan.accept(true, true);
        consent.require(plan);
        assertThrows(IOException.class, () -> consent.require(plan(manifest())));
        byte[] changed = new String(manifest(), StandardCharsets.UTF_8).replace("test.jar", "replacement.jar").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> consent.require(plan(changed)));
    }

    @Test
    void separatesIdentityByLogicalAddressAndOrigin() throws Exception {
        var plan = plan(manifest());
        for (var endpoint : new SyncEndpoint[] {
                SyncEndpoint.create("another.example.org", 25565, new SyncCapability(8443, plan.digest())),
                SyncEndpoint.create("example.org", 25566, new SyncCapability(8443, plan.digest())),
                SyncEndpoint.create("example.org", 25565, new SyncCapability(443, plan.digest())) }) {
            var other = InstallationPlan.create(endpoint, manifest(), Set.of(), null, "0.1.0-dev", "21.1.251");
            assertNotEquals(plan.identity(), other.identity());
        }
        byte[] changed = new String(manifest(), StandardCharsets.UTF_8).replace("95d92dfd", "a5d92dfd").getBytes(StandardCharsets.UTF_8);
        assertNotEquals(plan.identity(), plan(changed).identity());
    }

    @Test
    void rejectsChangedSnapshotLoaderAndServerOnlySource() throws Exception {
        byte[] bytes = manifest();
        assertThrows(IOException.class, () -> InstallationPlan.create(endpoint(bytes), SyncProtocolTest.manifest(), Set.of(), null, "0.1.0-dev", "21.1.251"));
        assertThrows(IOException.class, () -> InstallationPlan.create(endpoint(bytes), bytes, Set.of(), null, "0.2", "21.1.251"));
        assertThrows(IOException.class, () -> InstallationPlan.create(endpoint(bytes), bytes, Set.of(), null, "0.1.0-dev", "21.1.252"));
        assertThrows(IOException.class, () -> plan(SyncProtocolTest.manifest()));
    }

    @Test
    void reviewsRemovedAndReplacedFiles() throws Exception {
        var previous = SyncManifest.parse(manifest());
        byte[] changed = new String(manifest(), StandardCharsets.UTF_8).replace("\"1.0\"", "\"2.0\"").replace(SyncProtocolTest.HASH, "b".repeat(64)).getBytes(StandardCharsets.UTF_8);
        var plan = InstallationPlan.create(endpoint(changed), changed, Set.of(), previous, "0.1.0-dev", "21.1.251");
        assertTrue(plan.reviewLines().stream().anyMatch(line -> line.contains("Replace in the new profile: test_mod 1.0 with 2.0")));
        assertTrue(plan.reviewLines().stream().anyMatch(line -> line.contains("Omit previous file")));
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://example.org/mod.jar", "https://user@example.org/mod.jar", "https://example.org/mod.jar?token=secret", "https://example.org/mod.jar#fragment", "https://example.org:22/mod.jar" })
    void rejectsSourcesThatCannotBeSafelyRecorded(String url) {
        assertThrows(IOException.class, () -> InstallationPlan.externalSource(URI.create(url)));
    }
}
