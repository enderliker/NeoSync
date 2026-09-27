/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import net.neoforged.neoforge.neosync.server.AdminSecrets;
import net.neoforged.neoforge.neosync.server.AdminSelection;
import net.neoforged.neoforge.neosync.server.AdminService;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(60)
class AdminServiceTest {
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void protectsInventoryAndSelectionWithSessionOriginAndCsrf(boolean secure, @TempDir Path directory) throws Exception {
        Path jar = JarMetadataTest.jar(directory, JarMetadataTest.TOML, Map.of());
        Path config = directory.resolve("config/neosync-server.json");
        var selection = new AdminSelection(config, List.of(new AdminSelection.Candidate(jar, "<script>inventory</script>")), 25565);
        var secrets = AdminSecrets.open(directory.resolve("private"));
        var tls = secure ? trust(directory.resolve("private")) : null;
        try (var service = new AdminService(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), secrets, selection, secure)) {
            assertTrue(send(tls, service, "GET", "/api/state", "", "", "", "", "").startsWith("HTTP/1.1 401"));
            String page = send(tls, service, "GET", "/", "", "", "", "", "");
            assertTrue(page.contains("frame-ancestors 'none'"));
            assertTrue(page.contains("Server administration"));
            assertFalse(page.contains("<script>inventory</script>"));
            String password = Files.readString(directory.resolve("private/password.txt")).strip();
            var login = new JsonObject();
            login.addProperty("password", password);
            assertTrue(send(tls, service, "POST", "/api/login", login.toString(), "https://attacker.example", "", "", "").startsWith("HTTP/1.1 403"));
            assertTrue(send(tls, service, "GET", "/", "", "", "", "", "attacker.example:" + service.port()).startsWith("HTTP/1.1 400"));
            String origin = (secure ? "https" : "http") + "://127.0.0.1:" + service.port();
            String wrongScheme = (secure ? "http" : "https") + "://127.0.0.1:" + service.port();
            assertTrue(send(tls, service, "POST", "/api/login", login.toString(), wrongScheme, "", "", "").startsWith("HTTP/1.1 403"));
            String loggedIn = send(tls, service, "POST", "/api/login", login.toString(), origin, "", "", "");
            assertTrue(loggedIn.startsWith("HTTP/1.1 200"));
            String header = loggedIn.lines().filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("set-cookie:")).findFirst().orElseThrow();
            assertTrue(header.contains("HttpOnly; SameSite=Strict"));
            org.junit.jupiter.api.Assertions.assertEquals(secure, header.contains("Secure;"));
            assertTrue(header.contains(secure ? "__Host-neosync=" : "neosync-http="));
            String cookie = header.substring(header.indexOf(':') + 1).strip().split(";", 2)[0];
            assertFalse(loggedIn.contains(password));
            String stateResponse = send(tls, service, "GET", "/api/state", "", "", cookie, "", "");
            assertTrue(stateResponse.startsWith("HTTP/1.1 200"));
            var state = JsonParser.parseString(body(stateResponse)).getAsJsonObject();
            String csrf = state.get("csrf").getAsString();
            var request = AdminSelectionTest.request(selection, "modrinth");
            assertTrue(send(tls, service, "POST", "/api/selection", request.toString(), origin, cookie, "wrong", "").startsWith("HTTP/1.1 403"));
            assertFalse(Files.exists(config));
            assertTrue(send(tls, service, "POST", "/api/selection", request.toString(), origin, cookie, csrf, "").startsWith("HTTP/1.1 200"));
            assertTrue(Files.readString(config).contains("resolveProviders"));
            assertTrue(send(tls, service, "GET", "/../private/password.txt", "", "", cookie, "", "").startsWith("HTTP/1.1 404"));
            assertTrue(send(tls, service, "POST", "/api/logout", "{}", origin, cookie, csrf, "").startsWith("HTTP/1.1 200"));
            assertTrue(send(tls, service, "GET", "/api/state", "", "", cookie, "", "").startsWith("HTTP/1.1 401"));
            for (int i = 0; i < 10; i++) send(tls, service, "POST", "/api/login", "{\"password\":\"wrong\"}", origin, "", "", "");
            assertTrue(send(tls, service, "POST", "/api/login", login.toString(), origin, "", "", "").startsWith("HTTP/1.1 429"));
        }
    }

    private static String body(String response) {
        return response.substring(response.indexOf("\r\n\r\n") + 4);
    }

    private static String send(SSLContext tls, AdminService service, String method, String path, String body, String origin, String cookie, String csrf, String host) throws Exception {
        try (var socket = (tls == null ? javax.net.SocketFactory.getDefault() : tls.getSocketFactory()).createSocket("127.0.0.1", service.port())) {
            socket.setSoTimeout(5000);
            String request = method + " " + path + " HTTP/1.1\r\nHost: " + (host.isEmpty() ? "127.0.0.1:" + service.port() : host)
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                    + "\r\nOrigin: " + origin + "\r\nCookie: " + cookie + "\r\nX-NeoSync-CSRF: " + csrf + "\r\nConnection: close\r\n\r\n" + body;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readNBytes(262144), StandardCharsets.UTF_8);
        }
    }

    private static SSLContext trust(Path directory) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(directory.resolve("tls.p12"))) {
            store.load(input, Files.readString(directory.resolve("keystore-password.txt")).strip().toCharArray());
        }
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        var result = SSLContext.getInstance("TLS");
        result.init(null, trust.getTrustManagers(), null);
        return result;
    }
}
