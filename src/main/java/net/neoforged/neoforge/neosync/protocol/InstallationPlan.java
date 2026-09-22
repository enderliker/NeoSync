/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/** An immutable, validated review snapshot. Creating a plan performs no writes or artifact requests. */
public final class InstallationPlan {
    private final byte[] manifestBytes;
    private final SyncManifest manifest;
    private final Identity identity;
    @Nullable
    private final InetAddress approvedAddress;
    private final String digest;
    private final List<File> files;
    private final List<String> changes;
    @Nullable
    private final SyncManifest previous;

    private InstallationPlan(byte[] bytes, SyncManifest manifest, Identity identity, List<File> files, List<String> changes, @Nullable SyncManifest previous, @Nullable InetAddress approvedAddress) {
        this.approvedAddress = approvedAddress;
        this.manifestBytes = bytes.clone();
        this.manifest = manifest;
        this.identity = identity;
        this.digest = SyncManifest.sha256(bytes);
        this.files = List.copyOf(files);
        this.changes = List.copyOf(changes);
        this.previous = previous;
    }

    public record Identity(String host, int gamePort, URI origin, UUID serverId) {}

    public record File(SyncManifest.Artifact artifact, URI source, boolean available, boolean providedByServer) {}

    public static InstallationPlan create(SyncEndpoint endpoint, byte[] bytes, Set<String> verifiedAvailable,
            @Nullable SyncManifest previous, String loaderVersion, String neoForgeVersion) throws IOException {
        return create(endpoint, bytes, verifiedAvailable, previous, loaderVersion, neoForgeVersion, null);
    }

    /** The address must be the destination approved for and used by manifest discovery in this review. Never restore it from disk. */
    public static InstallationPlan create(SyncEndpoint endpoint, byte[] bytes, Set<String> verifiedAvailable,
            @Nullable SyncManifest previous, String loaderVersion, String neoForgeVersion, @Nullable InetAddress approvedAddress) throws IOException {
        if (approvedAddress != null && !SyncEndpoint.isPublic(approvedAddress) && !SyncEndpoint.isLocal(approvedAddress))
            throw new IOException("The approved server address is blocked by the network destination policy.");
        bytes = bytes.clone();
        if (!SyncManifest.sha256(bytes).equals(endpoint.digest())) throw new IOException("The installation snapshot changed. Review the server again.");
        var manifest = SyncManifest.parse(bytes);
        if (!manifest.loaderVersion().equals(loaderVersion) || !manifest.neoForgeVersion().equals(neoForgeVersion)) {
            throw new IOException("Select NeoSync " + manifest.loaderVersion() + " with NeoForge " + manifest.neoForgeVersion() + " in your launcher before installing these mods.");
        }
        var checkedEndpoint = SyncEndpoint.create(endpoint.host(), endpoint.gamePort(), new SyncCapability(endpoint.httpsPort(), endpoint.digest()));
        var identity = new Identity(checkedEndpoint.host(), checkedEndpoint.gamePort(), origin(checkedEndpoint.manifestUri()), manifest.serverId());
        var files = new ArrayList<File>();
        for (var artifact : manifest.files()) {
            if (artifact.mods().isEmpty()) throw new IOException("Library-only artifacts are not supported by this installation MVP: " + artifact.fileName());
            var source = artifact.sources().stream().filter(candidate -> candidate.type().equals("external")).findFirst().orElse(artifact.sources().getFirst());
            boolean hosted = source.type().equals("server");
            URI url = hosted ? serverSource(identity, artifact.sha256()) : externalSource(source.url());
            files.add(new File(artifact, url, verifiedAvailable.contains(artifact.sha256()), hosted));
        }
        var changes = new ArrayList<String>();
        Map<String, String> oldMods = previous == null ? Map.of()
                : previous.files().stream().flatMap(file -> file.mods().stream())
                        .collect(Collectors.toMap(SyncManifest.Mod::id, SyncManifest.Mod::version));
        Map<String, String> newMods = manifest.files().stream().flatMap(file -> file.mods().stream())
                .collect(Collectors.toMap(SyncManifest.Mod::id, SyncManifest.Mod::version));
        oldMods.forEach((id, version) -> {
            if (!newMods.containsKey(id)) changes.add("Remove from the new profile: " + id + " " + version);
            else if (!newMods.get(id).equals(version)) changes.add("Replace in the new profile: " + id + " " + version + " with " + newMods.get(id));
        });
        if (previous != null) {
            var nextHashes = manifest.files().stream().map(SyncManifest.Artifact::sha256).collect(Collectors.toSet());
            previous.files().stream().filter(file -> !nextHashes.contains(file.sha256()))
                    .forEach(file -> changes.add("Omit previous file from the new profile: " + file.fileName() + " (" + file.sha256() + ")"));
        }
        return new InstallationPlan(bytes, manifest, identity, files, changes, previous, approvedAddress);
    }

    public static URI serverSource(Identity identity, String sha256) throws IOException {
        if (!sha256.matches(SyncManifest.HASH_PATTERN)) throw new IOException("Invalid server artifact hash.");
        return identity.origin().resolve("/.well-known/neosync/v1/servers/" + identity.gamePort() + "/files/" + sha256);
    }

    @Nullable
    public InetAddress approvedAddress() {
        return approvedAddress;
    }

    public static URI externalSource(URI url) throws IOException {
        if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getRawUserInfo() != null
                || url.getRawFragment() != null || url.getRawQuery() != null
                || url.getPort() != -1 && url.getPort() != 443 && url.getPort() != 8443) {
            throw new IOException("Installation requires a direct HTTPS source on port 443 or 8443 without credentials, query parameters, or fragments.");
        }
        if (url.toASCIIString().length() > 2048) throw new IOException("The artifact source URL is too long.");
        return url;
    }

    public static URI origin(URI url) throws IOException {
        try {
            return new URI("https", null, SyncEndpoint.normalizeHost(url.getHost()), url.getPort() == -1 ? 443 : url.getPort(), null, null, null);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid HTTPS origin.", e);
        }
    }

    public SyncManifest manifest() {
        return manifest;
    }

    public byte[] manifestBytes() {
        return manifestBytes.clone();
    }

    public Identity identity() {
        return identity;
    }

    public String digest() {
        return digest;
    }

    public List<File> files() {
        return files;
    }

    public java.util.Optional<SyncManifest> previous() {
        return java.util.Optional.ofNullable(previous);
    }

    public long totalBytes() {
        return files.stream().mapToLong(file -> file.artifact().size()).sum();
    }

    public long downloadBytes() {
        return files.stream().filter(file -> !file.available()).mapToLong(file -> file.artifact().size()).sum();
    }

    public List<String> reviewLines() {
        var lines = new ArrayList<String>();
        lines.add(manifest.displayName() + " — " + identity.host() + ":" + identity.gamePort());
        lines.add("Manifest service: " + identity.origin() + "; server identity: " + identity.serverId());
        int mods = files.stream().mapToInt(file -> file.artifact().mods().size()).sum();
        long available = files.stream().filter(File::available).count();
        lines.add(count(mods, "mod") + " in " + count(files.size(), "file") + "; " + count(available, "file") + " available; "
                + count(files.size() - available, "file") + " to download (" + downloadBytes() + " bytes).");
        lines.add("Prepare a separate game directory, then activate it in your launcher. Your current mods and worlds stay in their current directory.");
        lines.addAll(changes);
        for (var file : files) {
            lines.add("");
            lines.add(file.artifact().fileName() + " — " + file.artifact().size() + " bytes — " + (file.available() ? "available for verified copying" : "download required"));
            file.artifact().mods().forEach(mod -> lines.add(mod.displayName() + " (" + mod.id() + ") " + mod.version()));
            lines.add("Source: " + sourceDescription(file));
            lines.add("SHA-256: " + file.artifact().sha256());
        }
        return List.copyOf(lines);
    }

    public List<String> warningLines() {
        var lines = new ArrayList<String>();
        lines.add("These files come from unverified sources. Installed mods can execute code with Minecraft's permissions. A matching hash does not prove that a mod is trustworthy.");
        for (var file : files) lines.add(file.artifact().fileName() + " — " + sourceDescription(file));
        lines.add("Accept only if you want to install this exact set from these sources.");
        return List.copyOf(lines);
    }

    private String sourceDescription(File file) {
        return file.providedByServer() ? "Provided by the server " + manifest.displayName() + " (" + identity.host() + ":" + identity.gamePort()
                + ") via " + identity.origin() + " (unverified source)" : file.source() + " (unverified source)";
    }

    private static String count(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    public Consent accept(boolean installationAccepted, boolean unverifiedSourcesAccepted) throws IOException {
        if (!installationAccepted || !unverifiedSourcesAccepted) throw new IOException("Installation was not accepted.");
        return new Consent(this, Instant.now());
    }

    /** Consent belongs to this in-memory review; persisted decisions are audit records, never reusable authority. */
    public static final class Consent {
        private final InstallationPlan plan;
        private final Instant acceptedAt;

        private Consent(InstallationPlan plan, Instant acceptedAt) {
            this.plan = plan;
            this.acceptedAt = acceptedAt;
        }

        public Instant acceptedAt() {
            return acceptedAt;
        }

        public void require(InstallationPlan current) throws IOException {
            if (plan != current) throw new IOException("The installation plan changed. New consent is required.");
        }
    }
}
