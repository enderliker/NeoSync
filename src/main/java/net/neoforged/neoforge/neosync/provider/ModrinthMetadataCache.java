/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

@org.jetbrains.annotations.ApiStatus.Internal
public final class ModrinthMetadataCache {
    public static final int MAX_ENTRIES = 256;
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final Duration LIFETIME = Duration.ofMinutes(10);

    private final LongSupplier clock;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private int bytes;

    public ModrinthMetadataCache() {
        this(System::nanoTime);
    }

    public ModrinthMetadataCache(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized byte[] get(ProviderHttpClient.Service service, String path, String body) {
        if (service != ProviderHttpClient.Service.MODRINTH) return null;
        var key = new Key(path, body);
        var entry = entries.get(key);
        if (entry == null) return null;
        if (clock.getAsLong() - entry.createdAt() >= LIFETIME.toNanos()) {
            remove(key);
            return null;
        }
        return entry.data().clone();
    }

    public synchronized void put(ProviderHttpClient.Service service, String path, String body, byte[] data) {
        if (service != ProviderHttpClient.Service.MODRINTH) return;
        if (data.length == 0 || data.length > ProviderHttpClient.MAX_BYTES) return;
        var key = new Key(path, body);
        remove(key);
        long now = clock.getAsLong();
        entries.put(key, new Entry(data.clone(), now));
        bytes += data.length;
        entries.entrySet().removeIf(entry -> {
            if (now - entry.getValue().createdAt() < LIFETIME.toNanos()) return false;
            bytes -= entry.getValue().data().length;
            return true;
        });
        while (entries.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
            remove(entries.keySet().iterator().next());
        }
    }

    private void remove(Key key) {
        var previous = entries.remove(key);
        if (previous != null) bytes -= previous.data().length;
    }

    private record Key(String path, String body) {}

    private record Entry(byte[] data, long createdAt) {}
}
