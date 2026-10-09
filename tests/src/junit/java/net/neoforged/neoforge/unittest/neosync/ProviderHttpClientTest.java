/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.provider.ProviderHttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class ProviderHttpClientTest {
    private static SSLContext serverTls;
    private static SSLContext clientTls;
    private static final String FAKE_KEY = "neosync-fixture-not-a-real-key";

    @BeforeAll
    static void certificates(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("fixture.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        var process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-validity", "1",
                "-dname", "CN=api.modrinth.com", "-ext", "SAN=dns:api.modrinth.com,dns:api.curseforge.com", "-storetype", "PKCS12",
                "-keystore", path.toString(), "-storepass", "fixture-password", "-noprompt")
                        .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path)) {
            store.load(input, "fixture-password".toCharArray());
        }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "fixture-password".toCharArray());
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), null, null);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trust.getTrustManagers(), null);
    }

    @Test
    void sendsNoCredentialHeadersAndIdentifiesNeoSync() throws Exception {
        try (var fixture = new Fixture("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")) {
            byte[] bytes = fetch(ProviderHttpClient.Service.MODRINTH, fixture, clientTls, new DiscoveryCancellation());
            assertEquals("{}", new String(bytes, StandardCharsets.UTF_8));
            String request = fixture.request.get(5, TimeUnit.SECONDS);
            assertTrue(request.contains("enderliker/NeoSync/"));
            assertFalse(request.contains("x-api-key"));
            assertFalse(request.lines().findFirst().orElseThrow().contains(FAKE_KEY));
        }
    }

    @Test
    void sendsMaskedFixtureAccessOnlyToCurseForgeWithNoStore() throws Exception {
        var loader = new ProviderFixtureLoader(ProviderFixtureLoader.masked(FAKE_KEY));
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var fixture = new Fixture("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")) {
                assertEquals("{}", new String(fetchCurseForge(loader, fixture, URI.create("https://api.curseforge.com/v1/fingerprints/432")), StandardCharsets.UTF_8));
                String request = fixture.request.get(5, TimeUnit.SECONDS);
                assertTrue(request.lines().anyMatch(line -> line.equalsIgnoreCase("x-api-key: " + FAKE_KEY)));
                assertTrue(request.lines().anyMatch(line -> line.equalsIgnoreCase("cache-control: no-store")));
                assertTrue(request.lines().anyMatch(line -> line.equalsIgnoreCase("host: api.curseforge.com")));
                assertFalse(request.lines().findFirst().orElseThrow().contains(FAKE_KEY));
                assertFalse(request.toLowerCase(java.util.Locale.ROOT).contains("authorization:"));
            }
        }
    }

    @Test
    void rejectsCurseForgeRedirectsAndWrongOriginsWithoutExposingAccess() throws Exception {
        var loader = new ProviderFixtureLoader(ProviderFixtureLoader.masked(FAKE_KEY));
        try (var fixture = new Fixture("HTTP/1.1 302 Found\r\nLocation: https://attacker.example/\r\nContent-Length: " + FAKE_KEY.length() + "\r\n\r\n" + FAKE_KEY)) {
            Exception failure = assertThrows(Exception.class, () -> fetchCurseForge(loader, fixture, URI.create("https://api.curseforge.com/v1/mods/123")));
            assertFalse(failure.toString().contains(FAKE_KEY));
            assertTrue(failure.getMessage().contains("302"));
        }
        try (var fixture = new Fixture("")) {
            Exception failure = assertThrows(Exception.class, () -> fetchCurseForge(loader, fixture, URI.create("https://api.modrinth.com/v1/mods/123")));
            assertFalse(failure.toString().contains(FAKE_KEY));
        }
    }

    @Test
    void rejectsRedirectsAndDoesNotEchoErrorBodiesOrKeys() throws Exception {
        try (var fixture = new Fixture("HTTP/1.1 302 Found\r\nLocation: https://attacker.example/\r\nContent-Length: " + FAKE_KEY.length() + "\r\n\r\n" + FAKE_KEY)) {
            Exception failure = assertThrows(Exception.class, () -> fetch(ProviderHttpClient.Service.MODRINTH, fixture, clientTls, new DiscoveryCancellation()));
            assertFalse(failure.toString().contains(FAKE_KEY));
            assertTrue(failure.getMessage().contains("302"));
            assertTrue(fixture.request.get(5, TimeUnit.SECONDS).toLowerCase(java.util.Locale.ROOT).contains("host: api.modrinth.com"));
        }
    }

    @Test
    void rejectsOversizedCompressedAndAmbiguousMetadata() throws Exception {
        for (String response : new String[] {
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + (ProviderHttpClient.MAX_BYTES + 1) + "\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nContent-Length: 2\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n" }) {
            try (var fixture = new Fixture(response)) {
                assertThrows(Exception.class, () -> fetch(ProviderHttpClient.Service.MODRINTH, fixture, clientTls, new DiscoveryCancellation()));
            }
        }
    }

    @Test
    void requiresTrustedTlsAndHonorsCancellation() throws Exception {
        try (var fixture = new Fixture("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")) {
            assertThrows(Exception.class, () -> fetch(ProviderHttpClient.Service.MODRINTH, fixture, SSLContext.getDefault(), new DiscoveryCancellation()));
        }
        var token = new DiscoveryCancellation();
        token.close();
        try (var fixture = new Fixture("")) {
            assertThrows(Exception.class, () -> fetch(ProviderHttpClient.Service.MODRINTH, fixture, clientTls, token));
        }
    }

    private static byte[] fetch(ProviderHttpClient.Service service, Fixture fixture, SSLContext tls, DiscoveryCancellation token) throws Exception {
        URI uri = URI.create("https://api.modrinth.com/v2/versions");
        return ProviderHttpClient.fetchPinned(service, uri, "", new InetSocketAddress(InetAddress.getLoopbackAddress(), fixture.listener.getLocalPort()), tls, token);
    }

    private static byte[] fetchCurseForge(ProviderFixtureLoader loader, Fixture fixture, URI uri) throws Exception {
        var type = loader.loadClass("net.neoforged.neoforge.neosync.provider.ProviderHttpClient");
        var serviceType = loader.loadClass("net.neoforged.neoforge.neosync.provider.ProviderHttpClient$Service");
        Object service = serviceType.getField("CURSEFORGE").get(null);
        var method = type.getMethod("fetchPinned", serviceType, URI.class, String.class, InetSocketAddress.class, SSLContext.class, DiscoveryCancellation.class);
        try {
            return (byte[]) method.invoke(null, service, uri, "", new InetSocketAddress(InetAddress.getLoopbackAddress(), fixture.listener.getLocalPort()), clientTls, new DiscoveryCancellation());
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final SSLServerSocket listener;
        final CompletableFuture<String> request = new CompletableFuture<>();

        Fixture(String response) throws IOException {
            listener = (SSLServerSocket) serverTls.getServerSocketFactory().createServerSocket(0, 4, InetAddress.getLoopbackAddress());
            listener.setSoTimeout(5000);
            CompletableFuture.runAsync(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    var bytes = new java.io.ByteArrayOutputStream();
                    while (bytes.size() < 8192) {
                        int next = input.read();
                        if (next < 0) throw new IOException("Incomplete fixture request.");
                        bytes.write(next);
                        if (bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
                    }
                    request.complete(bytes.toString(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException e) {
                    request.completeExceptionally(e);
                }
            });
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
