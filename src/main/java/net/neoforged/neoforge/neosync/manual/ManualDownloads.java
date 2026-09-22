/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.manual;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.JarMetadata;
import net.neoforged.neoforge.neosync.protocol.ManagedPaths;
import org.jetbrains.annotations.Nullable;

/** Collects approved browser downloads before taking the profile publication lock. Original downloads are never modified. */
public final class ManualDownloads {
    private ManualDownloads() {}

    public record Status(List<String> files, @Nullable Path directory, URI page, String notice) {
        public Status {
            files = List.copyOf(files);
        }
    }

    @FunctionalInterface
    public interface Browser {
        void open(URI page) throws IOException;
    }

    public record Controls(@Nullable Path directory, Supplier<@Nullable Path> selectedPath, Consumer<Status> status, Browser browser, Duration timeout) {}

    public static Acquired collect(InstallationPlan plan, InstallationPlan.Consent consent, Path stagingParent, String fml,
            Controls controls, DiscoveryCancellation token) throws IOException {
        consent.require(plan);
        token.check();
        var needed = new ArrayList<>(plan.files().stream().filter(file -> !file.available() && file.provider() != null && file.provider().manual()).toList());
        if (needed.isEmpty()) return new Acquired(null, Map.of());
        if (controls.timeout().isNegative() || controls.timeout().isZero() || controls.timeout().compareTo(Duration.ofMinutes(15)) > 0)
            throw new IOException("Invalid browser download deadline.");
        Path parent = ManagedPaths.directory(stagingParent, true);
        long requiredBytes = needed.stream().mapToLong(file -> file.artifact().size()).sum();
        if (Files.getFileStore(parent).getUsableSpace() < requiredBytes + 64L * 1024 * 1024)
            throw new IOException("Not enough free space for verified browser imports and a reserve.");
        Path staging = ManagedPaths.directory(parent.resolve(UUID.randomUUID().toString()), true);
        var imported = new HashMap<String, Path>();
        var seen = new HashMap<Path, BasicFileAttributes>();
        var opened = new HashSet<URI>();
        long budget = Math.max(64L * 1024 * 1024, needed.stream().mapToLong(file -> file.artifact().size()).sum() * 8);
        long deadline = System.nanoTime() + controls.timeout().toNanos();
        Path directory = controls.directory();
        WatchService watcher = null;
        boolean success = false;
        String notice = "Finish downloading in your browser. You can also choose the downloaded file or enter its path.";
        try {
            if (directory != null) {
                try {
                    directory = ManagedPaths.directory(directory, false);
                } catch (IOException e) {
                    directory = null;
                }
            }
            watcher = watch(directory, token);
            if (watcher == null) notice = "Automatic watching is unavailable. Choose the downloaded file or enter its full path.";
            while (!needed.isEmpty()) {
                token.check();
                if (System.nanoTime() >= deadline) throw new IOException("Waiting for browser downloads expired. Retry installation to start a new reviewed attempt.");
                long scanStarted = System.nanoTime();
                URI page = needed.getFirst().source();
                if (opened.add(page)) {
                    try {
                        controls.browser().open(page);
                    } catch (IOException e) {
                        token.check();
                        notice = "The browser could not be opened. Open the reviewed page manually or use Open CurseForge page.";
                    }
                    controls.status().accept(status(needed, directory, notice));
                }
                Path selected = controls.selectedPath().get();
                var candidates = new ArrayList<Path>();
                if (selected != null) {
                    if (Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) {
                        if (watcher != null) watcher.close();
                        token.detach();
                        directory = ManagedPaths.directory(selected, false);
                        watcher = watch(directory, token);
                        seen.clear();
                    } else {
                        candidates.add(selected);
                        seen.remove(selected);
                    }
                    controls.status().accept(status(needed, directory, notice));
                }
                if (candidates.isEmpty() && directory != null && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    ManagedPaths.directory(directory, false);
                    try (var entries = Files.newDirectoryStream(directory)) {
                        int count = 0;
                        for (Path entry : entries) {
                            token.check();
                            if (++count > 1024) {
                                if (watcher != null) watcher.close();
                                token.detach();
                                watcher = null;
                                directory = null;
                                candidates.clear();
                                notice = "The folder contains too many entries. Choose the exact downloaded file instead.";
                                controls.status().accept(status(needed, null, notice));
                                break;
                            }
                            candidates.add(entry);
                        }
                    }
                }
                for (Path candidate : candidates) {
                    token.check();
                    String name = candidate.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (!name.endsWith(".jar")) continue;
                    BasicFileAttributes before;
                    try {
                        before = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException e) {
                        continue;
                    }
                    if (!before.isRegularFile() || same(before, seen.get(candidate))) continue;
                    if (seen.size() >= 2048) throw new IOException("Too many changing browser download candidates. Select the exact file and retry.");
                    seen.put(candidate, before);
                    for (var file : List.copyOf(needed)) {
                        if (file.artifact().size() != before.size()) continue;
                        budget -= before.size() * 3;
                        if (budget < 0) throw new IOException("Browser download inspection exceeded its I/O limit. Select the exact file and retry.");
                        Path target = staging.resolve(file.artifact().sha256() + ".jar");
                        if (!copyCandidate(candidate, before, target, token)) continue;
                        try {
                            file.provider().verify(target, file.artifact(), token);
                            JarMetadata.verify(target, file.artifact(), fml, token);
                        } catch (IOException e) {
                            Files.deleteIfExists(target);
                            token.check();
                            continue;
                        }
                        token.check();
                        imported.put(file.artifact().sha256(), target);
                        needed.remove(file);
                        break;
                    }
                }
                if (!needed.isEmpty()) {
                    if (selected != null && !Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) {
                        notice = "The selected file did not pass the approved size, hashes and mod checks. Choose the completed exact download.";
                        controls.status().accept(status(needed, directory, notice));
                    }
                    long wait = Math.min(TimeUnit.SECONDS.toNanos(1), deadline - System.nanoTime());
                    if (wait <= 0) continue;
                    try {
                        if (watcher != null) {
                            var key = watcher.poll(wait, TimeUnit.NANOSECONDS);
                            if (key != null) {
                                key.pollEvents();
                                if (!key.reset()) {
                                    watcher.close();
                                    token.detach();
                                    watcher = null;
                                }
                            }
                        } else TimeUnit.NANOSECONDS.sleep(wait);
                        long nextScan = Math.min(deadline, scanStarted + TimeUnit.SECONDS.toNanos(1));
                        while (System.nanoTime() < nextScan) {
                            token.check();
                            TimeUnit.NANOSECONDS.sleep(Math.max(1, Math.min(TimeUnit.MILLISECONDS.toNanos(100), nextScan - System.nanoTime())));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Browser download import was cancelled.");
                    }
                }
            }
            token.check();
            success = true;
            return new Acquired(staging, imported);
        } catch (ClosedWatchServiceException e) {
            throw new IOException("Browser download watching was cancelled.");
        } finally {
            token.detach();
            if (watcher != null) watcher.close();
            if (!success) ManagedPaths.deleteTree(staging);
        }
    }

    private static Status status(List<InstallationPlan.File> needed, @Nullable Path directory, String notice) {
        return new Status(needed.stream().map(file -> file.artifact().fileName() + " — " + file.source()).toList(), directory, needed.getFirst().source(), notice);
    }

    @Nullable
    private static WatchService watch(@Nullable Path directory, DiscoveryCancellation token) throws IOException {
        token.check();
        if (directory == null) return null;
        WatchService watcher = null;
        try {
            ManagedPaths.directory(directory, false);
            watcher = directory.getFileSystem().newWatchService();
            directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            token.attach(watcher);
            return watcher;
        } catch (IOException | UnsupportedOperationException e) {
            if (watcher != null) watcher.close();
            token.check();
            return null;
        }
    }

    private static boolean same(BasicFileAttributes a, @Nullable BasicFileAttributes b) {
        return b != null && a.isRegularFile() == b.isRegularFile() && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime()) && Objects.equals(a.fileKey(), b.fileKey());
    }

    private static boolean copyCandidate(Path source, BasicFileAttributes before, Path target, DiscoveryCancellation token) throws IOException {
        ManagedPaths.directory(source.toAbsolutePath().getParent(), false);
        boolean complete = false;
        boolean created = false;
        try (var input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            created = true;
            var buffer = ByteBuffer.allocate(65536);
            long copied = 0;
            while (input.read(buffer) != -1) {
                token.check();
                buffer.flip();
                copied += buffer.remaining();
                if (copied > before.size()) return false;
                while (buffer.hasRemaining()) {
                    token.check();
                    output.write(buffer);
                }
                buffer.clear();
            }
            if (copied != before.size() || !same(before, Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))) return false;
            output.force(true);
            complete = true;
            return true;
        } finally {
            if (created && !complete) Files.deleteIfExists(target);
        }
    }

    public static final class Acquired implements AutoCloseable {
        @Nullable
        private final Path staging;
        private final Map<String, Path> files;

        private Acquired(@Nullable Path staging, Map<String, Path> files) {
            this.staging = staging;
            this.files = Map.copyOf(files);
        }

        public Map<String, Path> files() {
            return files;
        }

        @Override
        public void close() throws IOException {
            if (staging != null) ManagedPaths.deleteTree(staging);
        }
    }
}
