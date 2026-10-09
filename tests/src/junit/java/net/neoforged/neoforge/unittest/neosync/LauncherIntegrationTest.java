/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.neosync.launcher.LauncherIntegration;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherIntegrationTest {
    @Test
    void preparesBetaAndModrinthRevisionsWithoutRelocatingTheirVerifiedGameDirectory(@TempDir Path directory) throws Exception {
        var prepared = prepare(directory);
        for (var kind : java.util.List.of(LauncherIntegration.Kind.SKLAUNCHER_BETA, LauncherIntegration.Kind.MODRINTH)) {
            boolean modrinth = kind == LauncherIntegration.Kind.MODRINTH;
            Path root = Files.createDirectory(directory.resolve(kind.name() + " with spaces"));
            Path versions = Files.createDirectories(root.resolve(modrinth ? "meta/versions" : "versions"));
            String version = (modrinth ? "1.21.1-" : "") + LauncherIntegration.versionId(prepared);
            Path source = Files.createDirectory(versions.resolve(version));
            var metadata = new com.google.gson.JsonObject();
            metadata.addProperty("id", version);
            metadata.addProperty("mainClass", "cpw.mods.bootstraplauncher.BootstrapLauncher");
            metadata.add("libraries", new com.google.gson.JsonArray());
            var arguments = new com.google.gson.JsonObject();
            var game = new com.google.gson.JsonArray();
            for (String value : java.util.List.of("--username", "${auth_player_name}", "--gameDir", "${game_directory}",
                    "--fml.neoForgeVersion", "21.1.251-neosync-0.1.0-dev", "--fml.mcVersion", "1.21.1"))
                game.add(value);
            arguments.add("game", game);
            metadata.add("arguments", arguments);
            if (modrinth) {
                var overrides = new com.google.gson.JsonObject();
                var extra = new com.google.gson.JsonArray();
                extra.add("-DlibraryDirectory=" + Files.createDirectory(root.resolve("libraries")));
                extra.add("-Dneosync.launcher.root=" + root);
                extra.add("-Dneosync.launcher.kind=modrinth");
                overrides.add("extra_launch_args", extra);
                metadata.add("neosyncLaunchOverrides", overrides);
            }
            Files.writeString(source.resolve(version + ".json"), metadata.toString());
            if (modrinth) createModrinthDatabase(root);
            else Files.writeString(root.resolve("instances.json"), "{\"instances\":[{\"id\":\"personal\",\"name\":\"My world\"}],\"setting\":true}");
            Path accounts = Files.writeString(root.resolve("accounts.json"), "private fixture");
            var detected = new LauncherIntegration.Detected(kind, root, null);
            String id = LauncherIntegration.createInstallation(detected, prepared, "4.0.44", new DiscoveryCancellation());
            Path target = versions.resolve((modrinth ? "1.21.1-" : "") + id);
            Path record = target.resolve(target.getFileName() + ".json");
            var result = JsonParser.parseString(Files.readString(record)).getAsJsonObject();
            var launchArguments = result.getAsJsonObject("arguments").getAsJsonArray("game");
            assertEquals(prepared.gameDirectory().toString(), launchArguments.get(3).getAsString());
            assertEquals("${auth_player_name}", launchArguments.get(1).getAsString());
            assertEquals("private fixture", Files.readString(accounts));
            assertEquals(id, LauncherIntegration.createInstallation(detected, prepared, "4.0.44", new DiscoveryCancellation()));
            if (modrinth) {
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement()) {
                    try (var rows = statement.executeQuery("SELECT count(*) FROM instances")) {
                        assertTrue(rows.next());
                        assertEquals(2, rows.getInt(1));
                    }
                    try (var rows = statement.executeQuery("SELECT link_kind FROM instance_links WHERE instance_id='local:" + id + "'")) {
                        assertTrue(rows.next());
                        assertEquals("unmanaged", rows.getString(1));
                    }
                    try (var rows = statement.executeQuery("SELECT json(overrides) FROM instance_launch_overrides WHERE instance_id='local:" + id + "'")) {
                        assertTrue(rows.next());
                        assertEquals(metadata.get("neosyncLaunchOverrides"), JsonParser.parseString(rows.getString(1)));
                    }
                }
            } else {
                var inventory = JsonParser.parseString(Files.readString(root.resolve("instances.json"))).getAsJsonObject();
                assertEquals(2, inventory.getAsJsonArray("instances").size());
                assertEquals("My world", inventory.getAsJsonArray("instances").get(0).getAsJsonObject().get("name").getAsString());
                assertTrue(inventory.get("setting").getAsBoolean());
                var entry = inventory.getAsJsonArray("instances").get(1).getAsJsonObject();
                entry.addProperty("compatibilityMode", false);
                Files.writeString(root.resolve("instances.json"), inventory.toString());
                assertThrows(IOException.class, () -> LauncherIntegration.createInstallation(detected, prepared, "4.0.44", new DiscoveryCancellation()));
                entry.addProperty("compatibilityMode", true);
                Files.writeString(root.resolve("instances.json"), inventory.toString());
            }
            result.getAsJsonObject("arguments").getAsJsonArray("game").set(3, new com.google.gson.JsonPrimitive(directory.toString()));
            Files.writeString(record, result.toString());
            assertThrows(IOException.class, () -> LauncherIntegration.createInstallation(detected, prepared, "4.0.44", new DiscoveryCancellation()));
        }
    }

    @Test
    void unsupportedDatabaseRollsBackTheRuntimeAndInstance(@TempDir Path directory) throws Exception {
        var prepared = prepare(directory);
        Path root = Files.createDirectory(directory.resolve("modrinth"));
        Path libraries = Files.createDirectory(root.resolve("libraries"));
        String version = "1.21.1-" + LauncherIntegration.versionId(prepared);
        Path source = Files.createDirectories(root.resolve("meta/versions").resolve(version));
        var runtime = new com.google.gson.JsonObject();
        runtime.addProperty("id", version);
        runtime.addProperty("mainClass", "cpw.mods.bootstraplauncher.BootstrapLauncher");
        runtime.add("libraries", new com.google.gson.JsonArray());
        var game = new com.google.gson.JsonArray();
        for (String value : java.util.List.of("--fml.neoForgeVersion", "21.1.251-neosync-0.1.0-dev", "--fml.mcVersion", "1.21.1")) game.add(value);
        var arguments = new com.google.gson.JsonObject();
        arguments.add("game", game);
        runtime.add("arguments", arguments);
        var extra = new com.google.gson.JsonArray();
        extra.add("-DlibraryDirectory=" + libraries);
        extra.add("-Dneosync.launcher.root=" + root);
        extra.add("-Dneosync.launcher.kind=modrinth");
        var overrides = new com.google.gson.JsonObject();
        overrides.add("extra_launch_args", extra);
        runtime.add("neosyncLaunchOverrides", overrides);
        Files.writeString(source.resolve(version + ".json"), runtime.toString());
        createModrinthDatabase(root);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE instance_sync_preferences");
        }
        var detected = new LauncherIntegration.Detected(LauncherIntegration.Kind.MODRINTH, root, null);
        assertThrows(IOException.class, () -> LauncherIntegration.createInstallation(detected, prepared, "4.0.44", new DiscoveryCancellation()));
        try (var entries = Files.list(root.resolve("meta/versions"))) {
            assertEquals(1, entries.count());
        }
        try (var entries = Files.list(root.resolve("profiles"))) {
            assertEquals(0, entries.count());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db"));
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM instances")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void cancellationPreservesNativeLauncherInventories(@TempDir Path directory) throws Exception {
        var prepared = prepare(directory);
        Path root = Files.createDirectory(directory.resolve("beta"));
        Path inventory = Files.writeString(root.resolve("instances.json"), "{\"instances\":[]}");
        var token = new DiscoveryCancellation();
        token.close();
        assertThrows(IOException.class, () -> LauncherIntegration.createInstallation(new LauncherIntegration.Detected(LauncherIntegration.Kind.SKLAUNCHER_BETA, root, null), prepared, "4.0.44", token));
        assertEquals("{\"instances\":[]}", Files.readString(inventory));
        assertFalse(Files.exists(root.resolve("versions")));
    }

    private static ProfileStore.Prepared prepare(Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var artifact = JarMetadataTest.artifact(jar);
        var manifest = JsonParser.parseString(new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("sha256", artifact.sha256());
        manifest.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("size", artifact.size());
        byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        var plan = InstallationPlan.create(InstallationPlanTest.endpoint(bytes), bytes, Set.of(artifact.sha256()), null, "0.1.0-dev", "21.1.251");
        return ProfileStore.open(directory).prepare(plan, plan.accept(true, true), Map.of(artifact.sha256(), jar), "4.0.44", new DiscoveryCancellation(), (action, completed, total) -> {});
    }

    private static void createModrinthDatabase(Path root) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE instances(id TEXT PRIMARY KEY,path TEXT UNIQUE,applied_content_set_id TEXT,install_stage TEXT,launcher_feature_version TEXT,update_channel TEXT,name TEXT,created INTEGER,modified INTEGER)");
            statement.execute("CREATE TABLE instance_content_sets(id TEXT PRIMARY KEY,instance_id TEXT REFERENCES instances(id),name TEXT,source_kind TEXT,status TEXT,game_version TEXT,loader TEXT,loader_version TEXT,created INTEGER,modified INTEGER)");
            statement.execute("CREATE TABLE instance_links(instance_id TEXT PRIMARY KEY REFERENCES instances(id),link_kind TEXT)");
            statement.execute("CREATE TABLE instance_launch_overrides(instance_id TEXT PRIMARY KEY REFERENCES instances(id),overrides JSONB)");
            statement.execute("CREATE TABLE sync_feature_settings(feature TEXT,new_instance_default INTEGER)");
            statement.execute("CREATE TABLE instance_sync_preferences(instance_id TEXT REFERENCES instances(id),feature TEXT,enabled INTEGER)");
            statement.execute("INSERT INTO instances(id,path,name) VALUES('personal','personal','My world')");
        }
    }

    @Test
    void identifiesKnownLaunchersWithoutInventingRestartSupport() {
        assertEquals(LauncherIntegration.Kind.PRISM, LauncherIntegration.identify("PrismLauncher"));
        assertEquals(LauncherIntegration.Kind.SKLAUNCHER, LauncherIntegration.identify("SKlauncher"));
        assertEquals(LauncherIntegration.Kind.SKLAUNCHER_BETA, LauncherIntegration.identify("SKlauncher 4.0 Beta"));
        assertEquals(LauncherIntegration.Kind.MODRINTH, LauncherIntegration.identify("theseus"));
        assertEquals(LauncherIntegration.Kind.MINECRAFT, LauncherIntegration.identify("minecraft-launcher"));
        assertEquals(LauncherIntegration.Kind.LUNAR, LauncherIntegration.identify("Lunar Client"));
        assertEquals(LauncherIntegration.Kind.MULTIMC, LauncherIntegration.identify("MultiMC"));
        assertEquals(LauncherIntegration.Kind.UNKNOWN, LauncherIntegration.identify("unknown"));
        assertTrue(new LauncherIntegration.Detected(LauncherIntegration.Kind.SKLAUNCHER, Path.of("/"), null).canCreateInstallation());
        assertFalse(new LauncherIntegration.Detected(LauncherIntegration.Kind.MODRINTH, null, null).canCreateInstallation());
    }

    @Test
    void createsAnIsolatedInstallationWithoutCopyingAccountsOrEditingExistingProfiles(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var artifact = JarMetadataTest.artifact(jar);
        var manifest = JsonParser.parseString(new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("sha256", artifact.sha256());
        manifest.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("size", artifact.size());
        byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        var plan = InstallationPlan.create(InstallationPlanTest.endpoint(bytes), bytes, Set.of(artifact.sha256()), null, "0.1.0-dev", "21.1.251");
        var prepared = ProfileStore.open(directory).prepare(plan, plan.accept(true, true), Map.of(artifact.sha256(), jar), "4.0.44", new DiscoveryCancellation(), (action, completed, total) -> {});
        Path installation = Files.createDirectory(directory.resolve("Minecraft with spaces"));
        Path profiles = Files.writeString(installation.resolve("launcher_profiles.json"), "{\"profiles\":{\"personal\":{\"name\":\"My world\"}},\"settings\":{\"keep\":true}}");
        Path accounts = Files.writeString(installation.resolve("launcher_accounts.json"), "fixture account data");
        String version = LauncherIntegration.versionId(prepared);
        Path versions = Files.createDirectories(installation.resolve("versions").resolve(version));
        Files.writeString(versions.resolve(version + ".json"), "{\"id\":\"" + version + "\",\"inheritsFrom\":\"1.21.1\",\"mainClass\":\"cpw.mods.bootstraplauncher.BootstrapLauncher\"}");
        String id = LauncherIntegration.createInstallation(installation, prepared, "4.0.44", new DiscoveryCancellation());
        var root = JsonParser.parseString(Files.readString(profiles)).getAsJsonObject();
        assertEquals("My world", root.getAsJsonObject("profiles").getAsJsonObject("personal").get("name").getAsString());
        assertEquals(prepared.gameDirectory().toString(), root.getAsJsonObject("profiles").getAsJsonObject(id).get("gameDir").getAsString());
        assertTrue(root.getAsJsonObject("settings").get("keep").getAsBoolean());
        assertEquals("fixture account data", Files.readString(accounts));
        byte[] saved = Files.readAllBytes(profiles);
        assertEquals(id, LauncherIntegration.createInstallation(installation, prepared, "4.0.44", new DiscoveryCancellation()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(saved, Files.readAllBytes(profiles));
        root.getAsJsonObject("profiles").getAsJsonObject(id).addProperty("gameDir", directory.toString());
        Files.writeString(profiles, root.toString());
        assertThrows(IOException.class, () -> LauncherIntegration.createInstallation(installation, prepared, "4.0.44", new DiscoveryCancellation()));
        Files.writeString(profiles, "{\"profiles\":{},\"authenticationDatabase\":{\"fixture\":\"private\"}}");
        assertThrows(IOException.class, () -> LauncherIntegration.createInstallation(installation, prepared, "4.0.44", new DiscoveryCancellation()));
        Files.writeString(profiles, new String(saved, StandardCharsets.UTF_8));
        var cancelled = new DiscoveryCancellation();
        cancelled.close();
        assertThrows(IOException.class, () -> LauncherIntegration.createInstallation(installation, prepared, "4.0.44", cancelled));
        org.junit.jupiter.api.Assertions.assertArrayEquals(saved, Files.readAllBytes(profiles));
        Files.writeString(prepared.gameDirectory().resolve("mods/extra.jar"), "unreviewed");
        assertThrows(IOException.class, () -> LauncherIntegration.createInstallation(installation, prepared, "4.0.44", new DiscoveryCancellation()));
    }
}
