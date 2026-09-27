/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.UUID;
import net.neoforged.neoforge.neosync.protocol.CacheMaintenance;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheMaintenanceTest {
    @Test
    void reservesRoomWithoutDeletingNeededCopies(@TempDir Path directory) throws Exception {
        Path needed = Files.writeString(directory.resolve("a".repeat(64) + ".jar"), "1234");
        Path old = Files.writeString(directory.resolve("b".repeat(64) + ".jar"), "1234");
        Path recent = Files.writeString(directory.resolve("c".repeat(64) + ".jar"), "1234");
        Files.setLastModifiedTime(old, FileTime.fromMillis(1));
        Path temporary = Files.writeString(directory.resolve(UUID.randomUUID() + ".tmp"), "partial");
        CacheMaintenance.reserve(directory, Set.of("a".repeat(64)), 4, 8, new DiscoveryCancellation());
        assertTrue(Files.exists(needed));
        assertTrue(Files.exists(recent));
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(temporary));
    }

    @Test
    void rejectsUnsafeInventoryBeforeRemovingCopies(@TempDir Path directory) throws Exception {
        Path existing = Files.writeString(directory.resolve("a".repeat(64) + ".jar"), "1234");
        Path outside = Files.writeString(directory.resolve("personal.txt"), "private");
        assertThrows(IOException.class, () -> CacheMaintenance.reserve(directory, Set.of(), 0, 1, new DiscoveryCancellation()));
        assertTrue(Files.exists(existing));
        Files.delete(outside);
        Files.createSymbolicLink(directory.resolve("b".repeat(64) + ".jar"), existing);
        assertThrows(IOException.class, () -> CacheMaintenance.reserve(directory, Set.of(), 0, 1, new DiscoveryCancellation()));
        assertTrue(Files.exists(existing));
    }
}
