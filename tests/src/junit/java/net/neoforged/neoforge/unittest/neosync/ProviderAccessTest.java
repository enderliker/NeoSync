/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import org.junit.jupiter.api.Test;

class ProviderAccessTest {
    private static final String KEY = "neosync-fixture-not-a-real-key";

    @Test
    void decodesMaskedAccessWithoutDependingOnTheBuildCredential() throws Exception {
        assertEquals(KEY, decode(ProviderFixtureLoader.masked(KEY)));
        assertEquals("", decode(null));
    }

    @Test
    void rejectsMalformedAccessWithoutIncludingDecodedBytesInErrors() {
        for (byte[] access : new byte[][] { new byte[0], new byte[] { 1 }, new byte[8194], ProviderFixtureLoader.masked("invalid key") }) {
            var failure = assertThrows(InvocationTargetException.class, () -> decode(access));
            assertInstanceOf(IOException.class, failure.getCause());
            assertFalse(failure.getCause().toString().contains(KEY));
            assertFalse(failure.getCause().toString().contains("invalid key"));
        }
    }

    @Test
    void missingAccessKeepsModrinthAvailableAndRejectsCurseForgeBeforeNetwork() throws Exception {
        var loader = new ProviderFixtureLoader(null);
        var clientType = loader.loadClass("net.neoforged.neoforge.neosync.provider.ProviderHttpClient");
        var serviceType = loader.loadClass("net.neoforged.neoforge.neosync.provider.ProviderHttpClient$Service");
        var client = clientType.getConstructor().newInstance();
        var available = clientType.getMethod("available", serviceType);
        Object modrinth = serviceType.getField("MODRINTH").get(null);
        Object curseForge = serviceType.getField("CURSEFORGE").get(null);
        assertTrue((boolean) available.invoke(client, modrinth));
        assertFalse((boolean) available.invoke(client, curseForge));
        var request = clientType.getMethod("request", serviceType, String.class, String.class, DiscoveryCancellation.class);
        var failure = assertThrows(InvocationTargetException.class,
                () -> request.invoke(client, curseForge, "/v1/mods/123", "", new DiscoveryCancellation()));
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("no CurseForge API access"));
    }

    private static String decode(byte[] access) throws Exception {
        var loader = new ProviderFixtureLoader(access);
        var type = loader.loadClass("net.neoforged.neoforge.neosync.provider.ProviderAccess");
        var method = type.getDeclaredMethod("curseForge");
        method.setAccessible(true);
        return (String) method.invoke(null);
    }
}
