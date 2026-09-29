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
    void identifiesKnownLaunchersWithoutInventingRestartSupport() {
        assertEquals(LauncherIntegration.Kind.PRISM, LauncherIntegration.identify("PrismLauncher"));
        assertEquals(LauncherIntegration.Kind.SKLAUNCHER, LauncherIntegration.identify("SKlauncher"));
        assertEquals(LauncherIntegration.Kind.MINECRAFT, LauncherIntegration.identify("minecraft-launcher"));
        assertEquals(LauncherIntegration.Kind.LUNAR, LauncherIntegration.identify("Lunar Client"));
        assertEquals(LauncherIntegration.Kind.MULTIMC, LauncherIntegration.identify("MultiMC"));
        assertEquals(LauncherIntegration.Kind.UNKNOWN, LauncherIntegration.identify("unknown"));
        assertFalse(new LauncherIntegration.Detected(LauncherIntegration.Kind.SKLAUNCHER, Path.of("/"), null).canCreateInstallation());
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
