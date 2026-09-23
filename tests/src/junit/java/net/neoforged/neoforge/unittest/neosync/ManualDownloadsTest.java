/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.neoforge.neosync.manual.BrowserHandoff;
import net.neoforged.neoforge.neosync.manual.DownloadDirectory;
import net.neoforged.neoforge.neosync.manual.ManualDownloads;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManualDownloadsTest {
    private static InstallationPlan plan(Path jar) throws Exception {
        byte[] bytes = CurseForgeProviderTest.manifest(jar, true);
        var sources = CurseForgeProviderTest.fixtureSources(bytes);
        return InstallationPlan.create(InstallationPlanTest.endpoint(bytes), bytes, Set.of(), null, "0.1.0-dev", "21.1.251", null, sources);
    }

    @Test
    void importsRenameCompletionWithDuplicateFilenameWithoutTakingProfileLock(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var plan = plan(jar);
        var store = ProfileStore.open(Files.createDirectory(directory.resolve("game")));
        Files.createDirectory(store.root());
        Path downloads = Files.createDirectory(directory.resolve("Downloaded mods"));
        var opened = new AtomicInteger();
        var controls = new ManualDownloads.Controls(downloads, () -> null, status -> {}, page -> {
            assertEquals(CurseForgeProviderTest.PAGE, page.toString());
            opened.incrementAndGet();
            Files.copy(jar, downloads.resolve("duplicate (1).jar.part"));
            Files.move(downloads.resolve("duplicate (1).jar.part"), downloads.resolve("duplicate (1).jar"));
        }, Duration.ofSeconds(2));
        try (var channel = FileChannel.open(store.root().resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.lock();
                var imported = ManualDownloads.collect(plan, plan.accept(true, true), store.root().resolve("manual-imports"), "4.0.44", controls, new DiscoveryCancellation())) {
            assertTrue(lock.isValid());
            assertEquals(1, imported.files().size());
            assertEquals(Files.size(jar), Files.size(downloads.resolve("duplicate (1).jar")));
        }
        try (var imported = ManualDownloads.collect(plan, plan.accept(true, true), store.root().resolve("manual-imports"), "4.0.44",
                new ManualDownloads.Controls(downloads, () -> null, status -> {}, page -> {}, Duration.ofSeconds(2)), new DiscoveryCancellation())) {
            var prepared = store.prepare(plan, plan.accept(true, true), imported.files(), "4.0.44", new DiscoveryCancellation(), (a, b, c) -> {});
            ProfileStore.open(prepared.gameDirectory()).verify(prepared, "4.0.44", new DiscoveryCancellation());
        }
        assertEquals(1, opened.get());
        assertEquals(-1, Files.mismatch(jar, downloads.resolve("duplicate (1).jar")));
        try (var entries = Files.list(store.root().resolve("manual-imports"))) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void supportsExplicitFilesWhenDirectoryDiscoveryAndBrowserOpeningFail(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var plan = plan(jar);
        var selected = new AtomicReference<Path>(jar);
        var notice = new AtomicReference<String>();
        var controls = new ManualDownloads.Controls(null, () -> selected.getAndSet(null), status -> notice.set(status.notice()), page -> {
            throw new IOException("Injected browser launch failure");
        }, Duration.ofSeconds(2));
        try (var imported = ManualDownloads.collect(plan, plan.accept(true, true), directory.resolve("staging"), "4.0.44", controls, new DiscoveryCancellation())) {
            assertEquals(1, imported.files().size());
            assertTrue(notice.get().contains("browser could not be opened"));
        }
        assertTrue(Files.exists(jar));
    }

    @Test
    void declinesAndStaleConsentCreateNoWatcherBrowserOrStaging(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var plan = plan(jar);
        var count = new AtomicInteger();
        var controls = new ManualDownloads.Controls(directory, () -> null, status -> {}, page -> count.incrementAndGet(), Duration.ofSeconds(1));
        assertThrows(IOException.class, () -> plan.accept(false, true));
        assertThrows(IOException.class, () -> plan.accept(true, false));
        assertThrows(IOException.class, () -> ManualDownloads.collect(plan, plan(jar).accept(true, true), directory.resolve("staging"), "4.0.44", controls, new DiscoveryCancellation()));
        var cancelled = new DiscoveryCancellation();
        cancelled.close();
        assertThrows(IOException.class, () -> ManualDownloads.collect(plan, plan.accept(true, true), directory.resolve("staging"), "4.0.44", controls, cancelled));
        assertThrows(IOException.class, () -> BrowserHandoff.open(plan, plan.accept(true, true), java.net.URI.create("https://attacker.example"), new DiscoveryCancellation()));
        assertEquals(0, count.get());
        assertFalse(Files.exists(directory.resolve("staging")));
    }

    @Test
    void rejectsPartialWrongHashAndSymlinkCandidatesAndCleansTimeout(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var plan = plan(jar);
        Path downloads = Files.createDirectory(directory.resolve("downloads"));
        Files.copy(jar, downloads.resolve("partial.jar.crdownload"));
        Files.createSymbolicLink(downloads.resolve("symlink.jar"), jar);
        Files.write(downloads.resolve("wrong.jar"), new byte[(int) Files.size(jar)]);
        Files.write(downloads.resolve("oversized.jar"), new byte[(int) Files.size(jar) + 1]);
        Path staging = directory.resolve("staging");
        var controls = new ManualDownloads.Controls(downloads, () -> null, status -> {}, page -> {}, Duration.ofMillis(100));
        assertThrows(IOException.class, () -> ManualDownloads.collect(plan, plan.accept(true, true), staging, "4.0.44", controls, new DiscoveryCancellation()));
        try (var entries = Files.list(staging)) {
            assertEquals(0, entries.count());
        }
        assertTrue(Files.isSymbolicLink(downloads.resolve("symlink.jar")));
        assertTrue(Files.exists(downloads.resolve("partial.jar.crdownload")));
        assertEquals(Files.size(jar), Files.size(downloads.resolve("wrong.jar")));
    }

    @Test
    void cancellationRejectsLateDownloadsAndLeavesOriginals(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var plan = plan(jar);
        var token = new DiscoveryCancellation();
        Path downloads = Files.createDirectory(directory.resolve("downloads"));
        var controls = new ManualDownloads.Controls(downloads, () -> null, status -> {}, page -> {
            token.close();
            Files.copy(jar, downloads.resolve("late.jar"));
        }, Duration.ofSeconds(1));
        assertThrows(IOException.class, () -> ManualDownloads.collect(plan, plan.accept(true, true), directory.resolve("staging"), "4.0.44", controls, token));
        assertEquals(-1, Files.mismatch(jar, downloads.resolve("late.jar")));
        try (var entries = Files.list(directory.resolve("staging"))) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void readsRelocatedXdgDirectoriesAsData(@TempDir Path directory) {
        assertEquals(directory.resolve("Downloads local"), DownloadDirectory.parseXdg("XDG_DOWNLOAD_DIR=\"$HOME/Downloads local\"", directory).orElseThrow());
        Path relocated = directory.resolve("relocated");
        assertEquals(relocated, DownloadDirectory.parseXdg("# comment\nXDG_DOWNLOAD_DIR=\"" + relocated + "\" # selected", directory).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = { "$HOME", "$(touch /tmp/never-execute)", "`touch /tmp/never-execute`", "$HOME/../outside", "$OTHER/Downloads", "relative", "", "/tmp/downloads\\escaped" })
    void rejectsDisabledOrExecutableXdgValues(String value, @TempDir Path directory) {
        assertTrue(DownloadDirectory.parseXdg("XDG_DOWNLOAD_DIR=\"" + value + "\"", directory).isEmpty());
    }
}
