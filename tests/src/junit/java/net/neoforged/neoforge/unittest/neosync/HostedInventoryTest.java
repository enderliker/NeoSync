/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforge.neosync.server.HostedInventory;
import net.neoforged.neoforge.neosync.server.HostingPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostedInventoryTest {
    @TempDir
    Path directory;

    @Test
    void requiresEnablementAndAllDeclarationsForExactBytes() throws Exception {
        var selection = JsonParser.parseString("""
                {"sources":[{"type":"server"}],"hosting":{"authoredByAdministrator":true,
                "exclusiveToServer":true,"distributionRights":true,"sha256":"%s"}}
                """.formatted(SyncProtocolTest.HASH)).getAsJsonObject();
        var policy = HostingPolicy.parse(JsonParser.parseString("{\"enabled\":true}"));
        assertTrue(policy.validateSelection(selection, SyncProtocolTest.HASH));
        assertThrows(IOException.class, () -> HostingPolicy.parse(null).validateSelection(selection, SyncProtocolTest.HASH));
        assertThrows(IOException.class, () -> policy.validateSelection(selection, "b".repeat(64)));
        for (String field : new String[] { "authoredByAdministrator", "exclusiveToServer", "distributionRights" }) {
            var changed = selection.deepCopy();
            changed.getAsJsonObject("hosting").addProperty(field, false);
            assertThrows(IOException.class, () -> policy.validateSelection(changed, SyncProtocolTest.HASH));
            changed.getAsJsonObject("hosting").remove(field);
            assertThrows(IOException.class, () -> policy.validateSelection(changed, SyncProtocolTest.HASH));
        }
        selection.getAsJsonArray("sources").add(JsonParser.parseString("{\"type\":\"external\",\"url\":\"https://example.org/mod.jar\"}"));
        assertThrows(IOException.class, () -> policy.validateSelection(selection, SyncProtocolTest.HASH));
    }

    @Test
    void copiesVerifiedBytesAndNeverServesTheLiveFile() throws Exception {
        Path source = directory.resolve("own.jar");
        byte[] bytes = { 1, 2, 3 };
        Files.write(source, bytes);
        var token = new DiscoveryCancellation();
        var fingerprint = ArtifactFiles.fingerprint(source, token);
        try (var inventory = new HostedInventory(directory.resolve("hosting"), 16)) {
            inventory.add(source, fingerprint, token);
            Files.write(source, new byte[] { 4, 5, 6 });
            var snapshot = inventory.seal();
            assertArrayEquals(bytes, Files.readAllBytes(snapshot.get(fingerprint.sha256()).path()));
            assertThrows(UnsupportedOperationException.class, snapshot::clear);
            assertThrows(IOException.class, () -> inventory.add(source, fingerprint, token));
            assertThrows(IOException.class, () -> new HostedInventory(directory.resolve("hosting"), 16));
        }
        assertTrue(Files.notExists(directory.resolve("hosting/snapshot")));
    }

    @Test
    void refusesQuotaCorruptionCancellationAndSymlinks() throws Exception {
        Path source = directory.resolve("own.jar");
        Files.write(source, new byte[] { 1, 2, 3 });
        var token = new DiscoveryCancellation();
        var fingerprint = ArtifactFiles.fingerprint(source, token);
        try (var inventory = new HostedInventory(directory.resolve("hosting"), 2)) {
            assertThrows(IOException.class, () -> inventory.add(source, fingerprint, token));
            assertTrue(inventory.seal().isEmpty());
        }
        try (var inventory = new HostedInventory(directory.resolve("hosting"), 16)) {
            assertThrows(IOException.class, () -> inventory.add(source, new ArtifactFiles.Fingerprint("f".repeat(64), 3), token));
            Path link = directory.resolve("linked.jar");
            Files.createSymbolicLink(link, source);
            assertThrows(IOException.class, () -> inventory.add(link, fingerprint, token));
            token.close();
            assertThrows(IOException.class, () -> inventory.add(source, fingerprint, token));
            try (var entries = Files.list(directory.resolve("hosting/snapshot"))) {
                assertEquals(0, entries.count());
            }
        }
        Path link = directory.resolve("linked-directory");
        Files.createSymbolicLink(link, directory);
        assertThrows(IOException.class, () -> new HostedInventory(link.resolve("unsafe"), 16));
    }

    @Test
    void reclaimsOnlyPrivateSnapshotFilesAfterAnInterruptedLifetime() throws Exception {
        Path root = Files.createDirectory(directory.resolve("hosting"));
        Path snapshot = Files.createDirectory(root.resolve("snapshot"));
        Files.write(snapshot.resolve(SyncManifest.sha256(new byte[] { 1 }) + ".jar"), new byte[] { 1 });
        try (var inventory = new HostedInventory(root, 16)) {
            assertTrue(inventory.seal().isEmpty());
        }
        Files.createDirectory(snapshot);
        Files.writeString(snapshot.resolve("personal.txt"), "preserve");
        assertThrows(IOException.class, () -> new HostedInventory(root, 16));
        assertEquals("preserve", Files.readString(snapshot.resolve("personal.txt")));
    }
}
