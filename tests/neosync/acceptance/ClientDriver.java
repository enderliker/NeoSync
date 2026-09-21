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

    private static void drive() throws Exception {
        Object screen = field(minecraft, "screen");
        if (screen == null || call(minecraft, "getOverlay") != null) return;
        if (!screen.getClass().getSimpleName().equals(lastScreen)) {
            lastScreen = screen.getClass().getSimpleName();
            System.out.println("NeoSync acceptance screen: " + lastScreen);
        }
        if (lastScreen.equals("AccessibilityOnboardingScreen")) {
            click(screen, "Continue");
            return;
        }
        String mode = settings.getProperty("mode", "install");
        if (field(minecraft, "player") != null && field(minecraft, "level") != null) {
            Object modList = call(type("net.neoforged.fml.ModList"), "get");
            Object container = call(modList, "getModContainerById", "clumps");
            require((boolean) call(container, "isPresent"), "Clumps is loaded after selecting the prepared directory");
            evidence.add("Joined a real dedicated server with Clumps loaded and normal NeoForge negotiation.");
            screenshot("neosync-joined.png");
            finish();
            return;
        }
        if (screen.getClass().getSimpleName().equals("TitleScreen")) {
            parent = screen;
            if (mode.equals("install") && !connecting) {
                if (attempt > 0) {
                    Path game = ((java.io.File) field(minecraft, "gameDirectory")).toPath();
                    require(!Files.exists(game.resolve("neosync")), "Declined consent created no store or artifact staging area");
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
            require(text.contains("Clumps") && text.contains("cdn.modrinth.com") && text.contains("unverified"), "Review names the exact file and unverified source");
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
        } else if (hasButton(screen, "Yes, download these files")) {
            require(label(call(screen, "getFocused")).equals("No, cancel"), "Source warning defaults to No");
            require(text.contains("execute code") && text.contains("cdn.modrinth.com"), "Warning explains executable code and its source");
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
        String address = settings.getProperty("server", "127.0.0.1:25575");
        Object parsed = call(type("net.minecraft.client.multiplayer.resolver.ServerAddress"), "parseString", address);
        Class<?> kind = type("net.minecraft.client.multiplayer.ServerData$Type");
        Object other = kind.getField("OTHER").get(null);
        Object data = type("net.minecraft.client.multiplayer.ServerData").getConstructor(String.class, String.class, kind).newInstance("NeoSync acceptance", address, other);
        call(type("net.minecraft.client.gui.screens.ConnectScreen"), "startConnecting", parent, minecraft, parsed, data, false, null);
        connecting = true;
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
        call(button, "onPress");
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
