/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;

final class ProfileBrowser {
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), runnable -> {
        var thread = new Thread(runnable, "NeoSync profile recovery");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private final Minecraft minecraft = Minecraft.getInstance();
    private final DiscoveryCancellation token = new DiscoveryCancellation();
    private final DiscoveryScreen screen;
    private int index;

    private ProfileBrowser(Screen parent) {
        screen = new DiscoveryScreen(parent, token::close);
    }

    static void open(Screen parent) {
        var browser = new ProfileBrowser(parent);
        browser.minecraft.setScreen(browser.screen);
        browser.load();
    }

    private void load() {
        screen.show(List.of("Reading your prepared profiles and previous revisions..."), "Cancel", "", null);
        run(() -> {
            var store = ProfileStore.openForRecovery(minecraft.gameDirectory.toPath());
            var history = store.history();
            return () -> show(store, history);
        });
    }

    private void show(ProfileStore store, ProfileStore.History history) {
        if (history.revisions().isEmpty()) {
            var lines = new ArrayList<>(history.problems());
            lines.add("No readable server revisions are available in this installation. Join a NeoSync server to review and prepare its requirements.");
            screen.show(lines, "Back", "", null);
            return;
        }
        index = Math.floorMod(index, history.revisions().size());
        var revision = history.revisions().get(index);
        var lines = new ArrayList<String>();
        lines.add("Revision " + (index + 1) + " of " + history.revisions().size() + " — " + revision.manifest().displayName());
        lines.add("Server: " + revision.identity().host() + ":" + revision.identity().gamePort());
        lines.add("Set: " + revision.manifest().revision() + " · " + revision.manifest().files().size() + " files");
        lines.add("NeoSync " + revision.manifest().loaderVersion() + " · Minecraft " + revision.manifest().minecraftVersion());
        lines.add("Directory: " + revision.gameDirectory());
        lines.add("Selecting a revision verifies its files for your next launch. The current session keeps its loaded mods. Rejoining still checks the server's latest requirements.");
        if (!history.problems().isEmpty()) lines.add(history.problems().size() + " unreadable revisions were left untouched.");
        var choices = new ArrayList<DiscoveryScreen.Choice>();
        choices.add(new DiscoveryScreen.Choice("Review this revision", () -> review(store, revision)));
        if (history.revisions().size() > 1) {
            choices.add(new DiscoveryScreen.Choice("Previous revision", () -> {
                index--;
                show(store, history);
            }));
            choices.add(new DiscoveryScreen.Choice("Next revision", () -> {
                index++;
                show(store, history);
            }));
        }
        screen.menu(lines, choices);
    }

    private void review(ProfileStore store, ProfileStore.Prepared revision) {
        run(() -> {
            String expected = store.selectedRevision(revision.identity());
            var lines = new ArrayList<String>();
            lines.add("Select " + revision.manifest().displayName() + " / " + revision.manifest().revision() + " for the next launch?");
            lines.add("This restores an already accepted file set. No files will be downloaded. Your running revision and other revisions are preserved.");
            revision.manifest().files().forEach(file -> lines.add(file.fileName() + " · " + file.size() + " bytes"));
            return () -> screen.show(lines, "No, cancel", "Verify and select", () -> run(() -> {
                store.restore(revision, expected, FMLLoader.versionInfo().fmlVersion(), token);
                return () -> screen.show(NeoSyncClient.activationInstructions(revision), "Later", "", null);
            }));
        });
    }

    private void run(Work operation) {
        try {
            CompletableFuture.supplyAsync(() -> {
                try {
                    token.check();
                    return operation.run();
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, WORK).whenComplete((next, failure) -> minecraft.execute(() -> {
                if (minecraft.screen != screen) return;
                if (failure == null) next.run();
                else screen.show(List.of("The revision could not be selected. Your current selection was preserved.",
                        failure.getCause() instanceof IOException cause ? cause.getMessage() : "Read the local profile records and try again."), "Back", "", null);
            }));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            screen.show(List.of("Profile recovery is busy. Try again shortly."), "Back", "", null);
        }
    }

    @FunctionalInterface
    private interface Work {
        Runnable run() throws IOException;
    }
}
