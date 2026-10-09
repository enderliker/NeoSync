/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.neosync;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class ProviderFixtureLoader extends ClassLoader {
    private final byte[] access;

    ProviderFixtureLoader(byte[] access) {
        super(ProviderFixtureLoader.class.getClassLoader());
        this.access = access == null ? null : access.clone();
    }

    static byte[] masked(String key) {
        byte[] clear = key.getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = new byte[clear.length * 2];
        for (int index = 0; index < clear.length; index++) {
            encoded[index * 2] = (byte) 0xa5;
            encoded[index * 2 + 1] = (byte) (clear[index] ^ 0xa5);
        }
        return encoded;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (name.equals("META-INF/neosync/provider-access.bin")) return access == null ? null : new ByteArrayInputStream(access);
        return super.getResourceAsStream(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith("net.neoforged.neoforge.neosync.provider.")) return super.loadClass(name, resolve);
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) throw new ClassNotFoundException(name);
                    byte[] bytes = input.readAllBytes();
                    type = defineClass(name, bytes, 0, bytes.length);
                } catch (IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }
}
