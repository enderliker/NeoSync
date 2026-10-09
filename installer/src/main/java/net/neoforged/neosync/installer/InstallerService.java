/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neosync.installer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import net.minecraftforge.installer.SimpleInstaller;
import net.minecraftforge.installer.actions.ClientInstall;
import net.minecraftforge.installer.actions.ProgressCallback;
import net.minecraftforge.installer.json.Util;

public final class InstallerService {
    private InstallerService() {}

    public static Path install(LauncherTarget target, Path installer, Consumer<String> progress) throws Exception {
        if (!target.available()) throw new IOException("The selected launcher directory no longer exists.");
        Path root = InstallerFiles.directory(target.root(), false);
        LauncherSetup.preflight(target);
        try (var channel = FileChannel.open(root.resolve(".neosync-installer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another NeoSync installation is in progress.");
            var profile = Util.loadInstallProfile();
            String version = profile.getVersion();
            if (!version.matches("NeoSync-[A-Za-z0-9.+-]+-neoforge-[0-9.]+")) throw new IOException("Invalid NeoSync installer identity.");
            Path runtime = InstallerFiles.directory(root.resolve("neosync/runtime").resolve(version), true);
            Path completion = runtime.resolve(".neosync-installed.json");
            String installerHash = InstallerFiles.hash(installer, "SHA-256");
            if (Files.exists(completion, LinkOption.NOFOLLOW_LINKS)) {
                var record = InstallerFiles.json(completion);
                if (!version.equals(record.get("version").getAsString()) || !installerHash.equals(record.get("installerSha256").getAsString()))
                    throw new IOException("A different installer already uses this version. Choose another folder or use a new NeoSync version.");
            }
            if (!Files.isRegularFile(completion, LinkOption.NOFOLLOW_LINKS)) {
                progress.accept("Installing Minecraft 1.21.1 and NeoSync. This can take several minutes...");
                Path inventory = runtime.resolve("launcher_profiles.json");
                if (!Files.exists(inventory, LinkOption.NOFOLLOW_LINKS)) InstallerFiles.publish(inventory, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                SimpleInstaller.headless = true;
                var callback = new ProgressCallback() {
                    private String step = "Installing NeoSync";

                    @Override
                    public void message(String text, MessagePriority priority) {
                        progress.accept(text);
                    }

                    @Override
                    public void setCurrentStep(String text) {
                        step = text;
                        progress.accept(text);
                    }

                    @Override
                    public String getCurrentStep() {
                        return step;
                    }
                };
                if (!new ClientInstall(profile, callback).run(runtime.toFile(), ignored -> true, installer.toFile()))
                    throw new IOException("NeoSync installation did not complete. Close the launcher and retry.");
                var record = new com.google.gson.JsonObject();
                record.addProperty("version", version);
                record.addProperty("installerSha256", installerHash);
                InstallerFiles.publish(completion, InstallerFiles.encode(record));
            }
            progress.accept("Preparing " + target.kind().label() + "...");
            return LauncherSetup.configure(target, runtime, version, progress);
        }
    }
}
