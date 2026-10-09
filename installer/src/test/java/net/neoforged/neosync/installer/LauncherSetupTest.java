/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neosync.installer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherSetupTest {
    private static final String VERSION = "NeoSync-0.1.0-beta.7-neoforge-21.1.256";
    private static final String LOADER = "21.1.256-neosync-0.1.0-beta.7";
    @TempDir
    Path temp;
    Path runtime;
    Path root;

    @BeforeEach
    void runtime() throws Exception {
        runtime = Files.createDirectories(temp.resolve("installed runtime"));
        root = Files.createDirectories(temp.resolve("launcher with spaces"));
        Path library = runtime.resolve("libraries/example/runtime/1/runtime-1.jar");
        Files.createDirectories(library.getParent());
        Files.write(library, new byte[] { 1, 2, 3 });
        var artifact = new JsonObject();
        artifact.addProperty("path", "example/runtime/1/runtime-1.jar");
        artifact.addProperty("size", 3);
        artifact.addProperty("sha1", InstallerFiles.hash(library, "SHA-1"));
        var downloads = new JsonObject();
        downloads.add("artifact", artifact);
        var entry = new JsonObject();
        entry.addProperty("name", "example:runtime:1");
        entry.add("downloads", downloads);
        var libraries = new JsonArray();
        libraries.add(entry);
        var profile = new JsonObject();
        profile.addProperty("id", VERSION);
        profile.addProperty("inheritsFrom", "1.21.1");
        profile.addProperty("mainClass", "cpw.mods.bootstraplauncher.BootstrapLauncher");
        profile.add("libraries", libraries);
        var arguments = new JsonObject();
        var game = new JsonArray();
        game.add("--fml.neoForgeVersion");
        game.add(LOADER);
        var jvm = new JsonArray();
        jvm.add("-DlibraryDirectory=${library_directory}");
        jvm.add("-DignoreList=bootstraplauncher.jar");
        arguments.add("game", game);
        arguments.add("jvm", jvm);
        profile.add("arguments", arguments);
        InstallerFiles.publish(runtime.resolve("versions/" + VERSION + "/" + VERSION + ".json"), InstallerFiles.encode(profile));
        var vanilla = new JsonObject();
        vanilla.addProperty("id", "1.21.1");
        vanilla.add("libraries", new JsonArray());
        var vanillaArguments = new JsonObject();
        var vanillaGame = new JsonArray();
        vanillaGame.add("--gameDir");
        vanillaGame.add("${game_directory}");
        vanillaArguments.add("game", vanillaGame);
        vanillaArguments.add("jvm", new JsonArray());
        vanilla.add("arguments", vanillaArguments);
        InstallerFiles.publish(runtime.resolve("versions/1.21.1/1.21.1.json"), InstallerFiles.encode(vanilla));
        Files.write(runtime.resolve("versions/1.21.1/1.21.1.jar"), new byte[] { 4 });
        Path own = Files.createDirectories(runtime.resolve("libraries/net/neoforged/neoforge/" + LOADER));
        for (String side : java.util.List.of("client", "universal")) Files.write(own.resolve("neoforge-" + LOADER + "-" + side + ".jar"), new byte[] { 5 });
    }

    @Test
    void preservesOfficialProfilesAndAccountFileAndRepeatsWithoutDuplicates() throws Exception {
        Files.writeString(root.resolve("launcher_profiles.json"), "{\"profiles\":{\"personal\":{\"name\":\"Keep me\"}},\"settings\":{\"keep\":true}}");
        Files.writeString(root.resolve("launcher_accounts.json"), "private fixture");
        var target = new LauncherTarget(LauncherTarget.Kind.MINECRAFT, root);
        Path instance = LauncherSetup.configure(target, runtime, VERSION);
        byte[] before = Files.readAllBytes(root.resolve("launcher_profiles.json"));
        assertEquals(instance, LauncherSetup.configure(target, runtime, VERSION));
        assertArrayEquals(before, Files.readAllBytes(root.resolve("launcher_profiles.json")));
        var profiles = InstallerFiles.json(root.resolve("launcher_profiles.json")).getAsJsonObject("profiles");
        assertEquals(2, profiles.size());
        assertEquals("Keep me", profiles.getAsJsonObject("personal").get("name").getAsString());
        assertEquals(instance.toString(), profiles.getAsJsonObject(VERSION).get("gameDir").getAsString());
        assertEquals("private fixture", Files.readString(root.resolve("launcher_accounts.json")));
        assertEquals("1.21.1", InstallerFiles.json(root.resolve("versions/" + VERSION + "/" + VERSION + ".json")).get("inheritsFrom").getAsString());
    }

    @Test
    void detectsInstalledPrismAndMissingLaunchersWithoutCreatingPaths() throws Exception {
        Path roaming = Files.createDirectories(temp.resolve("Roaming"));
        Files.createDirectory(roaming.resolve("PrismLauncher"));
        var targets = LauncherTarget.discover(Map.of("APPDATA", roaming.toString()), temp, "Windows 11");
        assertTrue(targets.getFirst().available());
        assertFalse(targets.get(3).available());
        assertFalse(Files.exists(roaming.resolve(".sklauncher")));
    }

    @Test
    void rejectsMissingTargetBeforeWriting() {
        Path absent = temp.resolve("absent");
        assertThrows(IOException.class, () -> LauncherSetup.configure(new LauncherTarget(LauncherTarget.Kind.FOLDER, absent), runtime, VERSION));
        assertFalse(Files.exists(absent));
    }

    @Test
    void rejectsCorruptedRuntimeBeforeCreatingInstance() throws Exception {
        Files.write(runtime.resolve("libraries/example/runtime/1/runtime-1.jar"), new byte[] { 0 });
        assertThrows(IOException.class, () -> LauncherSetup.configure(new LauncherTarget(LauncherTarget.Kind.FOLDER, root), runtime, VERSION));
        assertFalse(Files.exists(root.resolve("versions")));
    }

    @Test
    void rejectsEditedMetadataWithoutOverwritingIt() throws Exception {
        var target = new LauncherTarget(LauncherTarget.Kind.SKLAUNCHER, root);
        LauncherSetup.configure(target, runtime, VERSION);
        Path metadata = root.resolve("versions/" + VERSION + "/" + VERSION + ".json");
        Files.writeString(metadata, "{\"id\":\"edited\"}");
        assertThrows(IOException.class, () -> LauncherSetup.configure(target, runtime, VERSION));
        assertEquals("{\"id\":\"edited\"}", Files.readString(metadata));
    }

    @Test
    void preservesBetaInventoryAndEnablesCompatibilityMode() throws Exception {
        Files.writeString(root.resolve("instances.json"), "{\"instances\":[{\"id\":\"personal\",\"name\":\"Keep me\"}],\"keep\":true}");
        var target = new LauncherTarget(LauncherTarget.Kind.SKLAUNCHER_BETA, root);
        LauncherSetup.configure(target, runtime, VERSION);
        LauncherSetup.configure(target, runtime, VERSION);
        var instances = InstallerFiles.json(root.resolve("instances.json")).getAsJsonArray("instances");
        assertEquals(2, instances.size());
        assertEquals("personal", instances.get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(instances.get(1).getAsJsonObject().get("compatibilityMode").getAsBoolean());
        var metadata = InstallerFiles.json(root.resolve("versions/" + VERSION + "/" + VERSION + ".json"));
        assertFalse(metadata.has("inheritsFrom"));
        assertTrue(metadata.getAsJsonObject("arguments").getAsJsonArray("jvm").toString().contains("1.21.1.jar"));
    }

    @Test
    void refusesLegacyAccountBearingInventory() throws Exception {
        Files.writeString(root.resolve("launcher_profiles.json"), "{\"authenticationDatabase\":{\"private\":true}}");
        assertThrows(IOException.class, () -> LauncherSetup.configure(new LauncherTarget(LauncherTarget.Kind.MINECRAFT, root), runtime, VERSION));
        assertFalse(Files.exists(root.resolve("versions")));
    }

    private void modrinthDatabase(boolean failInsert) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement()) {
            String schema = new String(getClass().getResourceAsStream("/modrinth-schema.sql").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
            statement.execute("CREATE TABLE private_account_fixture (token TEXT)");
            statement.execute("INSERT INTO private_account_fixture VALUES ('preserved')");
            statement.execute("INSERT INTO sync_feature_settings VALUES ('test',1,1)");
            if (failInsert) statement.execute("CREATE TRIGGER fail_registration BEFORE INSERT ON instance_links BEGIN SELECT RAISE(ABORT,'test failure'); END");
        }
    }

    @Test
    void registersModrinthWithSpacePreservingOverridesAndDisabledSync() throws Exception {
        modrinthDatabase(false);
        var target = new LauncherTarget(LauncherTarget.Kind.MODRINTH, root);
        LauncherSetup.configure(target, runtime, VERSION);
        LauncherSetup.configure(target, runtime, VERSION);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT count(*) FROM instances")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT overrides FROM instance_launch_overrides")) {
                assertTrue(rows.next());
                assertTrue(rows.getString(1).contains("launcher with spaces"));
            }
            try (var rows = statement.executeQuery("SELECT enabled FROM instance_sync_preferences")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery("SELECT token FROM private_account_fixture")) {
                assertTrue(rows.next());
                assertEquals("preserved", rows.getString(1));
            }
        }
    }

    @Test
    void rollsBackDatabaseAndInstanceMetadataWhenRegistrationFails() throws Exception {
        modrinthDatabase(true);
        assertThrows(Exception.class, () -> LauncherSetup.configure(new LauncherTarget(LauncherTarget.Kind.MODRINTH, root), runtime, VERSION));
        assertFalse(Files.exists(root.resolve("meta/versions/1.21.1-" + VERSION)));
        assertFalse(Files.exists(root.resolve("profiles/neosync-" + VERSION)));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM instances")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }
}
