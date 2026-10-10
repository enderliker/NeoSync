/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

/** Bounded metadata inspection for NeoForge JARs and declared JarJar entries; no classes are loaded. */
public final class JarMetadata {
    private static final int MAX_ENTRY = 512 * 1024;
    private static final int MAX_METADATA = 4 * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 65534;
    private static final int MAX_ALL_ENTRIES = 131072;
    private static final int MAX_NESTED_JARS = 32;
    private static final long MAX_NESTED_JAR_BYTES = 64L * 1024 * 1024;
    private static final long MAX_NESTED_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final long MAX_NESTED_EXPANSION = 256L * 1024 * 1024;
    private static final int MAX_NESTED_DEPTH = 4;
    private static final String MOD_METADATA = "META-INF/neoforge.mods.toml";
    private static final String JARJAR_PREFIX = "META-INF/jarjar/";
    private static final String JARJAR_METADATA = JARJAR_PREFIX + "metadata.json";
    private static final String LANGUAGE_SERVICE = "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader";
    private static final Set<String> LIBRARY_SERVICES = Set.of(
            "META-INF/services/net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper",
            "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator");

    private record Archive(List<SyncManifest.Mod> mods, Map<String, ZipEntry> nested, long expansion, int entries) {}

    private static final class Budget {
        int entries;
        int jars;
        long bytes;
        long expansion;
    }

    private JarMetadata() {}

    public static ModEnvironment environment(Path path, DiscoveryCancellation token) throws IOException {
        var before = ArtifactFiles.fingerprint(path, token);
        checkDirectory(path);
        ModEnvironment result = ModEnvironment.UNKNOWN;
        try (var zip = new ZipFile(path.toFile())) {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(read(zip, MOD_METADATA, true, token))).toString();
            boundToml(text);
            UnmodifiableConfig config = new TomlParser().parse(new StringReader(text));
            Object rawMods = config.get("mods");
            if (!(rawMods instanceof List<?> mods) || mods.isEmpty() || mods.size() > 64) throw new IOException("Invalid mod list for environment detection.");
            boolean client = false;
            boolean server = false;
            boolean unknown = false;
            for (Object value : mods) {
                if (!(value instanceof UnmodifiableConfig mod)) throw new IOException("Invalid mod metadata.");
                String id = string(mod, "modId", "");
                Object declaration = config.get(List.of("modproperties", id, "neosyncSide"));
                if (declaration == null) {
                    unknown = true;
                    continue;
                }
                if (!(declaration instanceof String side) || !Set.of("CLIENT", "BOTH", "SERVER").contains(side))
                    throw new IOException("Invalid NeoSync environment declaration.");
                client |= !side.equals("SERVER");
                server |= !side.equals("CLIENT");
            }
            if (!unknown) result = client ? (server ? ModEnvironment.BOTH : ModEnvironment.CLIENT) : ModEnvironment.SERVER;
        }
        if (!before.equals(ArtifactFiles.fingerprint(path, token))) throw new IOException("The artifact changed during environment inspection.");
        return result;
    }

    public static List<SyncManifest.Mod> inspect(Path path, String javaFmlVersion, DiscoveryCancellation token) throws IOException {
        var before = ArtifactFiles.fingerprint(path, token);
        var mods = inspectContents(path, javaFmlVersion, token);
        if (!before.equals(ArtifactFiles.fingerprint(path, token))) throw new IOException("The artifact changed during metadata inspection.");
        return mods;
    }

    public static Map<String, String> activeMods(List<Path> paths, String javaFmlVersion, DiscoveryCancellation token) throws IOException {
        var temporary = new ArrayList<Path>();
        var extracted = new HashMap<String, Path>();
        var fingerprints = new HashMap<Path, ArtifactFiles.Fingerprint>();
        long[] copied = { 0 };
        try {
            for (Path path : paths) {
                fingerprints.put(path, ArtifactFiles.fingerprint(path, token));
                inspect(path, javaFmlVersion, token);
            }
            var selected = net.neoforged.jarjar.selection.JarSelector.detectAndSelect(paths,
                    (path, entry) -> {
                        try (var zip = new ZipFile(path.toFile())) {
                            String name = entry.toString().replace('\\', '/');
                            return zip.getEntry(name) == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(read(zip, name, true, token)));
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }, (path, entry) -> {
                        String name = entry.toString().replace('\\', '/');
                        String key = path + "|" + name;
                        if (extracted.containsKey(key)) return Optional.of(extracted.get(key));
                        try (var zip = new ZipFile(path.toFile())) {
                            ZipEntry archive = zip.getEntry(name);
                            if (!nestedPath(name) || archive == null || archive.getSize() < 1 || archive.getSize() > MAX_NESTED_JAR_BYTES
                                    || (copied[0] += archive.getSize()) > SyncManifest.MAX_TOTAL_BYTES)
                                throw new IOException("The selected JarJar inventory exceeds its extraction limit.");
                            Path target = Files.createTempFile("neosync-selection-", ".jar");
                            temporary.add(target);
                            copyNested(zip, archive, target, token);
                            extracted.put(key, target);
                            return Optional.of(target);
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }, Path::toString, failures -> new IOException("The selected client artifacts have incompatible JarJar version ranges."));
            var active = new HashMap<String, String>();
            var modules = new HashMap<String, List<SyncManifest.Mod>>();
            var moduleFiles = new HashMap<String, Path>();
            var activePaths = new ArrayList<>(paths);
            activePaths.addAll(selected);
            for (Path path : activePaths.stream().distinct().toList()) {
                try (var zip = new ZipFile(path.toFile())) {
                    if (zip.getEntry(MOD_METADATA) == null) continue;
                    String version = new Manifest(new ByteArrayInputStream(read(zip, "META-INF/MANIFEST.MF", false, token))).getMainAttributes().getValue("Implementation-Version");
                    if (libraryArchive(zip, token)) continue;
                    var mods = parseMods(zip, version, javaFmlVersion, token);
                    String module = mods.getFirst().id();
                    var previous = modules.get(module);
                    int comparison = previous == null ? 1 : new DefaultArtifactVersion(mods.getFirst().version()).compareTo(new DefaultArtifactVersion(previous.getFirst().version()));
                    // FML groups mod files by their first mod ID and retains the newest version.
                    if (comparison == 0 && !ArtifactFiles.fingerprint(path, token).sha256().equals(ArtifactFiles.fingerprint(moduleFiles.get(module), token).sha256()))
                        throw new IOException("The selected client artifacts contain ambiguous equal-version modules: " + module);
                    if (comparison > 0) {
                        modules.put(module, mods);
                        moduleFiles.put(module, path);
                    }
                }
            }
            for (var mods : modules.values()) for (var mod : mods) {
                if (active.putIfAbsent(mod.id(), mod.version()) != null)
                    throw new IOException("The selected client artifacts load duplicate mod IDs: " + mod.id());
            }
            for (Path path : paths) {
                if (!fingerprints.get(path).equals(ArtifactFiles.fingerprint(path, token)))
                    throw new IOException("A selected artifact changed during JarJar selection.");
            }
            return Map.copyOf(active);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        } finally {
            for (Path path : temporary) Files.deleteIfExists(path);
        }
    }

    public static void verify(Path path, SyncManifest.Artifact expected, String javaFmlVersion, DiscoveryCancellation token) throws IOException {
        var before = ArtifactFiles.fingerprint(path, token);
        if (!before.sha256().equals(expected.sha256()) || before.size() != expected.size()) throw new IOException("The artifact changed before metadata verification.");
        var actual = inspectContents(path, javaFmlVersion, token);
        var reviewed = new HashMap<String, SyncManifest.Mod>();
        for (var mod : expected.mods()) {
            if (reviewed.putIfAbsent(mod.id(), mod) != null) throw new IOException("The reviewed manifest repeats a mod ID.");
        }
        if (actual.size() != reviewed.size()) throw new IOException("The JAR's mod IDs or versions differ from the reviewed manifest.");
        for (var mod : actual) {
            var expectedMod = reviewed.get(mod.id());
            if (expectedMod == null || !mod.version().equals(expectedMod.version()) || !mod.displayName().equals(expectedMod.displayName())
                    || expectedMod.embedded() && !mod.embedded())
                throw new IOException("The JAR's mod IDs, versions, or names differ from the reviewed manifest.");
            if (!dependencyKeys(mod).equals(dependencyKeys(expectedMod)))
                throw new IOException("The JAR's client dependencies differ from the reviewed manifest: " + mod.id());
        }
        if (!before.equals(ArtifactFiles.fingerprint(path, token))) throw new IOException("The artifact changed during metadata verification.");
    }

    private static List<String> dependencyKeys(SyncManifest.Mod mod) {
        return mod.dependencies().stream().map(dependency -> dependency.id() + "|" + dependency.type() + "|" + SyncManifest.versionSpec(dependency.range())).sorted().toList();
    }

    private static List<SyncManifest.Mod> inspectContents(Path path, String javaFmlVersion, DiscoveryCancellation token) throws IOException {
        try {
            var mods = inspectTree(path, javaFmlVersion, token, 0, new Budget());
            var ids = new HashSet<String>();
            for (var mod : mods) if (!ids.add(mod.id())) throw new IOException("The JAR contains duplicate top-level or embedded mod IDs.");
            if (mods.size() > 8192) throw new IOException("The JAR contains too many mods.");
            return List.copyOf(mods);
        } catch (IOException e) {
            throw new IOException(path.getFileName() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException(path.getFileName() + ": The JAR metadata is invalid or unsupported.", e);
        }
    }

    private static List<SyncManifest.Mod> inspectTree(Path path, String javaFmlVersion, DiscoveryCancellation token, int depth, Budget budget) throws IOException {
        int count = checkDirectory(path);
        try (var zip = new ZipFile(path.toFile())) {
            var outer = inspectArchive(zip, count, depth == 0, javaFmlVersion, token);
            budget.entries += outer.entries();
            if (depth > 0) budget.expansion += outer.expansion();
            if (budget.entries > MAX_ALL_ENTRIES || budget.expansion > MAX_NESTED_EXPANSION)
                throw new IOException("The nested JARs exceed the archive expansion limit.");
            var nestedPaths = declaredNested(zip, outer.nested(), token);
            var mods = new ArrayList<>(outer.mods());
            for (String nestedPath : nestedPaths) {
                token.check();
                if (depth >= MAX_NESTED_DEPTH || ++budget.jars > MAX_NESTED_JARS)
                    throw new IOException("The declared nested JARs exceed the depth or count limit.");
                ZipEntry entry = outer.nested().get(nestedPath);
                if (entry.getSize() < 1 || entry.getSize() > MAX_NESTED_JAR_BYTES
                        || (budget.bytes += entry.getSize()) > MAX_NESTED_TOTAL_BYTES)
                    throw new IOException("The declared nested JARs exceed the size limit.");
                Path temporary = Files.createTempFile("neosync-jarjar-", ".jar");
                try {
                    copyNested(zip, entry, temporary, token);
                    for (var mod : inspectTree(temporary, javaFmlVersion, token, depth + 1, budget))
                        mods.add(new SyncManifest.Mod(mod.id(), mod.version(), mod.displayName(), mod.dependencies(), true));
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            return List.copyOf(mods);
        }
    }

    private static Archive inspectArchive(ZipFile zip, int count, boolean outer, String javaFmlVersion, DiscoveryCancellation token) throws IOException {
        boolean library = libraryArchive(zip, token);
        var names = new HashSet<String>();
        var nested = new HashMap<String, ZipEntry>();
        long expansion = 0;
        int metadata = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            token.check();
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            validateEntryName(name, lower, names);
            long size = entry.getSize();
            long limit = outer ? SyncManifest.MAX_FILE_BYTES : MAX_NESTED_EXPANSION;
            if (size < 0 || size > limit || expansion > limit - size) throw new IOException("The JAR exceeds the archive expansion limit.");
            expansion += size;
            boolean nestedJar = false;
            if (lower.endsWith(".jar")) {
                if (!nestedPath(name))
                    throw new IOException("The JAR contains an undeclared or deeply nested archive.");
                nested.put(name, entry);
                nestedJar = true;
            }
            if (lower.startsWith("meta-inf/jarjar/") && !nestedJar
                    && !name.equals(JARJAR_PREFIX) && !name.equals(JARJAR_METADATA))
                throw new IOException("The JAR contains an unsupported JarJar entry.");
            if (lower.startsWith("meta-inf/services/net.neoforged.") || lower.startsWith("meta-inf/services/cpw.mods.")
                    || lower.startsWith("meta-inf/services/net.minecraftforge.")) {
                boolean inactiveForge = lower.startsWith("meta-inf/services/net.minecraftforge.") && zip.getEntry(LANGUAGE_SERVICE) != null;
                boolean libraryService = outer && library && zip.getEntry(JARJAR_METADATA) != null && LIBRARY_SERVICES.contains(name);
                if (!inactiveForge && !name.equals(LANGUAGE_SERVICE) && !libraryService)
                    throw new IOException("Unsupported loader service provider: " + name);
                String providers = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(read(zip, inactiveForge ? LANGUAGE_SERVICE : name, true, token))).toString();
                for (String line : providers.split("\\R")) {
                    String provider = line.split("#", 2)[0].strip();
                    if (!provider.isEmpty() && !provider.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))
                        throw new IOException("Invalid NeoForge service provider.");
                }
            }
            if (lower.endsWith("neosync-profile.json")) throw new IOException("A JAR cannot contain NeoSync profile records.");
            if (lower.equals("meta-inf/neoforge.mods.toml") && !name.equals(MOD_METADATA)
                    || lower.equals("meta-inf/manifest.mf") && !name.equals("META-INF/MANIFEST.MF"))
                throw new IOException("The JAR has a noncanonical metadata entry.");
            if (lower.startsWith("meta-inf/") && !entry.isDirectory() && !lower.endsWith(".class") && !nestedJar) {
                if (size > MAX_ENTRY || metadata > MAX_METADATA - size) throw new IOException("The JAR exceeds the metadata size limit.");
                metadata += (int) size;
            }
        }
        if (names.size() != count) throw new IOException("The JAR directory is inconsistent.");
        var attributes = new Manifest(new ByteArrayInputStream(read(zip, "META-INF/MANIFEST.MF", false, token))).getMainAttributes();
        String type = attributes.getValue("FMLModType");
        if (attributes.getValue("Class-Path") != null)
            throw new IOException("Unsupported JAR loading arrangement.");
        // FML treats LIBRARY containers as services, even when they carry a copy of their nested mod metadata.
        if (library) return new Archive(List.of(), Map.copyOf(nested), expansion, count);
        boolean modMetadata = zip.getEntry(MOD_METADATA) != null;
        if (!modMetadata) {
            if (zip.getEntry("fabric.mod.json") != null || zip.getEntry("quilt.mod.json") != null || zip.getEntry("META-INF/mods.toml") != null)
                throw new IOException("The JAR is missing NeoForge mod metadata.");
            if (type != null && !Set.of("LIBRARY", "GAMELIBRARY").contains(type) || outer && !"LIBRARY".equals(type))
                throw new IOException("Unsupported JAR loading arrangement.");
            return new Archive(List.of(), Map.copyOf(nested), expansion, count);
        }
        if (type != null && !type.equals("MOD")) throw new IOException("Unsupported JAR loading arrangement.");
        return new Archive(parseMods(zip, attributes.getValue("Implementation-Version"), javaFmlVersion, token), Map.copyOf(nested), expansion, count);
    }

    private static boolean libraryArchive(ZipFile zip, DiscoveryCancellation token) throws IOException {
        return "LIBRARY".equals(new Manifest(new ByteArrayInputStream(read(zip, "META-INF/MANIFEST.MF", false, token)))
                .getMainAttributes().getValue("FMLModType"));
    }

    private static void validateEntryName(String name, String lower, Set<String> names) throws IOException {
        if (name.isEmpty() || name.length() > 1024 || name.startsWith("/") || name.contains("\\") || name.codePoints().anyMatch(Character::isISOControl)
                || !names.add(lower.startsWith("assets/") || lower.startsWith("data/") ? name : lower) || names.size() > MAX_ARCHIVE_ENTRIES)
            throw new IOException("The JAR contains ambiguous or unsafe entry names.");
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(".") || parts[i].equals("..") || parts[i].isEmpty() && i != parts.length - 1)
                throw new IOException("The JAR contains ambiguous or unsafe entry names.");
        }
    }

    private static List<String> declaredNested(ZipFile zip, Map<String, ZipEntry> nested, DiscoveryCancellation token) throws IOException {
        var active = new HashMap<>(nested);
        if (zip.getEntry(MOD_METADATA) != null && zip.getEntry("fabric.mod.json") != null) {
            var fabric = SyncJson.parse(read(zip, "fabric.mod.json", true, token), MAX_ENTRY).getAsJsonObject();
            if (fabric.has("jars")) {
                for (var value : SyncJson.array(fabric.get("jars"), 0, MAX_NESTED_JARS)) {
                    var item = SyncJson.object(value, Set.of("file"), Set.of());
                    String path = SyncJson.string(item.get("file"), 256);
                    if (!nestedPath(path)) throw new IOException("Invalid inactive Fabric archive path.");
                    active.remove(path);
                }
            }
        }
        if (zip.getEntry(JARJAR_METADATA) == null) {
            if (!active.isEmpty()) throw new IOException("The JAR contains nested archives without JarJar metadata.");
            return List.of();
        }
        var root = SyncJson.object(SyncJson.parse(read(zip, JARJAR_METADATA, true, token), MAX_ENTRY), Set.of("jars"), Set.of());
        var result = new ArrayList<String>();
        var identities = new HashSet<String>();
        var paths = new HashSet<String>();
        for (var value : SyncJson.array(root.get("jars"), 0, MAX_NESTED_JARS)) {
            var item = SyncJson.object(value, Set.of("identifier", "version", "path"), Set.of("isObfuscated"));
            var identifier = SyncJson.object(item.get("identifier"), Set.of("group", "artifact"), Set.of());
            String group = identifier.get("group").isJsonPrimitive() && identifier.get("group").getAsJsonPrimitive().isString()
                    && identifier.get("group").getAsString().isEmpty() ? "" : SyncJson.matching(identifier.get("group"), 128, "[A-Za-z0-9][A-Za-z0-9._+-]*");
            String artifact = SyncJson.matching(identifier.get("artifact"), 128, "[A-Za-z0-9][A-Za-z0-9._+-]*");
            if (!identities.add(group + ":" + artifact)) throw new IOException("JarJar metadata repeats an artifact identifier.");
            var version = SyncJson.object(item.get("version"), Set.of("range", "artifactVersion"), Set.of());
            VersionRange declaredRange = range(SyncJson.string(version.get("range"), 128));
            String artifactVersion = SyncJson.string(version.get("artifactVersion"), 128);
            if (!declaredRange.containsVersion(new DefaultArtifactVersion(artifactVersion)))
                throw new IOException("JarJar metadata has an inconsistent artifact version.");
            if (item.has("isObfuscated")) SyncJson.bool(item.get("isObfuscated"));
            String path = SyncJson.string(item.get("path"), 256);
            if (!nestedPath(path) || !nested.containsKey(path))
                throw new IOException("JarJar metadata has an undeclared or missing archive path.");
            active.remove(path);
            if (paths.add(path)) result.add(path);
        }
        if (!active.isEmpty()) throw new IOException("The JAR contains an archive absent from JarJar metadata.");
        return List.copyOf(result);
    }

    private static boolean nestedPath(String path) {
        int prefix = path.startsWith(JARJAR_PREFIX) ? JARJAR_PREFIX.length() : path.startsWith("META-INF/jars/") ? "META-INF/jars/".length() : -1;
        return prefix >= 0 && path.substring(prefix).length() <= 128 && path.substring(prefix).matches(SyncManifest.FILE_PATTERN);
    }

    private static void copyNested(ZipFile zip, ZipEntry entry, Path temporary, DiscoveryCancellation token) throws IOException {
        var crc = new CRC32();
        long copied = 0;
        try (var input = zip.getInputStream(entry); var output = Files.newOutputStream(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) {
                token.check();
                copied += length;
                if (copied > entry.getSize() || copied > MAX_NESTED_JAR_BYTES) throw new IOException("A nested JAR expanded beyond its declared size.");
                output.write(buffer, 0, length);
                crc.update(buffer, 0, length);
            }
        }
        if (copied != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("A nested JAR failed its archive integrity check.");
    }

    private static List<SyncManifest.Mod> parseMods(ZipFile zip, String jarVersion, String javaFmlVersion, DiscoveryCancellation token) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(read(zip, MOD_METADATA, true, token))).toString();
        boundToml(text);
        UnmodifiableConfig config = new TomlParser().parse(new StringReader(text));
        String language = string(config, "modLoader", "");
        if (!language.matches("[a-z][a-z0-9_]{1,63}")) throw new IOException("Invalid NeoForge language loader: " + language);
        VersionRange languageRange = range(string(config, "loaderVersion", ""));
        if (language.equals("javafml") && !languageRange.containsVersion(new DefaultArtifactVersion(javaFmlVersion)))
            throw new IOException("The mod requires a different Java FML language loader.");
        if (string(config, "license", "").isBlank()) throw new IOException("The mod metadata has no license.");
        if (config.contains("services")) throw new IOException("Custom service requirements are not supported.");
        Object rawMods = config.get("mods");
        if (!(rawMods instanceof List<?> list) || list.isEmpty() || list.size() > 64) throw new IOException("The JAR has an invalid mod list.");
        var result = new ArrayList<SyncManifest.Mod>();
        var ids = new HashSet<String>();
        for (Object value : list) {
            if (!(value instanceof UnmodifiableConfig mod)) throw new IOException("Invalid mod metadata.");
            String id = string(mod, "modId", "");
            String version = string(mod, "version", "");
            if (version.equals("${file.jarVersion}")) version = jarVersion;
            String name = string(mod, "displayName", id);
            if (!id.matches("[a-z][a-z0-9_]{1,63}") || !ids.add(id)
                    || !validDisplay(version) || version.contains("${") || !validDisplay(name) || name.contains("${"))
                throw new IOException("Unsupported or duplicate mod identity metadata.");
            Object features = config.get(List.of("features", id));
            if (features != null) {
                if (!(features instanceof UnmodifiableConfig featureConfig) || featureConfig.valueMap().keySet().stream().anyMatch(key -> !key.equals("javaVersion"))
                        || !range(string(featureConfig, "javaVersion", "")).containsVersion(new DefaultArtifactVersion("21")))
                    throw new IOException("Unsupported mod feature requirements: " + id);
            }
            result.add(new SyncManifest.Mod(id, version, name, dependencies(config, id)));
        }
        return List.copyOf(result);
    }

    private static boolean validDisplay(String value) {
        return value != null && !value.isBlank() && value.length() <= 128 && value.codePoints().noneMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.SURROGATE || c == 0xA7);
    }

    private static List<SyncManifest.Dependency> dependencies(UnmodifiableConfig config, String id) throws IOException {
        Object dependencies = config.get(List.of("dependencies", id));
        if (dependencies == null) return List.of();
        if (!(dependencies instanceof List<?> list) || list.size() > 256) throw new IOException("Invalid dependency metadata.");
        var result = new ArrayList<SyncManifest.Dependency>();
        for (Object value : list) {
            if (!(value instanceof UnmodifiableConfig dependency)) throw new IOException("Invalid dependency entry.");
            String side = string(dependency, "side", "BOTH");
            if (!Set.of("BOTH", "CLIENT", "SERVER").contains(side)) throw new IOException("Unsupported dependency side.");
            String type = string(dependency, "type", "required").toLowerCase(Locale.ROOT);
            if (!Set.of("required", "optional", "incompatible", "discouraged").contains(type)) throw new IOException("Unsupported dependency type.");
            if (!side.equals("SERVER")) {
                String dependencyId = string(dependency, "modId", "");
                if (!dependencyId.matches("[a-z][a-z0-9_]{1,63}")) throw new IOException("Invalid dependency mod ID.");
                result.add(new SyncManifest.Dependency(dependencyId, range(string(dependency, "versionRange", "[0,)")), type));
            }
        }
        return List.copyOf(result);
    }

    private static VersionRange range(String value) throws IOException {
        try {
            return VersionRange.createFromVersionSpec(value.equals("*") ? "[0,)" : value);
        } catch (org.apache.maven.artifact.versioning.InvalidVersionSpecificationException e) {
            throw new IOException("Invalid metadata version range.", e);
        }
    }

    private static String string(UnmodifiableConfig config, String key, String fallback) throws IOException {
        Object value = config.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String string) || string.length() > MAX_ENTRY) throw new IOException("Invalid metadata field: " + key);
        return string;
    }

    private static byte[] read(ZipFile zip, String name, boolean required, DiscoveryCancellation token) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null) {
            if (required) throw new IOException("The JAR is missing NeoForge mod metadata.");
            return new byte[0];
        }
        if (entry.isDirectory() || entry.getSize() > MAX_ENTRY) throw new IOException("The JAR metadata entry is too large.");
        token.check();
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_ENTRY + 1);
            token.check();
            if (bytes.length > MAX_ENTRY || bytes.length != entry.getSize()) throw new IOException("The JAR metadata expansion is invalid.");
            return bytes;
        }
    }

    private static int checkDirectory(Path path) throws IOException {
        // Bound the central directory before ZipFile allocates its entry index. ZIP64 is unnecessary for the MVP limits.
        try (var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int length = (int) Math.min(file.size(), 65557);
            var tail = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
            file.position(file.size() - length);
            while (tail.hasRemaining() && file.read(tail) >= 0) {}
            for (int i = length - 22; i >= 0; i--) {
                if (tail.getInt(i) != 0x06054b50 || i + 22 + Short.toUnsignedInt(tail.getShort(i + 20)) != length) continue;
                int count = Short.toUnsignedInt(tail.getShort(i + 10));
                long directorySize = Integer.toUnsignedLong(tail.getInt(i + 12));
                long directoryOffset = Integer.toUnsignedLong(tail.getInt(i + 16));
                if (count < 1 || count > MAX_ARCHIVE_ENTRIES || tail.getShort(i + 4) != 0 || tail.getShort(i + 6) != 0
                        || Short.toUnsignedInt(tail.getShort(i + 8)) != count || directorySize > 8 * 1024 * 1024
                        || directoryOffset + directorySize != file.size() - length + i)
                    throw new IOException("Unsupported or oversized JAR directory.");
                return count;
            }
        }
        throw new IOException("The artifact is not a supported JAR archive.");
    }

    private static void boundToml(String text) throws IOException {
        int depth = 0;
        int dots = 0;
        char quote = 0;
        boolean triple = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                    continue;
                }
                if (c == quote && (!triple || text.startsWith(String.valueOf(quote).repeat(3), i))) {
                    if (triple) i += 2;
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                triple = text.startsWith(String.valueOf(c).repeat(3), i);
                if (triple) i += 2;
            } else if (c == '#') {
                while (i + 1 < text.length() && text.charAt(i + 1) != '\n') i++;
            } else if (c == '[' || c == '{') {
                if (++depth > 16) throw new IOException("The mod metadata is nested too deeply.");
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == '.') {
                if (++dots > 16) throw new IOException("The mod metadata key is nested too deeply.");
            } else if (c == '\n' || c == '=') dots = 0;
        }
    }
}
