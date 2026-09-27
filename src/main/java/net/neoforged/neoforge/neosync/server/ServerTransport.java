/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

import com.google.common.net.InetAddresses;
import com.google.gson.JsonObject;
import java.io.IOException;
import net.neoforged.neoforge.neosync.protocol.SyncEndpoint;
import net.neoforged.neoforge.neosync.protocol.SyncJson;

public record ServerTransport(String mode, String bindAddress, int port, int advertisedPort, String adminTransport, int adminPort) {
    public static ServerTransport parse(JsonObject config) throws IOException {
        String mode = config.has("mode") ? SyncJson.string(config.get("mode"), 32) : "managed-https";
        if (!java.util.Set.of("http", "https", "managed-https", "reverse-proxy").contains(mode))
            throw new IOException("Use http, https, managed-https, or reverse-proxy for the manifest service mode.");
        boolean http = mode.equals("http");
        String bind = config.has("bindAddress") ? SyncJson.string(config.get("bindAddress"), 64) : "0.0.0.0";
        if (!InetAddresses.isInetAddress(bind)) throw new IOException("The manifest bind address must be an IP literal.");
        int port = config.has("port") ? (int) SyncJson.number(config.get("port"), 1, 65535) : (http ? 8080 : 8443);
        String portKey = http ? "httpPort" : "httpsPort";
        if (config.has(http ? "httpsPort" : "httpPort")) throw new IOException("The advertised port field does not match the selected transport.");
        int advertised = config.has(portKey) ? (int) SyncJson.number(config.get(portKey), 1, 65535) : (http ? 8080 : 8443);
        SyncEndpoint.validateTransport(http ? "http" : "https", advertised);
        String admin = config.has("adminTransport") ? protocol(SyncJson.string(config.get("adminTransport"), 8)) : "https";
        int adminPort = config.has("adminPort") ? (int) SyncJson.number(config.get("adminPort"), 1, 65535) : 6742;
        if (port == adminPort && config.has("enabled") && SyncJson.bool(config.get("enabled")))
            throw new IOException("The administrator and manifest listeners must use different ports.");
        return new ServerTransport(mode, bind, port, advertised, admin, adminPort);
    }

    public static String protocol(String value) throws IOException {
        if (!value.equals("http") && !value.equals("https")) throw new IOException("Select HTTP or HTTPS.");
        return value;
    }

    public boolean insecure() {
        return mode.equals("http");
    }
}
