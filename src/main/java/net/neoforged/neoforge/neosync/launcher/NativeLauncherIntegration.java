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
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ManagedPaths;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

final class NativeLauncherIntegration {
    private NativeLauncherIntegration() {}

    static String prepare(LauncherIntegration.Detected detected, ProfileStore.Prepared prepared, String fmlVersion, DiscoveryCancellation token) throws IOException {
        ProfileStore.open(prepared.gameDirectory()).verify(prepared, fmlVersion, token);
        Path root = ManagedPaths.directory(detected.installation(), false);
        String id = "neosync-" + prepared.profileId() + "-" + prepared.revisionId();
        boolean modrinth = detected.kind() == LauncherIntegration.Kind.MODRINTH;
        Path versions = root.resolve(modrinth ? "meta/versions" : "versions");
        String sourceId = (modrinth ? "1.21.1-" : "") + LauncherIntegration.versionId(prepared);
        Path source = versions.resolve(sourceId).resolve(sourceId + ".json");
        var runtime = object(read(source));
        if (!SyncJson.string(runtime.get("id"), 256).equals(sourceId)
                || !SyncJson.string(runtime.get("mainClass"), 128).equals("cpw.mods.bootstraplauncher.BootstrapLauncher")
                || runtime.has("inheritsFrom"))
            throw new IOException("The installed NeoSync runtime does not match this profile. Run the launcher setup tool first.");
        if (!runtime.has("arguments") || !runtime.get("arguments").isJsonObject()
                || !runtime.has("libraries") || !runtime.get("libraries").isJsonArray())
            throw new IOException("The installed NeoSync runtime is incomplete.");
        String runtimeVersion = prepared.manifest().neoForgeVersion() + "-neosync-" + prepared.manifest().loaderVersion();
        boolean foundRuntime = false;
        boolean foundMinecraft = false;
        var game = runtime.getAsJsonObject("arguments").getAsJsonArray("game");
        if (game == null) throw new IOException("The installed runtime has no game arguments.");
        for (int i = 0; i + 1 < game.size(); i++) {
            if (game.get(i).isJsonPrimitive() && game.get(i).getAsString().equals("--fml.neoForgeVersion"))
                foundRuntime = game.get(i + 1).isJsonPrimitive() && game.get(i + 1).getAsString().equals(runtimeVersion);
            if (game.get(i).isJsonPrimitive() && game.get(i).getAsString().equals("--fml.mcVersion"))
                foundMinecraft = game.get(i + 1).isJsonPrimitive() && game.get(i + 1).getAsString().equals("1.21.1");
        }
        if (!foundRuntime || !foundMinecraft) throw new IOException("An ordinary NeoForge runtime cannot activate NeoSync profiles.");
        JsonObject launchOverrides = null;
        ModrinthRuntime.Verified verified = null;
        if (modrinth) {
            if (!runtime.has("neosyncLaunchOverrides") || !runtime.get("neosyncLaunchOverrides").isJsonObject())
                throw new IOException("Select Modrinth App in the NeoSync installer before preparing this profile.");
            launchOverrides = runtime.getAsJsonObject("neosyncLaunchOverrides");
            var extra = SyncJson.array(launchOverrides.get("extra_launch_args"), 3, 3);
            String libraryArgument = SyncJson.string(extra.get(0), 4096);
            if (!libraryArgument.startsWith("-DlibraryDirectory=")
                    || !SyncJson.string(extra.get(1), 4096).equals("-Dneosync.launcher.root=" + root)
                    || !SyncJson.string(extra.get(2), 128).equals("-Dneosync.launcher.kind=modrinth"))
                throw new IOException("The Modrinth runtime has invalid local path arguments.");
            Path libraryDirectory = ManagedPaths.directory(Path.of(libraryArgument.substring("-DlibraryDirectory=".length())), false);
            verified = ModrinthRuntime.verify(root, sourceId, runtime, libraryDirectory, token);
        }
        String targetId = (modrinth ? "1.21.1-" : "") + id;
        Path instance = root.resolve(modrinth ? "profiles" : "instances").resolve(id);
        Path target = versions.resolve(targetId);
        if (Files.exists(instance, LinkOption.NOFOLLOW_LINKS)) ManagedPaths.directory(instance, false);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.exists(instance, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The target launcher instance directory already exists. It was not reused.");
        boolean createdInstance = !Files.exists(instance, LinkOption.NOFOLLOW_LINKS);
        boolean created = false;
        Path natives = root.resolve("meta/natives").resolve(targetId);
        boolean createdNatives = modrinth && !Files.exists(natives, LinkOption.NOFOLLOW_LINKS);
        try {
            ProfileStore.Prepared launchProfile = prepared;
            if (!modrinth) {
                ManagedPaths.directory(instance, true);
                launchProfile = ProfileStore.open(prepared.gameDirectory()).export(prepared, instance, fmlVersion, token);
            }
            String gameDirectory = modrinth ? ModrinthRuntime.gameDirectoryArgument(instance, prepared.gameDirectory()) : launchProfile.gameDirectory().toString();
            runtime.addProperty("id", targetId);
            if (!modrinth) runtime.addProperty("type", "custom");
            var arguments = runtime.getAsJsonObject("arguments");
            var nextGame = new JsonArray();
            int directories = 0;
            for (int i = 0; i < game.size(); i++) {
                var argument = game.get(i);
                if (argument.isJsonPrimitive() && argument.getAsString().equals("--gameDir")) {
                    if (++directories > 1 || ++i >= game.size()) throw new IOException("The installed runtime has invalid game-directory arguments.");
                    nextGame.add("--gameDir");
                    nextGame.add(gameDirectory);
                } else {
                    nextGame.add(argument.deepCopy());
                }
            }
            if (directories == 0) {
                nextGame.add("--gameDir");
                nextGame.add(gameDirectory);
            }
            arguments.add("game", nextGame);
            byte[] metadata = runtime.toString().getBytes(StandardCharsets.UTF_8);
            if (metadata.length > SyncManifest.MAX_BYTES) throw new IOException("The launcher runtime exceeds its size limit.");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!runtime.equals(object(read(target.resolve(targetId + ".json")))))
                    throw new IOException("The prepared launcher runtime was edited. It was not overwritten.");
                if (modrinth) ModrinthRuntime.materialize(verified, target, targetId, natives, token);
            } else {
                ManagedPaths.directory(versions, false);
                Path stage = ManagedPaths.directory(versions.resolve(".neosync-" + UUID.randomUUID()), true);
                try {
                    Files.write(stage.resolve(targetId + ".json"), metadata, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    if (modrinth) ModrinthRuntime.materialize(verified, stage, targetId, natives, token);
                    token.check();
                    Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
                    created = true;
                } finally {
                    if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) ManagedPaths.deleteTree(stage);
                    if (!created && createdNatives && Files.isDirectory(natives, LinkOption.NOFOLLOW_LINKS)) ManagedPaths.deleteTree(natives);
                }
            }
            ManagedPaths.directory(instance, true);
            if (modrinth) registerModrinth(root, id, prepared, launchOverrides, verified.protocolVersion(), token);
            else prepareSklauncherRegistration(root, id, launchProfile, metadata, token);
        } catch (IOException failure) {
            if (created) ManagedPaths.deleteTree(target);
            if (createdNatives && Files.isDirectory(natives, LinkOption.NOFOLLOW_LINKS)) ManagedPaths.deleteTree(natives);
            if (createdInstance && Files.exists(instance, LinkOption.NOFOLLOW_LINKS)) ManagedPaths.deleteTree(instance);
            throw failure;
        }
        return id;
    }

    private static void prepareSklauncherRegistration(Path root, String id, ProfileStore.Prepared prepared, byte[] runtime, DiscoveryCancellation token) throws IOException {
        Path instance = root.resolve("instances").resolve(id);
        Path specification = instance.resolve("neosync-registration.properties");
        var record = new Properties();
        record.setProperty("launcherRoot", root.toString());
        record.setProperty("instanceId", id);
        record.setProperty("instanceName", name(prepared));
        record.setProperty("runtimeSha256", SyncManifest.sha256(runtime));
        record.setProperty("gameDirectory", prepared.gameDirectory().toString());
        record.setProperty("manifest", prepared.digest());
        record.setProperty("marker", SyncManifest.sha256(read(prepared.gameDirectory().resolve("neosync-profile.json"))));
        record.setProperty("consent", SyncManifest.sha256(read(prepared.gameDirectory().getParent().resolve("consent.json"))));
        record.setProperty("count", Integer.toString(prepared.manifest().files().size()));
        for (int i = 0; i < prepared.manifest().files().size(); i++) record.setProperty("file." + i, prepared.manifest().files().get(i).sha256());
        var processes = new java.util.ArrayList<ProcessHandle>();
        var ancestor = ProcessHandle.current().parent();
        for (int depth = 0; depth < 8 && ancestor.isPresent(); depth++) {
            var process = ancestor.get();
            if (process.info().command().map(command -> Path.of(command).getFileName().toString().matches("(?i)sklauncher(?:\\.exe)?")).orElse(false)
                    && process.info().startInstant().isPresent())
                processes.add(process);
            ancestor = process.parent();
        }
        if (!processes.isEmpty()) {
            processes.add(ProcessHandle.current());
            record.setProperty("processes", processes.stream().map(process -> process.pid() + "@" + process.info().startInstant().orElseThrow())
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        token.check();
        try (var output = Files.newOutputStream(specification, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            record.store(output, "NeoSync SKlauncher registration");
        }
        if (processes.isEmpty()) {
            SklauncherRegistration.register(specification);
            return;
        }
        Path helper = instance.resolve("neosync-registration.jar");
        try (var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(helper, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
            for (String name : java.util.List.of("SklauncherRegistration", "LauncherBridge", "LauncherBridge$Verifier")) {
                String resource = "net/neoforged/neoforge/neosync/launcher/" + name + ".class";
                try (var input = NativeLauncherIntegration.class.getClassLoader().getResourceAsStream(resource)) {
                    if (input == null) throw new IOException("The SKlauncher registration helper is unavailable.");
                    output.putNextEntry(new java.util.zip.ZipEntry(resource));
                    input.transferTo(output);
                    output.closeEntry();
                }
            }
        }
        Path gson = root.resolve("libraries/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar");
        ManagedPaths.directory(gson.getParent(), false);
        if (!Files.isRegularFile(gson, LinkOption.NOFOLLOW_LINKS) || Files.size(gson) != 283367)
            throw new IOException("The installed Gson library is missing or changed. Rerun the NeoSync installer.");
        try {
            if (!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(gson))).equals("b3add478d4382b78ea20b1671390a858002feb6c"))
                throw new IOException("The installed Gson library changed. Rerun the NeoSync installer.");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows") ? "javaw.exe" : "java");
        token.check();
        new ProcessBuilder(javaBinary.toString(), "-cp", helper + java.io.File.pathSeparator + gson, SklauncherRegistration.class.getName(), specification.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(instance.resolve("neosync-registration.log").toFile()).start();
    }

    private static void registerModrinth(Path root, String id, ProfileStore.Prepared prepared, JsonObject launchOverrides, int protocolVersion, DiscoveryCancellation token) throws IOException {
        Path database = root.resolve("app.db");
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) throw new IOException("The Modrinth instance database is missing or linked.");
        try {
            Class.forName("org.sqlite.JDBC");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=rw")) {
                execute(connection, "PRAGMA foreign_keys=ON");
                execute(connection, "PRAGMA busy_timeout=3000");
                execute(connection, "BEGIN IMMEDIATE");
                try {
                    String instanceId = "local:" + id;
                    try (var query = connection.prepareStatement("SELECT i.path,c.loader_version,c.game_version,c.loader,json(o.overrides),c.instance_id,i.install_stage FROM instances i LEFT JOIN instance_content_sets c ON c.id=i.applied_content_set_id LEFT JOIN instance_launch_overrides o ON o.instance_id=i.id WHERE i.id=?")) {
                        query.setString(1, instanceId);
                        try (var rows = query.executeQuery()) {
                            if (rows.next()) {
                                String overrides = rows.getString(5);
                                if (!id.equals(rows.getString(1)) || !id.equals(rows.getString(2))
                                        || !"1.21.1".equals(rows.getString(3)) || !"neoforge".equals(rows.getString(4))
                                        || !instanceId.equals(rows.getString(6)) || !java.util.List.of("installed", "not_installed").contains(rows.getString(7))
                                        || overrides == null || !launchOverrides.get("extra_launch_args").equals(object(overrides.getBytes(StandardCharsets.UTF_8)).get("extra_launch_args")))
                                    throw new IOException("The prepared Modrinth instance was edited. It was not overwritten.");
                                token.check();
                                updateReadiness(connection, instanceId, protocolVersion);
                                execute(connection, "COMMIT");
                                return;
                            }
                        }
                    }
                    String contentId = "content-set:" + id;
                    long now = Instant.now().getEpochSecond();
                    try (var insert = connection.prepareStatement("INSERT INTO instances(id,path,applied_content_set_id,install_stage,launcher_feature_version,update_channel,name,created,modified) VALUES(?,?,?,'installed','migrated_launch_hooks','release',?,?,?)")) {
                        insert.setString(1, instanceId);
                        insert.setString(2, id);
                        insert.setString(3, contentId);
                        insert.setString(4, name(prepared));
                        insert.setLong(5, now);
                        insert.setLong(6, now);
                        insert.executeUpdate();
                    }
                    try (var insert = connection.prepareStatement("INSERT INTO instance_content_sets(id,instance_id,name,source_kind,status,game_version,loader,loader_version,protocol_version,created,modified) VALUES(?,?,'Default','local','available','1.21.1','neoforge',?,?,?,?)")) {
                        insert.setString(1, contentId);
                        insert.setString(2, instanceId);
                        insert.setString(3, id);
                        insert.setInt(4, protocolVersion);
                        insert.setLong(5, now);
                        insert.setLong(6, now);
                        insert.executeUpdate();
                    }
                    try (var insert = connection.prepareStatement("INSERT INTO instance_links(instance_id,link_kind) VALUES(?,'unmanaged')")) {
                        insert.setString(1, instanceId);
                        insert.executeUpdate();
                    }
                    try (var insert = connection.prepareStatement("INSERT INTO instance_launch_overrides(instance_id,overrides) VALUES(?,json(?))")) {
                        insert.setString(1, instanceId);
                        insert.setString(2, launchOverrides.toString());
                        insert.executeUpdate();
                    }
                    try (var insert = connection.prepareStatement("INSERT INTO instance_sync_preferences(instance_id,feature,enabled) SELECT ?,feature,0 FROM sync_feature_settings")) {
                        insert.setString(1, instanceId);
                        insert.executeUpdate();
                    }
                    token.check();
                    execute(connection, "COMMIT");
                } catch (IOException | SQLException failure) {
                    execute(connection, "ROLLBACK");
                    throw failure;
                }
            }
        } catch (ClassNotFoundException | SQLException failure) {
            throw new IOException("The installed Modrinth database format could not be updated. Your existing instances were preserved.", failure);
        }
    }

    private static void updateReadiness(Connection connection, String instanceId, int protocolVersion) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE instances SET install_stage='installed' WHERE id=?")) {
            update.setString(1, instanceId);
            update.executeUpdate();
        }
        try (var update = connection.prepareStatement("UPDATE instance_content_sets SET protocol_version=? WHERE id=(SELECT applied_content_set_id FROM instances WHERE id=?)")) {
            update.setInt(1, protocolVersion);
            update.setString(2, instanceId);
            update.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String name(ProfileStore.Prepared prepared) {
        return "NeoSync " + prepared.manifest().displayName() + " / " + prepared.manifest().revision();
    }

    private static JsonObject object(byte[] bytes) throws IOException {
        var value = SyncJson.parse(bytes, SyncManifest.MAX_BYTES);
        if (!value.isJsonObject()) throw new IOException("Invalid local launcher record.");
        return value.getAsJsonObject();
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
