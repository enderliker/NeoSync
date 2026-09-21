/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class StatusQuery {
    private static final int MAX_PACKET_BYTES = 128 * 1024;

    private StatusQuery() {}

    public static Optional<SyncCapability> query(InetSocketAddress address, String logicalHost, int logicalPort,
            int minecraftProtocol, DiscoveryCancellation cancellation) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (var socket = new Socket(Proxy.NO_PROXY)) {
            cancellation.attach(socket);
            cancellation.check();
            socket.connect(address, 5000);
            var handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0);
            writeVarInt(handshake, minecraftProtocol);
            byte[] host = logicalHost.getBytes(StandardCharsets.UTF_8);
            if (host.length > 255) throw new IOException("Server hostname is too long.");
            writeVarInt(handshake, host.length);
            handshake.write(host);
            new DataOutputStream(handshake).writeShort(logicalPort);
            writeVarInt(handshake, 1);
            var output = socket.getOutputStream();
            writeVarInt(output, handshake.size());
            handshake.writeTo(output);
            output.write(new byte[] { 1, 0 });
            output.flush();
            var socketInput = socket.getInputStream();
            var input = new InputStream() {
                private void prepareRead() throws IOException {
                    cancellation.check();
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new java.net.SocketTimeoutException("Server status timed out.");
                    socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                }

                @Override
                public int read() throws IOException {
                    prepareRead();
                    return socketInput.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    prepareRead();
                    return socketInput.read(bytes, offset, length);
                }
            };
            int length = readVarInt(input);
            if (length < 2 || length > MAX_PACKET_BYTES) throw new IOException("Invalid server status packet size.");
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new EOFException("Incomplete server status packet.");
            var packet = new ByteArrayInputStream(bytes);
            if (readVarInt(packet) != 0) throw new IOException("Unexpected server status packet.");
            int jsonLength = readVarInt(packet);
            if (jsonLength < 0 || jsonLength != packet.available()) throw new IOException("Invalid server status JSON length.");
            var json = SyncJson.parse(packet.readAllBytes(), MAX_PACKET_BYTES);
            if (!json.isJsonObject()) throw new IOException("Invalid server status response.");
            var object = json.getAsJsonObject();
            return object.has("neosync") ? Optional.of(SyncCapability.parse(object.get("neosync"))) : Optional.empty();
        } finally {
            cancellation.detach();
        }
    }

    private static int readVarInt(InputStream input) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int value = input.read();
            if (value < 0) throw new EOFException("Incomplete packet integer.");
            if (shift == 28 && (value & 0xF0) != 0) throw new IOException("Packet integer exceeds the limit.");
            result |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) return result;
        }
        throw new IOException("Packet integer exceeds the limit.");
    }

    private static void writeVarInt(java.io.OutputStream output, int value) throws IOException {
        do {
            int next = value & 0x7F;
            value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }
}
