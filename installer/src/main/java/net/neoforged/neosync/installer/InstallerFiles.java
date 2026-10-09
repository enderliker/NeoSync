/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neosync.installer;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

final class InstallerFiles {
    static final int JSON_LIMIT = 1024 * 1024;
    static final long LIBRARY_LIMIT = 512L * 1024 * 1024;

    private InstallerFiles() {}

    static Path directory(Path path, boolean create) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (path.toString().chars().anyMatch(Character::isISOControl) || path.toString().contains("${"))
            throw new IOException("The selected path contains unsupported characters.");
        Path current = path.getRoot();
        for (Path segment : path) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current) || (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IOException("Linked installation paths are not supported.");
            if (create) Files.createDirectories(current);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("The selected directory is missing.");
        }
        return path;
    }

    static byte[] read(Path path, int limit) throws IOException {
        directory(path.getParent(), false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
            throw new IOException("A required installation record is missing, linked or oversized: " + path.getFileName());
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("The installation record exceeds its limit.");
            return bytes;
        }
    }

    static JsonObject json(Path path) throws IOException {
        try {
            return JsonParser.parseString(new String(read(path, JSON_LIMIT), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Invalid local installation metadata.", error);
        }
    }

    static byte[] encode(JsonObject object) throws IOException {
        byte[] result = (new GsonBuilder().setPrettyPrinting().create().toJson(object) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (result.length > JSON_LIMIT) throw new IOException("The launcher record exceeds its limit.");
        return result;
    }

    static void publish(Path path, byte[] bytes) throws IOException {
        directory(path.getParent(), true);
        Path stage = path.resolveSibling(".neosync-" + UUID.randomUUID());
        try {
            Files.write(stage, bytes, StandardOpenOption.CREATE_NEW);
            Files.createLink(path, stage);
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    static void update(Path path, byte[] original, JsonObject object) throws IOException {
        Path stage = path.resolveSibling(".neosync-" + UUID.randomUUID());
        try {
            Files.write(stage, encode(object), StandardOpenOption.CREATE_NEW);
            if (!Arrays.equals(original, read(path, JSON_LIMIT))) throw new IOException("The launcher changed its inventory. Close it and retry.");
            Files.move(stage, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    static String hash(Path path, String algorithm) throws IOException {
        directory(path.getParent(), false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > LIBRARY_LIMIT)
            throw new IOException("A runtime library is missing, linked or oversized.");
        try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[64 * 1024];
            int size;
            while ((size = stream.read(buffer)) != -1) digest.update(buffer, 0, size);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void copy(Path source, Path target) throws IOException {
        String expected = hash(source, "SHA-256");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!expected.equals(hash(target, "SHA-256"))) throw new IOException("An existing runtime library differs. It was not overwritten.");
            return;
        }
        directory(target.getParent(), true);
        Path stage = target.resolveSibling(".neosync-" + UUID.randomUUID());
        try {
            Files.copy(source, stage);
            if (!expected.equals(hash(stage, "SHA-256"))) throw new IOException("The runtime changed during installation.");
            Files.createLink(target, stage);
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    static Path relativeJar(String value) throws IOException {
        Path path = Path.of(value);
        if (path.isAbsolute() || value.contains(":") || !value.endsWith(".jar") || path.normalize().startsWith(".."))
            throw new IOException("Invalid installed library path.");
        for (Path segment : path) if (segment.toString().equals("..")) throw new IOException("Invalid installed library path.");
        return path;
    }
}
