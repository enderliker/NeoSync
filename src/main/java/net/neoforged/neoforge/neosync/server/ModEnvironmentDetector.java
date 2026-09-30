/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.JarMetadata;
import net.neoforged.neoforge.neosync.protocol.ModEnvironment;
import net.neoforged.neoforge.neosync.provider.AutomaticSources;
import net.neoforged.neoforge.neosync.provider.CurseForgeProvider;
import net.neoforged.neoforge.neosync.provider.ModrinthProvider;
import net.neoforged.neoforge.neosync.provider.ProviderArtifact;
import net.neoforged.neoforge.neosync.provider.ProviderHttpClient;
import net.neoforged.neoforge.neosync.provider.ProviderTransport;
import org.slf4j.Logger;

public final class ModEnvironmentDetector {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ModEnvironmentDetector() {}

    public static List<AdminSelection.Candidate> detect(List<AdminSelection.Candidate> candidates, ProviderTransport transport) {
        if (candidates.size() > 2048) throw new IllegalArgumentException("The environment detection inventory exceeds the file limit.");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        var environments = new HashMap<Path, ModEnvironment>();
        var token = new DiscoveryCancellation();
        for (var candidate : candidates) {
            try {
                environments.put(candidate.path(), JarMetadata.environment(candidate.path(), token));
            } catch (IOException | RuntimeException failure) {
                environments.put(candidate.path(), ModEnvironment.UNKNOWN);
            }
        }
        var unresolved = candidates.stream().filter(candidate -> environments.get(candidate.path()) == ModEnvironment.UNKNOWN).toList();
        for (int start = 0; start < unresolved.size(); start += 32) {
            if (System.nanoTime() >= deadline) break;
            var files = new HashMap<Path, ArtifactFiles.Fingerprint>();
            for (var candidate : unresolved.subList(start, Math.min(start + 32, unresolved.size()))) {
                try {
                    files.put(candidate.path(), ArtifactFiles.fingerprint(candidate.path(), token));
                } catch (IOException failure) {
                    LOGGER.warn("NeoSync could not inspect {} for automatic client selection. Review it in the administrator panel.", candidate.path().getFileName());
                }
            }
            try {
                resolve(files, environments, transport, token, deadline);
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("NeoSync provider environment detection is unavailable for an inventory batch. No unknown mod was automatically selected.");
            }
        }
        var result = new ArrayList<AdminSelection.Candidate>();
        for (var candidate : candidates) {
            var environment = environments.get(candidate.path());
            if (environment == ModEnvironment.UNKNOWN)
                LOGGER.warn("NeoSync could not determine whether {} is CLIENT, BOTH, or SERVER. Review its client requirement in the administrator panel.", candidate.path().getFileName());
            result.add(new AdminSelection.Candidate(candidate.path(), candidate.description(), environment));
        }
        return List.copyOf(result);
    }

    private static void resolve(Map<Path, ArtifactFiles.Fingerprint> files, Map<Path, ModEnvironment> environments,
            ProviderTransport transport, DiscoveryCancellation token, long deadline) throws IOException {
        var hashes = AutomaticSources.lookupHashes(files, token);
        var modrinth = new ModrinthProvider(transport);
        Map<String, ProviderArtifact> matches = Map.of();
        try {
            matches = modrinth.findHashes(hashes.values().stream().toList(), token);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("NeoSync exact Modrinth lookup is unavailable. No source or hosting authorization was changed.");
        }
        var remaining = new HashMap<Path, ArtifactFiles.Fingerprint>(files);
        try {
            var projects = modrinth.environments(matches.values().stream().map(match -> match.identity().projectId()).toList(), token);
            for (var entry : hashes.entrySet()) {
                var match = matches.get(entry.getValue());
                if (match == null || match.size() != files.get(entry.getKey()).size()) continue;
                var environment = projects.getOrDefault(match.identity().projectId(), ModEnvironment.UNKNOWN);
                environments.put(entry.getKey(), environment);
                if (environment != ModEnvironment.UNKNOWN) remaining.remove(entry.getKey());
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("NeoSync could not read Modrinth environment metadata. Unknown files still require review.");
        }
        if (remaining.isEmpty() || System.nanoTime() >= deadline || !transport.available(ProviderHttpClient.Service.CURSEFORGE)) return;
        var curseForge = new CurseForgeProvider(transport);
        for (var entry : curseForge.findFiles(remaining, token).entrySet()) {
            if (System.nanoTime() >= deadline) break;
            try {
                environments.put(entry.getKey(), curseForge.environment(entry.getValue(), token));
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("NeoSync could not read live CurseForge environment metadata for {}. Review it manually.", entry.getKey().getFileName());
            }
        }
    }
}
