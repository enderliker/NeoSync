/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;
import net.neoforged.neoforge.neosync.protocol.ManifestHttpClient;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;
import net.neoforged.neoforge.neosync.protocol.RequirementReport;
import net.neoforged.neoforge.neosync.protocol.StatusQuery;
import net.neoforged.neoforge.neosync.protocol.SyncEndpoint;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import org.jetbrains.annotations.Nullable;

public final class NeoSyncClient {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                var thread = new Thread(runnable, "NeoSync client work");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private NeoSyncClient() {}

    public static void begin(Minecraft minecraft, Screen parent, ServerAddress address, Runnable connect) {
        new Attempt(minecraft, parent, address, connect).start();
    }

    private static final class Attempt {
        private final Minecraft minecraft;
        private final ServerAddress address;
        private final Runnable connect;
        private final DiscoveryScreen screen;
        private DiscoveryCancellation cancellation = new DiscoveryCancellation();
        private int generation;
        private boolean finished;

        Attempt(Minecraft minecraft, Screen parent, ServerAddress address, Runnable connect) {
            this.minecraft = minecraft;
            this.address = address;
            this.connect = connect;
            this.screen = new DiscoveryScreen(parent, this::cancel);
        }

        void start() {
            minecraft.setScreen(screen);
            discover();
        }

        void cancel() {
            finished = true;
            generation++;
            cancellation.close();
        }

        void proceed() {
            if (finished || minecraft.screen != screen) return;
            finished = true;
            cancellation.close();
            connect.run();
        }

        void discover() {
            screen.show(List.of("Checking this server's mod requirements..."), "Cancel", "", null);
            run(token -> {
                var store = ProfileStore.open(minecraft.gameDirectory.toPath());
                if (store.active().isPresent()) store.verify(store.active().get(), FMLLoader.versionInfo().fmlVersion(), token);
                var resolved = ServerNameResolver.DEFAULT.resolveAddress(address).orElseThrow(() -> new IOException("The server address could not be resolved."));
                token.check();
                return StatusQuery.query(resolved.asInetSocketAddress(), address.getHost(), address.getPort(), SharedConstants.getCurrentVersion().getProtocolVersion(), token);
            }, capability -> {
                if (capability.isEmpty()) {
                    proceed();
                } else {
                    try {
                        resolveEndpoint(SyncEndpoint.create(address.getHost(), address.getPort(), capability.get()));
                    } catch (IOException e) {
                        error(e, false);
                    }
                }
            }, true);
        }

        void resolveEndpoint(SyncEndpoint endpoint) {
            run(token -> {
                InetAddress[] addresses = InetAddress.getAllByName(endpoint.host());
                token.check();
                var local = new ArrayList<InetAddress>();
                for (var candidate : addresses) {
                    if (SyncEndpoint.isPublic(candidate)) return new Destination(candidate, false);
                    if (SyncEndpoint.isLocal(candidate)) local.add(candidate);
                }
                if (!local.isEmpty()) return new Destination(local.getFirst(), true);
                throw new IOException("The manifest address is blocked by the network destination policy.");
            }, destination -> {
                if (destination.local) {
                    screen.show(List.of("The manifest service for " + endpoint.host() + ":" + endpoint.httpsPort() + " is on your local network.",
                            "Allow an HTTPS request to this server endpoint? Certificate verification remains required. This does not download any mods."),
                            "No, cancel", "Allow this endpoint", () -> fetch(endpoint, destination.address));
                } else {
                    fetch(endpoint, destination.address);
                }
            }, false);
        }

        void fetch(SyncEndpoint endpoint, InetAddress approvedAddress) {
            screen.show(List.of("Reading this server's mod requirements..."), "Cancel", "", null);
            run(token -> {
                byte[] bytes = ManifestHttpClient.fetch(endpoint, approvedAddress, SSLContext.getDefault(), token);
                var manifest = SyncManifest.parse(bytes);
                var store = ProfileStore.open(minecraft.gameDirectory.toPath());
                if (store.active().isPresent()) store.verify(store.active().get(), FMLLoader.versionInfo().fmlVersion(), token);
                var hashes = new HashSet<String>();
                var localFiles = new HashMap<String, java.nio.file.Path>();
                var versions = new HashMap<String, String>();
                ModList.get().getMods().forEach(mod -> versions.put(mod.getModId(), mod.getVersion().toString()));
                var visited = new HashSet<java.nio.file.Path>();
                long inspected = 0;
                for (var info : ModList.get().getModFiles()) {
                    token.check();
                    var path = info.getFile().getFilePath();
                    if (path.getFileSystem() != java.nio.file.FileSystems.getDefault()
                            || !visited.add(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || !path.getFileName().toString().endsWith(".jar"))
                        continue;
                    if (visited.size() > 2048) throw new IOException("The local mod inventory exceeds the file limit.");
                    var fingerprint = ArtifactFiles.fingerprint(path, token);
                    inspected += fingerprint.size();
                    if (inspected > SyncManifest.MAX_TOTAL_BYTES) throw new IOException("The local mod inventory exceeds the size limit.");
                    hashes.add(fingerprint.sha256());
                    localFiles.put(fingerprint.sha256(), path);
                }
                var report = RequirementReport.compare(manifest, hashes, versions, SyncManifest.NEOSYNC_VERSION, NeoForgeVersion.getVersion());
                var identity = new InstallationPlan.Identity(endpoint.host(), endpoint.gamePort(), InstallationPlan.origin(endpoint.manifestUri()), manifest.serverId());
                var pending = store.prepared(identity).orElse(null);
                boolean ready = report.ready() && (store.active().isEmpty()
                        || store.active().get().identity().equals(identity) && store.active().get().digest().equals(endpoint.digest()));
                if (pending != null && pending.digest().equals(endpoint.digest()) && (store.active().isEmpty() || !store.active().get().equals(pending))) {
                    store.verify(pending, FMLLoader.versionInfo().fmlVersion(), token);
                    return new Review(report, false, store, null, Map.of(), pending, null);
                }
                if (ready) return new Review(report, true, store, null, Map.of(), null, null);
                var available = store.reusable(manifest, localFiles, token);
                try {
                    var plan = InstallationPlan.create(endpoint, bytes, available.keySet(), pending == null ? null : pending.manifest(), SyncManifest.NEOSYNC_VERSION, NeoForgeVersion.getVersion());
                    return new Review(report, false, store, plan, available, null, null);
                } catch (IOException e) {
                    return new Review(report, false, store, null, Map.of(), null, e.getMessage());
                }
            }, this::review, false);
        }

        void review(Review review) {
            if (review.pending != null) {
                prepared(review.pending);
            } else if (review.ready) {
                screen.show(review.report.lines(), "Back", "Continue to server", this::proceed);
            } else if (review.plan != null) {
                var lines = new ArrayList<>(review.plan.reviewLines());
                lines.add("You cannot join with the currently loaded mod set. Accept installation to prepare this set for a new launch.");
                screen.show(lines, "No, cancel", "Accept installation", () -> screen.show(review.plan.warningLines(), "No, cancel", "Yes, download these files", () -> install(review)));
            } else {
                var lines = new ArrayList<>(review.report.lines());
                if (review.problem != null) lines.add(review.problem);
                screen.show(lines, "Back", "", null);
            }
        }

        void install(Review review) {
            if (review.plan == null) return;
            final InstallationPlan.Consent consent;
            try {
                consent = review.plan.accept(true, true);
            } catch (IOException e) {
                error(e, false);
                return;
            }
            screen.show(List.of("Preparing the reviewed server profile..."), "Cancel", "", null);
            var lastProgress = new java.util.concurrent.atomic.AtomicLong();
            run(token -> review.store.prepare(review.plan, consent, review.available, FMLLoader.versionInfo().fmlVersion(), token, (action, completed, total) -> {
                token.check();
                long now = System.nanoTime();
                if (now - lastProgress.get() < TimeUnit.MILLISECONDS.toNanos(200)) return;
                lastProgress.set(now);
                minecraft.execute(() -> {
                    if (!finished && cancellation == token && minecraft.screen == screen) {
                        screen.show(List.of(action, completed + " / " + total + " bytes", "Your current game directory remains unchanged."), "Cancel", "", null);
                    }
                });
            }), this::prepared, false, 60);
        }

        void prepared(ProfileStore.Prepared prepared) {
            screen.show(List.of("Profile prepared for " + prepared.manifest().displayName() + ".",
                    "Restart Minecraft using the prepared game directory to apply these mods. Closing and reopening your original installation will not activate them.",
                    prepared.gameDirectory().toString()), "Later", "Restart instructions", () -> screen.show(activationInstructions(prepared), "Later", "", null));
        }

        private <T> void run(Work<T> work, Consumer<T> success, boolean ordinaryFallback) {
            run(work, success, ordinaryFallback, 0);
        }

        private <T> void run(Work<T> work, Consumer<T> success, boolean ordinaryFallback, int installationMinutes) {
            cancellation.close();
            var token = new DiscoveryCancellation();
            cancellation = token;
            int current = ++generation;
            try {
                CompletableFuture.supplyAsync(() -> {
                    try {
                        token.check();
                        return work.run(token);
                    } catch (Exception e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                }, WORKER).orTimeout(installationMinutes == 0 ? 30 : installationMinutes * 60L, TimeUnit.SECONDS).whenComplete((value, failure) -> {
                    token.close();
                    minecraft.execute(() -> {
                        if (finished || generation != current || minecraft.screen != screen) return;
                        if (failure == null) {
                            success.accept(value);
                        } else {
                            Throwable cause = failure;
                            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                            error(cause, ordinaryFallback && (cause instanceof SocketException || cause instanceof SocketTimeoutException));
                        }
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                token.close();
                error(new IOException("NeoSync is busy. Try again shortly."), false);
            }
        }

        void error(Throwable error, boolean ordinaryFallback) {
            String message = error instanceof IOException && error.getMessage() != null && !error.getMessage().isBlank()
                    ? error.getMessage()
                    : "NeoSync could not complete this operation. Please try again.";
            screen.show(List.of("NeoSync could not complete preparation.", message,
                    ordinaryFallback ? "You can attempt an ordinary connection without synchronization." : "Your current installation was not changed. Previously prepared revisions remain available."),
                    "Back", ordinaryFallback ? "Connect without discovery" : "Retry", ordinaryFallback ? this::proceed : this::discover);
        }
    }

    static List<String> activationInstructions(ProfileStore.Prepared prepared) {
        return List.of("Close Minecraft, then create or edit an installation in your launcher.",
                "Select the installed NeoSync " + prepared.manifest().loaderVersion() + " build with NeoForge " + prepared.manifest().neoForgeVersion() + " for Minecraft " + prepared.manifest().minecraftVersion() + ".",
                "Set Game Directory to this exact path:", prepared.gameDirectory().toString(),
                "Launch that installation. NeoSync will verify the selected profile and offer to review and reconnect to the server.",
                "Keep your account and authentication settings in the launcher. The profile does not include your personal worlds or settings.",
                "A separate directory organizes your mods; it does not restrict the permissions of installed mod code.");
    }

    private record Review(RequirementReport report, boolean ready, ProfileStore store, @Nullable InstallationPlan plan,
            Map<String, java.nio.file.Path> available, @Nullable ProfileStore.Prepared pending, @Nullable String problem) {}

    private record Destination(InetAddress address, boolean local) {}

    @FunctionalInterface
    private interface Work<T> {
        T run(DiscoveryCancellation cancellation) throws Exception;
    }
}
