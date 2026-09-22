/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.manual;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

public final class DownloadDirectory {
    private DownloadDirectory() {}

    public static Optional<Path> discover() {
        try {
            String os = System.getProperty("os.name", "");
            if (os.startsWith("Windows")) return Optional.of(Path.of(com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(com.sun.jna.platform.win32.KnownFolders.FOLDERID_Downloads)));
            if (!os.equals("Linux")) return Optional.empty();
            Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            String configured = System.getenv("XDG_CONFIG_HOME");
            Path config = configured == null || configured.isBlank() ? home.resolve(".config") : Path.of(configured);
            if (!config.isAbsolute()) config = home.resolve(".config");
            try (var input = Files.newInputStream(config.resolve("user-dirs.dirs"), LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(32769);
                if (bytes.length > 32768) return Optional.empty();
                return parseXdg(new String(bytes, StandardCharsets.UTF_8), home);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** XDG configuration is data. Never pass it to a shell or expand arbitrary environment variables. */
    public static Optional<Path> parseXdg(String text, Path home) {
        if (text.length() > 32768 || !home.isAbsolute()) return Optional.empty();
        Path result = null;
        for (String line : text.split("\\R")) {
            line = line.trim();
            if (!line.startsWith("XDG_DOWNLOAD_DIR=")) continue;
            if (result != null) return Optional.empty();
            var matcher = java.util.regex.Pattern.compile("XDG_DOWNLOAD_DIR=\"([^\"\\\\]*)\"\\s*(?:#.*)?").matcher(line);
            if (!matcher.matches()) return Optional.empty();
            String value = matcher.group(1);
            if (value.equals("$HOME") || value.equals(home.toString()) || value.isEmpty()) return Optional.empty();
            if (value.startsWith("$HOME/")) value = home + value.substring(5);
            if (value.contains("$") || value.contains("`") || value.chars().anyMatch(Character::isISOControl)) return Optional.empty();
            try {
                result = Path.of(value);
                if (!result.isAbsolute() || !result.equals(result.normalize()) || result.equals(home)) return Optional.empty();
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(result);
    }
}
