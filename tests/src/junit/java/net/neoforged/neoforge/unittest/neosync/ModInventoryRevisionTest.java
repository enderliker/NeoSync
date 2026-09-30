/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.server.ModInventoryRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModInventoryRevisionTest {
    private static final Instant FIRST = Instant.parse("2026-09-29T12:34:56.123456789Z");
    private static final Instant NEXT = FIRST.plusSeconds(60);

    @Test
    void preservesExactRecordBytesWhenOnlyTimesAndNonJarsChange(@TempDir Path directory) throws Exception {
        Path mods = Files.createDirectory(directory.resolve("mods"));
        Path jar = Files.writeString(mods.resolve("first.jar"), "first version");
        Path record = directory.resolve("config/neosync-mod-inventory.json");
        var first = ModInventoryRevision.update(mods, List.of("first.jar"), record, FIRST, new DiscoveryCancellation());
        assertEquals(List.of("first.jar"), first.added());
        byte[] saved = Files.readAllBytes(record);
        FileTime recordTime = Files.getLastModifiedTime(record);
        Files.setLastModifiedTime(jar, FileTime.from(NEXT));
        Files.writeString(mods.resolve("notes.txt"), "not a mod");
        Files.writeString(mods.resolve("server-only.jar"), "not selected for clients");
        Files.createSymbolicLink(mods.resolve("unselected-link.jar"), jar);
        Files.createDirectory(mods.resolve("other"));
        var unchanged = ModInventoryRevision.update(mods, List.of("first.jar"), record, NEXT, new DiscoveryCancellation());
        assertEquals(first.digest(), unchanged.digest());
        assertEquals(FIRST, unchanged.changedAt());
        assertEquals(first.added(), unchanged.added());
        assertArrayEquals(saved, Files.readAllBytes(record));
        assertEquals(recordTime, Files.getLastModifiedTime(record));
    }

    @Test
    void recordsBatchedAdditionsReplacementsAndRemovalsByContent(@TempDir Path directory) throws Exception {
        Path mods = Files.createDirectory(directory.resolve("mods"));
        Path first = Files.writeString(mods.resolve("first.jar"), "version one");
        Files.writeString(mods.resolve("removed.jar"), "removed");
        Path keep = Files.writeString(mods.resolve("keep.jar"), "unchanged");
        Path record = directory.resolve("config/neosync-mod-inventory.json");
        var before = ModInventoryRevision.update(mods, List.of("first.jar", "removed.jar", "keep.jar"), record, FIRST, new DiscoveryCancellation());
        FileTime previousTime = Files.getLastModifiedTime(first);
        Files.writeString(first, "version two");
        Files.setLastModifiedTime(first, previousTime);
        Files.delete(mods.resolve("removed.jar"));
        Files.writeString(mods.resolve("added-b.jar"), "new b");
        Files.writeString(mods.resolve("added-a.jar"), "new a");
        var changed = ModInventoryRevision.update(mods, List.of("first.jar", "keep.jar", "added-b.jar", "added-a.jar"), record, NEXT, new DiscoveryCancellation());
        assertNotEquals(before.digest(), changed.digest());
        assertEquals(NEXT, changed.changedAt());
        assertEquals(List.of("added-a.jar", "added-b.jar"), changed.added());
        assertEquals(List.of("first.jar"), changed.replaced());
        assertEquals(List.of("removed.jar"), changed.removed());
        var files = JsonParser.parseString(Files.readString(record)).getAsJsonObject().getAsJsonArray("files");
        for (var entry : files) {
            var file = entry.getAsJsonObject();
            assertEquals(file.get("fileName").getAsString().equals(keep.getFileName().toString()) ? FIRST.toString() : NEXT.toString(), file.get("firstSeenAt").getAsString());
        }
        byte[] saved = Files.readAllBytes(record);
        ModInventoryRevision.update(mods, List.of("first.jar", "keep.jar", "added-b.jar", "added-a.jar"), record, NEXT.plusSeconds(60), new DiscoveryCancellation());
        assertArrayEquals(saved, Files.readAllBytes(record));
    }

    @Test
    void rejectsSymlinksAndCancellationWithoutReplacingTheRecord(@TempDir Path directory) throws Exception {
        Path mods = Files.createDirectory(directory.resolve("mods"));
        Files.writeString(mods.resolve("first.jar"), "first");
        Path record = directory.resolve("config/neosync-mod-inventory.json");
        ModInventoryRevision.update(mods, List.of("first.jar"), record, FIRST, new DiscoveryCancellation());
        byte[] saved = Files.readAllBytes(record);
        var cancellation = new DiscoveryCancellation();
        cancellation.close();
        assertThrows(IOException.class, () -> ModInventoryRevision.update(mods, List.of("first.jar"), record, NEXT, cancellation));
        assertArrayEquals(saved, Files.readAllBytes(record));
        Path personal = Files.writeString(directory.resolve("personal.jar"), "keep");
        Files.createSymbolicLink(mods.resolve("linked.jar"), personal);
        assertThrows(IOException.class, () -> ModInventoryRevision.update(mods, List.of("first.jar", "linked.jar"), record, NEXT, new DiscoveryCancellation()));
        assertArrayEquals(saved, Files.readAllBytes(record));
        Files.delete(mods.resolve("linked.jar"));
        Path linked = directory.resolve("linked-record.json");
        Files.createSymbolicLink(linked, record);
        assertThrows(IOException.class, () -> ModInventoryRevision.update(mods, List.of("first.jar"), linked, NEXT, new DiscoveryCancellation()));
        assertArrayEquals(saved, Files.readAllBytes(record));
        assertEquals("keep", Files.readString(personal));
    }

    @Test
    void rejectsCorruptedRecordsAndUnsafeJarNames(@TempDir Path directory) throws Exception {
        Path mods = Files.createDirectory(directory.resolve("mods"));
        Files.writeString(mods.resolve("first.jar"), "first");
        Path record = directory.resolve("config/neosync-mod-inventory.json");
        ModInventoryRevision.update(mods, List.of("first.jar"), record, FIRST, new DiscoveryCancellation());
        var invalid = JsonParser.parseString(Files.readString(record)).getAsJsonObject();
        invalid.addProperty("inventorySha256", "a".repeat(64));
        Files.writeString(record, invalid.toString());
        byte[] saved = Files.readAllBytes(record);
        assertThrows(IOException.class, () -> ModInventoryRevision.update(mods, List.of("first.jar"), record, NEXT, new DiscoveryCancellation()));
        assertArrayEquals(saved, Files.readAllBytes(record));
        Files.delete(record);
        Files.writeString(mods.resolve("invalid name.jar"), "unsafe name");
        assertThrows(IOException.class, () -> ModInventoryRevision.update(mods, List.of("invalid name.jar"), record, NEXT, new DiscoveryCancellation()));
    }

    @Test
    void tracksSelectionChangesEvenWhenTheFolderDoesNotChange(@TempDir Path directory) throws Exception {
        Path mods = Files.createDirectory(directory.resolve("mods"));
        Files.writeString(mods.resolve("first.jar"), "first");
        Files.writeString(mods.resolve("second.jar"), "second");
        Path record = directory.resolve("config/neosync-client-inventory.json");
        var first = ModInventoryRevision.update(mods, List.of("first.jar"), record, FIRST, new DiscoveryCancellation());
        var second = ModInventoryRevision.update(mods, List.of("second.jar"), record, NEXT, new DiscoveryCancellation());
        assertNotEquals(first.digest(), second.digest());
        assertEquals(List.of("second.jar"), second.added());
        assertEquals(List.of("first.jar"), second.removed());
        assertEquals(List.of("second.jar"), second.files().stream().map(file -> file.fileName()).toList());
        byte[] saved = Files.readAllBytes(record);
        assertThrows(IOException.class, () -> ModInventoryRevision.update(mods, List.of("missing.jar"), record, NEXT, new DiscoveryCancellation()));
        assertThrows(IOException.class, () -> ModInventoryRevision.update(mods, List.of("second.jar", "second.jar"), record, NEXT, new DiscoveryCancellation()));
        assertArrayEquals(saved, Files.readAllBytes(record));
        var empty = ModInventoryRevision.update(mods, List.of(), record, NEXT.plusSeconds(60), new DiscoveryCancellation());
        assertTrue(empty.files().isEmpty());
        assertEquals(List.of("second.jar"), empty.removed());
    }
}
