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
import java.util.HexFormat;
import java.util.Objects;

public final class ArtifactFiles {
    private ArtifactFiles() {}

    public record Fingerprint(String sha256, long size) {}

    public static Fingerprint fingerprint(Path path, DiscoveryCancellation cancellation) throws IOException {
        cancellation.check();
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() < 1 || before.size() > SyncManifest.MAX_FILE_BYTES) {
            throw new IOException("A selected mod is not a regular JAR within the size limit.");
        }
        var digest = SyncManifest.sha256Digest();
        long count = 0;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancellation.check();
                count += read;
                if (count > before.size()) throw new IOException("A mod file changed during inspection.");
                digest.update(buffer, 0, read);
            }
        }
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (count != before.size() || !after.isRegularFile() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()) || after.size() != count) {
            throw new IOException("A mod file changed during inspection.");
        }
        return new Fingerprint(HexFormat.of().formatHex(digest.digest()), count);
    }
}
