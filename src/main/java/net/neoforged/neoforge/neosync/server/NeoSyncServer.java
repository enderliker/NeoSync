/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

import com.google.common.net.InetAddresses;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import net.neoforged.neoforge.neosync.protocol.ArtifactFiles;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.SyncCapability;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

@EventBusSubscriber(modid = "neoforge")
public final class NeoSyncServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    @Nullable
    private static volatile State state;

    private NeoSyncServer() {}

    private static final class State {
        final ManifestService service;
        final SyncCapability capability;
        String original = "";
        String decorated = "";

        State(ManifestService service, SyncCapability capability) {
            this.service = service;
            this.capability = capability;
        }

        synchronized String decorate(String json) {
            if (!original.equals(json)) {
                var object = JsonParser.parseString(json).getAsJsonObject();
                object.add("neosync", capability.toJson());
                decorated = object.toString();
                original = json;
            }
            return decorated;
        }
    }

    public static String decorateStatus(String originalJson) {
        State current = state;
        return current == null ? originalJson : current.decorate(originalJson);
    }

    @SubscribeEvent
    public static void start(ServerAboutToStartEvent event) {
        if (!event.getServer().isDedicatedServer()) return;
        stopService();
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("neosync-server.json");
        if (!Files.exists(configPath, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            byte[] configBytes;
            try (var input = Files.newInputStream(configPath, LinkOption.NOFOLLOW_LINKS)) {
                configBytes = input.readNBytes(SyncManifest.MAX_BYTES + 1);
            }
            var config = SyncJson.object(SyncJson.parse(configBytes, SyncManifest.MAX_BYTES), Set.of("enabled"),
                    Set.of("mode", "bindAddress", "port", "httpsPort", "gamePort", "displayName", "files", "keyStore", "passwordEnvironment"));
            if (!SyncJson.bool(config.get("enabled"))) return;
            String mode = SyncJson.string(config.get("mode"), 32);
            String bind = SyncJson.string(config.get("bindAddress"), 64);
            if (!InetAddresses.isInetAddress(bind)) throw new IOException("The manifest bind address must be an IP literal.");
            int port = (int) SyncJson.number(config.get("port"), 1, 65535);
            int httpsPort = (int) SyncJson.number(config.get("httpsPort"), 1, 65535);
            int gamePort = config.has("gamePort") ? (int) SyncJson.number(config.get("gamePort"), 1, 65535) : event.getServer().getPort();
            if (httpsPort != 443 && httpsPort != 8443) throw new IOException("The advertised HTTPS port must be 443 or 8443.");
            SSLContext tls = switch (mode) {
                case "https" -> serverTls(config);
                case "reverse-proxy" -> null;
                default -> throw new IOException("The manifest service mode must be https or reverse-proxy.");
            };
            byte[] manifest = createManifest(config);
            SyncManifest parsed = SyncManifest.parse(manifest);
            var capability = new SyncCapability(httpsPort, SyncManifest.sha256(manifest));
            String route = "/.well-known/neosync/v1/servers/" + gamePort + "/manifests/" + capability.manifestSha256() + ".json";
            var service = new ManifestService(new InetSocketAddress(InetAddresses.forString(bind), port), tls, route, manifest);
            state = new State(service, capability);
            LOGGER.info("NeoSync discovery enabled for {} client artifacts on port {}. Public HTTPS port: {}", parsed.files().size(), service.port(), httpsPort);
        } catch (Exception e) {
            LOGGER.error("NeoSync discovery could not start. Review config/neosync-server.json: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public static void stop(ServerStoppedEvent event) {
        if (event.getServer().isDedicatedServer()) stopService();
    }

    private static void stopService() {
        State previous = state;
        state = null;
        if (previous != null) previous.service.close();
    }

    private static SSLContext serverTls(JsonObject config) throws Exception {
        Path keyStorePath = Path.of(SyncJson.string(config.get("keyStore"), 1024));
        if (!keyStorePath.isAbsolute()) keyStorePath = FMLPaths.CONFIGDIR.get().resolve(keyStorePath);
        String variable = SyncJson.matching(config.get("passwordEnvironment"), 128, "[A-Za-z_][A-Za-z0-9_]*");
        String passwordValue = System.getenv(variable);
        if (passwordValue == null) throw new IOException("The keystore password environment variable is not set.");
        char[] password = passwordValue.toCharArray();
        try {
            var store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keyStorePath, LinkOption.NOFOLLOW_LINKS)) {
                store.load(input, password);
            }
            var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            var context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static byte[] createManifest(JsonObject config) throws IOException {
        var loadedFiles = new HashMap<Path, IModFileInfo>();
        ModList.get().getModFiles().forEach(info -> loadedFiles.put(info.getFile().getFilePath().toAbsolutePath().normalize(), info));
        Path modsDirectory = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
        var files = new JsonArray();
        var names = new HashSet<String>();
        var cancellation = new DiscoveryCancellation();
        long total = 0;
        for (var entry : SyncJson.array(config.get("files"), 0, 2048)) {
            var selection = SyncJson.object(entry, Set.of("fileName", "sources"), Set.of());
            String fileName = SyncJson.matching(selection.get("fileName"), 128, SyncManifest.FILE_PATTERN);
            if (!names.add(fileName)) throw new IOException("Duplicate selected client file.");
            SyncManifest.parseSources(selection.get("sources"));
            Path path = modsDirectory.resolve(fileName);
            var info = loadedFiles.get(path);
            if (info == null) throw new IOException("A selected client file is not in the loaded server inventory: " + fileName);
            var fingerprint = ArtifactFiles.fingerprint(path, cancellation);
            total += fingerprint.size();
            if (total > SyncManifest.MAX_TOTAL_BYTES) throw new IOException("The selected mod set exceeds the size limit.");
            var file = new JsonObject();
            file.addProperty("sha256", fingerprint.sha256());
            file.addProperty("size", fingerprint.size());
            file.addProperty("fileName", fileName);
            file.addProperty("required", true);
            file.add("sources", selection.get("sources").deepCopy());
            var mods = new JsonArray();
            for (var modInfo : info.getMods()) {
                var mod = new JsonObject();
                mod.addProperty("id", modInfo.getModId());
                mod.addProperty("version", modInfo.getVersion().toString());
                mod.addProperty("displayName", modInfo.getDisplayName());
                var dependencies = new JsonArray();
                for (var dependencyInfo : modInfo.getDependencies()) {
                    if (!dependencyInfo.getSide().isContained(Dist.CLIENT)) continue;
                    var dependency = new JsonObject();
                    dependency.addProperty("id", dependencyInfo.getModId());
                    dependency.addProperty("versionRange", SyncManifest.versionSpec(dependencyInfo.getVersionRange()));
                    dependency.addProperty("type", dependencyInfo.getType().name().toLowerCase(Locale.ROOT));
                    dependencies.add(dependency);
                }
                mod.add("dependencies", dependencies);
                mods.add(mod);
            }
            file.add("mods", mods);
            files.add(file);
        }
        var manifest = new JsonObject();
        manifest.addProperty("schemaVersion", 1);
        manifest.addProperty("serverId", serverId().toString());
        manifest.addProperty("revision", "inventory-" + SyncManifest.sha256(files.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16));
        manifest.addProperty("displayName", SyncJson.string(config.get("displayName"), 128));
        manifest.addProperty("minecraftVersion", "1.21.1");
        var loader = new JsonObject();
        loader.addProperty("id", "neosync");
        loader.addProperty("version", SyncManifest.NEOSYNC_VERSION);
        loader.addProperty("neoForgeVersion", NeoForgeVersion.getVersion());
        manifest.add("loader", loader);
        manifest.add("files", files);
        return manifest.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static UUID serverId() throws IOException {
        Path path = FMLPaths.CONFIGDIR.get().resolve("neosync-server-id.txt");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(path, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
        }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            String value = new String(input.readNBytes(37), StandardCharsets.US_ASCII);
            if (value.length() != 36) throw new IOException("Invalid persistent NeoSync server ID.");
            return UUID.fromString(value);
        }
    }
}
