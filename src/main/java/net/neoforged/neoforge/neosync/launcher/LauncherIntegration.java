/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.launcher;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ManagedPaths;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import org.jetbrains.annotations.Nullable;

public final class LauncherIntegration {
    private LauncherIntegration() {}

    public enum Kind {
        PRISM("Prism Launcher"), MINECRAFT("Minecraft Launcher"), SKLAUNCHER("SKlauncher 3.2"), SKLAUNCHER_BETA("SKlauncher 4.0 Beta"),
        LUNAR("Lunar Client"), MULTIMC("MultiMC"), ATLAUNCHER("ATLauncher"),
        MODRINTH("Modrinth App"), CURSEFORGE("CurseForge App"), UNKNOWN("your launcher");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record Detected(Kind kind, @Nullable Path installation, @Nullable Path executable) {
        public boolean canCreateInstallation() {
            return installation != null && (kind == Kind.MINECRAFT || kind == Kind.SKLAUNCHER
                    || kind == Kind.SKLAUNCHER_BETA || kind == Kind.MODRINTH);
        }
    }

    public static Kind identify(String brand) {
        String value = brand.toLowerCase(Locale.ROOT);
        if (value.contains("prism")) return Kind.PRISM;
        if (value.contains("sklauncher")) return value.contains("beta") || value.contains("4.0") || value.contains("next") ? Kind.SKLAUNCHER_BETA : Kind.SKLAUNCHER;
        if (value.contains("lunar")) return Kind.LUNAR;
        if (value.contains("multimc")) return Kind.MULTIMC;
        if (value.contains("atlauncher")) return Kind.ATLAUNCHER;
        if (value.contains("modrinth") || value.equals("theseus")) return Kind.MODRINTH;
        if (value.contains("curseforge")) return Kind.CURSEFORGE;
        if (value.equals("minecraft-launcher") || value.equals("minecraftlauncher") || value.equals("minecraft launcher") || value.equals("mojang") || value.equals("microsoft")) return Kind.MINECRAFT;
        return Kind.UNKNOWN;
    }

    public static Detected detect(Path gameDirectory) {
        PrismIntegration.discover(gameDirectory);
        Kind kind = PrismIntegration.available() ? Kind.PRISM : identify(System.getProperty("minecraft.launcher.brand", ""));
        Path executable = null;
        var parent = ProcessHandle.current().parent();
        for (int depth = 0; depth < 8 && parent.isPresent(); depth++) {
            var command = parent.get().info().command();
            if (command.isPresent()) {
                Path path = Path.of(command.get()).toAbsolutePath().normalize();
                Kind ancestor = identify(path.getFileName().toString().replaceFirst("(?i)\\.exe$", ""));
                if (ancestor != Kind.UNKNOWN && (kind == Kind.UNKNOWN || kind == ancestor || kind == Kind.SKLAUNCHER_BETA && ancestor == Kind.SKLAUNCHER)) {
                    if (kind != Kind.SKLAUNCHER_BETA || ancestor != Kind.SKLAUNCHER) kind = ancestor;
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(path)) executable = path;
                    break;
                }
            }
            parent = parent.get().parent();
        }
        Path installation = null;
        String libraries = System.getProperty("libraryDirectory");
        if (libraries != null) {
            Path path = Path.of(libraries).toAbsolutePath().normalize();
            if (path.getFileName() != null && path.getFileName().toString().equals("libraries")) installation = path.getParent();
        }
        if (installation == null && Files.isDirectory(gameDirectory.resolve("versions"), LinkOption.NOFOLLOW_LINKS))
            installation = gameDirectory.toAbsolutePath().normalize();
        if (kind == Kind.SKLAUNCHER && System.getProperty("minecraft.launcher.version", "").matches("4\\..*")) kind = Kind.SKLAUNCHER_BETA;
        String configuredRoot = System.getProperty("neosync.launcher.root");
        if (configuredRoot != null && (kind == Kind.MINECRAFT || kind == Kind.SKLAUNCHER || kind == Kind.SKLAUNCHER_BETA || kind == Kind.MODRINTH)) {
            try {
                installation = Path.of(configuredRoot).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                installation = null;
            }
        }
        if (kind == Kind.MODRINTH && installation != null && installation.getFileName() != null && installation.getFileName().toString().equals("meta"))
            installation = installation.getParent();
        if (kind == Kind.SKLAUNCHER_BETA && (installation == null || !Files.isRegularFile(installation.resolve("instances.json"), LinkOption.NOFOLLOW_LINKS)))
            installation = null;
        if (kind == Kind.MODRINTH && (installation == null || !Files.isRegularFile(installation.resolve("app.db"), LinkOption.NOFOLLOW_LINKS)))
            installation = null;
        return new Detected(kind, installation, executable);
    }

    public static String createInstallation(Detected detected, ProfileStore.Prepared prepared, String fmlVersion, DiscoveryCancellation token) throws IOException {
        if (!detected.canCreateInstallation()) throw new IOException("Select a supported launcher with an installed NeoSync runtime.");
        return switch (detected.kind()) {
            case MINECRAFT, SKLAUNCHER -> createInstallation(detected.installation(), prepared, fmlVersion, token);
            case SKLAUNCHER_BETA, MODRINTH -> NativeLauncherIntegration.prepare(detected, prepared, fmlVersion, token);
            default -> throw new IOException("This launcher requires manual activation.");
        };
    }

    public static String versionId(ProfileStore.Prepared prepared) {
        return "NeoSync-" + prepared.manifest().loaderVersion() + "-neoforge-" + prepared.manifest().neoForgeVersion();
    }

    public static String createInstallation(Path installation, ProfileStore.Prepared prepared, String fmlVersion, DiscoveryCancellation token) throws IOException {
        ProfileStore.open(prepared.gameDirectory()).verify(prepared, fmlVersion, token);
        ManagedPaths.directory(installation, false);
        String version = versionId(prepared);
        Path versionRecord = installation.resolve("versions").resolve(version).resolve(version + ".json");
        var versionJson = SyncJson.parse(read(versionRecord), SyncManifest.MAX_BYTES);
        if (!versionJson.isJsonObject()) throw new IOException("Invalid installed NeoSync runtime record.");
        var installed = versionJson.getAsJsonObject();
        if (!SyncJson.string(installed.get("id"), 128).equals(version)
                || !SyncJson.string(installed.get("inheritsFrom"), 32).equals("1.21.1")
                || !SyncJson.string(installed.get("mainClass"), 128).equals("cpw.mods.bootstraplauncher.BootstrapLauncher"))
            throw new IOException("The installed NeoSync runtime does not match the prepared profile.");
        Path profiles = installation.resolve("launcher_profiles.json");
        byte[] original = read(profiles);
        var parsed = SyncJson.parse(original, SyncManifest.MAX_BYTES);
        if (!parsed.isJsonObject()) throw new IOException("Invalid Minecraft Launcher installation record.");
        var root = parsed.getAsJsonObject();
        if (root.has("authenticationDatabase") || root.has("selectedUser"))
            throw new IOException("Legacy launcher records containing account credentials cannot be edited. Upgrade your launcher or use manual activation.");
        if (!root.has("profiles") || !root.get("profiles").isJsonObject()) throw new IOException("Invalid Minecraft Launcher installation inventory.");
        String id = "neosync-" + prepared.profileId() + "-" + prepared.revisionId();
        var inventory = root.getAsJsonObject("profiles");
        if (inventory.has(id)) {
            if (!inventory.get(id).isJsonObject()) throw new IOException("The existing launcher installation is malformed.");
            var existing = inventory.getAsJsonObject(id);
            if (!version.equals(SyncJson.string(existing.get("lastVersionId"), 128))
                    || !prepared.gameDirectory().toString().equals(SyncJson.string(existing.get("gameDir"), 4096)))
                throw new IOException("The existing launcher installation was edited. It was not overwritten.");
            return id;
        }
        var entry = new JsonObject();
        entry.addProperty("name", "NeoSync " + prepared.manifest().displayName() + " / " + prepared.manifest().revision());
        entry.addProperty("type", "custom");
        entry.addProperty("lastVersionId", version);
        entry.addProperty("gameDir", prepared.gameDirectory().toString());
        entry.addProperty("created", Instant.now().toString());
        inventory.add(id, entry);
        byte[] output = root.toString().getBytes(StandardCharsets.UTF_8);
        if (output.length > SyncManifest.MAX_BYTES) throw new IOException("The launcher installation inventory exceeds its size limit.");
        Path stage = installation.resolve(".neosync-launcher-" + UUID.randomUUID() + ".tmp");
        try (var channel = FileChannel.open(installation.resolve(".neosync-launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another NeoSync process is editing launcher installations.");
            if (!java.util.Arrays.equals(original, read(profiles))) throw new IOException("The launcher changed its installations. Retry without closing Minecraft.");
            try {
                try {
                    Files.createFile(stage, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException ignored) {
                    Files.createFile(stage);
                }
                Files.write(stage, output, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                token.check();
                if (!java.util.Arrays.equals(original, read(profiles))) throw new IOException("The launcher changed its installations. Retry without closing Minecraft.");
                Files.move(stage, profiles, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(stage);
            }
        }
        return id;
    }

    private static byte[] read(Path path) throws IOException {
        ManagedPaths.directory(path.getParent(), false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > SyncManifest.MAX_BYTES)
            throw new IOException("A required launcher record is missing, linked, or oversized.");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(SyncManifest.MAX_BYTES + 1);
            if (bytes.length > SyncManifest.MAX_BYTES) throw new IOException("The launcher record exceeds its size limit.");
            return bytes;
        }
    }
}
