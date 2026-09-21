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
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import net.neoforged.neoforge.neosync.protocol.ArtifactHttpClient;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class ArtifactHttpClientTest {
    private static SSLContext serverTls;
    private static SSLContext clientTls;
    private static final byte[] BODY = "test artifact bytes".getBytes(StandardCharsets.US_ASCII);

    @BeforeAll
    static void certificates(@TempDir Path directory) throws Exception {
        Path storePath = directory.resolve("test.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        var process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-validity", "1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", storePath.toString(), "-storepass", "test-password", "-noprompt")
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

    private static SyncManifest.Artifact artifact() {
        return new SyncManifest.Artifact(SyncManifest.sha256(BODY), BODY.length, "test.jar", List.of(), List.of());
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    }

    @Test
    void streamsVerifiedBytesWithoutContentLength(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\ntest artifact bytes")) {
            Path target = directory.resolve("partial.jar");
            ArtifactHttpClient.fetchPinned(fixture.uri(), InetAddress.getLoopbackAddress(), clientTls, artifact(), target, new DiscoveryCancellation(), count -> {}, deadline());
            assertArrayEquals(BODY, Files.readAllBytes(target));
            fixture.served.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nx",
            "HTTP/1.1 200 OK\r\nContent-Length: 19\r\n\r\ntruncated",
            "HTTP/1.1 200 OK\r\n\r\ntest artifact bytes overflow",
            "HTTP/1.1 200 OK\r\n\r\nwrong artifact byte",
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\ntest artifact bytes",
            "HTTP/1.1 404 Not Found\r\n\r\nerror",
            "HTTP/1.1 200 OK\r\nContent-Length: 19\r\nTransfer-Encoding: chunked\r\n\r\n13\r\ntest artifact bytes\r\n0\r\n\r\n" })
    void rejectsInvalidBodiesAndRemovesPartialFiles(String response, @TempDir Path directory) throws Exception {
        try (var fixture = new Fixture(response)) {
            Path target = directory.resolve("partial.jar");
            assertThrows(IOException.class, () -> ArtifactHttpClient.fetchPinned(fixture.uri(), InetAddress.getLoopbackAddress(), clientTls, artifact(), target,
                    new DiscoveryCancellation(), count -> {}, deadline()));
            assertFalse(Files.exists(target));
        }
    }

    @Test
    void checksTlsCertificateAndLogicalHostname(@TempDir Path directory) throws Exception {
        for (boolean wrongHost : new boolean[] { true, false }) {
            try (var fixture = new Fixture("HTTP/1.1 200 OK\r\n\r\ntest artifact bytes")) {
                URI uri = wrongHost ? URI.create(fixture.uri().toString().replace("localhost", "wrong.invalid")) : fixture.uri();
                Path target = directory.resolve("partial.jar");
                assertThrows(IOException.class, () -> ArtifactHttpClient.fetchPinned(uri, InetAddress.getLoopbackAddress(), wrongHost ? clientTls : SSLContext.getDefault(),
                        artifact(), target, new DiscoveryCancellation(), count -> {}, deadline()));
                assertFalse(Files.exists(target));
            }
        }
    }

    @Test
    void returnsRedirectWithoutRequestingNewOriginOrWritingItsBody(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture("HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1/private\r\n\r\nunwanted body")) {
            Path target = directory.resolve("partial.jar");
            assertEquals(URI.create("https://127.0.0.1/private"), ArtifactHttpClient.fetchPinned(fixture.uri(), InetAddress.getLoopbackAddress(), clientTls,
                    artifact(), target, new DiscoveryCancellation(), count -> {
                        throw new AssertionError("Redirect body written");
                    }, deadline()));
            assertFalse(Files.exists(target));
        }
    }

    @Test
    void cancellationDuringStreamingRemovesPartialFile(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture("HTTP/1.1 200 OK\r\n\r\ntest artifact bytes")) {
            Path target = directory.resolve("partial.jar");
            var token = new DiscoveryCancellation();
            assertThrows(IOException.class, () -> ArtifactHttpClient.fetchPinned(fixture.uri(), InetAddress.getLoopbackAddress(), clientTls,
                    artifact(), target, token, count -> token.close(), deadline()));
            assertFalse(Files.exists(target));
        }
    }

    @Test
    void neverReplacesAnExistingFile(@TempDir Path directory) throws Exception {
        Path target = Files.writeString(directory.resolve("existing.jar"), "personal file");
        assertThrows(IOException.class, () -> ArtifactHttpClient.fetchPinned(URI.create("https://localhost:8443/file"), InetAddress.getLoopbackAddress(), clientTls,
                artifact(), target, new DiscoveryCancellation(), count -> {}, deadline()));
        assertEquals("personal file", Files.readString(target));
    }

    @ParameterizedTest
    @ValueSource(strings = { "127.0.0.1", "192.168.1.1", "169.254.169.254", "[::1]", "[::ffff:127.0.0.1]" })
    void externalSourcesCannotInheritLanPermission(String host, @TempDir Path directory) throws Exception {
        byte[] bytes = new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8).replace("https://example.org/", "https://" + host + "/").getBytes(StandardCharsets.UTF_8);
        var plan = InstallationPlanTest.plan(bytes);
        Path target = directory.resolve("partial.jar");
        assertThrows(IOException.class, () -> ArtifactHttpClient.download(plan, plan.accept(true, true), plan.files().getFirst(), target, new DiscoveryCancellation(), count -> {}));
        assertFalse(Files.exists(target));
    }

    private static final class Fixture implements AutoCloseable {
        final SSLServerSocket listener;
        final CompletableFuture<Void> served;

        Fixture(String response) throws IOException {
            listener = (SSLServerSocket) serverTls.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress());
            served = CompletableFuture.runAsync(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    int state = 0;
                    int read;
                    while (state < 4 && (read = input.read()) != -1) state = read == "\r\n\r\n".charAt(state) ? state + 1 : 0;
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // Invalid TLS and early cancellation intentionally close the fixture connection.
                }
            });
        }

        URI uri() {
            return URI.create("https://localhost:" + listener.getLocalPort() + "/artifact");
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
