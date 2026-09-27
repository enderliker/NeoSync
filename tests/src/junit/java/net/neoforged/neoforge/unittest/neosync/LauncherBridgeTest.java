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
        assertTrue(Files.readString(launch.instance().resolve("patches/org.neosync.json")).contains(launch.instance().resolve("libraries/bootstrap.jar").toString().replace('\\', '/')));
        LauncherBridge.Verifier.verify(launch.verification());
        assertEquals(launch, PrismIntegration.prepare(config, prepared, "4.0.44", new DiscoveryCancellation()));
        Path injected = Files.writeString(prepared.gameDirectory().resolve("mods/extra.jar"), "unreviewed");
        assertThrows(IOException.class, () -> LauncherBridge.Verifier.verify(launch.verification()));
        Files.delete(injected);
        Path installed = prepared.gameDirectory().resolve("mods").resolve(artifact.sha256() + ".jar");
        Files.writeString(installed, "changed after preparation");
        assertThrows(IOException.class, () -> LauncherBridge.Verifier.verify(launch.verification()));
        assertThrows(IOException.class, () -> PrismIntegration.prepare(config, prepared, "4.0.44", new DiscoveryCancellation()));
    }
}
