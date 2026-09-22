/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;

@org.jetbrains.annotations.ApiStatus.Internal
public final class ManagedPaths {
    private ManagedPaths() {}

    public static Path directory(Path path, boolean create) throws IOException {
        path = path.toAbsolutePath().normalize();
        Path current = path.getRoot();
        for (Path segment : path) {
            current = current.resolve(segment);
            if (create && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } catch (UnsupportedOperationException e) {
                    Files.createDirectory(current);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {}
            }
            if (!Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                throw new IOException("A managed directory is missing or is a symbolic link.");
            }
        }
        return path;
    }

    static byte[] read(Path path, int maxBytes) throws IOException {
        directory(path.getParent(), false);
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > maxBytes) throw new IOException("Invalid or oversized local profile record.");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("The local profile record exceeds its size limit.");
            return bytes;
        }
    }

    static void writeNew(Path path, byte[] bytes) throws IOException {
        directory(path.getParent(), false);
        try (var output = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
    }

    static void move(Path source, Path target) throws IOException {
        directory(source.getParent(), false);
        directory(target.getParent(), false);
        if (Files.isSymbolicLink(source) || Files.isSymbolicLink(target)) throw new IOException("Symbolic links are not allowed in managed publication paths.");
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void deleteTree(Path path) throws IOException {
        directory(path, false);
        try (var entries = Files.newDirectoryStream(path)) {
            for (var entry : entries) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) deleteTree(entry);
                else Files.delete(entry);
            }
        }
        Files.delete(path);
    }
}
