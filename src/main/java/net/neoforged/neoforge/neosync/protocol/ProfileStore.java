/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public final class ProfileStore {
    private static final String ID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final long SPACE_RESERVE = 64L * 1024 * 1024;
    private final Path root;
    @Nullable
    private final Prepared active;

    private ProfileStore(Path root, @Nullable Prepared active) {
        this.root = root;
        this.active = active;
    }

    public record Prepared(String profileId, String revisionId, InstallationPlan.Identity identity, String digest, SyncManifest manifest, Path gameDirectory) {}

    private record Profile(String id, InstallationPlan.Identity identity, String prepared, String launched) {}

    /** Reads local association only. Startup verification occurs after FML loading and cannot prevent execution of locally tampered mods. */
    public static ProfileStore open(Path gameDirectory) throws IOException {
        Path game = ManagedPaths.directory(gameDirectory, false);
        Path marker = game.resolve("neosync-profile.json");
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (game.getParent() != null && game.getParent().getParent() != null
                    && game.getParent().getParent().endsWith("revisions")) {
                throw new IOException("This managed game directory has no profile marker. Select a verified prepared revision in your launcher.");
            }
            return new ProfileStore(game.resolve("neosync"), null);
        }
        var record = marker(marker);
        Path root;
        try {
            root = Path.of(SyncJson.string(record.get("storageRoot"), 4096));
        } catch (RuntimeException e) {
            throw new IOException("Invalid local storage root.", e);
        }
        if (!root.isAbsolute() || !root.equals(root.normalize())) throw new IOException("Invalid local storage root.");
        String profile = id(record, "profileId");
        String revision = id(record, "revisionId");
        Path expected = root.resolve("profiles").resolve(profile).resolve("revisions").resolve(revision).resolve("game");
        if (!game.equals(expected)) throw new IOException("The profile marker does not describe the selected game directory.");
        ManagedPaths.directory(root, false);
        var store = new ProfileStore(root, null);
        var local = store.readProfile(root.resolve("profiles").resolve(profile));
        var prepared = store.readRevision(local, revision);
        if (!prepared.digest().equals(SyncJson.matching(record.get("manifestSha256"), 64, SyncManifest.HASH_PATTERN))) throw new IOException("The active profile marker has changed.");
        return new ProfileStore(root, prepared);
    }

    public Path root() {
        return root;
    }

    public Optional<Prepared> active() {
        return Optional.ofNullable(active);
    }

    public Optional<Prepared> prepared(InstallationPlan.Identity identity) throws IOException {
        var profile = find(identity);
        return profile == null ? Optional.empty() : Optional.of(readRevision(profile, profile.prepared()));
    }

    public java.util.List<Prepared> preparedProfiles() throws IOException {
        Path profiles = root.resolve("profiles");
        if (!Files.exists(profiles, LinkOption.NOFOLLOW_LINKS)) return java.util.List.of();
        ManagedPaths.directory(profiles, false);
        var result = new java.util.ArrayList<Prepared>();
        int count = 0;
        try (var entries = Files.newDirectoryStream(profiles)) {
            for (var directory : entries) {
                if (++count > 1024 || !directory.getFileName().toString().matches(ID_PATTERN)) throw new IOException("Invalid local profile inventory.");
                ManagedPaths.directory(directory, false);
                if (!Files.exists(directory.resolve("profile.json"), LinkOption.NOFOLLOW_LINKS)) continue;
                var profile = readProfile(directory);
                result.add(readRevision(profile, profile.prepared()));
            }
        }
        return java.util.List.copyOf(result);
    }

    public Map<String, Path> reusable(SyncManifest manifest, Map<String, Path> localFiles, DiscoveryCancellation token) throws IOException {
        var result = new HashMap<String, Path>();
        for (var artifact : manifest.files()) {
            token.check();
            Path path = localFiles.get(artifact.sha256());
            if (path != null && matches(path, artifact, token)) {
                result.put(artifact.sha256(), path);
                continue;
            }
            Path cache = root.resolve("cache/sha256").resolve(artifact.sha256() + ".jar");
            if (Files.exists(cache, LinkOption.NOFOLLOW_LINKS) && matches(cache, artifact, token)) result.put(artifact.sha256(), cache);
        }
        return Map.copyOf(result);
    }

    private static boolean matches(Path path, SyncManifest.Artifact artifact, DiscoveryCancellation token) throws IOException {
        ManagedPaths.directory(path.getParent(), false);
        var actual = ArtifactFiles.fingerprint(path, token);
        return actual.sha256().equals(artifact.sha256()) && actual.size() == artifact.size();
    }

    public Prepared prepare(InstallationPlan plan, InstallationPlan.Consent consent, Map<String, Path> available,
            String javaFmlVersion, DiscoveryCancellation token, Progress progress) throws IOException {
        consent.require(plan);
        token.check();
        ManagedPaths.directory(root, true);
        try (var lock = lock()) {
            var previous = find(plan.identity());
            var previousManifest = previous == null ? Optional.<SyncManifest>empty() : Optional.of(readRevision(previous, previous.prepared()).manifest());
            if (!previousManifest.equals(plan.previous())) throw new IOException("The prepared profile changed since review. Review the installation again.");
            String profileId = previous == null ? UUID.randomUUID().toString() : previous.id();
            String revisionId = UUID.randomUUID().toString();
            Path profiles = ManagedPaths.directory(root.resolve("profiles"), true);
            Path profile = ManagedPaths.directory(profiles.resolve(profileId), true);
            Path revisions = ManagedPaths.directory(profile.resolve("revisions"), true);
            Path stagingRoot = ManagedPaths.directory(root.resolve("staging"), true);
            Path cache = ManagedPaths.directory(root.resolve("cache/sha256"), true);
            if (Files.getFileStore(root).getUsableSpace() < Math.addExact(Math.multiplyExact(plan.totalBytes(), 3), SPACE_RESERVE)) {
                throw new IOException("Not enough free disk space for downloads, cache copies, the new profile, and a reserve.");
            }
            Path staging = ManagedPaths.directory(stagingRoot.resolve(UUID.randomUUID().toString()), true);
            Path game = ManagedPaths.directory(staging.resolve("game"), true);
            Path mods = ManagedPaths.directory(game.resolve("mods"), true);
            Path target = revisions.resolve(revisionId);
            try {
                long complete = 0;
                for (var file : plan.files()) {
                    token.check();
                    var artifact = file.artifact();
                    Path output = mods.resolve(artifact.sha256() + ".jar");
                    if (file.available()) {
                        Path source = available.get(artifact.sha256());
                        if (source == null || !matches(source, artifact, token)) throw new IOException("An available file changed. Review the installation again.");
                        progress.update("Copying " + artifact.fileName(), complete, plan.totalBytes());
                        copy(source, output, artifact.size(), token);
                    } else {
                        long base = complete;
                        ArtifactHttpClient.download(plan, consent, file, output, token, count -> progress.transfer("Downloading " + artifact.fileName(), base + count, plan.totalBytes()));
                    }
                    progress.update("Verifying " + artifact.fileName(), complete, plan.totalBytes());
                    if (file.provider() != null) file.provider().verify(output, artifact, token);
                    JarMetadata.verify(output, artifact, javaFmlVersion, token);
                    Path cached = cache.resolve(artifact.sha256() + ".jar");
                    if (!Files.exists(cached, LinkOption.NOFOLLOW_LINKS) || !matches(cached, artifact, token)) {
                        Path temp = cache.resolve(UUID.randomUUID() + ".tmp");
                        try {
                            copy(output, temp, artifact.size(), token);
                            if (!matches(temp, artifact, token)) throw new IOException("A cache copy failed verification.");
                            ManagedPaths.move(temp, cached);
                        } finally {
                            ManagedPaths.directory(cache, false);
                            Files.deleteIfExists(temp);
                        }
                    }
                    complete += artifact.size();
                }
                ManagedPaths.directory(game.resolve("config"), true);
                ManagedPaths.directory(game.resolve("logs"), true);
                ManagedPaths.writeNew(staging.resolve("manifest.json"), plan.manifestBytes());
                ManagedPaths.writeNew(staging.resolve("consent.json"), json(consentRecord(plan, consent)));
                ManagedPaths.writeNew(game.resolve("neosync-profile.json"), json(markerRecord(profileId, revisionId, plan.digest())));
                var next = new Profile(profileId, plan.identity(), revisionId, previous == null ? "none" : previous.launched());
                Path pointer = profile.resolve(UUID.randomUUID() + ".tmp");
                try {
                    ManagedPaths.writeNew(pointer, json(profileRecord(next)));
                    progress.update("Publishing the verified profile", complete, plan.totalBytes());
                    // Cancellation and publication have one ordering point; a canceled transaction cannot subsequently publish.
                    synchronized (token) {
                        token.check();
                        ManagedPaths.move(staging, target);
                        progress.update("Recording the prepared revision", complete, plan.totalBytes());
                        token.check();
                        ManagedPaths.move(pointer, profile.resolve("profile.json"));
                    }
                } finally {
                    ManagedPaths.directory(profile, false);
                    Files.deleteIfExists(pointer);
                }
                return new Prepared(profileId, revisionId, plan.identity(), plan.digest(), plan.manifest(), target.resolve("game"));
            } finally {
                if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) ManagedPaths.deleteTree(staging);
                // A revision orphaned between the two atomic renames remains recoverable; never delete published revisions here.
            }
        }
    }

    public void verify(Prepared prepared, String javaFmlVersion, DiscoveryCancellation token) throws IOException {
        if (!prepared.profileId().matches(ID_PATTERN) || !prepared.revisionId().matches(ID_PATTERN)) throw new IOException("Invalid local profile identifiers.");
        var record = readRevision(readProfile(root.resolve("profiles").resolve(prepared.profileId())), prepared.revisionId());
        if (!record.equals(prepared)) throw new IOException("The prepared profile record changed.");
        Path mods = ManagedPaths.directory(prepared.gameDirectory().resolve("mods"), false);
        var expected = new HashSet<String>();
        for (var file : prepared.manifest().files()) {
            expected.add(file.sha256() + ".jar");
            JarMetadata.verify(mods.resolve(file.sha256() + ".jar"), file, javaFmlVersion, token);
        }
        try (var entries = Files.newDirectoryStream(mods)) {
            for (var entry : entries) {
                token.check();
                if (!expected.remove(entry.getFileName().toString()) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("The managed profile contains unexpected mod files. Prepare a new revision before joining.");
                }
            }
        }
        if (!expected.isEmpty()) throw new IOException("The prepared profile is missing mod files.");
    }

    public void recordLaunch() throws IOException {
        if (active == null) return;
        try (var lock = lock()) {
            Path directory = root.resolve("profiles").resolve(active.profileId());
            var profile = readProfile(directory);
            if (!profile.identity().equals(active.identity())) throw new IOException("The profile association changed.");
            if (profile.launched().equals(active.revisionId())) return;
            Path temp = directory.resolve(UUID.randomUUID() + ".tmp");
            try {
                ManagedPaths.writeNew(temp, json(profileRecord(new Profile(profile.id(), profile.identity(), profile.prepared(), active.revisionId()))));
                ManagedPaths.move(temp, directory.resolve("profile.json"));
            } finally {
                ManagedPaths.directory(directory, false);
                Files.deleteIfExists(temp);
            }
        }
    }

    @Nullable
    private Profile find(InstallationPlan.Identity identity) throws IOException {
        Path profiles = root.resolve("profiles");
        if (!Files.exists(profiles, LinkOption.NOFOLLOW_LINKS)) return null;
        ManagedPaths.directory(profiles, false);
        Profile found = null;
        int count = 0;
        try (var entries = Files.newDirectoryStream(profiles)) {
            for (var path : entries) {
                if (++count > 1024) throw new IOException("The local profile count exceeds the limit.");
                if (!path.getFileName().toString().matches(ID_PATTERN)) throw new IOException("Invalid local profile directory.");
                ManagedPaths.directory(path, false);
                if (!Files.exists(path.resolve("profile.json"), LinkOption.NOFOLLOW_LINKS)) continue;
                var profile = readProfile(path);
                if (profile.identity().equals(identity)) {
                    if (found != null) throw new IOException("Duplicate local profile association.");
                    found = profile;
                }
            }
        }
        return found;
    }

    private Profile readProfile(Path directory) throws IOException {
        var object = SyncJson.object(SyncJson.parse(ManagedPaths.read(directory.resolve("profile.json"), 16384), 16384),
                Set.of("schemaVersion", "profileId", "host", "gamePort", "httpsPort", "serverId", "prepared", "launched"), Set.of());
        SyncJson.number(object.get("schemaVersion"), 1, 1);
        String id = id(object, "profileId");
        if (!directory.getFileName().toString().equals(id)) throw new IOException("The local profile ID does not match its directory.");
        String host = SyncJson.string(object.get("host"), 253);
        var endpoint = SyncEndpoint.create(host, (int) SyncJson.number(object.get("gamePort"), 1, 65535),
                new SyncCapability((int) SyncJson.number(object.get("httpsPort"), 1, 65535), "0".repeat(64)));
        if (!host.equals(endpoint.host())) throw new IOException("The local server identity is not normalized.");
        var identity = new InstallationPlan.Identity(host, endpoint.gamePort(), InstallationPlan.origin(endpoint.manifestUri()), UUID.fromString(id(object, "serverId")));
        String launched = SyncJson.string(object.get("launched"), 36);
        if (!launched.equals("none") && !launched.matches(ID_PATTERN)) throw new IOException("Invalid launched revision.");
        return new Profile(id, identity, id(object, "prepared"), launched);
    }

    private Prepared readRevision(Profile profile, String revision) throws IOException {
        if (!revision.matches(ID_PATTERN)) throw new IOException("Invalid local revision ID.");
        Path directory = root.resolve("profiles").resolve(profile.id()).resolve("revisions").resolve(revision);
        byte[] bytes = ManagedPaths.read(directory.resolve("manifest.json"), SyncManifest.MAX_BYTES);
        String digest = SyncManifest.sha256(bytes);
        var manifest = SyncManifest.parse(bytes);
        var marker = marker(directory.resolve("game/neosync-profile.json"));
        if (!id(marker, "profileId").equals(profile.id()) || !id(marker, "revisionId").equals(revision)
                || !SyncJson.string(marker.get("storageRoot"), 4096).equals(root.toString())
                || !SyncJson.matching(marker.get("manifestSha256"), 64, SyncManifest.HASH_PATTERN).equals(digest)
                || !manifest.serverId().equals(profile.identity().serverId()))
            throw new IOException("The local revision association is invalid.");
        var consent = SyncJson.object(SyncJson.parse(ManagedPaths.read(directory.resolve("consent.json"), SyncManifest.MAX_BYTES), SyncManifest.MAX_BYTES),
                Set.of("schemaVersion", "manifestSha256", "host", "gamePort", "origin", "serverId", "acceptedAt", "unverifiedSourcesAccepted", "files"), Set.of());
        SyncJson.number(consent.get("schemaVersion"), 1, 1);
        if (!SyncJson.string(consent.get("manifestSha256"), 64).equals(digest) || !SyncJson.bool(consent.get("unverifiedSourcesAccepted"))
                || !SyncJson.string(consent.get("host"), 253).equals(profile.identity().host())
                || SyncJson.number(consent.get("gamePort"), 1, 65535) != profile.identity().gamePort()
                || !SyncJson.string(consent.get("origin"), 512).equals(profile.identity().origin().toString())
                || !id(consent, "serverId").equals(profile.identity().serverId().toString()))
            throw new IOException("The local consent record does not match this revision.");
        try {
            java.time.Instant.parse(SyncJson.string(consent.get("acceptedAt"), 64));
        } catch (RuntimeException e) {
            throw new IOException("Invalid local consent time.", e);
        }
        var files = SyncJson.array(consent.get("files"), 0, 2048);
        if (files.size() != manifest.files().size()) throw new IOException("The local consent file set changed.");
        for (int i = 0; i < files.size(); i++) {
            var file = SyncJson.object(files.get(i), Set.of("sha256", "source"), Set.of("provider"));
            var artifact = manifest.files().get(i);
            URI source;
            try {
                source = InstallationPlan.externalSource(URI.create(SyncJson.string(file.get("source"), 2048)));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid local consent source.", e);
            }
            if (file.has("provider")) {
                var provider = net.neoforged.neoforge.neosync.provider.ProviderArtifact.readAudit(
                        SyncJson.object(file.get("provider"), Set.of("id", "projectId", "fileId", "algorithm", "hash", "manual"), Set.of()), source, artifact);
                if (!SyncJson.string(file.get("sha256"), 64).equals(artifact.sha256())) throw new IOException("The provider audit file identity changed.");
                continue;
            }
            URI hostedSource = InstallationPlan.serverSource(profile.identity(), artifact.sha256());
            if (!SyncJson.string(file.get("sha256"), 64).equals(artifact.sha256())
                    || artifact.sources().stream().noneMatch(candidate -> candidate.type().equals("server") ? source.equals(hostedSource) : source.equals(candidate.url())))
                throw new IOException("The local consent source or file identity changed.");
        }
        return new Prepared(profile.id(), revision, profile.identity(), digest, manifest, directory.resolve("game"));
    }

    private static JsonObject marker(Path path) throws IOException {
        var record = SyncJson.object(SyncJson.parse(ManagedPaths.read(path, 8192), 8192),
                Set.of("schemaVersion", "storageRoot", "profileId", "revisionId", "manifestSha256"), Set.of());
        SyncJson.number(record.get("schemaVersion"), 1, 1);
        return record;
    }

    private JsonObject markerRecord(String profile, String revision, String digest) {
        var object = new JsonObject();
        object.addProperty("schemaVersion", 1);
        object.addProperty("storageRoot", root.toString());
        object.addProperty("profileId", profile);
        object.addProperty("revisionId", revision);
        object.addProperty("manifestSha256", digest);
        return object;
    }

    private static JsonObject profileRecord(Profile profile) {
        var object = new JsonObject();
        object.addProperty("schemaVersion", 1);
        object.addProperty("profileId", profile.id());
        object.addProperty("host", profile.identity().host());
        object.addProperty("gamePort", profile.identity().gamePort());
        object.addProperty("httpsPort", profile.identity().origin().getPort());
        object.addProperty("serverId", profile.identity().serverId().toString());
        object.addProperty("prepared", profile.prepared());
        object.addProperty("launched", profile.launched());
        return object;
    }

    private static JsonObject consentRecord(InstallationPlan plan, InstallationPlan.Consent consent) {
        var object = new JsonObject();
        object.addProperty("schemaVersion", 1);
        object.addProperty("manifestSha256", plan.digest());
        object.addProperty("host", plan.identity().host());
        object.addProperty("gamePort", plan.identity().gamePort());
        object.addProperty("origin", plan.identity().origin().toString());
        object.addProperty("serverId", plan.identity().serverId().toString());
        object.addProperty("acceptedAt", consent.acceptedAt().toString());
        object.addProperty("unverifiedSourcesAccepted", true);
        var files = new JsonArray();
        for (var file : plan.files()) {
            var entry = new JsonObject();
            entry.addProperty("sha256", file.artifact().sha256());
            entry.addProperty("source", file.source().toString());
            if (file.provider() != null) entry.add("provider", file.provider().audit());
            files.add(entry);
        }
        object.add("files", files);
        return object;
    }

    private static String id(JsonObject record, String key) throws IOException {
        return SyncJson.matching(record.get(key), 36, ID_PATTERN);
    }

    private static byte[] json(JsonObject object) {
        return object.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Lock lock() throws IOException {
        ManagedPaths.directory(root, false);
        var channel = FileChannel.open(root.resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("Another NeoSync process is preparing this profile store. Try again later.");
            return new Lock(channel, lock);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw new IOException("The profile store is locked or does not support required locking.", e);
        }
    }

    private record Lock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    private static void copy(Path source, Path target, long size, DiscoveryCancellation token) throws IOException {
        ManagedPaths.directory(source.getParent(), false);
        ManagedPaths.directory(target.getParent(), false);
        try (var input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.allocate(65536);
            long copied = 0;
            while (input.read(buffer) != -1) {
                token.check();
                buffer.flip();
                copied += buffer.remaining();
                if (copied > size) throw new IOException("A reusable file grew during copying.");
                while (buffer.hasRemaining()) {
                    token.check();
                    output.write(buffer);
                }
                buffer.clear();
            }
            if (copied != size) throw new IOException("A reusable file changed during copying.");
            output.force(true);
        }
    }

    @FunctionalInterface
    public interface Progress {
        void update(String action, long completedBytes, long totalBytes) throws IOException;

        default void transfer(String action, long completedBytes, long totalBytes) {
            try {
                update(action, completedBytes, totalBytes);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
