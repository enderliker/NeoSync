/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import java.io.IOException;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;

@FunctionalInterface
public interface ProviderTransport {
    /** An empty result means HTTP 404; errors and restrictions must remain distinct. */
    byte[] request(ProviderHttpClient.Service service, String path, String body, DiscoveryCancellation cancellation) throws IOException;
}
