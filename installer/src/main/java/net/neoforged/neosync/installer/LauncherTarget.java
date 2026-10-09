/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neosync.installer;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record LauncherTarget(Kind kind, Path root) {
    public enum Kind {
        PRISM("Prism Launcher", "prismlauncher.org"),
        MINECRAFT("Minecraft Launcher", "minecraft.net/download"),
        SKLAUNCHER("SKlauncher 3.2", "skmedix.pl"),
        SKLAUNCHER_BETA("SKlauncher 4.0 Beta", "next.skmedix.pl"),
        MODRINTH("Modrinth App", "modrinth.com/app"),
        FOLDER("Minecraft folder", "");

        private final String label;
        private final String website;

        Kind(String label, String website) {
            this.label = label;
            this.website = website;
        }

        public String label() {
            return label;
        }

        public String website() {
            return website;
        }
    }

    public LauncherTarget {
        root = root.toAbsolutePath().normalize();
    }

    public boolean available() {
        try {
            InstallerFiles.directory(root, false);
            return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static List<LauncherTarget> discover(Map<String, String> environment, Path home, String os) {
        String platform = os.toLowerCase(Locale.ROOT);
        Path appData = environment.containsKey("APPDATA") ? Path.of(environment.get("APPDATA")) : home.resolve("AppData/Roaming");
        Path minecraft = platform.contains("win") ? appData.resolve(".minecraft")
                : platform.contains("mac") ? home.resolve("Library/Application Support/minecraft") : home.resolve(".minecraft");
        Path applications = platform.contains("win") ? appData
                : platform.contains("mac") ? home.resolve("Library/Application Support")
                        : Path.of(environment.getOrDefault("XDG_DATA_HOME", home.resolve(".local/share").toString()));
        Path sk = platform.contains("win") ? appData.resolve(".sklauncher") : home.resolve(".sklauncher");
        Path prism = applications.resolve("PrismLauncher");
        if (!Files.isDirectory(prism, LinkOption.NOFOLLOW_LINKS)) {
            for (Path portable : List.of(applications.resolve("../Local/Programs/PrismLauncher").normalize(), home.resolve("Applications/PrismLauncher"))) {
                if (Files.isRegularFile(portable.resolve("prismlauncher.cfg"), LinkOption.NOFOLLOW_LINKS)) {
                    prism = portable;
                    break;
                }
            }
        }
        Path modrinth = environment.containsKey("THESEUS_CONFIG_DIR") ? Path.of(environment.get("THESEUS_CONFIG_DIR")) : applications.resolve("ModrinthApp");
        return List.of(new LauncherTarget(Kind.PRISM, prism),
                new LauncherTarget(Kind.MINECRAFT, minecraft),
                new LauncherTarget(Kind.SKLAUNCHER, minecraft),
                new LauncherTarget(Kind.SKLAUNCHER_BETA, sk),
                new LauncherTarget(Kind.MODRINTH, modrinth));
    }

    public static LauncherTarget custom(Path root) {
        Kind kind = Files.isRegularFile(root.resolve("app.db"), LinkOption.NOFOLLOW_LINKS) ? Kind.MODRINTH
                : Files.isRegularFile(root.resolve("instances.json"), LinkOption.NOFOLLOW_LINKS) ? Kind.SKLAUNCHER_BETA
                        : Files.isRegularFile(root.resolve("prismlauncher.cfg"), LinkOption.NOFOLLOW_LINKS) ? Kind.PRISM : Kind.FOLDER;
        return new LauncherTarget(kind, root);
    }
}
