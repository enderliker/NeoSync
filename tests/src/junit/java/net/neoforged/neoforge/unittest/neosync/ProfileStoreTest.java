/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileStoreTest {
    private static final ProfileStore.Progress PROGRESS = (action, count, total) -> {};

    private static InstallationPlan plan(ProfileStore store, Path jar) throws Exception {
        var artifact = JarMetadataTest.artifact(jar);
        var root = JsonParser.parseString(new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        var file = root.getAsJsonArray("files").get(0).getAsJsonObject();
        file.addProperty("sha256", artifact.sha256());
        file.addProperty("size", artifact.size());
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        var initial = InstallationPlanTest.plan(bytes);
        var previous = store.prepared(initial.identity()).map(ProfileStore.Prepared::manifest).orElse(null);
        return InstallationPlan.create(InstallationPlanTest.endpoint(bytes), bytes, Set.of(artifact.sha256()), previous, "0.1.0-dev", "21.1.251");
    }

    private static ProfileStore.Prepared prepare(ProfileStore store, InstallationPlan plan, Path jar) throws IOException {
        return store.prepare(plan, plan.accept(true, true), Map.of(plan.files().getFirst().artifact().sha256(), jar), "4.0.44", new DiscoveryCancellation(), PROGRESS);
    }

    @Test
    void preparesIsolatedCopiesAndReopensOriginalStore(@TempDir Path directory) throws Exception {
        Path original = Files.createDirectory(directory.resolve("original"));
        Path personal = Files.writeString(Files.createDirectory(original.resolve("mods")).resolve("personal.jar"), "personal data");
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var store = ProfileStore.open(original);
        var plan = plan(store, jar);
        var prepared = prepare(store, plan, jar);
        assertEquals("personal data", Files.readString(personal));
        assertTrue(ProfileStore.open(original).active().isEmpty());
        assertEquals(prepared, store.prepared(plan.identity()).orElseThrow());
        var activated = ProfileStore.open(prepared.gameDirectory());
        assertEquals(store.root(), activated.root());
        assertEquals(prepared, activated.active().orElseThrow());
        activated.verify(prepared, "4.0.44", new DiscoveryCancellation());
        activated.recordLaunch();
        String hash = plan.files().getFirst().artifact().sha256();
        Path installed = prepared.gameDirectory().resolve("mods").resolve(hash + ".jar");
        Files.writeString(installed, "tampered");
        assertEquals(Files.size(jar), Files.size(store.root().resolve("cache/sha256").resolve(hash + ".jar")));
        assertThrows(IOException.class, () -> activated.verify(prepared, "4.0.44", new DiscoveryCancellation()));
    }

    @Test
    void canceledConsentStartsNoTransaction(@TempDir Path directory) throws Exception {
        var store = ProfileStore.open(directory);
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var plan = plan(store, jar);
        var token = new DiscoveryCancellation();
        token.close();
        assertThrows(IOException.class, () -> store.prepare(plan, plan.accept(true, true), Map.of(), "4.0.44", token, PROGRESS));
        assertFalse(Files.exists(store.root()));
    }

    @Test
    void preservesPreviousRevisionOnCancellationAndDiskFailure(@TempDir Path directory) throws Exception {
        var store = ProfileStore.open(directory);
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var originalPlan = plan(store, jar);
        var previous = prepare(store, originalPlan, jar);
        var next = plan(store, jar);
        for (String point : new String[] { "Copying", "Verifying", "Publishing", "Recording" }) {
            var token = new DiscoveryCancellation();
            assertThrows(IOException.class, () -> store.prepare(next, next.accept(true, true), Map.of(next.files().getFirst().artifact().sha256(), jar), "4.0.44", token,
                    (action, count, total) -> {
                        if (action.startsWith(point)) token.close();
                    }));
            assertEquals(previous, store.prepared(next.identity()).orElseThrow());
            store.verify(previous, "4.0.44", new DiscoveryCancellation());
        }
        assertThrows(IOException.class, () -> store.prepare(next, next.accept(true, true), Map.of(next.files().getFirst().artifact().sha256(), jar), "4.0.44", new DiscoveryCancellation(),
                (action, count, total) -> {
                    if (action.startsWith("Recording")) throw new IOException("Injected disk exhaustion");
                }));
        assertEquals(previous, store.prepared(next.identity()).orElseThrow());
        try (var staging = Files.list(store.root().resolve("staging"))) {
            assertEquals(0, staging.count());
        }
    }

    @Test
    void rejectsConcurrentWritesAndStaleReviews(@TempDir Path directory) throws Exception {
        var store = ProfileStore.open(directory);
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var firstPlan = plan(store, jar);
        var first = prepare(store, firstPlan, jar);
        assertThrows(IOException.class, () -> prepare(store, firstPlan, jar));
        var next = plan(store, jar);
        try (var channel = FileChannel.open(store.root().resolve("store.lock"), StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(IOException.class, () -> prepare(store, next, jar));
        }
        assertEquals(first, store.prepared(next.identity()).orElseThrow());
        var second = prepare(store, next, jar);
        assertNotEquals(first.gameDirectory(), second.gameDirectory());
        store.verify(first, "4.0.44", new DiscoveryCancellation());
    }

    @Test
    void rejectsSymlinkedStorageAncestorsAndMarkerRedirection(@TempDir Path directory) throws Exception {
        Path original = Files.createDirectory(directory.resolve("game"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        var store = ProfileStore.open(original);
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var plan = plan(store, jar);
        Files.createSymbolicLink(store.root(), outside);
        assertThrows(IOException.class, () -> prepare(store, plan, jar));
        try (var entries = Files.list(outside)) {
            assertEquals(0, entries.count());
        }
        Files.delete(store.root());
        var prepared = prepare(store, plan, jar);
        Path marker = prepared.gameDirectory().resolve("neosync-profile.json");
        String text = Files.readString(marker);
        Files.writeString(marker, text.replace(store.root().toString(), outside.toString()));
        assertThrows(IOException.class, () -> ProfileStore.open(prepared.gameDirectory()));
        Files.writeString(marker, text.replace(prepared.revisionId(), "../../escape"));
        assertThrows(IOException.class, () -> ProfileStore.open(prepared.gameDirectory()));
        Files.delete(marker);
        assertThrows(IOException.class, () -> ProfileStore.open(prepared.gameDirectory()));
    }

    @Test
    void rehashesCacheAndRejectsUnreviewedExtraFiles(@TempDir Path directory) throws Exception {
        var store = ProfileStore.open(directory);
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var plan = plan(store, jar);
        var prepared = prepare(store, plan, jar);
        String hash = plan.files().getFirst().artifact().sha256();
        assertTrue(store.reusable(plan.manifest(), Map.of(), new DiscoveryCancellation()).containsKey(hash));
        Files.writeString(store.root().resolve("cache/sha256").resolve(hash + ".jar"), "corrupted cache");
        assertTrue(store.reusable(plan.manifest(), Map.of(), new DiscoveryCancellation()).isEmpty());
        Files.writeString(prepared.gameDirectory().resolve("mods/extra.jar"), "extra code");
        assertThrows(IOException.class, () -> store.verify(prepared, "4.0.44", new DiscoveryCancellation()));
    }
}
