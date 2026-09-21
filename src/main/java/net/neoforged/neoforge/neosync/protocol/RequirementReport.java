/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RequirementReport(boolean ready, int missingFiles, List<String> lines) {
    public RequirementReport {
        lines = List.copyOf(lines);
    }

    public static RequirementReport compare(SyncManifest manifest, Set<String> loadedHashes, Map<String, String> loadedMods,
            String loaderVersion, String neoForgeVersion) {
        var lines = new ArrayList<String>();
        boolean ready = manifest.loaderVersion().equals(loaderVersion) && manifest.neoForgeVersion().equals(neoForgeVersion);
        lines.add(manifest.displayName());
        int modCount = manifest.files().stream().mapToInt(file -> file.mods().size()).sum();
        lines.add("Required: " + count(modCount, "mod") + " in " + count(manifest.files().size(), "file") + ".");
        if (!ready) lines.add("Required loader: NeoSync " + manifest.loaderVersion() + " with NeoForge " + manifest.neoForgeVersion() + ". Your loader version differs.");
        int missing = 0;
        for (var file : manifest.files()) {
            boolean exactFile = loadedHashes.contains(file.sha256());
            boolean activeMods = file.mods().stream().allMatch(mod -> mod.version().equals(loadedMods.get(mod.id())));
            if (!exactFile || !activeMods) {
                missing++;
                ready = false;
            }
            lines.add("");
            lines.add(file.fileName() + " — " + (exactFile && activeMods ? "available" : "missing or different") + " — " + file.size() + " bytes");
            for (var mod : file.mods()) {
                String current = loadedMods.get(mod.id());
                lines.add(mod.displayName() + " (" + mod.id() + ") " + mod.version()
                        + (current == null ? " — not loaded" : " — loaded: " + current));
                for (var dependency : mod.dependencies()) {
                    String installed = loadedMods.get(dependency.id());
                    if (installed != null && dependency.range().containsVersion(new org.apache.maven.artifact.versioning.DefaultArtifactVersion(installed))) {
                        if (dependency.type().equals("incompatible")) {
                            ready = false;
                            lines.add("Conflicting installed mod: " + dependency.id() + " " + installed + " is incompatible with " + mod.id() + ".");
                        } else if (dependency.type().equals("discouraged")) {
                            lines.add("Discouraged combination: " + dependency.id() + " " + dependency.range());
                        }
                    }
                }
            }
            for (var source : file.sources()) {
                lines.add(source.type().equals("server") ? "Source: provided by this server (unverified)"
                        : "Source: " + source.url().getHost() + " (unverified)");
            }
        }
        lines.add(2, count(missing, "file") + (missing == 1 ? " needs" : " need") + " installation or replacement.");
        lines.add(3, ready ? "The listed requirements are available. Normal server compatibility checks will still run."
                : "You cannot join with this mod set. An isolated profile and a new launch are required to apply different mods.");
        return new RequirementReport(ready, missing, lines);
    }

    private static String count(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
