/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
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
        Files.write(path, jarBytes(toml, extra));
        return path;
    }

    static byte[] jarBytes(String toml, Map<String, byte[]> extra) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            if (toml != null) {
                output.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
                output.write(toml.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            for (var entry : extra.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static String jarjarEntry(String artifact, String version, String path) {
        return """
                {"identifier":{"group":"example.test","artifact":"%s"},"version":{"range":"[%s,)","artifactVersion":"%s"},"path":"%s","isObfuscated":false}
                """.formatted(artifact, version, version, path).trim();
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
            assertEquals(artifact(path).mods(), JarMetadata.inspect(path, "4.0.44", new DiscoveryCancellation()));
            JarMetadata.verify(path, artifact(path), "4.0.44", new DiscoveryCancellation());
        }
        assertFalse(Files.exists(directory.resolve("neosync-profile.json")));
    }

    @Test
    void inspectsDeclaredModAndLibraryDependencies(@TempDir Path directory) throws Exception {
        String parent = TOML + """

                [[dependencies.test_mod]]
                modId="embedded_mod"
                versionRange="[1.2,)"
                type="required"
                side="BOTH"

                [[dependencies.test_mod]]
                modId="server_only"
                versionRange="[1.0,)"
                type="required"
                side="SERVER"
                """;
        String embedded = """
                modLoader="javafml"
                loaderVersion="[4,)"
                license="MIT"
                [[mods]]
                modId="embedded_mod"
                version="1.2"
                displayName="Embedded Mod"
                [[dependencies.embedded_mod]]
                modId="minecraft"
                versionRange="[1.21.1]"
                type="required"
                side="CLIENT"
                """;
        String embeddedPath = "META-INF/jarjar/embedded-1.2.jar";
        String libraryPath = "META-INF/jarjar/library-1.0.jar";
        String metadata = "{\"jars\":[" + jarjarEntry("embedded", "1.2", embeddedPath) + ","
                + jarjarEntry("library", "1.0", libraryPath) + "]}";
        byte[] library = jarBytes(null, Map.of("META-INF/MANIFEST.MF", json("Manifest-Version: 1.0\r\nFMLModType: GAMELIBRARY\r\n\r\n")));
        Path path = jar(directory, parent, Map.of("META-INF/jarjar/metadata.json", json(metadata),
                embeddedPath, jarBytes(embedded, Map.of()), libraryPath, library));

        var mods = JarMetadata.inspect(path, "4.0.44", new DiscoveryCancellation());
        assertEquals(List.of("test_mod", "embedded_mod"), mods.stream().map(SyncManifest.Mod::id).toList());
        assertEquals(List.of("embedded_mod"), mods.getFirst().dependencies().stream().map(SyncManifest.Dependency::id).toList());
        assertEquals(List.of("minecraft"), mods.getLast().dependencies().stream().map(SyncManifest.Dependency::id).toList());
        var fingerprint = ArtifactFiles.fingerprint(path, new DiscoveryCancellation());
        var reviewed = new SyncManifest.Artifact(fingerprint.sha256(), fingerprint.size(), "test.jar", mods, List.of());
        JarMetadata.verify(path, reviewed, "4.0.44", new DiscoveryCancellation());
        assertThrows(IOException.class, () -> JarMetadata.verify(path, artifact(path), "4.0.44", new DiscoveryCancellation()));
        var wrongEmbedded = new SyncManifest.Mod("embedded_mod", "1.3", "Embedded Mod", mods.getLast().dependencies(), true);
        var wrongVersion = new SyncManifest.Artifact(fingerprint.sha256(), fingerprint.size(), "test.jar", List.of(mods.getFirst(), wrongEmbedded), List.of());
        assertThrows(IOException.class, () -> JarMetadata.verify(path, wrongVersion, "4.0.44", new DiscoveryCancellation()));
        var wrongDependencies = new SyncManifest.Mod("embedded_mod", "1.2", "Embedded Mod", List.of(), true);
        var missingDependency = new SyncManifest.Artifact(fingerprint.sha256(), fingerprint.size(), "test.jar", List.of(mods.getFirst(), wrongDependencies), List.of());
        assertThrows(IOException.class, () -> JarMetadata.verify(path, missingDependency, "4.0.44", new DiscoveryCancellation()));
    }

    @Test
    void rejectsUnlistedAndDeeplyNestedJarJarArchives(@TempDir Path directory) throws Exception {
        String path = "META-INF/jarjar/embedded-1.2.jar";
        byte[] validNested = jarBytes(TOML, Map.of());
        Path unlisted = jar(directory, TOML, Map.of(path, validNested));
        assertThrows(IOException.class, () -> JarMetadata.inspect(unlisted, "4.0.44", new DiscoveryCancellation()));

        String listed = "{\"jars\":[" + jarjarEntry("embedded", "1.2", path) + "]}";
        Path extra = jar(directory, TOML, Map.of(path, validNested,
                "META-INF/jarjar/unlisted.jar", validNested, "META-INF/jarjar/metadata.json", json(listed)));
        assertThrows(IOException.class, () -> JarMetadata.inspect(extra, "4.0.44", new DiscoveryCancellation()));

        byte[] deep = jarBytes(TOML, Map.of("META-INF/jarjar/deep.jar", validNested));
        Path nested = jar(directory, TOML, Map.of(path, deep, "META-INF/jarjar/metadata.json", json(listed)));
        assertThrows(IOException.class, () -> JarMetadata.inspect(nested, "4.0.44", new DiscoveryCancellation()));
    }

    @Test
    void rejectsAlternativeLoaderInsideDeclaredJarJarArchive(@TempDir Path directory) throws Exception {
        String path = "META-INF/jarjar/embedded-1.2.jar";
        String metadata = "{\"jars\":[" + jarjarEntry("embedded", "1.2", path) + "]}";
        for (String forbidden : List.of("META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator", "META-INF/services/cpw.mods.modlauncher.api.ITransformationService")) {
            byte[] nested = jarBytes(TOML, Map.of(forbidden, json("untrusted loader")));
            Path outer = jar(directory, TOML, Map.of(path, nested, "META-INF/jarjar/metadata.json", json(metadata)));
            assertThrows(IOException.class, () -> JarMetadata.inspect(outer, "4.0.44", new DiscoveryCancellation()));
        }
    }

    @Test
    void acceptsCreateSizedArchiveDirectory(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("large.jar");
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            output.write(json(TOML));
            output.closeEntry();
            for (int index = 0; index < 55837; index++) {
                output.putNextEntry(new ZipEntry("example/C" + index + ".class"));
                output.write(0);
                output.closeEntry();
            }
        }
        assertEquals(1, JarMetadata.inspect(path, "4.0.44", new DiscoveryCancellation()).size());
        JarMetadata.verify(path, artifact(path), "4.0.44", new DiscoveryCancellation());
    }

    @ParameterizedTest
    @ValueSource(strings = { "modId=\"other_mod\"", "version=\"2.0\"", "modLoader=\"invalid loader\"", "loaderVersion=\"[99,)\"" })
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
    @ValueSource(strings = { "../../outside", "neosync-profile.json", "META-INF/jarjar/dependency.jar", "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator" })
    void rejectsUnsafePathsAndUnsupportedLoadingArrangements(String entry, @TempDir Path directory) throws Exception {
        Path path = jar(directory, TOML, Map.of(entry, new byte[] { 1 }));
        assertThrows(IOException.class, () -> JarMetadata.verify(path, artifact(path), "4.0.44", new DiscoveryCancellation()));
    }

    @Test
    void permitsInternalApplicationServicesWithoutLoadingThem(@TempDir Path directory) throws Exception {
        Path path = jar(directory, TOML, Map.of("META-INF/services/example.InternalService", "example.UnloadedImplementation".getBytes(StandardCharsets.UTF_8)));
        JarMetadata.verify(path, artifact(path), "4.0.44", new DiscoveryCancellation());
    }

    @Test
    void acceptsMergedLoaderMetadataAndInactiveFabricArchives(@TempDir Path directory) throws Exception {
        String path = "META-INF/jars/fabric-helper.jar";
        Path merged = jar(directory, TOML, Map.of("fabric.mod.json", json("{\"jars\":[{\"file\":\"" + path + "\"}]}"),
                "META-INF/mods.toml", json("inactive Forge metadata"), path, jarBytes(null, Map.of("fabric.mod.json", json("{}")))));
        JarMetadata.verify(merged, artifact(merged), "4.0.45", new DiscoveryCancellation());
        Path fabricOnly = jar(directory, null, Map.of("fabric.mod.json", json("{}")));
        assertThrows(IOException.class, () -> JarMetadata.inspect(fabricOnly, "4.0.45", new DiscoveryCancellation()));
    }

    @Test
    void acceptsDeclaredRecursiveLibrariesAndOptionalJarJarFields(@TempDir Path directory) throws Exception {
        String deepPath = "META-INF/jars/helper.jar";
        String metadata = "{\"jars\":[" + jarjarEntry("helper", "1.0", deepPath).replace(",\"isObfuscated\":false", "") + "]}";
        byte[] helper = jarBytes(null, Map.of("META-INF/MANIFEST.MF", json("Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n")));
        byte[] library = jarBytes(null, Map.of("META-INF/jarjar/metadata.json", json(metadata), deepPath, helper));
        String path = "META-INF/jars/library.jar";
        Path outer = jar(directory, TOML, Map.of(path, library, "META-INF/jarjar/metadata.json", json("{\"jars\":[" + jarjarEntry("library", "1.0", path) + "]}")));
        JarMetadata.verify(outer, artifact(outer), "4.0.45", new DiscoveryCancellation());
    }

    @Test
    void permitsResourceCaseVariantsButRejectsMetadataAndClassAliases(@TempDir Path directory) throws Exception {
        Path resources = jar(directory, TOML, Map.of("assets/example/Model.json", json("{}"), "assets/example/model.json", json("{}")));
        JarMetadata.verify(resources, artifact(resources), "4.0.45", new DiscoveryCancellation());
        for (var extra : List.of(Map.of("Example.class", new byte[] { 1 }, "example.class", new byte[] { 2 }),
                Map.of("meta-inf/neoforge.mods.toml", json(TOML)))) {
            Path aliases = jar(directory, TOML, extra);
            assertThrows(IOException.class, () -> JarMetadata.inspect(aliases, "4.0.45", new DiscoveryCancellation()));
        }
    }

    @Test
    void acceptsLanguageBundlesWithoutInventingModDependencies(@TempDir Path directory) throws Exception {
        String service = "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader";
        String nestedPath = "META-INF/jarjar/language.jar";
        byte[] language = jarBytes(null, Map.of("META-INF/MANIFEST.MF", json("Manifest-Version: 1.0\r\nFMLModType: LIBRARY\r\n\r\n"),
                service, json("example.LanguageLoader\n")));
        String custom = TOML.replace("javafml", "customlanguage").replace("[4,)", "[2,)");
        Path bundle = jar(directory, custom, Map.of(nestedPath, language, "META-INF/jarjar/metadata.json",
                json("{\"jars\":[" + jarjarEntry("language", "2.0", nestedPath) + "]}")));
        JarMetadata.verify(bundle, artifact(bundle), "4.0.45", new DiscoveryCancellation());
    }

    @Test
    void acceptsJarJarAliasesAndBoundsDeclaredRecursion(@TempDir Path directory) throws Exception {
        String nestedPath = "META-INF/jarjar/library.jar";
        String first = jarjarEntry("library", "1.0", nestedPath);
        String alias = jarjarEntry("alias", "1.0", nestedPath);
        byte[] library = jarBytes(null, Map.of("META-INF/MANIFEST.MF", json("Manifest-Version: 1.0\r\n\r\n")));
        Path aliases = jar(directory, TOML, Map.of(nestedPath, library, "META-INF/jarjar/metadata.json", json("{\"jars\":[" + first + "," + alias + "]}")));
        JarMetadata.verify(aliases, artifact(aliases), "4.0.45", new DiscoveryCancellation());
        for (int depth = 0; depth < 5; depth++)
            library = jarBytes(null, Map.of(nestedPath, library, "META-INF/jarjar/metadata.json", json("{\"jars\":[" + first + "]}")));
        Path deep = jar(directory, TOML, Map.of(nestedPath, library, "META-INF/jarjar/metadata.json", json("{\"jars\":[" + first + "]}")));
        assertThrows(IOException.class, () -> JarMetadata.inspect(deep, "4.0.45", new DiscoveryCancellation()));
    }

    @Test
    void usesNeoForgeJarJarSelectionAcrossArtifacts(@TempDir Path directory) throws Exception {
        String path = "META-INF/jarjar/shared.jar";
        String library = TOML.replace("test_mod", "shared_mod");
        var files = new java.util.ArrayList<Path>();
        for (String version : List.of("1.0", "2.0")) {
            byte[] embedded = jarBytes(library.replace("version=\"1.0\"", "version=\"" + version + "\""), Map.of());
            String declaration = jarjarEntry("shared", version, path).replace("[2.0,)", "[1.0,)");
            files.add(jar(directory, TOML.replace("test_mod", version.equals("1.0") ? "first_mod" : "second_mod"), Map.of(path, embedded,
                    "META-INF/jarjar/metadata.json", json("{\"jars\":[" + declaration + "]}"))));
        }
        var active = JarMetadata.activeMods(files, "4.0.45", new DiscoveryCancellation());
        assertEquals(Map.of("first_mod", "1.0", "second_mod", "1.0", "shared_mod", "2.0"), active);
        var hashed = new java.util.ArrayList<Path>();
        for (Path file : files) hashed.add(Files.copy(file, directory.resolve(ArtifactFiles.fingerprint(file, new DiscoveryCancellation()).sha256() + ".jar")));
        assertEquals(active, JarMetadata.activeMods(hashed, "4.0.45", new DiscoveryCancellation()));
    }

    @Test
    void selectsNewestModuleAcrossDifferentJarJarIdentifiers(@TempDir Path directory) throws Exception {
        String path = "META-INF/jarjar/shared.jar";
        var files = new java.util.ArrayList<Path>();
        for (String version : List.of("1.0", "2.0")) {
            byte[] embedded = jarBytes(TOML.replace("test_mod", "shared_mod").replace("version=\"1.0\"", "version=\"" + version + "\""), Map.of());
            files.add(jar(directory, TOML.replace("test_mod", version.equals("1.0") ? "first_mod" : "second_mod"), Map.of(path, embedded,
                    "META-INF/jarjar/metadata.json", json("{\"jars\":[" + jarjarEntry("alias" + version, version, path) + "]}"))));
        }
        assertEquals("2.0", JarMetadata.activeMods(files, "4.0.45", new DiscoveryCancellation()).get("shared_mod"));
    }

    @Test
    void reportsFileNameOnUnsupportedMetadata(@TempDir Path directory) throws Exception {
        Path path = jar(directory, TOML, Map.of("META-INF/services/cpw.mods.modlauncher.api.ITransformationService", json("example.Service")));
        var failure = assertThrows(IOException.class, () -> JarMetadata.inspect(path, "4.0.45", new DiscoveryCancellation()));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(path.getFileName().toString()));
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
