/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import java.io.IOException;

final class ProviderCredentials {
    private ProviderCredentials() {}

    static String serverCurseForge() throws IOException {
        String key = System.getenv("NEOSYNC_CURSEFORGE_API_KEY");
        if (key == null || key.isEmpty()) return "";
        if (key.length() > 512 || !key.matches("[!-~]+"))
            throw new IOException("The server's NEOSYNC_CURSEFORGE_API_KEY must contain 1–512 printable ASCII characters without whitespace.");
        return key;
    }
}
