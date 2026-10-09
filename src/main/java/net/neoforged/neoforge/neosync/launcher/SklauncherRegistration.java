/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Exported with Gson and the file verifier; registration runs after SKlauncher saves its in-memory inventory. */
public final class SklauncherRegistration {
    private static final int MAX_BYTES = 1024 * 1024;

    private SklauncherRegistration() {}

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IOException("Invalid SKlauncher registration handoff.");
            Path specification = Path.of(args[0]);
            var record = new Properties();
            try (var input = new java.io.ByteArrayInputStream(read(specification))) {
                record.load(input);
            }
            long deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(12);
            for (String value : record.getProperty("processes", "").split(",")) {
                if (value.isEmpty()) continue;
                String[] identity = value.split("@", 2);
                var process = ProcessHandle.of(Long.parseLong(identity[0]));
                if (process.isPresent() && process.get().info().startInstant().map(Instant::toString).orElse("").equals(identity[1]))
                    process.get().onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            register(specification);
        } catch (Exception e) {
            System.err.println("NeoSync could not finish SKlauncher registration. Reopen NeoSync and prepare the instance again.");
            System.exit(1);
        }
    }

    public static void register(Path specification) throws IOException {
        var record = new Properties();
        try (var input = new java.io.ByteArrayInputStream(read(specification))) {
            record.load(input);
        }
        LauncherBridge.Verifier.verify(specification);
        String id = record.getProperty("instanceId");
        if (id == null || !id.matches("neosync-[0-9a-f-]{36}-[0-9a-f-]{36}")) throw new IOException("Invalid SKlauncher instance ID.");
        Path root = Path.of(record.getProperty("launcherRoot"));
        if (!root.isAbsolute() || !root.equals(root.normalize())) throw new IOException("Invalid SKlauncher installation path.");
        Path instance = root.resolve("instances").resolve(id);
        if (!specification.getParent().equals(instance)) throw new IOException("The registration is outside its launcher instance.");
        Path runtime = root.resolve("versions").resolve(id).resolve(id + ".json");
        byte[] runtimeBytes = read(runtime);
        if (!sha256(runtimeBytes).equals(record.getProperty("runtimeSha256"))) throw new IOException("The prepared SKlauncher runtime changed.");
        var metadata = object(runtimeBytes);
        if (!id.equals(metadata.get("id").getAsString())) throw new IOException("Invalid prepared SKlauncher runtime.");
        Path inventory = root.resolve("instances.json");
        Path stage = root.resolve(".neosync-instances-" + UUID.randomUUID() + ".tmp");
        try (var channel = FileChannel.open(root.resolve(".neosync-launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another NeoSync process is preparing launcher instances.");
            byte[] original = read(inventory);
            var document = object(original);
            var instances = document.getAsJsonArray("instances");
            if (instances == null) throw new IOException("Unsupported SKlauncher instance inventory.");
            JsonObject existing = null;
            for (var value : instances) {
                var entry = value.getAsJsonObject();
                if (id.equals(entry.get("id").getAsString())) {
                    if (existing != null || !id.equals(entry.get("versionId").getAsString())
                            || !id.equals(entry.get("minecraftVersion").getAsString())
                            || !"custom".equals(entry.get("gameType").getAsString())
                            || !"custom".equals(entry.get("type").getAsString())
                            || !entry.get("compatibilityMode").getAsBoolean()
                            || !instance.toString().equals(entry.get("directory").getAsString()))
                        throw new IOException("The prepared SKlauncher instance was edited. It was not overwritten.");
                    existing = entry;
                }
            }
            if (existing != null) return;
            var entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("name", record.getProperty("instanceName"));
            entry.addProperty("type", "custom");
            entry.addProperty("versionId", id);
            entry.addProperty("gameType", "custom");
            entry.addProperty("minecraftVersion", id);
            entry.addProperty("directory", instance.toString());
            entry.addProperty("createdAt", Instant.now().toString());
            entry.addProperty("playTime", 0);
            entry.addProperty("sessionCount", 0);
            entry.addProperty("compatibilityMode", true);
            instances.add(entry);
            byte[] output = document.toString().getBytes(StandardCharsets.UTF_8);
            if (output.length > MAX_BYTES) throw new IOException("The SKlauncher instance inventory exceeds its size limit.");
            try {
                Files.write(stage, output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                if (!Arrays.equals(original, read(inventory))) throw new IOException("SKlauncher changed its instances. Retry preparation.");
                Files.move(stage, inventory, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(stage);
            }
        } catch (IllegalArgumentException | NullPointerException | IllegalStateException e) {
            throw new IOException("The SKlauncher instance inventory is malformed.", e);
        }
    }

    private static JsonObject object(byte[] bytes) throws IOException {
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid local SKlauncher record.", e);
        }
    }

    private static byte[] read(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not supported in launcher records.");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("A launcher record is missing or linked.");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("The launcher record exceeds its size limit.");
            return bytes;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
