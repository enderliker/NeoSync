/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

public enum ModEnvironment {
    CLIENT,
    BOTH,
    SERVER,
    UNKNOWN;

    public boolean clientDownload() {
        return this == CLIENT || this == BOTH;
    }
}
