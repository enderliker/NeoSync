/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicLong;
import net.neoforged.neoforge.neosync.provider.ModrinthMetadataCache;
import net.neoforged.neoforge.neosync.provider.ProviderHttpClient;
import org.junit.jupiter.api.Test;

class ModrinthMetadataCacheTest {
    @Test
    void keepsOnlyModrinthResponsesForTheirExactRequestUntilExpiry() {
        var now = new AtomicLong();
        var cache = new ModrinthMetadataCache(now::get);
        byte[] response = { 1, 2, 3 };
        cache.put(ProviderHttpClient.Service.CURSEFORGE, "/v1/mods", "{}", response);
        assertNull(cache.get(ProviderHttpClient.Service.CURSEFORGE, "/v1/mods", "{}"));

        cache.put(ProviderHttpClient.Service.MODRINTH, "/v2/version_files", "first", response);
        response[0] = 9;
        byte[] first = cache.get(ProviderHttpClient.Service.MODRINTH, "/v2/version_files", "first");
        assertArrayEquals(new byte[] { 1, 2, 3 }, first);
        first[1] = 9;
        assertArrayEquals(new byte[] { 1, 2, 3 }, cache.get(ProviderHttpClient.Service.MODRINTH, "/v2/version_files", "first"));
        assertNull(cache.get(ProviderHttpClient.Service.MODRINTH, "/v2/version_files", "second"));
        assertNull(cache.get(ProviderHttpClient.Service.MODRINTH, "/v2/versions", "first"));
        assertNull(cache.get(ProviderHttpClient.Service.CURSEFORGE, "/v2/version_files", "first"));

        now.set(ModrinthMetadataCache.LIFETIME.toNanos());
        assertNull(cache.get(ProviderHttpClient.Service.MODRINTH, "/v2/version_files", "first"));
    }

    @Test
    void boundsMemoryAndDoesNotKeepEmptyOrOversizedResponses() {
        var cache = new ModrinthMetadataCache();
        var service = ProviderHttpClient.Service.MODRINTH;
        cache.put(service, "/v2/empty", "", new byte[0]);
        cache.put(service, "/v2/oversized", "", new byte[ProviderHttpClient.MAX_BYTES + 1]);
        assertNull(cache.get(service, "/v2/empty", ""));
        assertNull(cache.get(service, "/v2/oversized", ""));

        byte[] response = new byte[ProviderHttpClient.MAX_BYTES];
        for (int i = 0; i < ModrinthMetadataCache.MAX_BYTES / response.length + 1; i++) {
            cache.put(service, "/v2/versions", Integer.toString(i), response);
        }
        assertNull(cache.get(service, "/v2/versions", "0"));
        assertArrayEquals(response, cache.get(service, "/v2/versions", "1"));

        for (int i = 0; i <= ModrinthMetadataCache.MAX_ENTRIES; i++) {
            cache.put(service, "/v2/versions", "small-" + i, new byte[] { 1 });
        }
        assertNull(cache.get(service, "/v2/versions", "small-0"));
        assertArrayEquals(new byte[] { 1 }, cache.get(service, "/v2/versions", "small-1"));
    }
}
