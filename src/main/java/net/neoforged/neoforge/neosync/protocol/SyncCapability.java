/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public record SyncCapability(int httpsPort, String manifestSha256, String transport) {
    public SyncCapability(int httpsPort, String manifestSha256) {
        this(httpsPort, manifestSha256, "https");
    }

    public static SyncCapability parse(JsonElement value) throws IOException {
        if (value == null || value.toString().getBytes(StandardCharsets.UTF_8).length > 512) throw new IOException("Invalid NeoSync capability size.");
        var object = SyncJson.object(value, Set.of("protocols", "manifestSha256"), Set.of("httpsPort", "httpPort"));
        var protocols = new HashSet<Long>();
        for (var protocol : SyncJson.array(object.get("protocols"), 1, 8)) {
            if (!protocols.add(SyncJson.number(protocol, 1, 65535))) throw new IOException("Duplicate NeoSync protocol version.");
        }
        boolean http = object.has("httpPort");
        if (http == object.has("httpsPort")) throw new IOException("Advertise exactly one NeoSync transport.");
        if (http ? !protocols.equals(Set.of(2L)) : !protocols.contains(1L))
            throw new IOException("This server requires an unsupported NeoSync protocol.");
        return new SyncCapability((int) SyncJson.number(object.get(http ? "httpPort" : "httpsPort"), 1, 65535),
                SyncJson.matching(object.get("manifestSha256"), 64, SyncManifest.HASH_PATTERN), http ? "http" : "https");
    }

    public JsonObject toJson() {
        var result = new JsonObject();
        var protocols = new com.google.gson.JsonArray();
        protocols.add(transport.equals("http") ? 2 : 1);
        result.add("protocols", protocols);
        result.addProperty(transport.equals("http") ? "httpPort" : "httpsPort", httpsPort);
        result.addProperty("manifestSha256", manifestSha256);
        return result;
    }
}
