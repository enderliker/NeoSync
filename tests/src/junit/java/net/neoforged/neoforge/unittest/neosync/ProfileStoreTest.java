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
    void exportsConsentedRevisionsWithoutSharingModFilesOrAcceptingTamperedCopies(@TempDir Path directory) throws Exception {
        var store = ProfileStore.open(directory);
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var original = prepare(store, plan(store, jar), jar);
        Path instance = Files.createDirectories(directory.resolve("instances/server"));
        var exported = store.export(original, instance, "4.0.44", new DiscoveryCancellation());
        assertTrue(exported.gameDirectory().startsWith(instance));
        assertEquals(original.digest(), exported.digest());
        assertEquals(original, store.prepared(original.identity()).orElseThrow());
        Path originalMod = original.gameDirectory().resolve("mods").resolve(original.manifest().files().getFirst().sha256() + ".jar");
        Path exportedMod = exported.gameDirectory().resolve("mods").resolve(originalMod.getFileName());
        assertFalse(Files.isSameFile(originalMod, exportedMod));
        assertEquals(exported, store.export(original, instance, "4.0.44", new DiscoveryCancellation()));
        assertEquals(exported, ProfileStore.open(exported.gameDirectory()).export(exported, instance, "4.0.44", new DiscoveryCancellation()));
        Files.writeString(exportedMod, "tampered copy");
        assertThrows(IOException.class, () -> store.export(original, instance, "4.0.44", new DiscoveryCancellation()));
        store.verify(original, "4.0.44", new DiscoveryCancellation());
        var cancelled = new DiscoveryCancellation();
        cancelled.close();
        Path canceledInstance = Files.createDirectory(directory.resolve("canceled"));
        assertThrows(IOException.class, () -> store.export(original, canceledInstance, "4.0.44", cancelled));
        assertFalse(Files.exists(canceledInstance.resolve("neosync")));
    }

    @Test
    void recoversAnOlderRevisionEvenWhenTheNewestRecordIsDamaged(@TempDir Path directory) throws Exception {
        var store = ProfileStore.open(directory);
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var first = prepare(store, plan(store, jar), jar);
        var second = prepare(store, plan(store, jar), jar);
        assertEquals(2, store.history().revisions().size());
        assertThrows(IOException.class, () -> store.restore(first, first.revisionId(), "4.0.44", new DiscoveryCancellation()));
        assertEquals(second, store.prepared(first.identity()).orElseThrow());
        Files.writeString(second.gameDirectory().getParent().resolve("consent.json"), "damaged");
        var history = store.history();
        assertEquals(java.util.List.of(first), history.revisions());
        assertEquals(1, history.problems().size());
        store.restore(first, second.revisionId(), "4.0.44", new DiscoveryCancellation());
        assertEquals(first, store.prepared(first.identity()).orElseThrow());
        assertTrue(Files.exists(second.gameDirectory()));
    }

    @Test
    void rejectsTamperedAndCanceledRecoveryWithoutChangingSelection(@TempDir Path directory) throws Exception {
        var store = ProfileStore.open(directory);
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var first = prepare(store, plan(store, jar), jar);
        var second = prepare(store, plan(store, jar), jar);
        var cancellation = new DiscoveryCancellation();
        cancellation.close();
        assertThrows(IOException.class, () -> store.restore(first, second.revisionId(), "4.0.44", cancellation));
        Files.writeString(first.gameDirectory().resolve("mods").resolve(first.manifest().files().getFirst().sha256() + ".jar"), "tampered");
        assertThrows(IOException.class, () -> store.restore(first, second.revisionId(), "4.0.44", new DiscoveryCancellation()));
        assertEquals(second, store.prepared(first.identity()).orElseThrow());
        store.verify(second, "4.0.44", new DiscoveryCancellation());
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
    void rejectsFalseActiveInventoryBeforePublishingAProfile(@TempDir Path directory) throws Exception {
        var store = ProfileStore.open(directory);
        String nested = "META-INF/jarjar/hidden.jar";
        String declaration = JarMetadataTest.jarjarEntry("hidden", "1.0", nested);
        byte[] library = JarMetadataTest.jarBytes(JarMetadataTest.TOML.replace("test_mod", "hidden_mod"), Map.of());
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of(nested, library,
                "META-INF/jarjar/metadata.json", ("{\"jars\":[" + declaration + "]}").getBytes(StandardCharsets.UTF_8)));
        var root = JsonParser.parseString(new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        var artifact = net.neoforged.neoforge.neosync.protocol.ArtifactFiles.fingerprint(jar, new DiscoveryCancellation());
        var file = root.getAsJsonArray("files").get(0).getAsJsonObject();
        file.addProperty("sha256", artifact.sha256());
        file.addProperty("size", artifact.size());
        var mod = file.getAsJsonArray("mods").get(0).deepCopy().getAsJsonObject();
        mod.addProperty("id", "hidden_mod");
        mod.addProperty("embedded", true);
        file.getAsJsonArray("mods").add(mod);
        root.addProperty("schemaVersion", 2);
        root.add("activeMods", JsonParser.parseString("[{\"id\":\"test_mod\",\"version\":\"1.0\"}]"));
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        var plan = InstallationPlan.create(InstallationPlanTest.endpoint(bytes), bytes, Set.of(artifact.sha256()), null, "0.1.0-dev", "21.1.251");
        assertThrows(IOException.class, () -> prepare(store, plan, jar));
        assertTrue(store.history().revisions().isEmpty());
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
        var redirected = JsonParser.parseString(text).getAsJsonObject();
        redirected.addProperty("storageRoot", outside.toString());
        Files.writeString(marker, redirected.toString());
        assertThrows(IOException.class, () -> ProfileStore.open(prepared.gameDirectory()));
        var escaped = JsonParser.parseString(text).getAsJsonObject();
        escaped.addProperty("revisionId", "../../escape");
        Files.writeString(marker, escaped.toString());
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
