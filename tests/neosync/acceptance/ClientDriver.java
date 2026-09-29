/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neosync.acceptance;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Isolated installed-build driver. Reflection keeps this agent out of FML's mod inventory. */
public final class ClientDriver {
    private static Instrumentation instrumentation;
    private static ClassLoader gameLoader;
    private static Object minecraft;
    private static final Properties settings = new Properties();
    private static final List<String> evidence = new ArrayList<>();
    private static boolean done;
    private static boolean connecting;
    private static int attempt;
    private static int step;
    private static Object parent;
    private static String lastScreen = "";
    private static Map<Path, String> originalFiles;

    public static void premain(String argument, Instrumentation agent) throws Exception {
        instrumentation = agent;
        try (var input = Files.newInputStream(Path.of(argument))) { settings.load(input); }
        Thread thread = new Thread(ClientDriver::run, "NeoSync acceptance driver");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        try {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            while (!done && System.nanoTime() < deadline) {
                if (minecraft == null) {
                    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                        if (type.getName().equals("net.minecraft.client.Minecraft")) {
                            if (!initialized(type)) continue;
                            gameLoader = type.getClassLoader();
                            minecraft = call(type, "getInstance");
                            break;
                        }
                    }
                } else {
                    var tick = new CompletableFuture<Void>();
                    call(minecraft, "execute", (Runnable) () -> {
                        try { drive(); tick.complete(null); } catch (Throwable error) { tick.completeExceptionally(error); }
                    });
                    tick.get(15, TimeUnit.SECONDS);
                }
                Thread.sleep(200);
            }
            if (!done) throw new IllegalStateException("Timed out: " + evidence);
        } catch (Throwable error) {
            try {
                evidence.add("FAIL: " + error);
                for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) evidence.add("Cause: " + cause);
                Files.write(Path.of(settings.getProperty("report")), evidence);
                if (minecraft != null) call(minecraft, "execute", (Runnable) () -> { try { call(minecraft, "stop"); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }
    }

    private static int worldFrames;
    private static int bootstrapFrames;

    private static void drive() throws Exception {
        Object screen = field(minecraft, "screen");
        if (call(minecraft, "getOverlay") != null) return;
        if (screen == null) {
            if (field(minecraft, "player") != null && field(minecraft, "level") != null && ++worldFrames >= 10) {
                Object modList = call(type("net.neoforged.fml.ModList"), "get");
                Object container = call(modList, "getModContainerById", settings.getProperty("expectedModId", "clumps"));
                require((boolean) call(container, "isPresent"), "The expected mod is loaded after selecting the prepared directory");
                for (String id : settings.getProperty("additionalMods", "").split(",")) if (!id.isBlank())
                    require((boolean) call(call(modList, "getModContainerById", id), "isPresent"), "Additional mod loaded: " + id);
                for (String id : settings.getProperty("absentMods", "").split(",")) if (!id.isBlank())
                    require(!(boolean) call(call(modList, "getModContainerById", id), "isPresent"), "Removed mod absent: " + id);
                evidence.add("Joined a real dedicated server with " + settings.getProperty("expectedModId", "clumps") + " loaded and normal NeoForge negotiation.");
                evidence.add("The loading screen closed and the game rendered for ten driver ticks.");
                screenshot("neosync-joined.png");
                finish();
            }
            return;
        }
        if (!screen.getClass().getSimpleName().equals(lastScreen)) {
            lastScreen = screen.getClass().getSimpleName();
            System.out.println("NeoSync acceptance screen: " + lastScreen);
        }
        if (lastScreen.equals("AccessibilityOnboardingScreen")) {
            click(screen, "Continue");
            return;
        }
        String mode = settings.getProperty("mode", "install");
        if (mode.equals("update") && originalFiles == null) originalFiles = snapshot();
        if (mode.equals("bootstrap") && lastScreen.equals("TitleScreen")) {
            if (++bootstrapFrames < 5) return;
            require(System.getProperty("neosync.launcher.config") != null, "The external launcher supplied its local NeoSync descriptor");
            require(type("net.neoforged.neoforge.neosync.protocol.SyncManifest").getField("NEOSYNC_VERSION").get(null).equals(settings.getProperty("expectedVersion")), "The expected NeoSync build is running");
            require(hasButton(screen, "NeoSync profiles"), "The profile recovery button is visible");
            screenshot("neosync-prism-startup.png");
            finish();
            return;
        }
        if ((mode.equals("crash") || mode.equals("space")) && (lastScreen.equals("TitleScreen") || lastScreen.equals("DiscoveryScreen"))) {
            done = true;
            Thread worker = new Thread(() -> {
                try { storageProbe(mode.equals("crash")); } catch (Throwable error) {
                    try { Files.writeString(Path.of(settings.getProperty("report")), "FAIL: " + error); } catch (Exception ignored) {}
                    Runtime.getRuntime().halt(74);
                }
            }, "NeoSync publication crash probe");
            worker.start();
            return;
        }
        if (screen.getClass().getSimpleName().equals("TitleScreen")) {
            parent = screen;
            if (mode.equals("recovery")) { click(screen, "NeoSync profiles"); return; }
            if ((mode.equals("install") || mode.equals("update")) && !connecting) {
                if (attempt > 0) {
                    Path game = ((java.io.File) field(minecraft, "gameDirectory")).toPath();
                    if (mode.equals("update")) require(snapshot().equals(originalFiles), "Declined update preserved the running mods and selected revision");
                    else require(!Files.exists(game.resolve("neosync")), "Declined consent created no store or artifact staging area");
                }
                connect();
            }
            return;
        }
        if (!screen.getClass().getSimpleName().equals("DiscoveryScreen")) return;
        @SuppressWarnings("unchecked")
        List<String> paragraphs = (List<String>) field(screen, "paragraphs");
        String text = String.join("\n", paragraphs);
        if (text.contains("could not complete") || text.contains("could not be verified")) throw new IllegalStateException(text);
        if (mode.equals("recovery")) {
            if (text.contains("Selected profile verified")) { click(screen, "Later"); return; }
            if (hasButton(screen, "Review this revision")) {
                if (text.contains("Directory: " + settings.getProperty("recoveryTarget"))) click(screen, "Review this revision");
                else click(screen, "Next revision");
                return;
            }
            if (hasButton(screen, "Verify and select")) {
                require(label(call(screen, "getFocused")).equals("No, cancel"), "Recovery defaults to No");
                click(screen, "Verify and select");
                return;
            }
            if (text.contains("Set Game Directory to this exact path")) {
                require(text.contains(settings.getProperty("recoveryTarget")), "Recovery selected the requested previous revision");
                evidence.add("Recovery verified the previous revision and displayed its activation instructions.");
                screenshot("neosync-recovery.png");
                finish();
            }
            return;
        }
        if (mode.equals("original") && text.contains("you launched your original game directory")) {
            evidence.add("Original directory reports pending activation.");
            finish();
        } else if (text.contains("Selected profile verified")) {
            evidence.add("Startup verified the selected managed profile.");
            click(screen, "Review and reconnect");
        } else if (text.contains("is on your local network")) {
            require(label(call(screen, "getFocused")).equals("No, cancel"), "LAN decision defaults to No");
            click(screen, "Allow this endpoint");
        } else if (hasButton(screen, "Accept installation")) {
            require(label(call(screen, "getFocused")).equals("No, cancel"), "Installation review defaults to No");
            require(text.contains(settings.getProperty("expectedName", "Clumps")) && text.contains(settings.getProperty("expectedSource", "cdn.modrinth.com")) && text.contains("unverified"), "Review names the exact file and unverified source");
            if (Boolean.parseBoolean(settings.getProperty("expectProvider", "false")))
                require(text.toLowerCase(java.util.Locale.ROOT).contains(settings.getProperty("expectedProvider", "modrinth").toLowerCase(java.util.Locale.ROOT))
                        && text.contains("provider metadata matched") && text.contains("approved SHA-256 and provider hash"), "Review identifies the provider and explains the pending independent byte checks");
            for (String change : settings.getProperty("expectedChanges", "").split(";")) if (!change.isBlank())
                require(text.contains(change), "Review reports change: " + change);
            if (mode.equals("changed")) {
                evidence.add("Changed server snapshot requires fresh installation review.");
                call(screen, "keyPressed", 257, 0, 0);
                finish();
            } else if (attempt == 0) {
                screenshot("neosync-review.png");
                call(screen, "keyPressed", 257, 0, 0);
                evidence.add("Enter declined installation review.");
                connecting = false;
                attempt++;
            } else {
                click(screen, "Accept installation");
            }
        } else if (hasButton(screen, "Yes, download these files") || hasButton(screen, "Yes, open and import files")) {
            require(label(call(screen, "getFocused")).equals("No, cancel"), "Source warning defaults to No");
            require(text.contains("execute code") && text.contains(settings.getProperty("expectedSource", "cdn.modrinth.com")), "Warning explains executable code and its source");
            if (attempt == 1) {
                call(screen, "keyPressed", 257, 0, 0);
                evidence.add("Enter declined unverified-source confirmation.");
                connecting = false;
                attempt++;
            } else {
                screenshot("neosync-warning.png");
                click(screen, "Yes, download these files");
                evidence.add("Explicitly accepted the displayed source and files.");
            }
        } else if (text.contains("Profile prepared for") && hasButton(screen, "Later")) {
            String game = paragraphs.stream().filter(line -> line.startsWith("/")).findFirst().orElseThrow();
            Files.writeString(Path.of(settings.getProperty("prepared")), game);
            require(label(call(screen, "getFocused")).equals("Later"), "Prepared profile activation defaults to Later");
            evidence.add("Prepared verified isolated game directory: " + game);
            if (mode.equals("update")) {
                for (var entry : originalFiles.entrySet()) if (entry.getKey().getFileName().toString().endsWith(".jar"))
                    require(hash(entry.getKey()).equals(entry.getValue()), "Update preserved the previous revision's mod bytes");
            }
            screenshot("neosync-activation.png");
            if (Boolean.parseBoolean(settings.getProperty("prismRestart", "false"))) {
                click(screen, "Prepare in Prism Launcher");
                step = 2;
            } else finish();
        } else if (hasButton(screen, "Restart instructions")) {
            String game = paragraphs.stream().filter(line -> line.startsWith("/")).findFirst().orElseThrow();
            Files.writeString(Path.of(settings.getProperty("prepared")), game);
            evidence.add("Prepared verified isolated game directory: " + game);
            if (mode.equals("update")) {
                for (var entry : originalFiles.entrySet()) if (entry.getKey().getFileName().toString().endsWith(".jar"))
                    require(hash(entry.getKey()).equals(entry.getValue()), "Update preserved the previous revision's mod bytes");
            }
            click(screen, "Restart instructions");
            step = 1;
        } else if (step == 1 && text.contains("Set Game Directory to this exact path")) {
            screenshot("neosync-activation.png");
            evidence.add("Manual activation instructions displayed with exact directory and loader version.");
            if (Boolean.parseBoolean(settings.getProperty("prismRestart", "false"))) {
                click(screen, "Prepare Prism instance");
                step = 2;
            } else finish();
        } else if (step == 2 && hasButton(screen, "Close and launch")) {
            String instance = paragraphs.stream().filter(line -> line.startsWith("Prism instance: ")).findFirst().orElseThrow().substring("Prism instance: ".length());
            Files.writeString(Path.of(settings.getProperty("prismInstance")), instance);
            evidence.add("Prepared a Prism instance after verifying the approved profile.");
            evidence.add("PASS: restart handoff requested; verify the separate resumed-client report.");
            Files.write(Path.of(settings.getProperty("report")), evidence);
            done = true;
            click(screen, "Close and launch");
        } else if (hasButton(screen, "Continue to server")) {
            click(screen, "Continue to server");
        }
    }

    private static Map<Path, String> snapshot() throws Exception {
        Path game = ((java.io.File) field(minecraft, "gameDirectory")).toPath();
        var files = new java.util.HashMap<Path, String>();
        try (var paths = Files.list(game.resolve("mods"))) {
            for (Path path : paths.toList()) files.put(path, hash(path));
        }
        Path pointer = game.getParent().getParent().getParent().resolve("profile.json");
        files.put(pointer, hash(pointer));
        return files;
    }

    private static String hash(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void connect() throws Exception {
        String address = settings.getProperty("server", "127.0.0.1:25575");
        Object parsed = call(type("net.minecraft.client.multiplayer.resolver.ServerAddress"), "parseString", address);
        Class<?> kind = type("net.minecraft.client.multiplayer.ServerData$Type");
        Object other = kind.getField("OTHER").get(null);
        Object data = type("net.minecraft.client.multiplayer.ServerData").getConstructor(String.class, String.class, kind).newInstance("NeoSync acceptance", address, other);
        call(type("net.minecraft.client.gui.screens.ConnectScreen"), "startConnecting", parent, minecraft, parsed, data, false, null);
        connecting = true;
    }

    private static boolean initialized(Class<?> type) throws Exception {
        // Observing a loaded class is insufficient: calling getInstance can race Minecraft's static initializers.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return !(boolean) unsafeClass.getMethod("shouldBeInitialized", Class.class).invoke(singleton.get(null), type);
    }

    private static void storageProbe(boolean crash) throws Exception {
        Path game = ((java.io.File) field(minecraft, "gameDirectory")).toPath();
        Object store = call(type("net.neoforged.neoforge.neosync.protocol.ProfileStore"), "open", game);
        Object reference = crash ? store : call(type("net.neoforged.neoforge.neosync.protocol.ProfileStore"), "open", Path.of(settings.getProperty("referenceGame")));
        Object prepared = ((List<?>) call(reference, "preparedProfiles")).getFirst();
        Object identity = call(prepared, "identity");
        String digest = (String) call(prepared, "digest");
        Object manifest = call(prepared, "manifest");
        byte[] bytes = Files.readAllBytes(((Path) call(prepared, "gameDirectory")).getParent().resolve("manifest.json"));
        Object capability = type("net.neoforged.neoforge.neosync.protocol.SyncCapability").getConstructor(int.class, String.class)
                .newInstance(((java.net.URI) call(identity, "origin")).getPort(), digest);
        Object endpoint = call(type("net.neoforged.neoforge.neosync.protocol.SyncEndpoint"), "create", call(identity, "host"), call(identity, "gamePort"), capability);
        var available = new java.util.HashMap<String, Path>();
        for (Object artifact : (List<?>) call(manifest, "files")) {
            String hash = (String) call(artifact, "sha256");
            available.put(hash, ((Path) call(reference, "root")).resolve("cache/sha256").resolve(hash + ".jar"));
        }
        Object plan = call(type("net.neoforged.neoforge.neosync.protocol.InstallationPlan"), "create", endpoint, bytes, available.keySet(), crash ? manifest : null,
                call(manifest, "loaderVersion"), call(manifest, "neoForgeVersion"));
        Object consent = call(plan, "accept", true, true);
        Class<?> progressType = type("net.neoforged.neoforge.neosync.protocol.ProfileStore$Progress");
        Object progress = java.lang.reflect.Proxy.newProxyInstance(gameLoader, new Class<?>[] { progressType }, (proxy, method, arguments) -> {
            if (crash && arguments != null && arguments.length == 3 && arguments[0].toString().startsWith("Recording")) {
                Files.writeString(Path.of(settings.getProperty("report")), "HALT: revision renamed; profile pointer not yet changed\n");
                Runtime.getRuntime().halt(73);
            }
            return null;
        });
        Object token = type("net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation").getConstructor().newInstance();
        try {
            call(store, "prepare", plan, consent, available, "4.0.44", token, progress);
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (crash || !(error.getCause() instanceof java.io.IOException) || !error.getCause().getMessage().contains("Not enough free disk space")) throw error;
            require(Files.getFileStore(game).getTotalSpace() <= 64L * 1024 * 1024, "Real limited filesystem has at most 64 MiB");
            require(((List<?>) call(store, "preparedProfiles")).isEmpty(), "Insufficient space publishes no profile");
            evidence.add("Preparation rejected by the actual filesystem free-space check before copying artifacts.");
            call(minecraft, "execute", (Runnable) () -> { try { finish(); } catch (Exception e) { throw new RuntimeException(e); } });
            return;
        }
        throw new AssertionError("Storage failure checkpoint was not reached");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        if (!evidence.contains(message)) evidence.add(message);
    }

    private static void screenshot(String name) throws Exception {
        Object renderTarget = call(minecraft, "getMainRenderTarget");
        call(type("net.minecraft.client.Screenshot"), "grab", field(minecraft, "gameDirectory"), name, renderTarget, (Consumer<Object>) ignored -> {});
    }

    private static void finish() throws Exception {
        evidence.add("PASS");
        Files.write(Path.of(settings.getProperty("report")), evidence);
        done = true;
        call(minecraft, "stop");
    }

    private static boolean hasButton(Object screen, String text) throws Exception { return button(screen, text) != null; }
    private static void click(Object screen, String text) throws Exception {
        Object button = button(screen, text);
        if (button == null) throw new IllegalStateException("Missing button: " + text);
        double x = (int) call(button, "getX") + (int) call(button, "getWidth") / 2.0;
        double y = (int) call(button, "getY") + (int) call(button, "getHeight") / 2.0;
        require((boolean) call(screen, "mouseClicked", x, y, 0), "Mouse click reached the selected button");
    }
    private static Object button(Object screen, String text) throws Exception {
        for (Object child : (List<?>) call(screen, "children")) {
            if (child.getClass().getSimpleName().equals("Button") && label(child).equals(text)) return child;
        }
        return null;
    }
    private static String label(Object object) throws Exception { return (String) call(call(object, "getMessage"), "getString"); }
    private static Class<?> type(String name) throws Exception { return Class.forName(name, false, gameLoader); }

    private static void open(Class<?> type) {
        Module module = type.getModule();
        if (module.isNamed()) instrumentation.redefineModule(module, Set.of(), Map.of(), Map.of(type.getPackageName(), Set.of(ClientDriver.class.getModule())), Set.of(), Map.of());
    }
    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                open(type);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private static Object call(Object target, String name, Object... arguments) throws Exception {
        Class<?> type = target instanceof Class<?> clazz ? clazz : target.getClass();
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            boolean matches = true;
            for (int i = 0; i < arguments.length; i++) {
                Class<?> expected = method.getParameterTypes()[i];
                if (expected == boolean.class) expected = Boolean.class;
                if (expected == int.class) expected = Integer.class;
                if (expected == double.class) expected = Double.class;
                if (arguments[i] != null && !expected.isInstance(arguments[i])) matches = false;
            }
            if (!matches) continue;
            open(method.getDeclaringClass());
            method.setAccessible(true);
            return method.invoke(target instanceof Class<?> ? null : target, arguments);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
