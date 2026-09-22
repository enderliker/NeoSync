/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

public final class SourceResolver {
    private final ModrinthProvider modrinth;

    public SourceResolver(ProviderTransport transport) {
        modrinth = new ModrinthProvider(transport);
    }

    public Map<String, ProviderArtifact> resolve(SyncManifest manifest, DiscoveryCancellation token) throws IOException {
        var hints = manifest.files().stream().flatMap(file -> file.sources().stream()).map(SyncManifest.Source::provider)
                .filter(hint -> hint != null && hint.id().equals("modrinth")).distinct().toList();
        var versions = modrinth.versions(hints, token);
        var result = new HashMap<String, ProviderArtifact>();
        for (var artifact : manifest.files()) {
            token.check();
            for (var source : artifact.sources()) {
                var hint = source.provider();
                if (hint == null) continue;
                if (!hint.id().equals("modrinth")) throw new IOException("CurseForge resolution is not available in this build yet.");
                var candidates = versions.get(hint.fileId());
                if (candidates == null) throw new IOException("The exact Modrinth version was not found. No alternate download was selected.");
                var matching = candidates.stream().filter(file -> file.identity().equals(hint) && file.source().equals(source.url()) && file.size() == artifact.size()).toList();
                if (matching.size() != 1) throw new IOException("The server's Modrinth hint, source, or size does not match the provider's exact file.");
                result.putIfAbsent(artifact.sha256(), matching.getFirst());
            }
        }
        return Map.copyOf(result);
    }
}
