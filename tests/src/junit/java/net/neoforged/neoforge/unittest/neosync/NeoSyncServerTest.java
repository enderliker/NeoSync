/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.neosync.protocol.RequirementReport;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforge.neosync.server.NeoSyncServer;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

class NeoSyncServerTest {
    private static final Map<String, String> LOADED = Map.of(
            "create", "6.0.10", "flywheel", "1.0.6", "ponder", "1.0.82+mc1.21.1");

    @Test
    void bundledModsAppearInOneReviewedArtifact() throws Exception {
        var mods = manifestMods(createMods(), Map.of("create", "6.0.10"), LOADED);
        assertEquals(3, mods.size());
        var manifest = SyncManifest.parse(manifest(mods).toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(1, manifest.files().size());
        assertEquals(List.of("create", "flywheel", "ponder"), manifest.files().getFirst().mods().stream().map(SyncManifest.Mod::id).toList());

        var report = RequirementReport.compare(manifest, Set.of(), Map.of(), SyncManifest.NEOSYNC_VERSION, "21.1.252");
        assertTrue(report.lines().contains("Required: 3 mods in 1 file."));
        for (String id : List.of("create", "flywheel", "ponder"))
            assertTrue(report.lines().stream().anyMatch(line -> line.contains("(" + id + ")")), id);
    }

    @Test
    void rejectsDuplicateOrMismatchedBundledMods() throws Exception {
        var inspected = createMods();
        assertThrows(IOException.class, () -> manifestMods(List.of(inspected.getFirst(), inspected.getFirst()), Map.of("create", "6.0.10"), LOADED));
        assertThrows(IOException.class, () -> manifestMods(inspected, Map.of("create", "6.0.10"),
                Map.of("create", "6.0.10", "flywheel", "1.0.7", "ponder", "1.0.82+mc1.21.1")));
        assertThrows(IOException.class, () -> manifestMods(inspected.subList(1, inspected.size()), Map.of("create", "6.0.10"), LOADED));
        assertThrows(IOException.class, () -> manifestMods(inspected, Map.of("create", "6.0.10"), Map.of("create", "6.0.10", "flywheel", "1.0.6")));
    }

    private static JsonArray manifestMods(List<SyncManifest.Mod> inspected, Map<String, String> selectedVersions,
            Map<String, String> loadedVersions) throws Exception {
        var method = NeoSyncServer.class.getDeclaredMethod("manifestMods", List.class, Map.class, Map.class);
        method.setAccessible(true);
        try {
            return (JsonArray) method.invoke(null, inspected, selectedVersions, loadedVersions);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof IOException io) throw io;
            throw exception;
        }
    }

    private static List<SyncManifest.Mod> createMods() throws Exception {
        return List.of(
                new SyncManifest.Mod("create", "6.0.10", "Create", List.of(
                        dependency("flywheel", "[1.0.0,2.0)"), dependency("ponder", "[1.0.82,)"))),
                new SyncManifest.Mod("flywheel", "1.0.6", "Flywheel", List.of()),
                new SyncManifest.Mod("ponder", "1.0.82+mc1.21.1", "Ponder", List.of(dependency("flywheel", "[1.0.0,2.0)"))));
    }

    private static SyncManifest.Dependency dependency(String id, String range) throws Exception {
        return new SyncManifest.Dependency(id, VersionRange.createFromVersionSpec(range), "required");
    }

    private static JsonObject manifest(JsonArray mods) {
        var source = new JsonObject();
        source.addProperty("type", "external");
        source.addProperty("url", "https://cdn.modrinth.com/data/create.jar");
        var sources = new JsonArray();
        sources.add(source);

        var artifact = new JsonObject();
        artifact.addProperty("sha256", "0".repeat(64));
        artifact.addProperty("size", 1);
        artifact.addProperty("fileName", "create.jar");
        artifact.addProperty("required", true);
        artifact.add("mods", mods);
        artifact.add("sources", sources);
        var files = new JsonArray();
        files.add(artifact);

        var loader = new JsonObject();
        loader.addProperty("id", "neosync");
        loader.addProperty("version", SyncManifest.NEOSYNC_VERSION);
        loader.addProperty("neoForgeVersion", "21.1.252");
        var result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("serverId", "00000000-0000-0000-0000-000000000001");
        result.addProperty("revision", "inventory-test");
        result.addProperty("displayName", "Test server");
        result.addProperty("minecraftVersion", "1.21.1");
        result.add("loader", loader);
        result.add("files", files);
        return result;
    }
}
