/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public final class RevisionCheck {
    private RevisionCheck() {}

    public static boolean matches(SyncEndpoint endpoint, SyncRevision revision, ProfileStore.Prepared active,
            Set<String> loadedHashes, Map<String, String> loadedMods, String loaderVersion, String neoForgeVersion) throws IOException {
        var identity = new InstallationPlan.Identity(endpoint.host(), endpoint.gamePort(), InstallationPlan.origin(endpoint.manifestUri()), revision.serverId());
        return revision.manifestSha256().equals(endpoint.digest()) && revision.manifestSha256().equals(active.digest())
                && active.identity().equals(identity) && active.manifest().serverId().equals(revision.serverId())
                && revision.matchesManifest(active.manifest())
                && RequirementReport.compare(active.manifest(), loadedHashes, loadedMods, loaderVersion, neoForgeVersion).ready();
    }
}
