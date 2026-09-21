/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.JarMetadata;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JarMetadataTest {
    static final String TOML = """
            modLoader="javafml"
            loaderVersion="[4,)"
            license="MIT"
            [[mods]]
            modId="test_mod"
            version="1.0"
            displayName="Test Mod"
            """;

    static Path jar(Path directory, String toml, Map<String, byte[]> extra) throws IOException {
        Path path = directory.resolve(java.util.UUID.randomUUID() + ".jar");
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            output.write(toml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (var entry : extra.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    static SyncManifest.Artifact artifact(Path path) throws IOException {
        var fingerprint = ArtifactFiles.fingerprint(path, new DiscoveryCancellation());
        return new SyncManifest.Artifact(fingerprint.sha256(), fingerprint.size(), "test.jar",
                List.of(new SyncManifest.Mod("test_mod", "1.0", "Test Mod", List.of())), List.of());
    }

    @Test
    void verifiesTopLevelModAndJarVersionWithoutLoadingClasses(@TempDir Path directory) throws Exception {
        for (boolean placeholder : new boolean[] { false, true }) {
            Path path = jar(directory, placeholder ? TOML.replace("version=\"1.0\"", "version=\"${file.jarVersion}\"") : TOML,
                    Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8),
                            "Test.class", "not bytecode; must never be loaded during inspection".getBytes(StandardCharsets.UTF_8)));
            JarMetadata.verify(path, artifact(path), "4.0.44", new DiscoveryCancellation());
        }
        assertFalse(Files.exists(directory.resolve("neosync-profile.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = { "modId=\"other_mod\"", "version=\"2.0\"", "modLoader=\"kotlinforforge\"", "loaderVersion=\"[99,)\"" })
    void rejectsIdentityAndLanguageLoaderMismatches(String replacement, @TempDir Path directory) throws Exception {
        String key = replacement.substring(0, replacement.indexOf('='));
        String toml = TOML.replaceAll("(?m)^" + key + "=.*$", java.util.regex.Matcher.quoteReplacement(replacement));
        Path path = jar(directory, toml, Map.of());
        assertThrows(IOException.class, () -> JarMetadata.verify(path, artifact(path), "4.0.44", new DiscoveryCancellation()));
    }

    @Test
    void rejectsUndeclaredModsAndClientDependencies(@TempDir Path directory) throws Exception {
        for (String extra : List.of("\n[[mods]]\nmodId=\"extra_mod\"\nversion=\"1.0\"\n",
                "\n[[dependencies.test_mod]]\nmodId=\"minecraft\"\nversionRange=\"[1.21.1]\"\ntype=\"required\"\nside=\"CLIENT\"\n")) {
            Path path = jar(directory, TOML + extra, Map.of());
            assertThrows(IOException.class, () -> JarMetadata.verify(path, artifact(path), "4.0.44", new DiscoveryCancellation()));
        }
        Path serverDependency = jar(directory, TOML + "\n[[dependencies.test_mod]]\nmodId=\"server_mod\"\nside=\"SERVER\"\n", Map.of());
        JarMetadata.verify(serverDependency, artifact(serverDependency), "4.0.44", new DiscoveryCancellation());
    }

    @ParameterizedTest
    @ValueSource(strings = { "../../outside", "neosync-profile.json", "META-INF/jarjar/dependency.jar", "META-INF/services/provider", "META-INF/mods.toml", "fabric.mod.json" })
    void rejectsUnsafePathsAndUnsupportedLoadingArrangements(String entry, @TempDir Path directory) throws Exception {
        Path path = jar(directory, TOML, Map.of(entry, new byte[] { 1 }));
        assertThrows(IOException.class, () -> JarMetadata.verify(path, artifact(path), "4.0.44", new DiscoveryCancellation()));
    }

    @Test
    void boundsMetadataExpansionAndTomlNesting(@TempDir Path directory) throws Exception {
        for (String toml : List.of(TOML + "#".repeat(512 * 1024), TOML + "\nx=" + "[".repeat(1000) + "0" + "]".repeat(1000))) {
            Path path = jar(directory, toml, Map.of());
            assertThrows(IOException.class, () -> JarMetadata.verify(path, artifact(path), "4.0.44", new DiscoveryCancellation()));
        }
    }

    @Test
    void rejectsMalformedArchiveAndCancelledInspection(@TempDir Path directory) throws Exception {
        Path invalid = Files.writeString(directory.resolve("bad.jar"), "not a jar");
        assertThrows(IOException.class, () -> JarMetadata.verify(invalid, artifact(invalid), "4.0.44", new DiscoveryCancellation()));
        Path valid = jar(directory, TOML, Map.of());
        var token = new DiscoveryCancellation();
        token.close();
        assertThrows(IOException.class, () -> JarMetadata.verify(valid, artifact(valid), "4.0.44", token));
    }
}
