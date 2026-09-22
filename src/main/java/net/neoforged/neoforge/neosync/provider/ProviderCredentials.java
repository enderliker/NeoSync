/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ProviderCredentials {
    private ProviderCredentials() {}

    static String curseForge() throws IOException {
        try (var input = ProviderCredentials.class.getResourceAsStream("/META-INF/neosync/provider-access.bin")) {
            if (input == null) return "";
            byte[] bytes = input.readNBytes(513);
            if (bytes.length == 0 || bytes.length > 512) throw new IOException("Invalid bundled provider credential.");
            String key = new String(bytes, StandardCharsets.US_ASCII);
            if (!key.matches("[!-~]+")) throw new IOException("Invalid bundled provider credential.");
            return key;
        }
    }
}
