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

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.neoforged.neoforge.neosync.protocol.SyncCapability;
import net.neoforged.neoforge.neosync.protocol.SyncEndpoint;
import net.neoforged.neoforge.neosync.server.AdminSelection;
import net.neoforged.neoforge.neosync.server.ServerTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerTransportTest {
    @Test
    void defaultsToHttpsAndRequiresUnambiguousTransport() throws Exception {
        var config = new JsonObject();
        var defaults = ServerTransport.parse(config);
        assertEquals("managed-https", defaults.mode());
        assertEquals("https", defaults.adminTransport());
        assertEquals(6742, defaults.adminPort());
        assertEquals(8443, defaults.advertisedPort());
        config.addProperty("mode", "http");
        var http = ServerTransport.parse(config);
        assertEquals(8080, http.advertisedPort());
        assertEquals("https", http.adminTransport());
        config.addProperty("adminTransport", "http");
        assertEquals("http", ServerTransport.parse(config).adminTransport());
        config.addProperty("httpsPort", 8443);
        assertThrows(IOException.class, () -> ServerTransport.parse(config));
        config.remove("httpsPort");
        config.addProperty("httpPort", 6742);
        assertThrows(IOException.class, () -> ServerTransport.parse(config));
        config.remove("httpPort");
        config.addProperty("adminTransport", "automatic");
        assertThrows(IOException.class, () -> ServerTransport.parse(config));
    }

    @Test
    void acceptsCustomAdministratorPortAndRejectsListenerCollision() throws Exception {
        var config = new JsonObject();
        config.addProperty("adminPort", 7654);
        assertEquals(7654, ServerTransport.parse(config).adminPort());
        config.addProperty("adminPort", 0);
        assertThrows(IOException.class, () -> ServerTransport.parse(config));
        config.addProperty("adminPort", 7654);
        config.addProperty("port", 7654);
        config.addProperty("enabled", true);
        assertThrows(IOException.class, () -> ServerTransport.parse(config));
    }

    @Test
    void httpDiscoveryRequiresVersionTwoAndRejectsAmbiguousAnnouncements() throws Exception {
        var http = new SyncCapability(8080, SyncProtocolTest.HASH, "http");
        assertEquals(http, SyncCapability.parse(http.toJson()));
        assertEquals("http", SyncEndpoint.create("localhost", 25565, http).manifestUri().getScheme());
        assertEquals("https", SyncEndpoint.create("localhost", 25565, new SyncCapability(8443, SyncProtocolTest.HASH)).manifestUri().getScheme());
        var downgraded = http.toJson();
        downgraded.getAsJsonArray("protocols").set(0, new com.google.gson.JsonPrimitive(1));
        assertThrows(IOException.class, () -> SyncCapability.parse(downgraded));
        var ambiguous = http.toJson();
        ambiguous.addProperty("httpsPort", 8443);
        assertThrows(IOException.class, () -> SyncCapability.parse(ambiguous));
        assertThrows(IOException.class, () -> SyncEndpoint.create("localhost", 25565, new SyncCapability(8443, SyncProtocolTest.HASH, "http")));
    }

    @Test
    void savesIndependentTransportsTransactionallyAndRestoresHttps(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config/neosync-server.json");
        var selection = new AdminSelection(config, List.of(), 25565);
        var request = request(selection);
        request.addProperty("transport", "http");
        request.addProperty("adminTransport", "http");
        selection.save(request.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("http", selection.state().get("transport").getAsString());
        assertEquals("http", selection.state().get("adminTransport").getAsString());
        assertEquals(8080, selection.transport().advertisedPort());
        assertTrue(selection.state().get("restartRequired").getAsBoolean());
        byte[] saved = Files.readAllBytes(config);
        var invalid = request(selection);
        invalid.addProperty("adminTransport", "ftp");
        assertThrows(IOException.class, () -> selection.save(invalid.toString().getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(saved, Files.readAllBytes(config));
        var restore = request(selection);
        restore.addProperty("transport", "https");
        restore.addProperty("adminTransport", "https");
        selection.save(restore.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("managed-https", selection.transport().mode());
        assertEquals(8443, selection.transport().advertisedPort());
        assertFalse(Files.readString(config).contains("httpPort"));
    }

    private JsonObject request(AdminSelection selection) throws IOException {
        var request = new JsonObject();
        request.add("revision", selection.state().get("revision"));
        request.addProperty("enabled", true);
        request.addProperty("displayName", "Local server");
        request.add("files", new com.google.gson.JsonArray());
        return request;
    }
}
