/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import net.neoforged.neoforge.neosync.protocol.SyncJson;

final class ProviderJson {
    private ProviderJson() {}

    // Providers may add fields independently of NeoSync; the strict bounded JSON reader still rejects duplicates.
    static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) throw new IOException("Invalid provider metadata object.");
        return value.getAsJsonObject();
    }

    static URI uri(JsonElement value) throws IOException {
        try {
            return URI.create(SyncJson.string(value, 2048));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid provider URL.");
        }
    }
}
