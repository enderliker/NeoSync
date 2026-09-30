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
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

/** Bounded metadata inspection for javafml JARs and declared NeoForge JarJar entries; no classes are loaded. */
public final class JarMetadata {
    private static final int MAX_ENTRY = 512 * 1024;
    private static final int MAX_METADATA = 4 * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 32768;
    private static final int MAX_ALL_ENTRIES = 65536;
    private static final int MAX_NESTED_JARS = 32;
    private static final long MAX_NESTED_JAR_BYTES = 64L * 1024 * 1024;
    private static final long MAX_NESTED_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final long MAX_NESTED_EXPANSION = 256L * 1024 * 1024;
    private static final String MOD_METADATA = "META-INF/neoforge.mods.toml";
    private static final String JARJAR_PREFIX = "META-INF/jarjar/";
    private static final String JARJAR_METADATA = JARJAR_PREFIX + "metadata.json";

    private record Archive(List<SyncManifest.Mod> mods, Map<String, ZipEntry> nested, long expansion, int entries) {}

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
            if (expectedMod == null || !mod.version().equals(expectedMod.version()) || !mod.displayName().equals(expectedMod.displayName()))
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
        int count = checkDirectory(path);
        try (var zip = new ZipFile(path.toFile())) {
            var outer = inspectArchive(zip, count, true, javaFmlVersion, token);
            var nestedPaths = declaredNested(zip, outer.nested(), token);
            var mods = new ArrayList<>(outer.mods());
            long nestedBytes = 0;
            long nestedExpansion = 0;
            int allEntries = outer.entries();
            for (String nestedPath : nestedPaths) {
                token.check();
                ZipEntry entry = outer.nested().get(nestedPath);
                if (entry.getSize() < 1 || entry.getSize() > MAX_NESTED_JAR_BYTES
                        || (nestedBytes += entry.getSize()) > MAX_NESTED_TOTAL_BYTES)
                    throw new IOException("The declared nested JARs exceed the size limit.");
                Path temporary = Files.createTempFile("neosync-jarjar-", ".jar");
                try {
                    copyNested(zip, entry, temporary, token);
                    int nestedCount = checkDirectory(temporary);
                    try (var nested = new ZipFile(temporary.toFile())) {
                        var inspected = inspectArchive(nested, nestedCount, false, javaFmlVersion, token);
                        allEntries += inspected.entries();
                        nestedExpansion += inspected.expansion();
                        if (allEntries > MAX_ALL_ENTRIES || nestedExpansion > MAX_NESTED_EXPANSION)
                            throw new IOException("The nested JARs exceed the archive expansion limit.");
                        mods.addAll(inspected.mods());
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            var ids = new HashSet<String>();
            for (var mod : mods) if (!ids.add(mod.id())) throw new IOException("The JAR contains duplicate top-level or embedded mod IDs.");
            if (mods.size() > 8192) throw new IOException("The JAR contains too many mods.");
            return List.copyOf(mods);
        } catch (RuntimeException e) {
            throw new IOException("The JAR metadata is invalid or unsupported.", e);
        }
    }

    private static Archive inspectArchive(ZipFile zip, int count, boolean outer, String javaFmlVersion, DiscoveryCancellation token) throws IOException {
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
                String fileName = name.startsWith(JARJAR_PREFIX) ? name.substring(JARJAR_PREFIX.length()) : "";
                if (!outer || fileName.length() > 128 || !fileName.matches(SyncManifest.FILE_PATTERN))
                    throw new IOException("The JAR contains an undeclared or deeply nested archive.");
                nested.put(name, entry);
                nestedJar = true;
            }
            if (lower.startsWith("meta-inf/jarjar/") && !nestedJar
                    && (!outer || !name.equals(JARJAR_PREFIX) && !name.equals(JARJAR_METADATA)))
                throw new IOException("The JAR contains an unsupported JarJar entry.");
            if (lower.startsWith("meta-inf/services/net.neoforged.") || lower.startsWith("meta-inf/services/cpw.mods.")
                    || lower.startsWith("meta-inf/services/net.minecraftforge.") || lower.equals("meta-inf/mods.toml")
                    || lower.equals("fabric.mod.json") || lower.equals("quilt.mod.json") || lower.endsWith("neosync-profile.json"))
                throw new IOException("Alternative loaders and loader service providers are not supported.");
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
        if (attributes.getValue("Class-Path") != null || Boolean.parseBoolean(attributes.getValue("Multi-Release")))
            throw new IOException("Unsupported JAR loading arrangement.");
        if (!outer && "GAMELIBRARY".equals(type)) {
            if (zip.getEntry(MOD_METADATA) != null) throw new IOException("A declared game library also contains mod metadata.");
            return new Archive(List.of(), Map.of(), expansion, count);
        }
        if (type != null && !type.equals("MOD")) throw new IOException("Unsupported JAR loading arrangement.");
        return new Archive(parseMods(zip, attributes.getValue("Implementation-Version"), javaFmlVersion, token), Map.copyOf(nested), expansion, count);
    }

    private static void validateEntryName(String name, String lower, Set<String> names) throws IOException {
        if (name.isEmpty() || name.length() > 1024 || name.startsWith("/") || name.contains("\\") || name.codePoints().anyMatch(Character::isISOControl)
                || !names.add(lower) || names.size() > MAX_ARCHIVE_ENTRIES)
            throw new IOException("The JAR contains ambiguous or unsafe entry names.");
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(".") || parts[i].equals("..") || parts[i].isEmpty() && i != parts.length - 1)
                throw new IOException("The JAR contains ambiguous or unsafe entry names.");
        }
    }

    private static List<String> declaredNested(ZipFile zip, Map<String, ZipEntry> nested, DiscoveryCancellation token) throws IOException {
        if (zip.getEntry(JARJAR_METADATA) == null) {
            if (!nested.isEmpty()) throw new IOException("The JAR contains nested archives without JarJar metadata.");
            return List.of();
        }
        var root = SyncJson.object(SyncJson.parse(read(zip, JARJAR_METADATA, true, token), MAX_ENTRY), Set.of("jars"), Set.of());
        var result = new ArrayList<String>();
        var identities = new HashSet<String>();
        var paths = new HashSet<String>();
        for (var value : SyncJson.array(root.get("jars"), 0, MAX_NESTED_JARS)) {
            var item = SyncJson.object(value, Set.of("identifier", "version", "path", "isObfuscated"), Set.of());
            var identifier = SyncJson.object(item.get("identifier"), Set.of("group", "artifact"), Set.of());
            String group = SyncJson.matching(identifier.get("group"), 128, "[A-Za-z0-9][A-Za-z0-9._+-]*");
            String artifact = SyncJson.matching(identifier.get("artifact"), 128, "[A-Za-z0-9][A-Za-z0-9._+-]*");
            if (!identities.add(group + ":" + artifact)) throw new IOException("JarJar metadata repeats an artifact identifier.");
            var version = SyncJson.object(item.get("version"), Set.of("range", "artifactVersion"), Set.of());
            VersionRange declaredRange = range(SyncJson.string(version.get("range"), 128));
            String artifactVersion = SyncJson.string(version.get("artifactVersion"), 128);
            if (!declaredRange.containsVersion(new DefaultArtifactVersion(artifactVersion)))
                throw new IOException("JarJar metadata has an inconsistent artifact version.");
            SyncJson.bool(item.get("isObfuscated"));
            String path = SyncJson.string(item.get("path"), 256);
            String fileName = path.startsWith(JARJAR_PREFIX) ? path.substring(JARJAR_PREFIX.length()) : "";
            if (fileName.length() > 128 || !fileName.matches(SyncManifest.FILE_PATTERN) || !nested.containsKey(path) || !paths.add(path))
                throw new IOException("JarJar metadata has an undeclared, missing, or duplicate archive path.");
            result.add(path);
        }
        if (paths.size() != nested.size()) throw new IOException("The JAR contains an archive absent from JarJar metadata.");
        return List.copyOf(result);
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
        if (!"javafml".equals(string(config, "modLoader", ""))) throw new IOException("Only javafml mod artifacts are supported.");
        if (!range(string(config, "loaderVersion", "")).containsVersion(new DefaultArtifactVersion(javaFmlVersion)))
            throw new IOException("The mod requires a different Java FML language loader.");
        if (string(config, "license", "").isBlank()) throw new IOException("The mod metadata has no license.");
        if (config.contains("features") || config.contains("services")) throw new IOException("Custom feature and service requirements are not supported.");
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
            return VersionRange.createFromVersionSpec(value);
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
