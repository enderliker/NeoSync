/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

public record HostingPolicy(boolean enabled, long maxBytes, int concurrentTransfers, long bytesPerSecond, int requestsPerMinute) {
    public static HostingPolicy parse(JsonElement value) throws IOException {
        if (value == null) return new HostingPolicy(false, 1024L * 1024 * 1024, 8, 8L * 1024 * 1024, 120);
        var config = SyncJson.object(value, Set.of("enabled"), Set.of("maxBytes", "concurrentTransfers", "bytesPerSecond", "requestsPerMinute"));
        return new HostingPolicy(SyncJson.bool(config.get("enabled")),
                number(config, "maxBytes", 1024L * 1024 * 1024, 1, SyncManifest.MAX_TOTAL_BYTES),
                (int) number(config, "concurrentTransfers", 8, 1, 32),
                number(config, "bytesPerSecond", 8L * 1024 * 1024, 65536, 128L * 1024 * 1024),
                (int) number(config, "requestsPerMinute", 120, 1, 6000));
    }

    public HostingPolicy {
        if (maxBytes < 1 || maxBytes > SyncManifest.MAX_TOTAL_BYTES || concurrentTransfers < 1 || concurrentTransfers > 32
                || bytesPerSecond < 65536 || bytesPerSecond > 128L * 1024 * 1024 || requestsPerMinute < 1 || requestsPerMinute > 6000)
            throw new IllegalArgumentException("Invalid hosting limits.");
    }

    /** Declarations bind eligibility to reviewed bytes; they do not independently prove authorship. */
    public boolean validateSelection(JsonObject selection, String sha256) throws IOException {
        var sources = SyncManifest.parseSources(selection.get("sources"));
        boolean hosted = sources.stream().anyMatch(source -> source.type().equals("server"));
        if (!hosted) {
            if (selection.has("hosting")) throw new IOException("Hosting declarations require a server source.");
            return false;
        }
        if (!enabled) throw new IOException("Server artifact hosting is disabled.");
        if (sources.size() != 1) throw new IOException("Hosted mods must be exclusive to this server and cannot have external sources.");
        var declaration = SyncJson.object(selection.get("hosting"),
                Set.of("authoredByAdministrator", "exclusiveToServer", "distributionRights", "sha256"), Set.of());
        if (!SyncJson.bool(declaration.get("authoredByAdministrator")) || !SyncJson.bool(declaration.get("exclusiveToServer"))
                || !SyncJson.bool(declaration.get("distributionRights")))
            throw new IOException("Hosting requires administrator authorship, no distribution elsewhere, and distribution rights. Third-party mods are ineligible.");
        if (!SyncJson.matching(declaration.get("sha256"), 64, SyncManifest.HASH_PATTERN).equals(sha256))
            throw new IOException("The hosted file changed. Renew the eligibility declaration for its exact SHA-256.");
        return true;
    }

    private static long number(JsonObject object, String name, long fallback, long min, long max) throws IOException {
        return object.has(name) ? SyncJson.number(object.get(name), min, max) : fallback;
    }
}
