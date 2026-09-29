/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class ProviderAccess {
    private ProviderAccess() {}

    static String curseForge() throws IOException {
        try (var input = ProviderAccess.class.getResourceAsStream("/META-INF/neosync/provider-access.bin")) {
            if (input == null) return "";
            byte[] encoded = input.readNBytes(8193);
            if (encoded.length == 0 || encoded.length > 8192 || encoded.length % 2 != 0)
                throw new IOException("Invalid embedded provider access configuration.");
            byte[] clear = new byte[encoded.length / 2];
            try {
                for (int index = 0; index < clear.length; index++) clear[index] = (byte) (encoded[index * 2] ^ encoded[index * 2 + 1]);
                String result = new String(clear, StandardCharsets.US_ASCII);
                if (!result.matches("[\\x21-\\x7E]+")) throw new IOException("Invalid embedded provider access configuration.");
                return result;
            } finally {
                Arrays.fill(clear, (byte) 0);
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }
}
