/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.neosync.launcher.LauncherBridge;
import net.neoforged.neoforge.neosync.launcher.PrismIntegration;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherBridgeTest {
    @Test
    void rejectsMalformedPrelaunchRecordsWithoutUncheckedFailures(@TempDir Path directory) throws Exception {
        Path record = Files.writeString(directory.resolve("verification.properties"), "count=0\n");
        assertThrows(IOException.class, () -> LauncherBridge.Verifier.verify(record));
        Files.writeString(record, "gameDirectory=../outside\ncount=0\n");
        assertThrows(IOException.class, () -> LauncherBridge.Verifier.verify(record));
    }

    @Test
    void exportsAnIsolatedInstanceAndChecksModsBeforeLaunch(@TempDir Path directory) throws Exception {
        var store = ProfileStore.open(directory);
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var artifact = JarMetadataTest.artifact(jar);
        var manifest = JsonParser.parseString(new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        var file = manifest.getAsJsonArray("files").get(0).getAsJsonObject();
        file.addProperty("sha256", artifact.sha256());
        file.addProperty("size", artifact.size());
        byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        var plan = InstallationPlan.create(InstallationPlanTest.endpoint(bytes), bytes, Set.of(artifact.sha256()), null, "0.1.0-dev", "21.1.251");
        var prepared = store.prepare(plan, plan.accept(true, true), Map.of(artifact.sha256(), jar), "4.0.44", new DiscoveryCancellation(), (a, b, c) -> {});
        Path root = Files.createDirectory(directory.resolve("Prism with spaces"));
        Path base = Files.createDirectories(root.resolve("instances/base"));
        Files.createDirectory(base.resolve("libraries"));
        Path bridge = Files.writeString(base.resolve("neosync-launcher-bridge.jar"), "fixture bridge");
        Path executable = Files.writeString(base.resolve("prism"), "fixture executable");
        Path java = Files.writeString(base.resolve("java"), "fixture Java");
        var descriptor = new JsonObject();
        descriptor.addProperty("schemaVersion", 1);
        descriptor.addProperty("kind", "prism");
        descriptor.addProperty("version", "0.1.0-dev");
        descriptor.addProperty("neoForgeVersion", "21.1.251");
        descriptor.addProperty("root", root.toString());
        descriptor.addProperty("instance", base.toString());
        descriptor.addProperty("executable", executable.toString());
        descriptor.addProperty("java", java.toString());
        descriptor.addProperty("bridgeSha256", SyncManifest.sha256(Files.readAllBytes(bridge)));
        descriptor.add("libraries", new JsonObject());
        var component = new JsonObject();
        component.addProperty("minecraftArguments", "--gameDir ${game_directory} --accessToken ${auth_access_token}");
        var jvm = new com.google.gson.JsonArray();
        jvm.add("-p");
        jvm.add(base.resolve("libraries/bootstrap.jar").toString().replace('\\', '/'));
        component.add("+jvmArgs", jvm);
        descriptor.add("component", component);
        Path config = Files.writeString(base.resolve("neosync-launcher.json"), descriptor.toString());
        var launch = PrismIntegration.prepare(config, prepared, "4.0.44", new DiscoveryCancellation());
        assertTrue(launch.instance().startsWith(root.resolve("instances")));
        assertTrue(Files.readString(launch.instance().resolve("patches/org.neosync.json")).contains("${auth_access_token}"));
        assertTrue(Files.readString(launch.instance().resolve("instance.cfg")).contains("PreLaunchCommand="));
        assertTrue(Files.readString(launch.instance().resolve("instance.cfg")).contains("ConfigVersion=1.3"));
        assertTrue(Files.readString(launch.instance().resolve("patches/org.neosync.json")).contains(launch.instance().resolve("libraries/bootstrap.jar").toString().replace('\\', '/')));
        var components = JsonParser.parseString(Files.readString(launch.instance().resolve("mmc-pack.json"))).getAsJsonObject().getAsJsonArray("components");
        assertEquals("org.lwjgl3", components.get(0).getAsJsonObject().get("uid").getAsString());
        assertEquals("net.minecraft", components.get(1).getAsJsonObject().get("uid").getAsString());
        assertEquals("org.neosync", components.get(2).getAsJsonObject().get("uid").getAsString());
        LauncherBridge.Verifier.verify(launch.verification());
        assertEquals(launch, PrismIntegration.prepare(config, prepared, "4.0.44", new DiscoveryCancellation()));
        var repeated = PrismIntegration.prepare(launch.instance().resolve("neosync-launcher.json"), prepared, "4.0.44", new DiscoveryCancellation());
        assertEquals(launch.instance(), repeated.instance());
        assertEquals(1, Files.readString(launch.instance().resolve("patches/org.neosync.json")).split("--gameDir", -1).length - 1);
        String settings = Files.readString(launch.instance().resolve("instance.cfg"));
        Files.writeString(launch.instance().resolve("instance.cfg"), settings.replace("OverrideCommands=true", "OverrideCommands=false"));
        assertThrows(IOException.class, () -> LauncherBridge.Verifier.verify(launch.verification()));
        assertThrows(IOException.class, () -> PrismIntegration.prepare(config, prepared, "4.0.44", new DiscoveryCancellation()));
        Files.writeString(launch.instance().resolve("instance.cfg"), settings);
        Path injected = Files.writeString(prepared.gameDirectory().resolve("mods/extra.jar"), "unreviewed");
        assertThrows(IOException.class, () -> LauncherBridge.Verifier.verify(launch.verification()));
        Files.delete(injected);
        Path installed = prepared.gameDirectory().resolve("mods").resolve(artifact.sha256() + ".jar");
        Files.writeString(installed, "changed after preparation");
        assertThrows(IOException.class, () -> LauncherBridge.Verifier.verify(launch.verification()));
        assertThrows(IOException.class, () -> PrismIntegration.prepare(config, prepared, "4.0.44", new DiscoveryCancellation()));
    }

    @Test
    void createsALocalDescriptorOnlyAfterVerifiedPreparation(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var artifact = JarMetadataTest.artifact(jar);
        var manifest = JsonParser.parseString(new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("sha256", artifact.sha256());
        manifest.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("size", artifact.size());
        byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        var plan = InstallationPlan.create(InstallationPlanTest.endpoint(bytes), bytes, Set.of(artifact.sha256()), null, "0.1.0-dev", "21.1.251");
        var prepared = ProfileStore.open(directory).prepare(plan, plan.accept(true, true), Map.of(artifact.sha256(), jar), "4.0.44", new DiscoveryCancellation(), (action, completed, total) -> {});
        Path base = Files.createDirectories(directory.resolve("Prism root/instances/base"));
        Files.createDirectory(base.resolve("libraries"));
        Files.writeString(base.resolve("libraries/bootstrap.jar"), "fixture library");
        Path patches = Files.createDirectory(base.resolve("patches"));
        var component = new JsonObject();
        component.addProperty("uid", "org.neosync");
        component.addProperty("version", "0.1.0-dev");
        component.addProperty("mainClass", "cpw.mods.bootstraplauncher.BootstrapLauncher");
        component.addProperty("minecraftArguments", "--gameDir ${game_directory} --accessToken ${auth_access_token}");
        component.add("libraries", JsonParser.parseString("[{\"name\":\"fixture:bootstrap:1\",\"MMC-hint\":\"local\"}]"));
        Files.writeString(patches.resolve("org.neosync.json"), component.toString());
        Path executable = Files.writeString(base.resolve("prismlauncher"), "fixture executable");
        Path descriptor = base.resolve("neosync-launcher.json");
        var cancelled = new DiscoveryCancellation();
        cancelled.close();
        assertThrows(IOException.class, () -> PrismIntegration.prepareDetected(base, executable, prepared, "4.0.44", cancelled));
        assertTrue(!Files.exists(descriptor));
        String previous = System.getProperty("neosync.launcher.config");
        try {
            var launch = PrismIntegration.prepareDetected(base, executable, prepared, "4.0.44", new DiscoveryCancellation());
            assertTrue(Files.isRegularFile(descriptor));
            assertTrue(Files.isRegularFile(launch.instance().resolve("neosync-launcher.json")));
            assertTrue(Files.isRegularFile(launch.instance().resolve("neosync-launcher-bridge.jar")));
            assertTrue(Files.readString(descriptor).contains("${auth_access_token}"));
            assertTrue(!Files.readString(descriptor).contains("authenticationDatabase"));
            LauncherBridge.Verifier.verify(launch.verification());
        } finally {
            if (previous == null) System.clearProperty("neosync.launcher.config");
            else System.setProperty("neosync.launcher.config", previous);
        }
    }
}
