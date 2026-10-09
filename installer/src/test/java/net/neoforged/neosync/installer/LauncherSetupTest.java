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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherSetupTest {
    private static final String VERSION = "NeoSync-0.1.0-beta.8-neoforge-21.1.256";
    private static final String LOADER = "21.1.256-neosync-0.1.0-beta.8";
    @TempDir
    Path temp;
    Path runtime;
    Path root;
    Path asset;
    Path officialLibrary;
    Path nativeLibrary;

    @BeforeEach
    void runtime() throws Exception {
        root = Files.createDirectories(temp.resolve("launcher with spaces"));
        runtime = Files.createDirectories(root.resolve("neosync/runtime").resolve(VERSION));
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
        var officialLibraries = new JsonArray();
        officialLibrary = writeRuntime("libraries/example/vanilla/1/vanilla-1.jar", new byte[] { 6, 7 });
        officialLibraries.add(library("example:vanilla:1", "example/vanilla/1/vanilla-1.jar", officialLibrary));
        nativeLibrary = writeRuntime("libraries/example/native/1/native-1.jar", new byte[] { 8, 9 });
        JsonObject nativeEntry = library("example:native:1", "example/native/1/native-1.jar", nativeLibrary);
        JsonObject nativeRule = new JsonObject();
        nativeRule.addProperty("action", "allow");
        JsonObject os = new JsonObject();
        os.addProperty("name", ModrinthRuntimeSetup.platform());
        nativeRule.add("os", os);
        JsonArray rules = new JsonArray();
        rules.add(nativeRule);
        nativeEntry.add("rules", rules);
        officialLibraries.add(nativeEntry);
        JsonObject excluded = library("example:excluded:1", "example/excluded/1/excluded-1.jar", officialLibrary);
        JsonObject excludedOs = new JsonObject();
        excludedOs.addProperty("name", "unsupported");
        JsonObject excludedRule = new JsonObject();
        excludedRule.addProperty("action", "allow");
        excludedRule.add("os", excludedOs);
        JsonArray excludedRules = new JsonArray();
        excludedRules.add(excludedRule);
        excluded.add("rules", excludedRules);
        officialLibraries.add(excluded);
        vanilla.add("libraries", officialLibraries);
        var vanillaArguments = new JsonObject();
        var vanillaGame = new JsonArray();
        vanillaGame.add("--gameDir");
        vanillaGame.add("${game_directory}");
        vanillaArguments.add("game", vanillaGame);
        vanillaArguments.add("jvm", new JsonArray());
        vanilla.add("arguments", vanillaArguments);
        Path client = runtime.resolve("versions/1.21.1/1.21.1.jar");
        Files.createDirectories(client.getParent());
        try (var jar = new JarOutputStream(Files.newOutputStream(client))) {
            jar.putNextEntry(new JarEntry("version.json"));
            jar.write("{\"id\":\"1.21.1\",\"protocol_version\":767}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        JsonObject downloadsClient = new JsonObject();
        downloadsClient.add("client", artifact(client, "https://piston-data.mojang.com/client.jar"));
        vanilla.add("downloads", downloadsClient);
        Path object = writeRuntime("asset-content", new byte[] { 10, 11, 12 });
        String objectHash = InstallerFiles.hash(object, "SHA-1");
        asset = runtime.resolve("assets/objects/" + objectHash.substring(0, 2) + "/" + objectHash);
        Files.createDirectories(asset.getParent());
        Files.move(object, asset);
        JsonObject objects = new JsonObject();
        JsonObject objectIdentity = new JsonObject();
        objectIdentity.addProperty("hash", objectHash);
        objectIdentity.addProperty("size", Files.size(asset));
        objects.add("test/asset", objectIdentity);
        JsonObject index = new JsonObject();
        index.add("objects", objects);
        index.addProperty("virtual", false);
        Path indexFile = writeRuntime("assets/indexes/17.json", InstallerFiles.encode(index));
        JsonObject indexIdentity = artifact(indexFile, "https://piston-meta.mojang.com/index.json");
        indexIdentity.addProperty("id", "17");
        vanilla.add("assetIndex", indexIdentity);
        Path log = writeRuntime("assets/log_configs/client.xml", "<Configuration/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonObject logIdentity = artifact(log, "https://piston-data.mojang.com/client.xml");
        logIdentity.addProperty("id", "client.xml");
        JsonObject clientLogging = new JsonObject();
        clientLogging.add("file", logIdentity);
        JsonObject logging = new JsonObject();
        logging.add("client", clientLogging);
        vanilla.add("logging", logging);
        InstallerFiles.publish(runtime.resolve("versions/1.21.1/1.21.1.json"), InstallerFiles.encode(vanilla));
        Path own = Files.createDirectories(runtime.resolve("libraries/net/neoforged/neoforge/" + LOADER));
        for (String side : java.util.List.of("client", "universal")) Files.write(own.resolve("neoforge-" + LOADER + "-" + side + ".jar"), new byte[] { 5 });
    }

    private Path writeRuntime(String path, byte[] content) throws Exception {
        Path file = runtime.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        return file;
    }

    private static JsonObject artifact(Path file, String url) throws Exception {
        JsonObject artifact = new JsonObject();
        artifact.addProperty("sha1", InstallerFiles.hash(file, "SHA-1"));
        artifact.addProperty("size", Files.size(file));
        artifact.addProperty("url", url);
        return artifact;
    }

    private static JsonObject library(String name, String path, Path file) throws Exception {
        JsonObject artifact = artifact(file, "https://libraries.minecraft.net/" + path);
        artifact.addProperty("path", path);
        JsonObject downloads = new JsonObject();
        downloads.add("artifact", artifact);
        JsonObject library = new JsonObject();
        library.addProperty("name", name);
        library.add("downloads", downloads);
        return library;
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
    void registersCompleteModrinthRuntimeAndPreservesExistingPreferences() throws Exception {
        modrinthDatabase(false);
        var target = new LauncherTarget(LauncherTarget.Kind.MODRINTH, root);
        LauncherSetup.configure(target, runtime, VERSION);
        String versionId = "1.21.1-" + VERSION;
        Path meta = root.resolve("meta");
        assertArrayEquals(Files.readAllBytes(runtime.resolve("versions/1.21.1/1.21.1.jar")), Files.readAllBytes(meta.resolve("versions/" + versionId + "/" + versionId + ".jar")));
        assertTrue(Files.isDirectory(meta.resolve("natives/" + versionId)));
        assertArrayEquals(Files.readAllBytes(asset), Files.readAllBytes(meta.resolve(runtime.relativize(asset))));
        assertTrue(Files.exists(meta.resolve("log_configs/client.xml")));
        assertFalse(Files.exists(meta.resolve("libraries/example/excluded/1/excluded-1.jar")));
        JsonObject descriptor = InstallerFiles.json(meta.resolve("versions/" + versionId + "/neosync-runtime.json"));
        assertEquals(767, descriptor.get("protocolVersion").getAsInt());
        assertEquals(1, descriptor.get("schemaVersion").getAsInt());
        assertEquals(InstallerFiles.hash(meta.resolve("versions/" + versionId + "/" + versionId + ".json"), "SHA-256"), descriptor.get("metadataSha256").getAsString());
        JsonArray mergedLibraries = InstallerFiles.json(meta.resolve("versions/" + versionId + "/" + versionId + ".json")).getAsJsonArray("libraries");
        assertFalse(mergedLibraries.get(mergedLibraries.size() - 1).getAsJsonObject().get("downloadable").getAsBoolean());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement()) {
            statement.execute("UPDATE instance_sync_preferences SET enabled=1");
            statement.execute("UPDATE instances SET name='My name',install_stage='not_installed'");
            statement.execute("UPDATE instance_content_sets SET protocol_version=NULL");
        }
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
                assertEquals(1, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT i.name,i.install_stage,c.protocol_version FROM instances i JOIN instance_content_sets c ON c.id=i.applied_content_set_id")) {
                assertTrue(rows.next());
                assertEquals("My name", rows.getString(1));
                assertEquals("installed", rows.getString(2));
                assertEquals(767, rows.getInt(3));
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
    void downloadsMissingOfficialResourcesIntoVerifiedStaging() throws Exception {
        modrinthDatabase(false);
        byte[] library = Files.readAllBytes(officialLibrary);
        Files.delete(officialLibrary);
        byte[] assetBytes = Files.readAllBytes(asset);
        Files.delete(asset);
        var downloads = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        LauncherSetup.configure(new LauncherTarget(LauncherTarget.Kind.MODRINTH, root), runtime, VERSION, (uri, file, size) -> {
            downloads.add(uri.getHost());
            Files.write(file, uri.getHost().equals("libraries.minecraft.net") ? library : assetBytes);
        });
        assertEquals(2, downloads.size());
        assertArrayEquals(library, Files.readAllBytes(root.resolve("meta/libraries/example/vanilla/1/vanilla-1.jar")));
        assertArrayEquals(assetBytes, Files.readAllBytes(root.resolve("meta").resolve(runtime.relativize(asset))));
    }

    @Test
    void failedDownloadNeverRegistersOrPublishesAnIncompleteRuntime() throws Exception {
        modrinthDatabase(false);
        Files.delete(asset);
        assertThrows(IOException.class, () -> LauncherSetup.configure(new LauncherTarget(LauncherTarget.Kind.MODRINTH, root), runtime, VERSION, (uri, file, size) -> Files.write(file, new byte[] { 0 })));
        assertFalse(Files.exists(root.resolve("meta")));
        assertFalse(Files.exists(root.resolve("profiles")));
        try (var paths = Files.list(root)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".neosync-runtime-")));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM instances")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void rejectsEditedReadyResourcesWhilePreservingInstancePreferences() throws Exception {
        modrinthDatabase(false);
        var target = new LauncherTarget(LauncherTarget.Kind.MODRINTH, root);
        LauncherSetup.configure(target, runtime, VERSION);
        Path edited = root.resolve("meta/libraries/example/runtime/1/runtime-1.jar");
        Files.write(edited, new byte[] { 0 });
        assertThrows(IOException.class, () -> LauncherSetup.configure(target, runtime, VERSION));
        assertArrayEquals(new byte[] { 0 }, Files.readAllBytes(edited));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT install_stage FROM instances")) {
            assertTrue(rows.next());
            assertEquals("installed", rows.getString(1));
        }
    }

    @Test
    void repairsCorruptOfficialResourcesFromVerifiedCopiesAndDownloads() throws Exception {
        modrinthDatabase(false);
        var target = new LauncherTarget(LauncherTarget.Kind.MODRINTH, root);
        LauncherSetup.configure(target, runtime, VERSION);
        Path sharedLibrary = root.resolve("meta/libraries/example/vanilla/1/vanilla-1.jar");
        Path sharedAsset = root.resolve("meta").resolve(runtime.relativize(asset));
        Path sharedClient = root.resolve("meta/versions/1.21.1-" + VERSION + "/1.21.1-" + VERSION + ".jar");
        byte[] libraryBytes = Files.readAllBytes(officialLibrary);
        byte[] assetBytes = Files.readAllBytes(asset);
        byte[] clientBytes = Files.readAllBytes(sharedClient);
        Files.write(sharedLibrary, new byte[] { 0 });
        Files.write(sharedAsset, new byte[] { 0 });
        Files.write(sharedClient, new byte[] { 0 });
        Files.delete(officialLibrary);
        Files.delete(asset);
        var requests = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        LauncherSetup.configure(target, runtime, VERSION, (uri, file, size) -> {
            requests.add(uri.getHost());
            Files.write(file, uri.getHost().equals("libraries.minecraft.net") ? libraryBytes : assetBytes);
        });
        assertEquals(2, requests.size());
        assertArrayEquals(libraryBytes, Files.readAllBytes(sharedLibrary));
        assertArrayEquals(assetBytes, Files.readAllBytes(sharedAsset));
        assertArrayEquals(clientBytes, Files.readAllBytes(sharedClient));
        try (var paths = Files.list(root)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".neosync-runtime-")));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db"));
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*),install_stage FROM instances")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals("installed", rows.getString(2));
        }
    }

    @Test
    void rollsBackOfficialRepairsWhenRegistrationFails() throws Exception {
        modrinthDatabase(false);
        var target = new LauncherTarget(LauncherTarget.Kind.MODRINTH, root);
        LauncherSetup.configure(target, runtime, VERSION);
        Path sharedLibrary = root.resolve("meta/libraries/example/vanilla/1/vanilla-1.jar");
        Path sharedAsset = root.resolve("meta").resolve(runtime.relativize(asset));
        Path version = root.resolve("meta/versions/1.21.1-" + VERSION + "/1.21.1-" + VERSION + ".json");
        byte[] versionBytes = Files.readAllBytes(version);
        Files.write(sharedLibrary, new byte[] { 0 });
        Files.write(sharedAsset, new byte[] { 1 });
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement()) {
            statement.execute("UPDATE instances SET name='Keep my name',install_stage='not_installed'");
            statement.execute("UPDATE instance_sync_preferences SET enabled=1");
            statement.execute("CREATE TRIGGER fail_repair_registration BEFORE UPDATE ON instances BEGIN SELECT RAISE(ABORT,'repair fixture failure'); END");
        }
        assertThrows(Exception.class, () -> LauncherSetup.configure(target, runtime, VERSION));
        assertArrayEquals(new byte[] { 0 }, Files.readAllBytes(sharedLibrary));
        assertArrayEquals(new byte[] { 1 }, Files.readAllBytes(sharedAsset));
        assertArrayEquals(versionBytes, Files.readAllBytes(version));
        try (var paths = Files.list(root)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".neosync-runtime-")));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT name,install_stage FROM instances")) {
                assertTrue(rows.next());
                assertEquals("Keep my name", rows.getString(1));
                assertEquals("not_installed", rows.getString(2));
            }
            try (var rows = statement.executeQuery("SELECT enabled FROM instance_sync_preferences")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void rollbackPreservesAnUnrelatedReplacementOfPublishedMetadata() throws Exception {
        var setup = prepareModrinthRuntime();
        setup.publish();
        Path descriptor = root.resolve("meta/versions/1.21.1-" + VERSION + "/neosync-runtime.json");
        Files.delete(descriptor);
        Files.write(descriptor, new byte[] { 42 });
        IOException failure = assertThrows(IOException.class, setup::close);
        assertTrue(failure.getMessage().contains("preserved for recovery"));
        assertArrayEquals(new byte[] { 42 }, Files.readAllBytes(descriptor));
        try (var paths = Files.list(root)) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().startsWith(".neosync-runtime-")));
        }
    }

    @Test
    void rollbackPreservesAConcurrentEditAndTheOriginalRepairBackup() throws Exception {
        try (var initial = prepareModrinthRuntime()) {
            initial.publish();
            initial.commit();
        }
        Path sharedLibrary = root.resolve("meta/libraries/example/vanilla/1/vanilla-1.jar");
        Files.write(sharedLibrary, new byte[] { 0 });
        var repair = prepareModrinthRuntime();
        repair.publish();
        Files.write(sharedLibrary, new byte[] { 42 });
        IOException failure = assertThrows(IOException.class, repair::close);
        assertTrue(failure.getMessage().contains("preserved for recovery"));
        assertArrayEquals(new byte[] { 42 }, Files.readAllBytes(sharedLibrary));
        Path recovery;
        try (var paths = Files.list(root)) {
            recovery = paths.filter(path -> path.getFileName().toString().startsWith(".neosync-runtime-")).findFirst().orElseThrow();
        }
        assertArrayEquals(new byte[] { 0 }, Files.readAllBytes(recovery.resolve("backups/libraries/example/vanilla/1/vanilla-1.jar")));
    }

    private ModrinthRuntimeSetup prepareModrinthRuntime() throws Exception {
        JsonObject vanilla = InstallerFiles.json(runtime.resolve("versions/1.21.1/1.21.1.json"));
        JsonObject merged = vanilla.deepCopy();
        String versionId = "1.21.1-" + VERSION;
        merged.addProperty("id", versionId);
        return ModrinthRuntimeSetup.prepare(root, runtime, versionId, merged, vanilla, Map.of(), (uri, target, size) -> {
            throw new IOException("The fixture must use local verified resources.");
        }, ignored -> {});
    }

    @Test
    void rejectsEditedModrinthContentIdentityWithoutUpdatingIt() throws Exception {
        modrinthDatabase(false);
        var target = new LauncherTarget(LauncherTarget.Kind.MODRINTH, root);
        LauncherSetup.configure(target, runtime, VERSION);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement()) {
            statement.execute("UPDATE instance_content_sets SET game_version='1.20.1'");
            statement.execute("UPDATE instances SET install_stage='not_installed'");
        }
        assertThrows(IOException.class, () -> LauncherSetup.configure(target, runtime, VERSION));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db")); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT i.install_stage,c.game_version FROM instances i JOIN instance_content_sets c ON c.id=i.applied_content_set_id")) {
            assertTrue(rows.next());
            assertEquals("not_installed", rows.getString(1));
            assertEquals("1.20.1", rows.getString(2));
        }
    }

    @Test
    void rejectsUnapprovedOfficialDownloadHostBeforeDownloaderRuns() throws Exception {
        modrinthDatabase(false);
        Files.delete(officialLibrary);
        Path metadata = runtime.resolve("versions/1.21.1/1.21.1.json");
        JsonObject vanilla = InstallerFiles.json(metadata);
        vanilla.getAsJsonArray("libraries").get(0).getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact").addProperty("url", "https://localhost/private.jar");
        Files.write(metadata, InstallerFiles.encode(vanilla));
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IOException.class, () -> LauncherSetup.configure(new LauncherTarget(LauncherTarget.Kind.MODRINTH, root), runtime, VERSION, (uri, file, size) -> requests.incrementAndGet()));
        assertEquals(0, requests.get());
        assertFalse(Files.exists(root.resolve("meta")));
    }

    @Test
    void interruptedOfficialDownloadRemovesPartialStaging() throws Exception {
        modrinthDatabase(false);
        Files.delete(asset);
        assertThrows(IOException.class, () -> LauncherSetup.configure(new LauncherTarget(LauncherTarget.Kind.MODRINTH, root), runtime, VERSION, (uri, file, size) -> {
            Files.write(file, new byte[] { 10 });
            throw new IOException("Interrupted fixture download");
        }));
        assertFalse(Files.exists(root.resolve("meta")));
        try (var paths = Files.list(root)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".neosync-runtime-")));
        }
    }

    @Test
    void rejectsOversizedOfficialArtifactBeforeDownloaderRuns() throws Exception {
        modrinthDatabase(false);
        Files.delete(officialLibrary);
        Path metadata = runtime.resolve("versions/1.21.1/1.21.1.json");
        JsonObject vanilla = InstallerFiles.json(metadata);
        vanilla.getAsJsonArray("libraries").get(0).getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact").addProperty("size", InstallerFiles.LIBRARY_LIMIT + 1);
        Files.write(metadata, InstallerFiles.encode(vanilla));
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IOException.class, () -> LauncherSetup.configure(new LauncherTarget(LauncherTarget.Kind.MODRINTH, root), runtime, VERSION, (uri, file, size) -> requests.incrementAndGet()));
        assertEquals(0, requests.get());
        assertFalse(Files.exists(root.resolve("meta")));
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
