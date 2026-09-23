/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

/** Owns a bounded snapshot for one service lifetime. Live mods are never opened by HTTP requests. */
public final class HostedInventory implements AutoCloseable {
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final long maxBytes;
    private final Map<String, Entry> files = new HashMap<>();
    private long bytes;
    private boolean sealed;

    public record Entry(Path path, long size) {
        public FileChannel open() throws IOException {
            checkDirectory(path.getParent());
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() != size) throw new IOException("The hosted snapshot changed.");
            return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        }
    }

    public HostedInventory(Path root, long maxBytes) throws IOException {
        if (maxBytes < 1 || maxBytes > SyncManifest.MAX_TOTAL_BYTES) throw new IOException("Invalid hosting disk quota.");
        root = root.toAbsolutePath().normalize();
        checkDirectory(root.getParent());
        createDirectory(root);
        checkDirectory(root);
        this.maxBytes = maxBytes;
        directory = root.resolve("snapshot");
        lockChannel = FileChannel.open(root.resolve("inventory.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("The hosting inventory is already in use.");
            clearSnapshot(directory);
            createDirectory(directory);
        } catch (Exception e) {
            if (acquired != null) acquired.close();
            lockChannel.close();
            throw new IOException("Could not prepare the hosting inventory.", e);
        }
        lock = acquired;
    }

    public void add(Path source, ArtifactFiles.Fingerprint expected, DiscoveryCancellation cancellation) throws IOException {
        if (sealed) throw new IOException("The hosting snapshot is already published.");
        if (!expected.sha256().matches(SyncManifest.HASH_PATTERN) || expected.size() < 1 || expected.size() > SyncManifest.MAX_FILE_BYTES)
            throw new IOException("Invalid hosted artifact identity.");
        if (files.containsKey(expected.sha256())) throw new IOException("Duplicate hosted artifact.");
        if (files.size() >= 2048 || expected.size() > maxBytes - bytes) throw new IOException("The hosting inventory exceeds its disk quota.");
        checkDirectory(source.toAbsolutePath().getParent());
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("A hosted source must be a regular file, not a symbolic link.");
        if (Files.getFileStore(directory).getUsableSpace() < expected.size() + 64L * 1024 * 1024)
            throw new IOException("Not enough disk space for the hosting snapshot.");
        Path target = directory.resolve(expected.sha256() + ".jar");
        boolean complete = false;
        try {
            try (var input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                    var output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.allocate(65536);
                long count = 0;
                while (input.read(buffer) != -1) {
                    cancellation.check();
                    count += buffer.position();
                    if (count > expected.size()) throw new IOException("The hosted source changed during copying.");
                    buffer.flip();
                    while (buffer.hasRemaining()) output.write(buffer);
                    buffer.clear();
                }
                output.force(true);
            }
            if (!ArtifactFiles.fingerprint(target, cancellation).equals(expected)) throw new IOException("The hosted source does not match its approved identity.");
            files.put(expected.sha256(), new Entry(target, expected.size()));
            bytes += expected.size();
            complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(target);
        }
    }

    public Map<String, Entry> seal() {
        sealed = true;
        return Map.copyOf(files);
    }

    private static void createDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectory(path);
        }
    }

    private static void checkDirectory(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize().getRoot();
        for (Path segment : path.toAbsolutePath().normalize()) {
            current = current.resolve(segment);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Hosting directories must not contain symbolic links.");
        }
    }

    private static void clearSnapshot(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        checkDirectory(path);
        var files = new java.util.ArrayList<Path>();
        try (var entries = Files.newDirectoryStream(path)) {
            for (Path entry : entries) {
                if (files.size() >= 2048 || !entry.getFileName().toString().matches(SyncManifest.HASH_PATTERN + "\\.jar")
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unexpected content in the private hosting snapshot.");
                files.add(entry);
            }
        }
        for (var file : files) Files.delete(file);
        Files.delete(path);
    }

    @Override
    public void close() throws IOException {
        try {
            clearSnapshot(directory);
        } finally {
            try {
                lock.close();
            } finally {
                lockChannel.close();
            }
        }
    }
}
