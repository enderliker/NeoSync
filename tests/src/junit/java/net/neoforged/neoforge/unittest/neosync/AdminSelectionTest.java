/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.neosync.server.AdminSecrets;
import net.neoforged.neoforge.neosync.server.AdminSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminSelectionTest {
    @Test
    void createsEnabledDefaultsOnFirstStartAndPreservesExistingConfig(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config/neosync-server.json");
        new AdminSelection(config, List.of(), 25565);
        var defaults = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertTrue(defaults.get("enabled").getAsBoolean());
        assertEquals("managed-https", defaults.get("mode").getAsString());
        assertEquals("https", defaults.get("adminTransport").getAsString());
        assertEquals(6742, defaults.get("adminPort").getAsInt());
        assertEquals(8443, defaults.get("httpsPort").getAsInt());
        assertEquals(25565, defaults.get("gamePort").getAsInt());
        defaults.addProperty("adminPort", 7654);
        defaults.addProperty("enabled", false);
        Files.writeString(config, defaults.toString());
        byte[] original = Files.readAllBytes(config);
        var restarted = new AdminSelection(config, List.of(), 25566);
        assertArrayEquals(original, Files.readAllBytes(config));
        assertEquals(7654, restarted.transport().adminPort());
        assertFalse(restarted.state().get("enabled").getAsBoolean());
    }

    @Test
    void persistsPrivateGeneratedCredentialsAndTlsIdentity(@TempDir Path directory) throws Exception {
        Path privateDirectory = directory.resolve("admin");
        var first = AdminSecrets.open(privateDirectory);
        String password = Files.readString(privateDirectory.resolve("password.txt")).strip();
        assertEquals(43, password.length());
        assertTrue(first.accepts(password));
        assertFalse(first.accepts("wrong"));
        assertEquals(first.fingerprint(), AdminSecrets.open(privateDirectory).fingerprint());
        if (Files.getFileStore(privateDirectory).supportsFileAttributeView("posix")) {
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(privateDirectory)));
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(privateDirectory.resolve("password.txt"))));
        }
        assertNotEquals(password, Files.readString(privateDirectory.resolve("keystore-password.txt")).strip());
    }

    @Test
    void rejectsSecretSymlinksWithoutOverwritingTheirTarget(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("personal.txt");
        Files.writeString(target, "keep this");
        Path admin = Files.createDirectory(directory.resolve("admin"));
        Files.createSymbolicLink(admin.resolve("password.txt"), target);
        assertThrows(IOException.class, () -> AdminSecrets.open(admin));
        assertEquals("keep this", Files.readString(target));
    }

    @Test
    void savesOnlyLoadedFilesAndRejectsStaleReviews(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        Path config = directory.resolve("config/neosync-server.json");
        var selection = new AdminSelection(config, List.of(new AdminSelection.Candidate(jar, "Test mod 1.0")), 25565);
        var request = request(selection, "modrinth");
        byte[] original = Files.readAllBytes(config);
        var unknown = request.deepCopy();
        unknown.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("fileName", "unknown.jar");
        assertThrows(IOException.class, () -> selection.save(bytes(unknown)));
        assertArrayEquals(original, Files.readAllBytes(config));
        selection.save(bytes(request));
        assertTrue(Files.readString(config).contains("\"resolveProviders\":true"));
        assertTrue(selection.state().get("restartRequired").getAsBoolean());
        byte[] saved = Files.readAllBytes(config);
        assertThrows(IOException.class, () -> selection.save(bytes(request)));
        assertArrayEquals(saved, Files.readAllBytes(config));
        var empty = request(selection, "modrinth");
        empty.add("files", new JsonArray());
        selection.save(bytes(empty));
        assertFalse(selection.state().getAsJsonArray("files").get(0).getAsJsonObject().get("selected").getAsBoolean());
        assertTrue(Files.exists(jar));
    }

    @Test
    void requiresEveryHostingDeclarationAndUnchangedBytes(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        Path config = directory.resolve("config/neosync-server.json");
        var selection = new AdminSelection(config, List.of(new AdminSelection.Candidate(jar, "Test mod 1.0")), 25565);
        var request = request(selection, "server");
        var file = request.getAsJsonArray("files").get(0).getAsJsonObject();
        for (String key : List.of("authoredByAdministrator", "exclusiveToServer", "distributionRights")) {
            assertThrows(IOException.class, () -> selection.save(bytes(request)));
            file.addProperty(key, true);
        }
        selection.save(bytes(request));
        assertTrue(Files.readString(config).contains("authoredByAdministrator"));
        byte[] saved = Files.readAllBytes(config);
        var changed = request(selection, "modrinth");
        Files.writeString(jar, "changed");
        assertThrows(IOException.class, () -> selection.save(bytes(changed)));
        assertArrayEquals(saved, Files.readAllBytes(config));
    }

    static JsonObject request(AdminSelection selection, String source) throws IOException {
        var state = selection.state();
        var request = new JsonObject();
        request.add("revision", state.get("revision"));
        request.addProperty("enabled", true);
        request.addProperty("displayName", "Test server");
        var files = new JsonArray();
        var file = new JsonObject();
        var candidate = state.getAsJsonArray("files").get(0).getAsJsonObject();
        file.add("fileName", candidate.get("fileName"));
        file.add("sha256", candidate.get("sha256"));
        file.addProperty("source", source);
        files.add(file);
        request.add("files", files);
        return request;
    }

    private static byte[] bytes(JsonObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }
}
