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
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ManifestHttpClient;
import net.neoforged.neoforge.neosync.protocol.RequirementReport;
import net.neoforged.neoforge.neosync.protocol.StatusQuery;
import net.neoforged.neoforge.neosync.protocol.SyncEndpoint;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

public final class NeoSyncClient {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                var thread = new Thread(runnable, "NeoSync discovery");
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
                var manifest = SyncManifest.parse(ManifestHttpClient.fetch(endpoint, approvedAddress, SSLContext.getDefault(), token));
                var hashes = new HashSet<String>();
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
                }
                return RequirementReport.compare(manifest, hashes, versions, SyncManifest.NEOSYNC_VERSION, NeoForgeVersion.getVersion());
            }, report -> screen.show(report.lines(), "Back", report.ready() ? "Continue to server" : "", report.ready() ? this::proceed : null), false);
        }

        private <T> void run(Work<T> work, Consumer<T> success, boolean ordinaryFallback) {
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
                }, WORKER).orTimeout(30, TimeUnit.SECONDS).whenComplete((value, failure) -> {
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
                error(new IOException("NeoSync discovery is busy. Try again shortly."), false);
            }
        }

        void error(Throwable error, boolean ordinaryFallback) {
            String message = error instanceof IOException && error.getMessage() != null && !error.getMessage().isBlank()
                    ? error.getMessage()
                    : "NeoSync could not complete discovery. Please try again.";
            screen.show(List.of("Server requirements could not be verified.", message,
                    ordinaryFallback ? "You can attempt an ordinary connection without synchronization." : "No files were downloaded or installed."),
                    "Back", ordinaryFallback ? "Connect without discovery" : "Retry", ordinaryFallback ? this::proceed : this::discover);
        }
    }

    private record Destination(InetAddress address, boolean local) {}

    @FunctionalInterface
    private interface Work<T> {
        T run(DiscoveryCancellation cancellation) throws Exception;
    }
}
