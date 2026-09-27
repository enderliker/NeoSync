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
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ManagedPaths;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

/** Uses only a locally installed launcher descriptor; a server manifest cannot choose commands or arguments. */
public final class PrismIntegration {
    private PrismIntegration() {}

    public record Launch(Path javaBinary, Path executable, Path root, Path instance, Path bridge, Path verification) {}

    public static boolean available() {
        return System.getProperty("neosync.launcher.config") != null;
    }

    public static Launch prepare(ProfileStore.Prepared prepared, String fmlVersion, DiscoveryCancellation token) throws IOException {
        String configured = System.getProperty("neosync.launcher.config");
        if (configured == null) throw new IOException("Configure a NeoSync Prism instance first, or use manual restart instructions.");
        return prepare(Path.of(configured), prepared, fmlVersion, token);
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
            if (!gameArguments.contains("--gameDir ${game_directory}")) throw new IOException("The Prism component has no supported game directory argument.");
            component.addProperty("minecraftArguments", gameArguments.replace("--gameDir ${game_directory}", "--gameDir " + quote(prepared.gameDirectory().toString())));
            if (component.has("+jvmArgs")) {
                var args = component.getAsJsonArray("+jvmArgs");
                for (int i = 0; i < args.size(); i++) {
                    args.set(i, new com.google.gson.JsonPrimitive(args.get(i).getAsString().replace(base.resolve("libraries").toString().replace('\\', '/'), target.resolve("libraries").toString().replace('\\', '/'))));
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
            for (var entry : Map.of("org.lwjgl3", "3.3.3", "net.minecraft", "1.21.1", "org.neosync", prepared.manifest().loaderVersion()).entrySet()) {
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
            try (var writer = Files.newBufferedWriter(stage.resolve("neosync-verification.properties"), StandardCharsets.UTF_8)) {
                record.store(writer, "NeoSync pre-launch verification; contains no account data");
            }
            String preflight = quote(javaBinary.toString()) + " -cp " + quote(bridge.toString()) + " " + LauncherBridge.class.getName() + " verify " + quote(verification.toString());
            String settings = "[General]\nConfigVersion=1.3\nInstanceType=OneSix\nname=" + quote("NeoSync " + prepared.manifest().revision()) + "\niconKey=default\nOverrideJavaLocation=true\nJavaPath=" + quote(javaBinary.toString().replace('\\', '/'))
                    + "\nOverrideMemory=true\nMaxMemAlloc=2048\nMinMemAlloc=512\nOverrideCommands=true\nPreLaunchCommand=" + quote(preflight) + "\n";
            Files.writeString(stage.resolve("instance.cfg"), settings, StandardCharsets.UTF_8);
            token.check();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                ManagedPaths.directory(target, false);
                if (!java.util.Arrays.equals(read(target.resolve("patches/org.neosync.json"), SyncManifest.MAX_BYTES), read(stage.resolve("patches/org.neosync.json"), SyncManifest.MAX_BYTES)))
                    throw new IOException("The existing Prism instance was edited. Select it manually or create a new instance.");
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
            return new Launch(javaBinary, executable, root, target, bridge, verification);
        } finally {
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) ManagedPaths.deleteTree(stage);
        }
    }

    public static void restart(Launch launch) throws IOException {
        LauncherBridge.Verifier.verify(launch.verification());
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
