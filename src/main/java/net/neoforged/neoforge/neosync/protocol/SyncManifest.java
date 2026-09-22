/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.jetbrains.annotations.Nullable;

public record SyncManifest(UUID serverId, String revision, String displayName, String minecraftVersion,
        String loaderVersion, String neoForgeVersion, List<Artifact> files) {

    public static final String NEOSYNC_VERSION = "0.1.0-alpha.2";
    public static final int MAX_BYTES = 1024 * 1024;
    public static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;
    public static final String HASH_PATTERN = "[0-9a-f]{64}";
    public static final String FILE_PATTERN = "[A-Za-z0-9][A-Za-z0-9._+-]*\\.jar";
    private static final String MOD_PATTERN = "[a-z][a-z0-9_]{1,63}";
    private static final Set<String> PLATFORM_IDS = Set.of("minecraft", "neoforge", "neosync");

    public SyncManifest {
        files = List.copyOf(files);
    }
    public record Artifact(String sha256, long size, String fileName, List<Mod> mods, List<Source> sources) {
        public Artifact {
            mods = List.copyOf(mods);
            sources = List.copyOf(sources);
        }
    }

    public record Mod(String id, String version, String displayName, List<Dependency> dependencies) {
        public Mod {
            dependencies = List.copyOf(dependencies);
        }
    }

    public record Dependency(String id, VersionRange range, String type) {}

    public record Source(String type, @Nullable URI url) {}

    public static SyncManifest parse(byte[] bytes) throws IOException {
        var root = SyncJson.object(SyncJson.parse(bytes, MAX_BYTES), Set.of("schemaVersion", "serverId", "revision", "displayName", "minecraftVersion", "loader", "files"), Set.of());
        SyncJson.number(root.get("schemaVersion"), 1, 1);
        String serverId = SyncJson.matching(root.get("serverId"), 36, "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        String revision = SyncJson.matching(root.get("revision"), 64, "[A-Za-z0-9][A-Za-z0-9._-]*");
        String name = SyncJson.string(root.get("displayName"), 128);
        String minecraft = SyncJson.string(root.get("minecraftVersion"), 128);
        if (!minecraft.equals("1.21.1")) throw new IOException("This server requires a different Minecraft version.");
        var loader = SyncJson.object(root.get("loader"), Set.of("id", "version", "neoForgeVersion"), Set.of());
        if (!SyncJson.string(loader.get("id"), 32).equals("neosync")) throw new IOException("Unsupported mod loader.");
        String loaderVersion = SyncJson.string(loader.get("version"), 128);
        String neoForgeVersion = SyncJson.string(loader.get("neoForgeVersion"), 128);
        var files = new ArrayList<Artifact>();
        var hashes = new HashSet<String>();
        var modIds = new HashSet<String>();
        long total = 0;
        for (var entry : SyncJson.array(root.get("files"), 0, 2048)) {
            var file = SyncJson.object(entry, Set.of("sha256", "size", "fileName", "required", "mods", "sources"), Set.of());
            String hash = SyncJson.matching(file.get("sha256"), 64, HASH_PATTERN);
            if (!hashes.add(hash)) throw new IOException("Duplicate artifact in manifest.");
            long size = SyncJson.number(file.get("size"), 1, MAX_FILE_BYTES);
            total += size;
            if (total > MAX_TOTAL_BYTES) throw new IOException("The server's mod set exceeds the size limit.");
            String fileName = SyncJson.matching(file.get("fileName"), 128, FILE_PATTERN);
            if (!SyncJson.bool(file.get("required"))) throw new IOException("Optional artifact selection is not supported yet.");
            var mods = new ArrayList<Mod>();
            for (var modEntry : SyncJson.array(file.get("mods"), 0, 64)) {
                var mod = SyncJson.object(modEntry, Set.of("id", "version", "displayName", "dependencies"), Set.of());
                String id = SyncJson.matching(mod.get("id"), 64, MOD_PATTERN);
                if (PLATFORM_IDS.contains(id) || !modIds.add(id)) throw new IOException("Duplicate or reserved mod ID in manifest.");
                if (modIds.size() > 8192) throw new IOException("Too many mods in manifest.");
                var dependencies = new ArrayList<Dependency>();
                for (var dependencyEntry : SyncJson.array(mod.get("dependencies"), 0, 256)) {
                    var dependency = SyncJson.object(dependencyEntry, Set.of("id", "versionRange", "type"), Set.of());
                    String type = SyncJson.string(dependency.get("type"), 16);
                    if (!Set.of("required", "optional", "incompatible", "discouraged").contains(type)) throw new IOException("Invalid dependency type.");
                    try {
                        dependencies.add(new Dependency(SyncJson.matching(dependency.get("id"), 64, MOD_PATTERN),
                                VersionRange.createFromVersionSpec(SyncJson.string(dependency.get("versionRange"), 128)), type));
                    } catch (InvalidVersionSpecificationException e) {
                        throw new IOException("Invalid dependency version range.", e);
                    }
                }
                mods.add(new Mod(id, SyncJson.string(mod.get("version"), 128), SyncJson.string(mod.get("displayName"), 128), dependencies));
            }
            files.add(new Artifact(hash, size, fileName, mods, parseSources(file.get("sources"))));
        }
        var result = new SyncManifest(UUID.fromString(serverId), revision, name, minecraft, loaderVersion, neoForgeVersion, files);
        result.validateDependencies();
        return result;
    }

    public static List<Source> parseSources(JsonElement value) throws IOException {
        var result = new ArrayList<Source>();
        for (var entry : SyncJson.array(value, 1, 8)) {
            var source = SyncJson.object(entry, Set.of("type"), Set.of("url", "provider"));
            String type = SyncJson.string(source.get("type"), 16);
            if (type.equals("server")) {
                SyncJson.object(source, Set.of("type"), Set.of());
                result.add(new Source(type, null));
            } else if (type.equals("external")) {
                SyncJson.object(source, Set.of("type", "url"), Set.of("provider"));
                try {
                    URI url = new URI(SyncJson.string(source.get("url"), 2048));
                    if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getRawUserInfo() != null || url.getRawFragment() != null) {
                        throw new IOException("Invalid external HTTPS source.");
                    }
                    if (url.getPort() != -1 && url.getPort() != 443 && url.getPort() != 8443) throw new IOException("Unsupported external HTTPS port.");
                    if (source.has("provider")) {
                        var provider = SyncJson.object(source.get("provider"), Set.of("id", "projectId", "fileId"), Set.of());
                        if (!Set.of("modrinth", "curseforge").contains(SyncJson.string(provider.get("id"), 32))) throw new IOException("Unknown provider hint.");
                        SyncJson.matching(provider.get("projectId"), 128, "[A-Za-z0-9_-]+");
                        SyncJson.matching(provider.get("fileId"), 128, "[A-Za-z0-9_-]+");
                    }
                    result.add(new Source(type, url));
                } catch (URISyntaxException e) {
                    throw new IOException("Invalid source URL.", e);
                }
            } else {
                throw new IOException("Unknown artifact source.");
            }
        }
        return List.copyOf(result);
    }

    private void validateDependencies() throws IOException {
        var versions = new HashMap<String, DefaultArtifactVersion>();
        versions.put("minecraft", new DefaultArtifactVersion(minecraftVersion));
        versions.put("neoforge", new DefaultArtifactVersion(neoForgeVersion));
        versions.put("neosync", new DefaultArtifactVersion(loaderVersion));
        files.forEach(file -> file.mods.forEach(mod -> versions.put(mod.id, new DefaultArtifactVersion(mod.version))));
        for (var file : files) {
            for (var mod : file.mods) {
                for (var dependency : mod.dependencies) {
                    var version = versions.get(dependency.id);
                    boolean matches = version != null && dependency.range.containsVersion(version);
                    if (dependency.type.equals("required") && !matches) throw new IOException("The manifest has an unsatisfied required dependency: " + dependency.id);
                    if (dependency.type.equals("incompatible") && matches) throw new IOException("The manifest contains incompatible mods: " + dependency.id);
                }
            }
        }
    }

    public static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String versionSpec(VersionRange range) {
        if (range.getRecommendedVersion() != null) return range.getRecommendedVersion().toString();
        return range.getRestrictions().stream().map(restriction -> {
            var lower = restriction.getLowerBound();
            var upper = restriction.getUpperBound();
            // Maven's toString emits [v,v] for an exact range, but its parser only accepts [v].
            if (lower != null && lower.equals(upper) && restriction.isLowerBoundInclusive() && restriction.isUpperBoundInclusive()) return "[" + lower + "]";
            return (restriction.isLowerBoundInclusive() ? "[" : "(") + (lower == null ? "" : lower.toString()) + ","
                    + (upper == null ? "" : upper.toString()) + (restriction.isUpperBoundInclusive() ? "]" : ")");
        }).collect(java.util.stream.Collectors.joining(","));
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }
}
