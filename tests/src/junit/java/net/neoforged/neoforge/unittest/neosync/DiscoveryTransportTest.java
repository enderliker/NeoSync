/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ManifestHttpClient;
import net.neoforged.neoforge.neosync.protocol.StatusQuery;
import net.neoforged.neoforge.neosync.protocol.SyncCapability;
import net.neoforged.neoforge.neosync.protocol.SyncEndpoint;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforge.neosync.server.ManifestService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class DiscoveryTransportTest {
    private static SSLContext serverTls;
    private static SSLContext clientTls;

    @BeforeAll
    static void certificates(@TempDir Path directory) throws Exception {
        Path storePath = directory.resolve("test.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", storePath.toString(), "-storepass", "test-password", "-noprompt")
                        .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storePath)) {
            store.load(input, "test-password".toCharArray());
        }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-password".toCharArray());
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), null, null);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trust.getTrustManagers(), null);
    }

    @Test
    void usesStatusIntentionBeforeAnyLogin() throws Exception {
        var capability = new SyncCapability(8443, SyncProtocolTest.HASH);
        String status = "{\"description\":\"Modded server\",\"neosync\":" + capability.toJson() + "}";
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var served = CompletableFuture.runAsync(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    var handshake = new ByteArrayInputStream(input.readNBytes(readVarInt(input)));
                    assertEquals(0, readVarInt(handshake));
                    assertEquals(767, readVarInt(handshake));
                    assertEquals("example.org", new String(handshake.readNBytes(readVarInt(handshake)), StandardCharsets.UTF_8));
                    assertEquals(25565, new DataInputStream(handshake).readUnsignedShort());
                    assertEquals(1, readVarInt(handshake));
                    assertEquals(0, handshake.available());
                    assertEquals(1, readVarInt(input));
                    assertEquals(0, readVarInt(input));
                    var packet = new ByteArrayOutputStream();
                    writeVarInt(packet, 0);
                    byte[] json = status.getBytes(StandardCharsets.UTF_8);
                    writeVarInt(packet, json.length);
                    packet.write(json);
                    writeVarInt(socket.getOutputStream(), packet.size());
                    packet.writeTo(socket.getOutputStream());
                    socket.getOutputStream().flush();
                    assertEquals(-1, input.read());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            assertEquals(capability, StatusQuery.query(new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort()), "example.org", 25565, 767, new DiscoveryCancellation()).orElseThrow());
            served.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancellationClosesPendingStatusSocket() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var cancellation = new DiscoveryCancellation();
            var result = CompletableFuture.runAsync(() -> assertThrows(IOException.class,
                    () -> StatusQuery.query(new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort()), "localhost", 25565, 767, cancellation)));
            try (var socket = listener.accept()) {
                cancellation.close();
                result.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void verifiesHttpsDigestAndHostname() throws Exception {
        byte[] body = SyncProtocolTest.manifest();
        String digest = SyncManifest.sha256(body);
        String route = "/.well-known/neosync/v1/servers/25565/manifests/" + digest + ".json";
        try (var service = new ManifestService(new InetSocketAddress("127.0.0.1", 0), serverTls, route, body)) {
            var endpoint = new SyncEndpoint("localhost", 25565, service.port(), digest);
            assertEquals(digest, SyncManifest.sha256(ManifestHttpClient.fetch(endpoint, InetAddress.getByName("127.0.0.1"), clientTls, new DiscoveryCancellation())));
            var wrongHost = new SyncEndpoint("wrong.example.invalid", 25565, service.port(), digest);
            assertThrows(IOException.class, () -> ManifestHttpClient.fetch(wrongHost, InetAddress.getByName("127.0.0.1"), clientTls, new DiscoveryCancellation()));
            assertThrows(IOException.class, () -> ManifestHttpClient.fetch(endpoint, InetAddress.getByName("127.0.0.1"), SSLContext.getDefault(), new DiscoveryCancellation()));
        }
    }

    @Test
    void rejectsOversizedAndMismatchedManifestBodies() throws Exception {
        String digest = SyncProtocolTest.HASH;
        String route = "/.well-known/neosync/v1/servers/25565/manifests/" + digest + ".json";
        for (byte[] body : new byte[][] { SyncProtocolTest.manifest(), new byte[SyncManifest.MAX_BYTES + 1] }) {
            try (var service = new ManifestService(new InetSocketAddress("127.0.0.1", 0), serverTls, route, body)) {
                var endpoint = new SyncEndpoint("localhost", 25565, service.port(), digest);
                assertThrows(IOException.class, () -> ManifestHttpClient.fetch(endpoint, InetAddress.getByName("127.0.0.1"), clientTls, new DiscoveryCancellation()));
            }
        }
    }

    @Test
    void servesOnlyThePublishedManifest() throws Exception {
        try (var service = new ManifestService(new InetSocketAddress("127.0.0.1", 0), null, "/manifest.json", SyncProtocolTest.manifest());
                var client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + service.port();
            var valid = client.send(HttpRequest.newBuilder(URI.create(base + "/manifest.json")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, valid.statusCode());
            assertFalse(SyncManifest.parse(valid.body()).files().isEmpty());
            for (String path : new String[] { "/files/" + SyncProtocolTest.HASH, "/manifest.json?other=true", "/config/server.properties", "/%2e%2e/manifest.json" }) {
                assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
        assertThrows(IOException.class, () -> new ManifestService(new InetSocketAddress("0.0.0.0", 0), null, "/manifest.json", new byte[0]));
    }

    private static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.read();
            if (next < 0) throw new IOException("Unexpected EOF in test server.");
            value |= (next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IOException("Invalid test packet.");
    }

    private static void writeVarInt(java.io.OutputStream output, int value) throws IOException {
        do {
            int next = value & 127;
            value >>>= 7;
            output.write(next | (value == 0 ? 0 : 128));
        } while (value != 0);
    }
}
