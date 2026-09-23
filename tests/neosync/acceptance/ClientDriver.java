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
    private static boolean selectedManualFile;
    private static Object parent;
    private static String lastScreen = "";

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

    private static void drive() throws Exception {
        Object screen = field(minecraft, "screen");
        if (call(minecraft, "getOverlay") != null) return;
        if (screen == null) {
            if (field(minecraft, "player") != null && field(minecraft, "level") != null && ++worldFrames >= 10) {
                Object modList = call(type("net.neoforged.fml.ModList"), "get");
                Object container = call(modList, "getModContainerById", settings.getProperty("expectedModId", "clumps"));
                require((boolean) call(container, "isPresent"), "The expected mod is loaded after selecting the prepared directory");
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
            if (mode.equals("install") && !connecting) {
                if (attempt > 0) {
                    Path game = ((java.io.File) field(minecraft, "gameDirectory")).toPath();
                    require(!Files.exists(game.resolve("neosync")), "Declined consent created no store or artifact staging area");
                    if (!settings.getProperty("manualFixture", "").isEmpty())
                        require(!Files.exists(Path.of(settings.getProperty("manualFixture"), "browser-report.txt")), "Declined manual consent opened no browser");
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
                require(text.contains("modrinth") && text.contains("provider metadata matched") && text.contains("approved SHA-256 and provider hash"), "Review identifies Modrinth and explains the pending independent byte checks");
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
                click(screen, hasButton(screen, "Yes, open and import files") ? "Yes, open and import files" : "Yes, download these files");
                evidence.add("Explicitly accepted the displayed source and files.");
            }
        } else if (text.contains("Waiting for approved browser downloads") && !selectedManualFile && (settings.containsKey("manualSelection") || Boolean.parseBoolean(settings.getProperty("manualChooser", "false")))) {
            selectedManualFile = true;
            if (Boolean.parseBoolean(settings.getProperty("manualChooser", "false"))) {
                click(screen, "Choose downloaded file...");
                evidence.add("The product's native downloaded-file chooser returned.");
            } else {
                call(field(screen, "pathInput"), "setValue", settings.getProperty("manualSelection"));
                screenshot("neosync-manual-selection.png");
                click(screen, "Use path");
                evidence.add("Selected an explicit local browser download through the product screen.");
            }
        } else if (hasButton(screen, "Restart instructions")) {
            String game = paragraphs.stream().filter(line -> line.startsWith("/")).findFirst().orElseThrow();
            Files.writeString(Path.of(settings.getProperty("prepared")), game);
            evidence.add("Prepared verified isolated game directory: " + game);
            click(screen, "Restart instructions");
            step = 1;
        } else if (step == 1 && text.contains("Set Game Directory to this exact path")) {
            screenshot("neosync-activation.png");
            evidence.add("Manual activation instructions displayed with exact directory and loader version.");
            finish();
        } else if (hasButton(screen, "Continue to server")) {
            click(screen, "Continue to server");
        }
    }

    private static void connect() throws Exception {
        if (!settings.getProperty("manualFixture", "").isEmpty()) {
            manualReview();
            connecting = true;
            return;
        }
        String address = settings.getProperty("server", "127.0.0.1:25575");
        Object parsed = call(type("net.minecraft.client.multiplayer.resolver.ServerAddress"), "parseString", address);
        Class<?> kind = type("net.minecraft.client.multiplayer.ServerData$Type");
        Object other = kind.getField("OTHER").get(null);
        Object data = type("net.minecraft.client.multiplayer.ServerData").getConstructor(String.class, String.class, kind).newInstance("NeoSync acceptance", address, other);
        call(type("net.minecraft.client.gui.screens.ConnectScreen"), "startConnecting", parent, minecraft, parsed, data, false, null);
        connecting = true;
    }

    private static void manualReview() throws Exception {
        Path fixture = Path.of(settings.getProperty("manualFixture"));
        require(System.getenv("PATH").split(java.io.File.pathSeparator)[0].equals(fixture.resolve("browser-bin").toString()), "Controlled browser executable is first on the client PATH");
        require(fixture.resolve("xdg-config").toString().equals(System.getenv("XDG_CONFIG_HOME")), "Client uses the relocated XDG Downloads fixture");
        Object token = type("net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation").getConstructor().newInstance();
        Object capability = call(call(type("net.neoforged.neoforge.neosync.protocol.StatusQuery"), "query", new java.net.InetSocketAddress("127.0.0.1", 25575), "127.0.0.1", 25575, 767, token), "orElseThrow");
        Object endpoint = call(type("net.neoforged.neoforge.neosync.protocol.SyncEndpoint"), "create", "127.0.0.1", 25575, capability);
        byte[] bytes = (byte[]) call(type("net.neoforged.neoforge.neosync.protocol.ManifestHttpClient"), "fetch", endpoint, java.net.InetAddress.getByName("127.0.0.1"), javax.net.ssl.SSLContext.getDefault(), token);
        Object manifest = call(type("net.neoforged.neoforge.neosync.protocol.SyncManifest"), "parse", bytes);
        Class<?> transportType = type("net.neoforged.neoforge.neosync.provider.ProviderTransport");
        Object transport = java.lang.reflect.Proxy.newProxyInstance(gameLoader, new Class<?>[] { transportType }, (proxy, method, arguments) -> {
            throw new IllegalStateException("A client must not request CurseForge metadata or use the administrator's key.");
        });
        Object resolver = type("net.neoforged.neoforge.neosync.provider.SourceResolver").getConstructor(transportType).newInstance(transport);
        Object sources = call(resolver, "resolve", manifest, token);
        String loader = (String) call(manifest, "loaderVersion");
        String base = (String) call(manifest, "neoForgeVersion");
        Object plan = call(type("net.neoforged.neoforge.neosync.protocol.InstallationPlan"), "create", endpoint, bytes, Set.of(), null, loader, base, null, sources);
        Object report = call(type("net.neoforged.neoforge.neosync.protocol.RequirementReport"), "compare", manifest, Set.of(), Map.of(), loader, base);
        Object store = call(type("net.neoforged.neoforge.neosync.protocol.ProfileStore"), "open", ((java.io.File) field(minecraft, "gameDirectory")).toPath());
        Class<?> attemptType = type("net.neoforged.neoforge.neosync.client.NeoSyncClient$Attempt");
        Class<?> reviewType = type("net.neoforged.neoforge.neosync.client.NeoSyncClient$Review");
        open(attemptType);
        open(reviewType);
        var attemptConstructor = attemptType.getDeclaredConstructors()[0];
        attemptConstructor.setAccessible(true);
        Object address = call(type("net.minecraft.client.multiplayer.resolver.ServerAddress"), "parseString", "127.0.0.1:25575");
        Object attemptObject = attemptConstructor.newInstance(minecraft, parent, address, (Runnable) () -> {});
        var reviewConstructor = reviewType.getDeclaredConstructors()[0];
        reviewConstructor.setAccessible(true);
        Object review = reviewConstructor.newInstance(report, false, store, plan, Map.of(), null, null);
        call(minecraft, "setScreen", field(attemptObject, "screen"));
        var reviewMethod = attemptType.getDeclaredMethod("review", reviewType);
        reviewMethod.setAccessible(true);
        reviewMethod.invoke(attemptObject, review);
        evidence.add("Manual branch uses synthetic server-reported CurseForge metadata and a controlled browser launcher; this is not live CurseForge acceptance.");
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
