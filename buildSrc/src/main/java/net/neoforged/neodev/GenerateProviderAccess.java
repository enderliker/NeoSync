/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neodev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Read credentials only during execution, keeping them out of Gradle inputs and configuration-cache state. */
@DisableCachingByDefault(because = "Provider credentials must not enter shared build caches")
public abstract class GenerateProviderAccess extends DefaultTask {
    public GenerateProviderAccess() {
        getOutputs().upToDateWhen(task -> false);
    }

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void generate() throws IOException {
        Path target = getOutputDirectory().get().getAsFile().toPath().resolve("META-INF/neosync/provider-access.bin");
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[0]);
        String path = System.getenv("NEOSYNC_PROVIDER_KEY_FILE");
        String secret = System.getenv("NEOSYNC_PROVIDER_KEY");
        if (path == null && secret == null) return;
        if (path != null && secret != null) throw new GradleException("Use only one NeoSync provider credential input.");
        if (!"desktop-key-and-audit-approved".equals(System.getenv("NEOSYNC_PROVIDER_AGREEMENT")))
            throw new GradleException("Bundling a real provider key requires an applicable CurseForge agreement for desktop key distribution and retained audit data. See docs/neosync/phase-5.md.");
        byte[] bytes;
        if (path != null) {
            try (var input = Files.newInputStream(Path.of(path), LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(513);
            } catch (Exception e) {
                throw new GradleException("Could not read the NeoSync provider credential file.");
            }
        } else {
            if (!secret.matches("[!-~]+")) throw new GradleException("The provider credential must contain printable ASCII without whitespace.");
            bytes = secret.getBytes(StandardCharsets.US_ASCII);
        }
        try {
            if (bytes.length < 1 || bytes.length > 512) throw new GradleException("Invalid NeoSync provider credential length.");
            for (byte value : bytes) if (value < 33 || value > 126) throw new GradleException("The provider credential must contain printable ASCII without whitespace.");
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
