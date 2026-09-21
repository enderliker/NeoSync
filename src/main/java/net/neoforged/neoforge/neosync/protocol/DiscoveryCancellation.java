/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import java.io.IOException;
import org.jetbrains.annotations.Nullable;

public final class DiscoveryCancellation implements AutoCloseable {
    private boolean cancelled;
    @Nullable
    private AutoCloseable active;

    public synchronized void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Discovery was cancelled.");
    }

    public synchronized void attach(AutoCloseable resource) throws IOException {
        if (cancelled) {
            closeQuietly(resource);
            throw new IOException("Discovery was cancelled.");
        }
        active = resource;
    }

    public synchronized void detach() {
        active = null;
    }

    @Override
    public synchronized void close() {
        cancelled = true;
        if (active != null) closeQuietly(active);
        active = null;
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {}
    }
}
