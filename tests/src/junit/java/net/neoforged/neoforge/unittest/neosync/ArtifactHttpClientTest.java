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

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.ArtifactHttpClient;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import net.neoforged.neoforge.neosync.protocol.SyncCapability;
import net.neoforged.neoforge.neosync.protocol.SyncEndpoint;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforge.neosync.server.HostedInventory;
import net.neoforged.neoforge.neosync.server.HostingPolicy;
import net.neoforged.neoforge.neosync.server.ManifestService;
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

    @Test
    void expiredTotalDeadlineRemovesThePartialFile(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture("HTTP/1.1 200 OK\r\n\r\ntest artifact bytes")) {
            Path target = directory.resolve("partial.jar");
            assertThrows(IOException.class, () -> ArtifactHttpClient.fetchPinned(fixture.uri(), InetAddress.getLoopbackAddress(), clientTls,
                    artifact(), target, new DiscoveryCancellation(), count -> {}, System.nanoTime() - 1));
            assertFalse(Files.exists(target));
        }
    }

    @Test
    @Timeout(40)
    void idleTimeoutClosesAStalledTlsResponse(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture("HTTP/1.1 200 OK\r\nContent-Length: 19\r\n\r\n", true)) {
            Path target = directory.resolve("partial.jar");
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> ArtifactHttpClient.fetchPinned(fixture.uri(), InetAddress.getLoopbackAddress(), clientTls,
                    artifact(), target, new DiscoveryCancellation(), count -> {}, start + TimeUnit.SECONDS.toNanos(38)));
            assertTrue(System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(29));
            assertFalse(Files.exists(target));
            fixture.served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void refusesSymlinkTargetsWithoutChangingTheirContents(@TempDir Path directory) throws Exception {
        Path personal = Files.writeString(directory.resolve("personal.txt"), "unchanged");
        Path target = Files.createSymbolicLink(directory.resolve("partial.jar"), personal);
        assertThrows(IOException.class, () -> ArtifactHttpClient.fetchPinned(URI.create("https://localhost:8443/file"), InetAddress.getLoopbackAddress(), clientTls,
                artifact(), target, new DiscoveryCancellation(), count -> {}, deadline()));
        assertTrue(Files.isSymbolicLink(target));
        assertEquals("unchanged", Files.readString(personal));
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

    @Test
    void downloadsHostedProfileOverTlsAndPreservesItOnFailedReplacement(@TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        var fingerprint = ArtifactFiles.fingerprint(jar, new DiscoveryCancellation());
        var root = JsonParser.parseString(new String(SyncProtocolTest.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        var file = root.getAsJsonArray("files").get(0).getAsJsonObject();
        file.addProperty("sha256", fingerprint.sha256());
        file.addProperty("size", fingerprint.size());
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        var endpoint = SyncEndpoint.create("localhost", 25575, new SyncCapability(8443, SyncManifest.sha256(bytes)));
        var plan = InstallationPlan.create(endpoint, bytes, Set.of(), null, "0.1.0-dev", "21.1.251", InetAddress.getLoopbackAddress());
        var store = ProfileStore.open(Files.createDirectory(directory.resolve("game")));
        var originalTls = SSLContext.getDefault();
        SSLContext.setDefault(clientTls);
        try (var inventory = new HostedInventory(directory.resolve("hosting"), 1024 * 1024)) {
            inventory.add(jar, fingerprint, new DiscoveryCancellation());
            var files = inventory.seal();
            try (var service = new ManifestService(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8443), serverTls, endpoint.manifestUri().getPath(), bytes,
                    "/.well-known/neosync/v1/servers/25575/files/", files, new HostingPolicy(true, 1024 * 1024, 8, 65536, 120))) {
                var prepared = store.prepare(plan, plan.accept(true, true), Map.of(), "4.0.44", new DiscoveryCancellation(), (action, count, total) -> {});
                var active = ProfileStore.open(prepared.gameDirectory());
                active.verify(prepared, "4.0.44", new DiscoveryCancellation());
                assertEquals(prepared, active.active().orElseThrow());
                assertTrue(Files.notExists(directory.resolve("game/mods")));
                var next = InstallationPlan.create(endpoint, bytes, Set.of(), plan.manifest(), "0.1.0-dev", "21.1.251", InetAddress.getLoopbackAddress());
                byte[] corrupt = Files.readAllBytes(jar);
                corrupt[0] ^= 1;
                Files.write(files.get(fingerprint.sha256()).path(), corrupt);
                assertThrows(IOException.class, () -> store.prepare(next, next.accept(true, true), Map.of(), "4.0.44", new DiscoveryCancellation(), (action, count, total) -> {}));
                assertEquals(prepared, store.prepared(plan.identity()).orElseThrow());
                store.verify(prepared, "4.0.44", new DiscoveryCancellation());
                Files.write(files.get(fingerprint.sha256()).path(), Files.readAllBytes(jar));
                var cancellation = new DiscoveryCancellation();
                assertThrows(IOException.class, () -> store.prepare(next, next.accept(true, true), Map.of(), "4.0.44", cancellation,
                        (action, count, total) -> {
                            if (action.startsWith("Downloading")) cancellation.close();
                        }));
                assertEquals(prepared, store.prepared(plan.identity()).orElseThrow());
                try (var staging = Files.list(store.root().resolve("staging"))) {
                    assertEquals(0, staging.count());
                }
                var consentPath = prepared.gameDirectory().getParent().resolve("consent.json");
                String record = Files.readString(consentPath);
                Files.writeString(consentPath, record.replace("/servers/25575/files/", "/servers/25576/files/"));
                assertThrows(IOException.class, () -> ProfileStore.open(prepared.gameDirectory()));
            }
        } finally {
            SSLContext.setDefault(originalTls);
        }
    }

    @Test
    void publishesMixedManualAndIndependentHostedArtifactsAtomically(@TempDir Path directory) throws Exception {
        Path manualJar = JarMetadataTest.jar(Files.createDirectory(directory.resolve("manual")), JarMetadataTest.TOML, Map.of());
        Path privateJar = JarMetadataTest.jar(Files.createDirectory(directory.resolve("private")), JarMetadataTest.TOML.replace("test_mod", "private_mod"), Map.of());
        var privateHash = ArtifactFiles.fingerprint(privateJar, new DiscoveryCancellation());
        var root = JsonParser.parseString(new String(CurseForgeProviderTest.manifest(manualJar, true), StandardCharsets.UTF_8)).getAsJsonObject();
        var extra = JsonParser.parseString(new String(SyncProtocolTest.manifest(), StandardCharsets.UTF_8).replace("test_mod", "private_mod")).getAsJsonObject().getAsJsonArray("files").get(0).getAsJsonObject();
        extra.addProperty("sha256", privateHash.sha256());
        extra.addProperty("size", privateHash.size());
        extra.addProperty("fileName", "private.jar");
        root.getAsJsonArray("files").add(extra);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        var manifest = SyncManifest.parse(bytes);
        var sources = CurseForgeProviderTest.fixtureSources(CurseForgeProviderTest.manifest(manualJar, true));
        var endpoint = SyncEndpoint.create("localhost", 25575, new SyncCapability(8443, SyncManifest.sha256(bytes)));
        var plan = InstallationPlan.create(endpoint, bytes, Set.of(), null, "0.1.0-dev", "21.1.251", InetAddress.getLoopbackAddress(), sources);
        var store = ProfileStore.open(Files.createDirectory(directory.resolve("game")));
        var originalTls = SSLContext.getDefault();
        SSLContext.setDefault(clientTls);
        try (var inventory = new HostedInventory(directory.resolve("hosting"), 1024 * 1024)) {
            inventory.add(privateJar, privateHash, new DiscoveryCancellation());
            try (var service = new ManifestService(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8443), serverTls, endpoint.manifestUri().getPath(), bytes,
                    "/.well-known/neosync/v1/servers/25575/files/", inventory.seal(), new HostingPolicy(true, 1024 * 1024, 8, 65536, 120));
                    var imported = net.neoforged.neoforge.neosync.manual.ManualDownloads.collect(plan, plan.accept(true, true), directory.resolve("imports"), "4.0.44",
                            new net.neoforged.neoforge.neosync.manual.ManualDownloads.Controls(manualJar.getParent(), () -> null, status -> {}, page -> {}, java.time.Duration.ofSeconds(2)), new DiscoveryCancellation())) {
                var prepared = store.prepare(plan, plan.accept(true, true), imported.files(), "4.0.44", new DiscoveryCancellation(), (a, b, c) -> {});
                store.verify(prepared, "4.0.44", new DiscoveryCancellation());
                assertEquals(2, prepared.manifest().files().size());
                var next = InstallationPlan.create(endpoint, bytes, Set.of(), manifest, "0.1.0-dev", "21.1.251", InetAddress.getLoopbackAddress(), sources);
                assertThrows(IOException.class, () -> store.prepare(next, next.accept(true, true), Map.of(), "4.0.44", new DiscoveryCancellation(), (a, b, c) -> {}));
                assertEquals(prepared, store.prepared(plan.identity()).orElseThrow());
                assertEquals(manifest.files().getFirst().sha256(), ArtifactFiles.fingerprint(manualJar, new DiscoveryCancellation()).sha256());
            }
        } finally {
            SSLContext.setDefault(originalTls);
        }
    }

    @Test
    void hostedApprovalNeverAuthorizesRedirectsOrExternalLanSources(@TempDir Path directory) throws Exception {
        byte[] bytes = SyncProtocolTest.manifest();
        var endpoint = SyncEndpoint.create("localhost", 25575, new SyncCapability(8443, SyncManifest.sha256(bytes)));
        var unapproved = InstallationPlan.create(endpoint, bytes, Set.of(), null, "0.1.0-dev", "21.1.251");
        Path target = directory.resolve("partial.jar");
        assertThrows(IOException.class, () -> ArtifactHttpClient.download(unapproved, unapproved.accept(true, true), unapproved.files().getFirst(), target, new DiscoveryCancellation(), count -> {}));
        var plan = InstallationPlan.create(endpoint, bytes, Set.of(), null, "0.1.0-dev", "21.1.251", InetAddress.getLoopbackAddress());
        var originalTls = SSLContext.getDefault();
        SSLContext.setDefault(clientTls);
        try (var fixture = new Fixture("HTTP/1.1 302 Found\r\nLocation: /other-file\r\nContent-Length: 0\r\n\r\n", false, 8443)) {
            var failure = assertThrows(IOException.class, () -> ArtifactHttpClient.download(plan, plan.accept(true, true), plan.files().getFirst(), target, new DiscoveryCancellation(), count -> {}));
            assertTrue(failure.getMessage().contains("must not redirect"));
            fixture.served.get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(target));
        } finally {
            SSLContext.setDefault(originalTls);
        }
        byte[] external = new String(InstallationPlanTest.manifest(), StandardCharsets.UTF_8).replace("example.org", "localhost").getBytes(StandardCharsets.UTF_8);
        var externalEndpoint = SyncEndpoint.create("localhost", 25575, new SyncCapability(8443, SyncManifest.sha256(external)));
        var externalPlan = InstallationPlan.create(externalEndpoint, external, Set.of(), null, "0.1.0-dev", "21.1.251", InetAddress.getLoopbackAddress());
        assertThrows(IOException.class, () -> ArtifactHttpClient.download(externalPlan, externalPlan.accept(true, true), externalPlan.files().getFirst(), target, new DiscoveryCancellation(), count -> {}));
        assertFalse(Files.exists(target));
    }

    private static final class Fixture implements AutoCloseable {
        final SSLServerSocket listener;
        final CompletableFuture<Void> served;

        Fixture(String response) throws IOException {
            this(response, false);
        }

        Fixture(String response, boolean stall) throws IOException {
            this(response, stall, 0);
        }

        Fixture(String response, boolean stall, int port) throws IOException {
            listener = (SSLServerSocket) serverTls.getServerSocketFactory().createServerSocket(port, 1, InetAddress.getLoopbackAddress());
            served = CompletableFuture.runAsync(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(stall ? 35000 : 5000);
                    var input = socket.getInputStream();
                    int state = 0;
                    int read;
                    while (state < 4 && (read = input.read()) != -1) state = read == "\r\n\r\n".charAt(state) ? state + 1 : 0;
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    if (stall) assertEquals(-1, input.read());
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
