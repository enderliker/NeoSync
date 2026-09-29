/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ManagedPaths;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

/** Uses only a locally installed launcher descriptor; a server manifest cannot choose commands or arguments. */
public final class PrismIntegration {
    @org.jetbrains.annotations.Nullable
    private static Path detectedInstance;
    @org.jetbrains.annotations.Nullable
    private static Path detectedExecutable;

    private PrismIntegration() {}

    public record Launch(Path javaBinary, Path executable, Path root, Path instance, Path bridge, Path verification) {}

    public static boolean available() {
        return descriptor() != null || detectedInstance != null && detectedExecutable != null;
    }

    public static void discover(Path gameDirectory) {
        if (System.getProperty("neosync.launcher.config") != null) return;
        Path game = gameDirectory.toAbsolutePath().normalize();
        for (Path current = game; current != null; current = current.getParent()) {
            Path candidate = current.resolve("neosync-launcher.json");
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(current.resolve("instance.cfg"), LinkOption.NOFOLLOW_LINKS)) {
                System.setProperty("neosync.launcher.config", candidate.toString());
                return;
            }
            if (Files.isRegularFile(current.resolve("instance.cfg"), LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(current.resolve("patches/org.neosync.json"), LinkOption.NOFOLLOW_LINKS)
                    && current.getParent() != null && current.getParent().getFileName().toString().equals("instances")) {
                detectedInstance = current;
                var parent = ProcessHandle.current().parent();
                for (int depth = 0; depth < 8 && parent.isPresent(); depth++) {
                    var command = parent.get().info().command();
                    if (command.isPresent()) {
                        Path executable = Path.of(command.get()).toAbsolutePath().normalize();
                        if (executable.getFileName().toString().matches("(?i)prismlauncher(?:\\.exe)?") && Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
                            detectedExecutable = executable;
                            return;
                        }
                    }
                    parent = parent.get().parent();
                }
            }
        }
    }

    private static Path descriptor() {
        String configured = System.getProperty("neosync.launcher.config");
        return configured == null ? null : Path.of(configured);
    }

    public static Launch prepare(ProfileStore.Prepared prepared, String fmlVersion, DiscoveryCancellation token) throws IOException {
        Path configured = descriptor();
        if (configured == null && detectedInstance != null && detectedExecutable != null) {
            ProfileStore.open(prepared.gameDirectory()).verify(prepared, fmlVersion, token);
            configured = configureDetected(detectedInstance, detectedExecutable, prepared, token);
        }
        if (configured == null) throw new IOException("This Prism instance has no verified NeoSync runtime descriptor.");
        return prepare(configured, prepared, fmlVersion, token);
    }

    private static Path configureDetected(Path base, Path executable, ProfileStore.Prepared prepared, DiscoveryCancellation token) throws IOException {
        ManagedPaths.directory(base, false);
        var parsed = SyncJson.parse(read(base.resolve("patches/org.neosync.json"), SyncManifest.MAX_BYTES), SyncManifest.MAX_BYTES);
        if (!parsed.isJsonObject()) throw new IOException("Invalid local NeoSync Prism component.");
        var component = parsed.getAsJsonObject();
        if (!SyncJson.string(component.get("uid"), 64).equals("org.neosync")
                || !SyncJson.string(component.get("version"), 128).equals(prepared.manifest().loaderVersion())
                || !SyncJson.string(component.get("mainClass"), 128).equals("cpw.mods.bootstraplauncher.BootstrapLauncher"))
            throw new IOException("This Prism instance does not contain the matching NeoSync runtime.");
        var libraries = new JsonObject();
        long total = 0;
        for (var library : SyncJson.array(component.get("libraries"), 1, 256)) {
            if (!library.isJsonObject() || !library.getAsJsonObject().has("MMC-hint")
                    || !SyncJson.string(library.getAsJsonObject().get("MMC-hint"), 16).equals("local"))
                throw new IOException("Automatic Prism setup requires locally installed NeoSync runtime libraries.");
        }
        try (var entries = Files.newDirectoryStream(ManagedPaths.directory(base.resolve("libraries"), false))) {
            for (var entry : entries) {
                token.check();
                if (libraries.size() >= 256 || !entry.getFileName().toString().matches(SyncManifest.FILE_PATTERN))
                    throw new IOException("Unsupported local Prism library inventory.");
                byte[] bytes = read(entry, 64 * 1024 * 1024);
                total += bytes.length;
                if (total > 512L * 1024 * 1024) throw new IOException("Local Prism runtime exceeds the copy limit.");
                libraries.addProperty(entry.getFileName().toString(), SyncManifest.sha256(bytes));
            }
        }
        Path bridge = base.resolve("neosync-launcher-bridge.jar");
        if (!Files.exists(bridge, LinkOption.NOFOLLOW_LINKS)) {
            Path temporary = base.resolve(".neosync-bridge-" + UUID.randomUUID() + ".tmp");
            try {
                try (var archive = new JarOutputStream(Files.newOutputStream(temporary, java.nio.file.StandardOpenOption.CREATE_NEW))) {
                    for (String name : java.util.List.of("LauncherBridge", "LauncherBridge$Verifier")) {
                        String resource = "net/neoforged/neoforge/neosync/launcher/" + name + ".class";
                        try (var input = LauncherBridge.class.getClassLoader().getResourceAsStream(resource)) {
                            if (input == null) throw new IOException("The installed launcher bridge is missing.");
                            archive.putNextEntry(new JarEntry(resource));
                            archive.write(input.readNBytes(1024 * 1024));
                            archive.closeEntry();
                        }
                    }
                }
                token.check();
                Files.move(temporary, bridge, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        var config = new JsonObject();
        config.addProperty("schemaVersion", 1);
        config.addProperty("kind", "prism");
        config.addProperty("version", prepared.manifest().loaderVersion());
        config.addProperty("neoForgeVersion", prepared.manifest().neoForgeVersion());
        config.addProperty("root", base.getParent().getParent().toString());
        config.addProperty("instance", base.toString());
        config.addProperty("executable", executable.toString());
        config.addProperty("java", Path.of(System.getProperty("java.home")).resolve("bin").resolve(System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toRealPath().toString());
        config.addProperty("bridgeSha256", SyncManifest.sha256(read(bridge, 1024 * 1024)));
        config.add("libraries", libraries);
        config.add("component", component);
        Path descriptor = base.resolve("neosync-launcher.json");
        token.check();
        Files.writeString(descriptor, config.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
        System.setProperty("neosync.launcher.config", descriptor.toString());
        return descriptor;
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public static Launch prepareDetected(Path base, Path executable, ProfileStore.Prepared prepared, String fmlVersion, DiscoveryCancellation token) throws IOException {
        ProfileStore.open(prepared.gameDirectory()).verify(prepared, fmlVersion, token);
        Path configuration = configureDetected(base, executable, prepared, token);
        return prepare(configuration, prepared, fmlVersion, token);
    }

    public static Launch prepare(Path descriptor, ProfileStore.Prepared prepared, String fmlVersion, DiscoveryCancellation token) throws IOException {
        var config = SyncJson.object(SyncJson.parse(read(descriptor, SyncManifest.MAX_BYTES), SyncManifest.MAX_BYTES),
                Set.of("schemaVersion", "kind", "version", "neoForgeVersion", "root", "instance", "executable", "java", "libraries", "component", "bridgeSha256"), Set.of());
        SyncJson.number(config.get("schemaVersion"), 1, 1);
        if (!SyncJson.string(config.get("kind"), 16).equals("prism")
                || !SyncJson.string(config.get("version"), 128).equals(prepared.manifest().loaderVersion())
                || !SyncJson.string(config.get("neoForgeVersion"), 128).equals(prepared.manifest().neoForgeVersion()))
            throw new IOException("The local Prism runtime does not match this profile's NeoSync version.");
        ProfileStore.open(prepared.gameDirectory()).verify(prepared, fmlVersion, token);
        Path root = localPath(config, "root");
        Path base = localPath(config, "instance");
        Path javaBinary = localPath(config, "java");
        Path executable = localPath(config, "executable");
        ManagedPaths.directory(root, false);
        ManagedPaths.directory(base, false);
        if (!Files.isRegularFile(javaBinary) || !Files.isRegularFile(executable)) throw new IOException("The configured Java or Prism executable is missing.");
        Path bridge = base.resolve("neosync-launcher-bridge.jar");
        if (!SyncManifest.sha256(read(bridge, 1024 * 1024)).equals(SyncJson.matching(config.get("bridgeSha256"), 64, SyncManifest.HASH_PATTERN)))
            throw new IOException("The launcher bridge changed. Configure the Prism instance again.");
        var libraries = config.getAsJsonObject("libraries");
        if (libraries == null || libraries.size() > 256) throw new IOException("Invalid launcher library inventory.");
        String instanceId = "NeoSync-" + prepared.profileId() + "-" + prepared.revisionId();
        Path instances = ManagedPaths.directory(root.resolve("instances"), false);
        Path target = instances.resolve(instanceId);
        Path stage = ManagedPaths.directory(instances.resolve(".neosync-" + UUID.randomUUID()), true);
        try {
            var component = config.getAsJsonObject("component").deepCopy();
            String gameArguments = SyncJson.string(component.get("minecraftArguments"), 16384);
            var gameDirectoryArgument = java.util.regex.Pattern.compile("--gameDir \\$\\{game_directory\\}(?:/[^\\s]*)?(?=\\s|$)").matcher(gameArguments);
            if (!gameDirectoryArgument.find() || gameDirectoryArgument.find()) throw new IOException("The Prism component must contain one supported game directory argument.");
            String relativeGame = target.resolve(".minecraft").relativize(prepared.gameDirectory()).toString().replace('\\', '/');
            if (relativeGame.contains("${") || relativeGame.chars().anyMatch(c -> Character.isWhitespace(c) || c == '"'))
                throw new IOException("This profile path cannot be passed through Prism's argument format. Use manual restart instructions.");
            // Prism splits arguments on spaces before expanding variables; shell quoting becomes a literal path character.
            component.addProperty("minecraftArguments", gameDirectoryArgument.replaceFirst(java.util.regex.Matcher.quoteReplacement("--gameDir ${game_directory}/" + relativeGame)));
            if (component.has("+jvmArgs")) {
                var args = component.getAsJsonArray("+jvmArgs");
                for (int i = 0; i < args.size(); i++) {
                    args.set(i, new com.google.gson.JsonPrimitive(args.get(i).getAsString()
                            .replace(base.resolve("libraries").toString().replace('\\', '/'), target.resolve("libraries").toString().replace('\\', '/'))
                            .replace(base.resolve("neosync-launcher.json").toString().replace('\\', '/'), target.resolve("neosync-launcher.json").toString().replace('\\', '/'))));
                }
            }
            Path libraryTarget = ManagedPaths.directory(stage.resolve("libraries"), true);
            long total = 0;
            for (var entry : libraries.entrySet()) {
                token.check();
                String name = entry.getKey();
                if (!name.matches(SyncManifest.FILE_PATTERN) || name.length() > 256) throw new IOException("Invalid local launcher library filename.");
                Path source = base.resolve("libraries").resolve(name);
                byte[] bytes = read(source, 64 * 1024 * 1024);
                total += bytes.length;
                if (total > 512L * 1024 * 1024) throw new IOException("Launcher libraries exceed the local copy limit.");
                if (!SyncManifest.sha256(bytes).equals(SyncJson.matching(entry.getValue(), 64, SyncManifest.HASH_PATTERN)))
                    throw new IOException("A local Prism library changed. Configure the instance again.");
                Files.write(libraryTarget.resolve(name), bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            ManagedPaths.directory(stage.resolve("patches"), true);
            ManagedPaths.directory(stage.resolve(".minecraft"), true);
            Files.writeString(stage.resolve("patches/org.neosync.json"), component.toString(), StandardCharsets.UTF_8);
            var pack = new JsonObject();
            pack.addProperty("formatVersion", 1);
            var components = new JsonArray();
            for (var entry : java.util.List.of(Map.entry("org.lwjgl3", "3.3.3"), Map.entry("net.minecraft", "1.21.1"), Map.entry("org.neosync", prepared.manifest().loaderVersion()))) {
                var item = new JsonObject();
                item.addProperty("uid", entry.getKey());
                item.addProperty("version", entry.getValue());
                components.add(item);
            }
            pack.add("components", components);
            Files.writeString(stage.resolve("mmc-pack.json"), pack.toString(), StandardCharsets.UTF_8);
            Path verification = target.resolve("neosync-verification.properties");
            var record = new Properties();
            record.setProperty("gameDirectory", prepared.gameDirectory().toString());
            record.setProperty("manifest", prepared.digest());
            record.setProperty("marker", SyncManifest.sha256(read(prepared.gameDirectory().resolve("neosync-profile.json"), 8192)));
            record.setProperty("consent", SyncManifest.sha256(read(prepared.gameDirectory().getParent().resolve("consent.json"), SyncManifest.MAX_BYTES)));
            record.setProperty("count", Integer.toString(prepared.manifest().files().size()));
            for (int i = 0; i < prepared.manifest().files().size(); i++) record.setProperty("file." + i, prepared.manifest().files().get(i).sha256());
            String preflight = quote(javaBinary.toString()) + " -cp " + quote(target.resolve("neosync-launcher-bridge.jar").toString()) + " " + LauncherBridge.class.getName() + " verify " + quote(verification.toString());
            String settings = "[General]\nConfigVersion=1.3\nInstanceType=OneSix\nname=" + quote("NeoSync " + prepared.manifest().revision()) + "\niconKey=default\nOverrideJavaLocation=true\nJavaPath=" + quote(javaBinary.toString().replace('\\', '/'))
                    + "\nOverrideMemory=true\nMaxMemAlloc=2048\nMinMemAlloc=512\nOverrideCommands=true\nPreLaunchCommand=" + quote(preflight) + "\n";
            Files.writeString(stage.resolve("instance.cfg"), settings, StandardCharsets.UTF_8);
            var nextConfig = config.deepCopy();
            nextConfig.addProperty("instance", target.toString());
            nextConfig.add("component", component.deepCopy());
            Files.copy(bridge, stage.resolve("neosync-launcher-bridge.jar"));
            Files.writeString(stage.resolve("neosync-launcher.json"), nextConfig.toString(), StandardCharsets.UTF_8);
            for (String name : java.util.List.of("instance.cfg", "mmc-pack.json", "patches/org.neosync.json", "neosync-launcher-bridge.jar"))
                record.setProperty("launcher." + name, SyncManifest.sha256(read(stage.resolve(name), 1024 * 1024)));
            try (var writer = Files.newBufferedWriter(stage.resolve("neosync-verification.properties"), StandardCharsets.UTF_8)) {
                record.store(writer, "NeoSync pre-launch verification; contains no account data");
            }
            token.check();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                ManagedPaths.directory(target, false);
                if (!java.util.Arrays.equals(read(target.resolve("patches/org.neosync.json"), SyncManifest.MAX_BYTES), read(stage.resolve("patches/org.neosync.json"), SyncManifest.MAX_BYTES)))
                    throw new IOException("The existing Prism instance was edited. Select it manually or create a new instance.");
                if (!java.util.Arrays.equals(read(target.resolve("instance.cfg"), SyncManifest.MAX_BYTES), read(stage.resolve("instance.cfg"), SyncManifest.MAX_BYTES)))
                    throw new IOException("The existing Prism launch settings changed. No automatic restart was attempted.");
                for (var entry : libraries.entrySet()) {
                    if (!SyncManifest.sha256(read(target.resolve("libraries").resolve(entry.getKey()), 64 * 1024 * 1024)).equals(entry.getValue().getAsString()))
                        throw new IOException("A library in the existing Prism instance changed. Configure a new instance.");
                }
                LauncherBridge.Verifier.verify(verification);
            } else {
                synchronized (token) {
                    token.check();
                    Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
                }
            }
            return new Launch(javaBinary, executable, root, target, target.resolve("neosync-launcher-bridge.jar"), verification);
        } finally {
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) ManagedPaths.deleteTree(stage);
        }
    }

    public static void restart(Launch launch) throws IOException {
        LauncherBridge.Verifier.verify(launch.verification());
        ManagedPaths.directory(launch.root(), false);
        ManagedPaths.directory(launch.instance(), false);
        if (!Files.isRegularFile(launch.executable(), LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(launch.executable()))
            throw new IOException("The detected Prism executable is no longer available.");
        Path log = launch.instance().resolve("neosync-handoff-" + UUID.randomUUID() + ".log");
        new ProcessBuilder(launch.javaBinary().toString(), "-cp", launch.bridge().toString(), LauncherBridge.class.getName(), "restart", Long.toString(ProcessHandle.current().pid()),
                launch.executable().toString(), launch.root().toString(), launch.instance().getFileName().toString(), launch.verification().toString())
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static Path localPath(JsonObject config, String key) throws IOException {
        Path path = Path.of(SyncJson.string(config.get(key), 4096));
        if (!path.isAbsolute() || !path.equals(path.normalize()) || path.toString().contains("${")) throw new IOException("Invalid local launcher path.");
        return path;
    }

    private static byte[] read(Path path, int limit) throws IOException {
        ManagedPaths.directory(path.getParent(), false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) throw new IOException("Missing or oversized local launcher file.");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Oversized local launcher file.");
            return bytes;
        }
    }

    private static String quote(String value) throws IOException {
        if (value.contains("${") || value.chars().anyMatch(c -> c < 32)) throw new IOException("Unsupported control character or placeholder in a launcher path.");
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
