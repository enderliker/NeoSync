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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

/** Bounded metadata validation for top-level javafml JARs; no classes or initializers are loaded. */
public final class JarMetadata {
    private static final int MAX_ENTRY = 512 * 1024;
    private static final int MAX_METADATA = 4 * 1024 * 1024;

    private JarMetadata() {}

    public static void verify(Path path, SyncManifest.Artifact expected, String javaFmlVersion, DiscoveryCancellation token) throws IOException {
        var before = ArtifactFiles.fingerprint(path, token);
        if (!before.sha256().equals(expected.sha256()) || before.size() != expected.size()) throw new IOException("The artifact changed before metadata verification.");
        int count = checkDirectory(path);
        try (var zip = new ZipFile(path.toFile())) {
            var names = new HashSet<String>();
            long expansion = 0;
            int metadata = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                token.check();
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.length() > 1024 || name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0
                        || List.of(name.split("/")).stream().anyMatch(part -> part.equals("..") || part.equals("."))
                        || !names.add(name.toLowerCase(Locale.ROOT)) || names.size() > 10000) {
                    throw new IOException("The JAR contains ambiguous or unsafe entry names.");
                }
                if (entry.getSize() < 0 || entry.getSize() > SyncManifest.MAX_FILE_BYTES
                        || (expansion += entry.getSize()) > SyncManifest.MAX_FILE_BYTES)
                    throw new IOException("The JAR exceeds the archive expansion limit.");
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jar") || lower.startsWith("meta-inf/jarjar/") || lower.startsWith("meta-inf/services/")
                        || lower.equals("meta-inf/mods.toml") || lower.equals("fabric.mod.json") || lower.endsWith("neosync-profile.json")) {
                    throw new IOException("Nested archives, alternative loaders, and service-provider artifacts are not supported by this installation MVP.");
                }
                if (lower.startsWith("meta-inf/") && !entry.isDirectory() && !lower.endsWith(".class")) {
                    if (entry.getSize() > MAX_ENTRY || (metadata += (int) entry.getSize()) > MAX_METADATA) throw new IOException("The JAR exceeds the metadata size limit.");
                }
            }
            if (names.size() != count) throw new IOException("The JAR directory is inconsistent.");
            var attributes = new Manifest(new ByteArrayInputStream(read(zip, "META-INF/MANIFEST.MF", false, token))).getMainAttributes();
            String type = attributes.getValue("FMLModType");
            if (type != null && !type.equals("MOD") || attributes.getValue("Class-Path") != null
                    || Boolean.parseBoolean(attributes.getValue("Multi-Release")))
                throw new IOException("Unsupported JAR loading arrangement.");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(read(zip, "META-INF/neoforge.mods.toml", true, token))).toString();
            boundToml(text);
            UnmodifiableConfig config = new TomlParser().parse(new StringReader(text));
            if (!"javafml".equals(string(config, "modLoader", ""))) throw new IOException("Only javafml mod artifacts are supported by this installation MVP.");
            if (!range(string(config, "loaderVersion", "")).containsVersion(new DefaultArtifactVersion(javaFmlVersion))) {
                throw new IOException("The mod requires a different Java FML language loader.");
            }
            if (string(config, "license", "").isBlank()) throw new IOException("The mod metadata has no license.");
            if (config.contains("features") || config.contains("services")) throw new IOException("Custom feature and service requirements are not supported by this installation MVP.");
            var declared = new HashMap<String, String>();
            Object mods = config.get("mods");
            if (!(mods instanceof List<?> list) || list.isEmpty() || list.size() > 64) throw new IOException("The JAR has an invalid mod list.");
            for (Object value : list) {
                if (!(value instanceof UnmodifiableConfig mod)) throw new IOException("Invalid mod metadata.");
                String id = string(mod, "modId", "");
                String version = string(mod, "version", "");
                if (version.equals("${file.jarVersion}")) version = attributes.getValue("Implementation-Version");
                if (version == null || version.isBlank() || version.contains("${") || declared.put(id, version) != null) throw new IOException("Unsupported or duplicate mod version metadata.");
            }
            Map<String, String> approved = new HashMap<>();
            expected.mods().forEach(mod -> approved.put(mod.id(), mod.version()));
            if (!declared.equals(approved)) throw new IOException("The JAR's mod IDs or versions differ from the reviewed manifest.");
            for (var mod : expected.mods()) {
                var actual = dependencies(config, mod.id());
                var reviewed = mod.dependencies().stream().map(dependency -> dependency.id() + "|" + dependency.type() + "|" + dependency.range()).sorted().toList();
                if (!actual.equals(reviewed)) throw new IOException("The JAR's client dependencies differ from the reviewed manifest: " + mod.id());
            }
        } catch (RuntimeException e) {
            throw new IOException("The JAR metadata is invalid or unsupported.", e);
        }
        if (!before.equals(ArtifactFiles.fingerprint(path, token))) throw new IOException("The artifact changed during metadata verification.");
    }

    private static List<String> dependencies(UnmodifiableConfig config, String id) throws IOException {
        Object dependencies = config.get(List.of("dependencies", id));
        if (dependencies == null) return List.of();
        if (!(dependencies instanceof List<?> list) || list.size() > 256) throw new IOException("Invalid dependency metadata.");
        var result = new ArrayList<String>();
        for (Object value : list) {
            if (!(value instanceof UnmodifiableConfig dependency)) throw new IOException("Invalid dependency entry.");
            String side = string(dependency, "side", "BOTH");
            if (!Set.of("BOTH", "CLIENT", "SERVER").contains(side)) throw new IOException("Unsupported dependency side.");
            String type = string(dependency, "type", "required").toLowerCase(Locale.ROOT);
            if (!Set.of("required", "optional", "incompatible", "discouraged").contains(type)) throw new IOException("Unsupported dependency type.");
            if (!side.equals("SERVER")) result.add(string(dependency, "modId", "") + "|" + type + "|" + range(string(dependency, "versionRange", "[0,)")));
        }
        return result.stream().sorted().toList();
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
                if (count < 1 || count > 10000 || tail.getShort(i + 4) != 0 || tail.getShort(i + 6) != 0
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
