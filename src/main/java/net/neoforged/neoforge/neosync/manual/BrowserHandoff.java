/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.manual;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.InstallationPlan;

public final class BrowserHandoff {
    private BrowserHandoff() {}

    public static void open(InstallationPlan plan, InstallationPlan.Consent consent, URI page, DiscoveryCancellation token) throws IOException {
        consent.require(plan);
        boolean reviewed = plan.files().stream().anyMatch(file -> file.provider() != null && file.provider().manual() && file.source().equals(page));
        if (!reviewed) throw new IOException("The browser page was not part of this installation review.");
        Process process;
        synchronized (token) {
            token.check();
            String os = System.getProperty("os.name", "");
            ProcessBuilder command;
            if (os.startsWith("Windows")) command = new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", page.toASCIIString());
            else if (os.equals("Linux")) command = new ProcessBuilder("xdg-open", page.toASCIIString());
            else throw new IOException("Open the reviewed file page in your browser, then select the downloaded file here.");
            command.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            process = command.start();
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("The browser handoff could not be confirmed. Open the reviewed page manually or retry.");
            token.check();
            if (process.exitValue() != 0) throw new IOException("The browser could not be opened. Open the reviewed page manually or retry.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Browser handoff was cancelled.");
        }
    }
}
