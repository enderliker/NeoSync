/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** JDK-only entry point, exported without game classes so verification precedes mod loading. */
public final class LauncherBridge {
    private LauncherBridge() {}

    public static void main(String[] args) {
        try {
            if (args.length == 2 && args[0].equals("verify")) {
                Verifier.verify(Path.of(args[1]));
                return;
            }
            if (args.length != 6 || !args[0].equals("restart")) throw new IOException("Invalid launcher handoff.");
            long pid = Long.parseLong(args[1]);
            var previous = ProcessHandle.of(pid);
            if (previous.isPresent()) previous.get().onExit().get(2, TimeUnit.MINUTES);
            Verifier.verify(Path.of(args[5]));
            new ProcessBuilder(args[2], "--dir", args[3], "--launch", args[4])
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception e) {
            System.err.println("NeoSync could not verify or launch the selected profile. Open your launcher and select a verified revision.");
            System.exit(1);
        }
    }

    public static final class Verifier {
        private Verifier() {}

        public static void verify(Path specification) throws IOException {
            requirePath(specification, false);
            if (Files.size(specification) > 1024 * 1024) throw new IOException("The launch verification record is oversized.");
            var record = new Properties();
            try (var input = Files.newBufferedReader(specification, StandardCharsets.UTF_8)) {
                record.load(input);
            }
            Path game = Path.of(record.getProperty("gameDirectory"));
            requirePath(game, true);
            verifyFile(game.resolve("neosync-profile.json"), record.getProperty("marker"), 8192);
            verifyFile(game.getParent().resolve("manifest.json"), record.getProperty("manifest"), 1024 * 1024);
            verifyFile(game.getParent().resolve("consent.json"), record.getProperty("consent"), 1024 * 1024);
            Path mods = game.resolve("mods");
            requirePath(mods, true);
            var expected = new HashSet<String>();
            int count;
            try {
                count = Integer.parseInt(record.getProperty("count"));
            } catch (RuntimeException e) {
                throw new IOException("Invalid launch verification count.");
            }
            if (count < 0 || count > 2048) throw new IOException("The launch verification count exceeds the limit.");
            long total = 0;
            for (int i = 0; i < count; i++) {
                String hash = record.getProperty("file." + i);
                if (hash == null || !hash.matches("[0-9a-f]{64}") || !expected.add(hash + ".jar")) throw new IOException("Invalid launch verification file.");
                Path file = mods.resolve(hash + ".jar");
                requirePath(file, false);
                total = Math.addExact(total, Files.size(file));
                if (total > 4L * 1024 * 1024 * 1024) throw new IOException("The selected mod set exceeds the size limit.");
                verifyFile(file, hash, 512L * 1024 * 1024);
            }
            try (var entries = Files.newDirectoryStream(mods)) {
                for (var entry : entries) if (!expected.remove(entry.getFileName().toString())) throw new IOException("Unexpected file in the selected mod set.");
            }
            if (!expected.isEmpty()) throw new IOException("Missing file in the selected mod set.");
        }

        private static void verifyFile(Path path, String expected, long limit) throws IOException {
            requirePath(path, false);
            if (expected == null || !expected.matches("[0-9a-f]{64}") || Files.size(path) > limit) throw new IOException("Invalid launch verification identity.");
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            long size = 0;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > limit) throw new IOException("A launch verification file grew during inspection.");
                    digest.update(buffer, 0, read);
                }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IOException("A selected profile file changed before launch.");
        }

        private static void requirePath(Path path, boolean directory) throws IOException {
            if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IOException("A launcher path is not absolute and normalized.");
            for (Path current = path; current != null; current = current.getParent())
                if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not supported in verified launcher paths.");
            if (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("A required launcher file or directory is missing.");
        }
    }
}
