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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import net.neoforged.neoforge.neosync.protocol.RevisionCheck;
import net.neoforged.neoforge.neosync.protocol.SyncEndpoint;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforge.neosync.protocol.SyncRevision;
import org.junit.jupiter.api.Test;

class RevisionCheckTest {
    @Test
    void requiresMatchingIdentityDigestLoadedBytesAndLoadedMods() throws Exception {
        byte[] bytes = InstallationPlanTest.manifest();
        var manifest = SyncManifest.parse(bytes);
        var endpoint = InstallationPlanTest.endpoint(bytes);
        var identity = new InstallationPlan.Identity(endpoint.host(), endpoint.gamePort(), InstallationPlan.origin(endpoint.manifestUri()), manifest.serverId());
        var active = new ProfileStore.Prepared("profile", "revision", identity, endpoint.digest(), manifest, Path.of("game"));
        var revision = revision(manifest, endpoint.digest());
        var hashes = Set.of(manifest.files().getFirst().sha256());
        var mod = manifest.files().getFirst().mods().getFirst();
        var mods = Map.of(mod.id(), mod.version());
        assertTrue(RevisionCheck.matches(endpoint, revision, active, hashes, mods, manifest.loaderVersion(), manifest.neoForgeVersion()));
        assertFalse(RevisionCheck.matches(endpoint, revision, active, Set.of(), mods, manifest.loaderVersion(), manifest.neoForgeVersion()));
        assertFalse(RevisionCheck.matches(endpoint, revision, active, hashes, Map.of(mod.id(), "changed"), manifest.loaderVersion(), manifest.neoForgeVersion()));
        assertFalse(RevisionCheck.matches(endpoint, revision, active, hashes, mods, "different", manifest.neoForgeVersion()));
        var other = new SyncEndpoint("other.example", endpoint.gamePort(), endpoint.httpsPort(), endpoint.digest());
        assertFalse(RevisionCheck.matches(other, revision, active, hashes, mods, manifest.loaderVersion(), manifest.neoForgeVersion()));
        var changed = new SyncRevision(manifest.serverId(), "b".repeat(64), revision.inventorySha256(), revision.changedAt(), revision.files(), List.of(), List.of(), List.of());
        assertFalse(RevisionCheck.matches(endpoint, changed, active, hashes, mods, manifest.loaderVersion(), manifest.neoForgeVersion()));
        var otherServer = new SyncRevision(UUID.randomUUID(), endpoint.digest(), revision.inventorySha256(), revision.changedAt(), revision.files(), List.of(), List.of(), List.of());
        assertFalse(RevisionCheck.matches(endpoint, otherServer, active, hashes, mods, manifest.loaderVersion(), manifest.neoForgeVersion()));
    }

    @Test
    void boundsRevisionAndRejectsInvalidDatesAndHashes() throws Exception {
        var revision = revision(SyncManifest.parse(InstallationPlanTest.manifest()), "a".repeat(64));
        assertEquals(revision, SyncRevision.parse(revision.bytes()));
        var invalid = JsonParser.parseString(new String(revision.bytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        invalid.addProperty("changedAt", "not a date");
        assertThrows(IOException.class, () -> SyncRevision.parse(invalid.toString().getBytes(StandardCharsets.UTF_8)));
        invalid.addProperty("changedAt", Instant.EPOCH.toString());
        invalid.addProperty("manifestSha256", "not a hash");
        assertThrows(IOException.class, () -> SyncRevision.parse(invalid.toString().getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> SyncRevision.parse(new byte[SyncRevision.MAX_BYTES + 1]));
    }

    static SyncRevision revision(SyncManifest manifest, String digest) {
        var files = manifest.files().stream().map(file -> new SyncRevision.Jar(file.fileName(), file.sha256(), file.size(), Instant.EPOCH)).toList();
        return new SyncRevision(manifest.serverId(), digest, SyncRevision.inventoryDigest(files), Instant.EPOCH, files, List.of(), List.of(), List.of());
    }

    @Test
    void rejectsInventoriesThatDoNotMatchTheSelectedManifest() throws Exception {
        var manifest = SyncManifest.parse(InstallationPlanTest.manifest());
        var revision = revision(manifest, "a".repeat(64));
        assertTrue(revision.matchesManifest(manifest));
        var files = List.of(new SyncRevision.Jar("unselected.jar", "b".repeat(64), 1, Instant.EPOCH));
        var unselected = new SyncRevision(manifest.serverId(), revision.manifestSha256(), SyncRevision.inventoryDigest(files), Instant.EPOCH, files, List.of(), List.of(), List.of());
        assertFalse(unselected.matchesManifest(manifest));
        var invalid = JsonParser.parseString(new String(revision.bytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        invalid.addProperty("inventorySha256", "b".repeat(64));
        assertThrows(IOException.class, () -> SyncRevision.parse(invalid.toString().getBytes(StandardCharsets.UTF_8)));
        invalid.addProperty("inventorySha256", revision.inventorySha256());
        invalid.getAsJsonArray("files").add(invalid.getAsJsonArray("files").get(0).deepCopy());
        assertThrows(IOException.class, () -> SyncRevision.parse(invalid.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsInvalidChangeListsAndDoesNotUseDatesAsIdentity() throws Exception {
        var manifest = SyncManifest.parse(InstallationPlanTest.manifest());
        var revision = revision(manifest, "a".repeat(64));
        var name = revision.files().getFirst().fileName();
        for (String field : List.of("added", "replaced", "removed")) {
            var invalid = JsonParser.parseString(new String(revision.bytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            invalid.getAsJsonArray(field).add(field.equals("removed") ? name : "unselected.jar");
            assertThrows(IOException.class, () -> SyncRevision.parse(invalid.toString().getBytes(StandardCharsets.UTF_8)));
        }
        var changedDate = new SyncRevision(revision.serverId(), revision.manifestSha256(), revision.inventorySha256(), Instant.now(),
                revision.files(), List.of(), List.of(), List.of());
        assertTrue(changedDate.matchesManifest(manifest));
        assertEquals(revision.inventorySha256(), changedDate.inventorySha256());
    }
}
