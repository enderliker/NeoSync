/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

/** Provider metadata binds a provider file, not the server's SHA-256, until the approved bytes are verified. */
public record ProviderArtifact(SyncManifest.ProviderHint identity, URI source, long size, String algorithm, String hash, boolean manual) {
    public void validate() throws IOException {
        String provider = identity.id();
        if (provider.equals("modrinth")) {
            if (!identity.projectId().matches("[A-Za-z0-9]{8}") || !identity.fileId().matches("[A-Za-z0-9]{8}")
                    || !algorithm.equals("SHA-512") || !hash.matches("[0-9a-f]{128}") || manual)
                throw new IOException("Invalid Modrinth file identity.");
            requireHost(source, Set.of("cdn.modrinth.com"));
            if (!source.getRawPath().startsWith("/data/" + identity.projectId() + "/versions/")) throw new IOException("Modrinth file URL does not match its project.");
        } else if (provider.equals("curseforge")) {
            if (!identity.projectId().matches("[1-9][0-9]{0,9}") || !identity.fileId().matches("[1-9][0-9]{0,9}")
                    || !algorithm.equals("SHA-1") || !hash.matches("[0-9a-f]{40}"))
                throw new IOException("Invalid CurseForge file identity.");
            if (manual) {
                requireHost(source, Set.of("www.curseforge.com"));
                // Earlier prepared profiles retain the exact file-details page in their local audit.
                if (!source.getRawPath().matches("/minecraft/mc-mods/[a-z0-9][a-z0-9-]{0,127}/(?:download|files)/" + identity.fileId()))
                    throw new IOException("Invalid exact CurseForge file page.");
            } else {
                requireHost(source, Set.of("edge.forgecdn.net", "mediafilez.forgecdn.net"));
                if (!source.getRawPath().startsWith("/files/")) throw new IOException("Invalid CurseForge download path.");
            }
        } else throw new IOException("Unsupported provider identity.");
        if (size < 1 || size > SyncManifest.MAX_FILE_BYTES) throw new IOException("Provider file size exceeds the limit.");
    }

    public static URI requireHost(URI source, Set<String> hosts) throws IOException {
        InstallationPlan.externalSource(source);
        if (!hosts.contains(source.getHost()) || source.getPort() != -1 && source.getPort() != 443
                || source.getRawPath().contains("..") || source.getRawPath().contains("\\"))
            throw new IOException("The provider returned an unsupported source URL.");
        return source;
    }

    public void require(SyncManifest.Artifact artifact) throws IOException {
        validate();
        if (size != artifact.size() || artifact.sources().stream().noneMatch(s -> identity.equals(s.provider())))
            throw new IOException("The provider result does not match the reviewed artifact hint.");
    }

    public void verify(Path path, SyncManifest.Artifact artifact, DiscoveryCancellation token) throws IOException {
        require(artifact);
        var providerDigest = digest(algorithm);
        var manifestDigest = SyncManifest.sha256Digest();
        long count = 0;
        try (var input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.allocate(65536);
            while (input.read(buffer) != -1) {
                token.check();
                buffer.flip();
                count += buffer.remaining();
                if (count > size) throw new IOException("The provider artifact grew during verification.");
                providerDigest.update(buffer.asReadOnlyBuffer());
                manifestDigest.update(buffer);
                buffer.clear();
            }
        }
        token.check();
        if (count != size || !HexFormat.of().formatHex(providerDigest.digest()).equals(hash)
                || !HexFormat.of().formatHex(manifestDigest.digest()).equals(artifact.sha256()))
            throw new IOException("The file does not match both the approved SHA-256 and provider identity.");
    }

    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonObject audit() {
        var result = new JsonObject();
        result.addProperty("id", identity.id());
        result.addProperty("projectId", identity.projectId());
        result.addProperty("fileId", identity.fileId());
        result.addProperty("algorithm", algorithm);
        result.addProperty("hash", hash);
        result.addProperty("manual", manual);
        return result;
    }

    /** Reads a local audit only; it must never be used as authority for a new download. */
    public static ProviderArtifact readAudit(JsonObject value, URI source, SyncManifest.Artifact artifact) throws IOException {
        var record = SyncJson.object(value, Set.of("id", "projectId", "fileId", "algorithm", "hash", "manual"), Set.of());
        var result = new ProviderArtifact(new SyncManifest.ProviderHint(SyncJson.string(record.get("id"), 32),
                SyncJson.string(record.get("projectId"), 128), SyncJson.string(record.get("fileId"), 128)), source, artifact.size(),
                SyncJson.string(record.get("algorithm"), 16), SyncJson.string(record.get("hash"), 128), SyncJson.bool(record.get("manual")));
        result.require(artifact);
        return result;
    }
}
