/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neosync.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

final class LauncherSetup {
    private LauncherSetup() {}

    static void preflight(LauncherTarget target) throws Exception {
        InstallerFiles.directory(target.root(), false);
        if (target.kind() == LauncherTarget.Kind.SKLAUNCHER_BETA) {
            if (!InstallerFiles.json(target.root().resolve("instances.json")).has("instances"))
                throw new IOException("Open SKlauncher once before installing NeoSync.");
        } else if (target.kind() == LauncherTarget.Kind.MODRINTH) {
            Path database = target.root().resolve("app.db");
            InstallerFiles.directory(database.getParent(), false);
            if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Open Modrinth App once before installing NeoSync.");
            Class.forName("org.sqlite.JDBC");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro"); var statement = connection.createStatement()) {
                for (String table : java.util.List.of("instances", "instance_content_sets", "instance_links", "instance_launch_overrides", "instance_sync_preferences", "sync_feature_settings")) {
                    try (var rows = statement.executeQuery("SELECT 1 FROM " + table + " LIMIT 0")) {}
                }
            }
        } else if (target.kind() == LauncherTarget.Kind.PRISM) {
            findPrism(target.root());
        } else if (Files.exists(target.root().resolve("launcher_profiles.json"), LinkOption.NOFOLLOW_LINKS)) {
            JsonObject document = InstallerFiles.json(target.root().resolve("launcher_profiles.json"));
            if (document.has("authenticationDatabase") || document.has("selectedUser"))
                throw new IOException("Legacy account-bearing profiles cannot be edited. Update your launcher.");
        }
    }

    static Path configure(LauncherTarget target, Path installation, String version) throws Exception {
        preflight(target);
        JsonObject profile = InstallerFiles.json(installation.resolve("versions").resolve(version).resolve(version + ".json"));
        if (!version.equals(profile.get("id").getAsString()) || !"1.21.1".equals(profile.get("inheritsFrom").getAsString())
                || !"cpw.mods.bootstraplauncher.BootstrapLauncher".equals(profile.get("mainClass").getAsString()))
            throw new IOException("The installed runtime is not NeoSync for Minecraft 1.21.1.");
        JsonArray game = profile.getAsJsonObject("arguments").getAsJsonArray("game");
        String loader = argument(game, "--fml.neoForgeVersion");
        String neoSync = version.substring("NeoSync-".length(), version.indexOf("-neoforge-"));
        String base = version.substring(version.indexOf("-neoforge-") + "-neoforge-".length());
        if (!loader.equals(base + "-neosync-" + neoSync)) throw new IOException("Ordinary NeoForge cannot be used as the NeoSync runtime.");
        Map<Path, Path> libraries = verifiedLibraries(installation, profile, loader);
        if (target.kind() == LauncherTarget.Kind.PRISM) return prism(target, installation, profile, libraries, version, neoSync, base);
        boolean modrinth = target.kind() == LauncherTarget.Kind.MODRINTH;
        boolean beta = target.kind() == LauncherTarget.Kind.SKLAUNCHER_BETA;
        Path metadata = target.root().resolve(modrinth ? "meta" : "");
        JsonObject vanilla = InstallerFiles.json(installation.resolve("versions/1.21.1/1.21.1.json"));
        JsonArray jvm = profile.getAsJsonObject("arguments").getAsJsonArray("jvm");
        JsonArray nextJvm = new JsonArray();
        String libraryArgument = "-DlibraryDirectory=" + installation.resolve("libraries");
        for (var value : jvm) {
            String text = value.getAsString();
            if (text.startsWith("-DlibraryDirectory=")) {
                if (!modrinth) nextJvm.add(libraryArgument);
            } else if (beta && text.startsWith("-DignoreList=")) {
                nextJvm.add(text + ",1.21.1.jar");
            } else nextJvm.add(value.deepCopy());
        }
        JsonObject overrides = new JsonObject();
        JsonArray extra = new JsonArray();
        if (modrinth) extra.add(libraryArgument);
        extra.add("-Dneosync.launcher.root=" + target.root());
        extra.add("-Dneosync.launcher.kind=" + switch (target.kind()) {
            case SKLAUNCHER_BETA -> "sklauncher-beta";
            case MODRINTH -> "modrinth";
            case SKLAUNCHER -> "sklauncher";
            default -> "minecraft-launcher";
        });
        if (modrinth) {
            overrides.add("extra_launch_args", extra);
            profile.add("neosyncLaunchOverrides", overrides);
            nextJvm.add("-Dminecraft.launcher.brand=theseus");
        } else for (var value : extra) nextJvm.add(value);
        profile.getAsJsonObject("arguments").add("jvm", nextJvm);
        JsonObject merged = vanilla.deepCopy();
        for (var entry : profile.entrySet()) merged.add(entry.getKey(), entry.getValue().deepCopy());
        merged.remove("inheritsFrom");
        JsonObject arguments = new JsonObject();
        for (String kind : java.util.List.of("game", "jvm")) {
            JsonArray values = vanilla.getAsJsonObject("arguments").getAsJsonArray(kind).deepCopy();
            values.addAll(profile.getAsJsonObject("arguments").getAsJsonArray(kind));
            arguments.add(kind, values);
        }
        merged.add("arguments", arguments);
        JsonArray allLibraries = vanilla.getAsJsonArray("libraries").deepCopy();
        allLibraries.addAll(profile.getAsJsonArray("libraries"));
        merged.add("libraries", allLibraries);
        String targetVersion = (modrinth ? "1.21.1-" : "") + version;
        merged.addProperty("id", targetVersion);
        if (!modrinth) merged.addProperty("jar", "1.21.1");
        if (!beta && !modrinth) merged = profile;
        Path record = metadata.resolve("versions").resolve(targetVersion).resolve(targetVersion + ".json");
        checkRecord(record, merged);
        for (var entry : libraries.entrySet()) InstallerFiles.copy(entry.getValue(), metadata.resolve("libraries").resolve(entry.getKey()));
        if (!modrinth) {
            Path vanillaRecord = metadata.resolve("versions/1.21.1/1.21.1.json");
            writeRecord(vanillaRecord, vanilla);
            InstallerFiles.copy(installation.resolve("versions/1.21.1/1.21.1.jar"), metadata.resolve("versions/1.21.1/1.21.1.jar"));
        }
        String instanceId = beta || modrinth ? "neosync-" + version : version;
        Path instance = target.root().resolve(modrinth ? "profiles" : beta ? "instances" : "neosync/instances").resolve(instanceId);
        if (Files.exists(instance, LinkOption.NOFOLLOW_LINKS) && !Files.exists(record, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The target instance folder already exists. Choose another launcher directory.");
        boolean createdRecord = !Files.exists(record, LinkOption.NOFOLLOW_LINKS);
        boolean createdInstance = !Files.exists(instance, LinkOption.NOFOLLOW_LINKS);
        try {
            writeRecord(record, merged);
            InstallerFiles.directory(instance, true);
            if (modrinth) registerModrinth(target.root(), instanceId, version, overrides);
            else if (beta) registerBeta(target.root(), instanceId, version);
            else registerOfficial(target.root(), version, instance);
        } catch (Exception failure) {
            if (createdRecord) {
                Files.deleteIfExists(record);
                Files.deleteIfExists(record.getParent());
            }
            if (createdInstance && Files.isDirectory(instance, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(instance);
            throw failure;
        }
        return instance;
    }

    private static Map<Path, Path> verifiedLibraries(Path installation, JsonObject profile, String loader) throws IOException {
        JsonArray libraries = profile.getAsJsonArray("libraries");
        if (libraries.size() > 256) throw new IOException("The installed library count exceeds the limit.");
        Map<Path, Path> files = new LinkedHashMap<>();
        long total = 0;
        for (var entry : libraries) {
            JsonObject artifact = entry.getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact");
            Path relative = InstallerFiles.relativeJar(artifact.get("path").getAsString());
            Path source = installation.resolve("libraries").resolve(relative);
            if (!InstallerFiles.hash(source, "SHA-1").equals(artifact.get("sha1").getAsString()) || Files.size(source) != artifact.get("size").getAsLong())
                throw new IOException("Installed library failed verification: " + source.getFileName());
            total += Files.size(source);
            files.put(relative, source);
        }
        for (String suffix : java.util.List.of("client", "universal")) {
            Path relative = Path.of("net/neoforged/neoforge", loader, "neoforge-" + loader + "-" + suffix + ".jar");
            Path source = installation.resolve("libraries").resolve(relative);
            InstallerFiles.hash(source, "SHA-256");
            if (!files.containsKey(relative)) total += Files.size(source);
            files.put(relative, source);
        }
        if (total > InstallerFiles.LIBRARY_LIMIT) throw new IOException("The runtime exceeds its copy limit.");
        return files;
    }

    private static String argument(JsonArray values, String key) throws IOException {
        for (int i = 0; i + 1 < values.size(); i++) if (key.equals(values.get(i).getAsString())) return values.get(i + 1).getAsString();
        throw new IOException("The installed runtime is missing " + key + ".");
    }

    private static void checkRecord(Path path, JsonObject expected) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !InstallerFiles.json(path).equals(expected))
            throw new IOException("The existing NeoSync runtime was edited. It was not overwritten.");
    }

    private static void writeRecord(Path path, JsonObject expected) throws IOException {
        checkRecord(path, expected);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) InstallerFiles.publish(path, InstallerFiles.encode(expected));
    }

    private static void registerOfficial(Path root, String version, Path game) throws IOException {
        Path path = root.resolve("launcher_profiles.json");
        JsonObject document;
        byte[] original = null;
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            original = InstallerFiles.read(path, InstallerFiles.JSON_LIMIT);
            document = InstallerFiles.json(path);
        } else {
            document = new JsonObject();
            document.add("profiles", new JsonObject());
        }
        if (document.has("authenticationDatabase") || document.has("selectedUser")) throw new IOException("Legacy account-bearing profiles cannot be edited. Update your launcher.");
        JsonObject profiles = document.getAsJsonObject("profiles");
        if (profiles == null) {
            profiles = new JsonObject();
            document.add("profiles", profiles);
        }
        if (profiles.has(version)) {
            var entry = profiles.getAsJsonObject(version);
            if (!version.equals(entry.get("lastVersionId").getAsString()) || !game.toString().equals(entry.get("gameDir").getAsString()))
                throw new IOException("The existing installation was edited. It was not overwritten.");
            return;
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("name", version);
        entry.addProperty("type", "custom");
        entry.addProperty("lastVersionId", version);
        entry.addProperty("gameDir", game.toString());
        entry.addProperty("created", Instant.now().toString());
        profiles.add(version, entry);
        if (original == null) InstallerFiles.publish(path, InstallerFiles.encode(document));
        else InstallerFiles.update(path, original, document);
    }

    private static void registerBeta(Path root, String id, String version) throws IOException {
        Path path = root.resolve("instances.json");
        byte[] original = InstallerFiles.read(path, InstallerFiles.JSON_LIMIT);
        JsonObject document = InstallerFiles.json(path);
        JsonArray instances = document.getAsJsonArray("instances");
        if (instances == null) throw new IOException("Initialize SKlauncher once before installing NeoSync.");
        for (var value : instances) {
            JsonObject entry = value.getAsJsonObject();
            if (id.equals(entry.get("id").getAsString())) {
                if (!version.equals(entry.get("versionId").getAsString()) || !entry.get("compatibilityMode").getAsBoolean())
                    throw new IOException("The existing SKlauncher instance was edited.");
                return;
            }
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.addProperty("name", version);
        entry.addProperty("type", "custom");
        entry.addProperty("versionId", version);
        entry.addProperty("gameType", "custom");
        entry.addProperty("minecraftVersion", version);
        entry.addProperty("directory", root.resolve("instances").resolve(id).toString());
        entry.addProperty("compatibilityMode", true);
        entry.addProperty("createdAt", Instant.now().toString());
        entry.addProperty("playTime", 0);
        entry.addProperty("sessionCount", 0);
        instances.add(entry);
        InstallerFiles.update(path, original, document);
    }

    private static void registerModrinth(Path root, String id, String version, JsonObject overrides) throws Exception {
        Path database = root.resolve("app.db");
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Open Modrinth App once before installing NeoSync.");
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=rw")) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=3000");
            }
            connection.setAutoCommit(false);
            try {
                String instance = "local:" + id;
                try (var query = connection.prepareStatement("SELECT i.path,c.loader_version,json(o.overrides) FROM instances i LEFT JOIN instance_content_sets c ON c.id=i.applied_content_set_id LEFT JOIN instance_launch_overrides o ON o.instance_id=i.id WHERE i.id=?")) {
                    query.setString(1, instance);
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) {
                            if (!id.equals(rows.getString(1)) || !version.equals(rows.getString(2)) || rows.getString(3) == null
                                    || !com.google.gson.JsonParser.parseString(rows.getString(3)).getAsJsonObject().get("extra_launch_args").equals(overrides.get("extra_launch_args")))
                                throw new IOException("The existing Modrinth instance was edited.");
                            connection.rollback();
                            return;
                        }
                    }
                }
                String content = "content-set:" + id;
                long now = Instant.now().getEpochSecond();
                try (var insert = connection.prepareStatement("INSERT INTO instances(id,path,applied_content_set_id,install_stage,launcher_feature_version,update_channel,name,created,modified) VALUES(?,?,?,'not_installed','migrated_launch_hooks','release',?,?,?)")) {
                    insert.setString(1, instance);
                    insert.setString(2, id);
                    insert.setString(3, content);
                    insert.setString(4, version);
                    insert.setLong(5, now);
                    insert.setLong(6, now);
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO instance_content_sets(id,instance_id,name,source_kind,status,game_version,loader,loader_version,created,modified) VALUES(?,?,'Default','local','available','1.21.1','neoforge',?,?,?)")) {
                    insert.setString(1, content);
                    insert.setString(2, instance);
                    insert.setString(3, version);
                    insert.setLong(4, now);
                    insert.setLong(5, now);
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO instance_links(instance_id,link_kind) VALUES(?,'unmanaged')")) {
                    insert.setString(1, instance);
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO instance_launch_overrides(instance_id,overrides) VALUES(?,json(?))")) {
                    insert.setString(1, instance);
                    insert.setString(2, overrides.toString());
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO instance_sync_preferences(instance_id,feature,enabled) SELECT ?,feature,0 FROM sync_feature_settings")) {
                    insert.setString(1, instance);
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static Path prism(LauncherTarget target, Path installation, JsonObject profile, Map<Path, Path> libraries, String version, String neoSync, String base) throws Exception {
        Path instances = InstallerFiles.directory(target.root().resolve("instances"), true);
        Path instance = instances.resolve(version);
        if (Files.exists(instance, LinkOption.NOFOLLOW_LINKS)) {
            JsonObject descriptor = InstallerFiles.json(instance.resolve("neosync-launcher.json"));
            if (!neoSync.equals(descriptor.get("version").getAsString()) || !base.equals(descriptor.get("neoForgeVersion").getAsString()))
                throw new IOException("The existing Prism instance was edited.");
            if (!descriptor.getAsJsonObject("component").equals(InstallerFiles.json(instance.resolve("patches/org.neosync.json")))
                    || !descriptor.get("bridgeSha256").getAsString().equals(InstallerFiles.hash(instance.resolve("neosync-launcher-bridge.jar"), "SHA-256")))
                throw new IOException("The existing Prism runtime was edited.");
            for (var entry : descriptor.getAsJsonObject("libraries").entrySet()) {
                if (!entry.getKey().matches("[A-Za-z0-9._+-]+\\.jar")
                        || !entry.getValue().getAsString().equals(InstallerFiles.hash(instance.resolve("libraries").resolve(entry.getKey()), "SHA-256")))
                    throw new IOException("The existing Prism libraries were edited.");
            }
            return instance;
        }
        Path stage = InstallerFiles.directory(instances.resolve(".neosync-" + UUID.randomUUID()), true);
        try {
            InstallerFiles.directory(stage.resolve("libraries"), true);
            InstallerFiles.directory(stage.resolve("patches"), true);
            InstallerFiles.directory(stage.resolve(".minecraft"), true);
            JsonObject fingerprints = new JsonObject();
            JsonArray entries = new JsonArray();
            for (var value : profile.getAsJsonArray("libraries")) {
                var library = value.getAsJsonObject();
                Path source = libraries.get(InstallerFiles.relativeJar(library.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString()));
                InstallerFiles.copy(source, stage.resolve("libraries").resolve(source.getFileName()));
                fingerprints.addProperty(source.getFileName().toString(), InstallerFiles.hash(source, "SHA-256"));
                JsonObject entry = new JsonObject();
                entry.addProperty("name", library.get("name").getAsString());
                entry.addProperty("MMC-hint", "local");
                entries.add(entry);
            }
            String loader = argument(profile.getAsJsonObject("arguments").getAsJsonArray("game"), "--fml.neoForgeVersion");
            Path universal = installation.resolve("libraries/net/neoforged/neoforge").resolve(loader).resolve("neoforge-" + loader + "-universal.jar");
            Path bridge = stage.resolve("neosync-launcher-bridge.jar");
            try (var jar = new JarFile(universal.toFile()); var output = new JarOutputStream(Files.newOutputStream(bridge))) {
                for (String cls : java.util.List.of("LauncherBridge", "LauncherBridge$Verifier")) {
                    String resource = "net/neoforged/neoforge/neosync/launcher/" + cls + ".class";
                    output.putNextEntry(new JarEntry(resource));
                    try (var input = jar.getInputStream(jar.getJarEntry(resource))) {
                        input.transferTo(output);
                    }
                    output.closeEntry();
                }
            }
            JsonArray jvm = new JsonArray();
            boolean modulePath = false;
            for (var value : profile.getAsJsonObject("arguments").getAsJsonArray("jvm")) {
                String argument = value.getAsString().replace("${library_directory}", installation.resolve("libraries").toString().replace('\\', '/')).replace("${classpath_separator}", java.io.File.pathSeparator).replace("${version_name}", "minecraft-1.21.1-client");
                if (modulePath) {
                    String[] parts = argument.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
                    argument = java.util.Arrays.stream(parts).map(part -> instance.resolve("libraries").resolve(Path.of(part).getFileName()).toString().replace('\\', '/')).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
                }
                modulePath = argument.equals("-p");
                jvm.add(argument);
            }
            jvm.add("-Dneosync.launcher.config=" + instance.resolve("neosync-launcher.json").toString().replace('\\', '/'));
            JsonObject component = new JsonObject();
            component.addProperty("formatVersion", 1);
            component.addProperty("uid", "org.neosync");
            component.addProperty("name", "NeoSync");
            component.addProperty("version", neoSync);
            JsonObject requirement = new JsonObject();
            requirement.addProperty("uid", "net.minecraft");
            requirement.addProperty("equals", "1.21.1");
            JsonArray requirements = new JsonArray();
            requirements.add(requirement);
            component.add("requires", requirements);
            component.addProperty("mainClass", profile.get("mainClass").getAsString());
            String vanilla = "--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${assets_root} --assetIndex ${assets_index_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} --userType ${user_type} --versionType ${version_type}";
            component.addProperty("minecraftArguments", vanilla + " " + java.util.stream.StreamSupport.stream(profile.getAsJsonObject("arguments").getAsJsonArray("game").spliterator(), false).map(com.google.gson.JsonElement::getAsString).collect(java.util.stream.Collectors.joining(" ")));
            component.add("libraries", entries);
            component.add("+jvmArgs", jvm);
            JsonObject pack = new JsonObject();
            pack.addProperty("formatVersion", 1);
            JsonArray components = new JsonArray();
            for (String[] values : java.util.List.of(new String[] { "org.lwjgl3", "3.3.3" }, new String[] { "net.minecraft", "1.21.1" }, new String[] { "org.neosync", neoSync })) {
                JsonObject entry = new JsonObject();
                entry.addProperty("uid", values[0]);
                entry.addProperty("version", values[1]);
                components.add(entry);
            }
            pack.add("components", components);
            JsonObject descriptor = new JsonObject();
            descriptor.addProperty("schemaVersion", 1);
            descriptor.addProperty("kind", "prism");
            descriptor.addProperty("version", neoSync);
            descriptor.addProperty("neoForgeVersion", base);
            descriptor.addProperty("root", target.root().toString());
            descriptor.addProperty("instance", instance.toString());
            Path executable = findPrism(target.root());
            descriptor.addProperty("executable", executable.toString());
            descriptor.addProperty("java", Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").contains("Windows") ? "java.exe" : "java").toString());
            descriptor.add("libraries", fingerprints);
            descriptor.add("component", component);
            descriptor.addProperty("bridgeSha256", InstallerFiles.hash(bridge, "SHA-256"));
            InstallerFiles.publish(stage.resolve("mmc-pack.json"), InstallerFiles.encode(pack));
            InstallerFiles.publish(stage.resolve("patches/org.neosync.json"), InstallerFiles.encode(component));
            InstallerFiles.publish(stage.resolve("neosync-launcher.json"), InstallerFiles.encode(descriptor));
            String java = descriptor.get("java").getAsString().replace('\\', '/');
            Files.writeString(stage.resolve("instance.cfg"), "[General]\nConfigVersion=1.3\nInstanceType=OneSix\nname=" + version + "\niconKey=default\nOverrideJavaLocation=true\nJavaPath=\"" + java + "\"\nOverrideMemory=true\nMaxMemAlloc=2048\nMinMemAlloc=512\n");
            Files.move(stage, instance, StandardCopyOption.ATOMIC_MOVE);
            return instance;
        } finally {
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                try (var files = Files.walk(stage)) {
                    for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private static Path findPrism(Path root) throws IOException {
        String local = System.getenv("LOCALAPPDATA");
        for (Path path : java.util.List.of(root.resolve("prismlauncher.exe"), root.resolve("prismlauncher"),
                local == null ? root.resolve("missing") : Path.of(local, "Programs", "PrismLauncher", "prismlauncher.exe"),
                Path.of("C:/Program Files/PrismLauncher/prismlauncher.exe"), Path.of("/usr/bin/prismlauncher"))) {
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return path.toAbsolutePath();
        }
        throw new IOException("Prism Launcher could not be found. Select its portable installation folder or install it in the standard location.");
    }
}
