/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.server.HostedInventory;
import net.neoforged.neoforge.neosync.server.HostingPolicy;
import net.neoforged.neoforge.neosync.server.ManifestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class HostingServiceTest {
    private static final String PREFIX = "/.well-known/neosync/v1/servers/25575/files/";
    @TempDir
    Path directory;

    @Test
    void servesOnlySnapshotHashesAndRejectsPathsRangesAndBodies() throws Exception {
        Path source = directory.resolve("own.jar");
        byte[] body = { 1, 2, 3, 4 };
        Files.write(source, body);
        var token = new DiscoveryCancellation();
        var fingerprint = ArtifactFiles.fingerprint(source, token);
        try (var inventory = new HostedInventory(directory.resolve("hosting"), 1024)) {
            inventory.add(source, fingerprint, token);
            var files = inventory.seal();
            try (var service = service(files, new HostingPolicy(true, 1024, 8, 65536, 120)); var client = HttpClient.newHttpClient()) {
                Files.writeString(source, "live mods are mutable");
                String base = "http://127.0.0.1:" + service.port();
                var response = get(client, base + PREFIX + fingerprint.sha256());
                assertEquals(200, response.statusCode());
                assertArrayEquals(body, response.body());
                assertEquals("4", response.headers().firstValue("Content-Length").orElseThrow());
                assertEquals("application/java-archive", response.headers().firstValue("Content-Type").orElseThrow());
                for (String path : new String[] { PREFIX + "f".repeat(64), PREFIX + "../config/server.properties", PREFIX + "%2e%2e/config",
                        PREFIX + fingerprint.sha256() + "?x=1", PREFIX + fingerprint.sha256() + "/", "/config/server.properties",
                        PREFIX.replace("25575", "25565") + fingerprint.sha256() }) {
                    assertEquals(404, get(client, base + path).statusCode());
                }
                URI uri = URI.create(base + PREFIX + fingerprint.sha256());
                assertEquals(400, client.send(HttpRequest.newBuilder(uri).header("Range", "bytes=0-1").build(), HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(405, client.send(HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
                assertFalse(client.send(HttpRequest.newBuilder(uri).method("GET", HttpRequest.BodyPublishers.ofString("body")).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200);
                Path snapshot = files.get(fingerprint.sha256()).path();
                Files.delete(snapshot);
                Files.createSymbolicLink(snapshot, source);
                assertEquals(503, get(client, uri.toString()).statusCode());
                Files.delete(snapshot);
            }
        }
    }

    @Test
    void boundsConcurrentStreamsBandwidthAndRecoversAfterDisconnect() throws Exception {
        byte[] body = new byte[256 * 1024];
        new java.util.Random(1).nextBytes(body);
        Path source = directory.resolve("own.jar");
        Files.write(source, body);
        var token = new DiscoveryCancellation();
        var fingerprint = ArtifactFiles.fingerprint(source, token);
        try (var inventory = new HostedInventory(directory.resolve("hosting"), body.length)) {
            inventory.add(source, fingerprint, token);
            try (var service = service(inventory.seal(), new HostingPolicy(true, body.length, 1, 65536, 120)); var client = HttpClient.newHttpClient()) {
                URI uri = URI.create("http://127.0.0.1:" + service.port() + PREFIX + fingerprint.sha256());
                var first = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, first.statusCode());
                assertEquals(503, get(client, uri.toString()).statusCode());
                first.body().close();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                HttpResponse<java.io.InputStream> retry;
                do {
                    retry = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofInputStream());
                    if (retry.statusCode() == 200) break;
                    retry.body().close();
                    Thread.sleep(50);
                } while (System.nanoTime() < deadline);
                assertEquals(200, retry.statusCode());
                long start = System.nanoTime();
                try (var input = retry.body()) {
                    assertArrayEquals(body, input.readAllBytes());
                }
                assertTrue(System.nanoTime() - start >= java.util.concurrent.TimeUnit.SECONDS.toNanos(3), "Aggregate bandwidth limit must pace the full response");
            }
        }
    }

    @Test
    void boundsRequestsAndRejectsAmbiguousFraming() throws Exception {
        try (var service = service(Map.of(), new HostingPolicy(true, 1024, 8, 65536, 2)); var client = HttpClient.newHttpClient()) {
            String uri = "http://127.0.0.1:" + service.port() + "/manifest.json";
            assertEquals(200, get(client, uri).statusCode());
            assertEquals(200, get(client, uri).statusCode());
            assertEquals(429, get(client, uri).statusCode());
        }
        try (var service = service(Map.of(), new HostingPolicy(true, 1024, 8, 65536, 120));
                var socket = new Socket("127.0.0.1", service.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /manifest.json HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertFalse(response.contains("200 OK"));
        }
        assertThrows(IOException.class, () -> new ManifestService(new InetSocketAddress("0.0.0.0", 0), null, "/manifest.json", new byte[0]));
    }

    private static HttpResponse<byte[]> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static ManifestService service(Map<String, HostedInventory.Entry> inventory, HostingPolicy policy) throws IOException {
        return new ManifestService(new InetSocketAddress("127.0.0.1", 0), null, "/manifest.json", SyncProtocolTest.manifest(), PREFIX, inventory, policy);
    }
}
