/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.client;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.ProfileStore;

@EventBusSubscriber(modid = "neoforge", value = Dist.CLIENT)
public final class ProfileStartup {
    private static boolean started;

    private ProfileStartup() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (started || !(minecraft.screen instanceof TitleScreen parent) || minecraft.getOverlay() != null) return;
        started = true;
        var token = new DiscoveryCancellation();
        CompletableFuture.supplyAsync(() -> {
            try {
                var store = ProfileStore.open(minecraft.gameDirectory.toPath());
                if (store.active().isPresent()) {
                    store.verify(store.active().get(), FMLLoader.versionInfo().fmlVersion(), token);
                    store.recordLaunch();
                    return new Result(store.active().get(), List.of());
                }
                return new Result(null, store.preparedProfiles());
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).orTimeout(15, java.util.concurrent.TimeUnit.MINUTES).whenComplete((result, failure) -> {
            token.close();
            minecraft.execute(() -> {
                if (minecraft.screen != parent) return;
                var screen = new DiscoveryScreen(parent, () -> {});
                if (failure != null) {
                    LogUtils.getLogger().warn("NeoSync could not verify the selected profile records: {}", failure.getMessage());
                    minecraft.setScreen(screen);
                    screen.show(List.of("The selected NeoSync profile could not be verified. Select a previously verified revision or your original game directory in the launcher.",
                            "This check runs after mod loading. It cannot prevent modified local code from executing."), "Back", "", null);
                } else if (result.active != null) {
                    var active = result.active;
                    String host = active.identity().host();
                    String address = (host.contains(":") ? "[" + host + "]" : host) + ":" + active.identity().gamePort();
                    minecraft.setScreen(screen);
                    screen.show(List.of("Selected profile verified for " + active.manifest().displayName() + ".", "Server: " + address,
                            "Review current server requirements before reconnecting. Changes require another installation review."),
                            "Later", "Review and reconnect", () -> ConnectScreen.startConnecting(parent, minecraft, ServerAddress.parseString(address),
                                    new ServerData(active.manifest().displayName(), address, ServerData.Type.OTHER), false, null));
                } else if (!result.pending.isEmpty()) {
                    var lines = new ArrayList<String>();
                    lines.add("Server profiles are prepared, but you launched your original game directory. Select the corresponding game directory in your launcher to activate one.");
                    for (var prepared : result.pending) {
                        lines.add(prepared.manifest().displayName() + " — " + prepared.gameDirectory());
                        lines.add("NeoSync " + prepared.manifest().loaderVersion() + " with NeoForge " + prepared.manifest().neoForgeVersion());
                    }
                    minecraft.setScreen(screen);
                    screen.show(lines, "Later", "", null);
                }
            });
        });
    }

    private record Result(@org.jetbrains.annotations.Nullable ProfileStore.Prepared active, List<ProfileStore.Prepared> pending) {}
}
