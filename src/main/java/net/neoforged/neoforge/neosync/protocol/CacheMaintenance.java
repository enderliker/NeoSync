/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;

@org.jetbrains.annotations.ApiStatus.Internal
public final class CacheMaintenance {
    private CacheMaintenance() {}

    private record Entry(Path path, long bytes, long modified, boolean temporary) {}

    /** Called under the profile-store lock. Cache files are copies; revisions never share their file identity. */
    public static void reserve(Path directory, Set<String> needed, long reservation, long capacity, DiscoveryCancellation token) throws IOException {
        ManagedPaths.directory(directory, false);
        if (reservation < 0 || reservation > capacity || capacity > SyncManifest.MAX_TOTAL_BYTES) throw new IOException("Invalid cache reservation.");
        var evictable = new ArrayList<Entry>();
        long bytes = 0;
        int count = 0;
        try (var entries = Files.newDirectoryStream(directory)) {
            for (var path : entries) {
                token.check();
                if (++count > 8192) throw new IOException("The download cache inventory exceeds its limit.");
                String name = path.getFileName().toString();
                boolean temporary = name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp");
                if (!temporary && !name.matches("[0-9a-f]{64}\\.jar")) throw new IOException("The download cache contains an unexpected entry.");
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.size() > SyncManifest.MAX_FILE_BYTES) throw new IOException("The download cache contains an unsafe or oversized entry.");
                if (!temporary && needed.contains(name.substring(0, 64))) continue;
                bytes += attributes.size();
                evictable.add(new Entry(path, attributes.size(), attributes.lastModifiedTime().toMillis(), temporary));
            }
        }
        // Validate the complete inventory before deleting anything; unexpected entries must not trigger partial cleanup.
        evictable.sort(Comparator.comparing((Entry entry) -> !entry.temporary()).thenComparingLong(Entry::modified));
        for (var entry : evictable) {
            if (!entry.temporary() && bytes <= capacity - reservation) break;
            token.check();
            Files.delete(entry.path());
            bytes -= entry.bytes();
        }
    }
}
