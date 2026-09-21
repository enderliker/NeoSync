/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

public record SyncEndpoint(String host, int gamePort, int httpsPort, String digest) {
    public static SyncEndpoint create(String host, int gamePort, SyncCapability capability) throws IOException {
        if (gamePort < 1 || gamePort > 65535) throw new IOException("Invalid game port.");
        if (capability.httpsPort() != 443 && capability.httpsPort() != 8443) throw new IOException("NeoSync discovery only supports HTTPS ports 443 and 8443.");
        if (!capability.manifestSha256().matches(SyncManifest.HASH_PATTERN)) throw new IOException("Invalid manifest hash.");
        return new SyncEndpoint(normalizeHost(host), gamePort, capability.httpsPort(), capability.manifestSha256());
    }

    public static String normalizeHost(String host) throws IOException {
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (InetAddresses.isInetAddress(host)) return InetAddresses.toAddrString(InetAddresses.forString(host));
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        try {
            String normalized = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
            if (normalized.isEmpty() || normalized.length() > 253 || normalized.contains(":")) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid server hostname.", e);
        }
    }

    public URI manifestUri() throws IOException {
        try {
            return new URI("https", null, host, httpsPort, "/.well-known/neosync/v1/servers/" + gamePort + "/manifests/" + digest + ".json", null, null);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid manifest endpoint.", e);
        }
    }

    public static boolean isLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isLoopbackAddress() || address.isSiteLocalAddress()
                || bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress())
            return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return a != 0 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 192 && b == 0 && (c == 0 || c == 2))
                    && !(a == 192 && b == 88 && c == 99)
                    && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                    && !(a == 203 && b == 0 && c == 113);
        }
        if ((a & 0xE0) != 0x20 || a == 0x20 && b == 0x02 || a == 0x3F && b == 0xFF && (bytes[2] & 0xF0) == 0) return false;
        int c = bytes[2] & 255;
        int d = bytes[3] & 255;
        return !(a == 0x20 && b == 0x01 && (c <= 1 || c == 0x0D && d == 0xB8));
    }
}
