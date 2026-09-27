/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

import com.google.common.net.InetAddresses;
import com.google.gson.JsonObject;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.neoforged.neoforge.neosync.protocol.SyncJson;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;
import org.jetbrains.annotations.Nullable;

public final class AdminService implements AutoCloseable {
    private final NioEventLoopGroup network = new NioEventLoopGroup(2, (java.util.concurrent.ThreadFactory) runnable -> daemon(runnable, "NeoSync admin service"));
    private final ThreadPoolExecutor work = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
            runnable -> daemon(runnable, "NeoSync admin selection"), new ThreadPoolExecutor.AbortPolicy());
    private final DefaultChannelGroup connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, byte[]> assets = new HashMap<>();
    private final AdminSecrets secrets;
    private final AdminSelection selection;
    private final boolean secure;
    private final Channel server;
    private long window = System.nanoTime();
    private int requests;
    private int logins;

    private record Session(String csrf, long expires) {}

    private record Request(String method, String path, String host, String origin, String cookie, String csrf, String type, byte[] body) {}

    public AdminService(InetSocketAddress address, AdminSecrets secrets, AdminSelection selection) throws IOException {
        this(address, secrets, selection, true);
    }

    public AdminService(InetSocketAddress address, AdminSecrets secrets, AdminSelection selection, boolean secure) throws IOException {
        this.secure = secure;
        this.secrets = secrets;
        this.selection = selection;
        var permits = new Semaphore(32);
        try {
            for (String asset : Set.of("index.html", "app.js", "style.css", "icon.svg")) {
                try (var input = AdminService.class.getResourceAsStream("/neosync/admin/" + asset)) {
                    if (input == null) throw new IOException("The administrator panel resources are missing.");
                    byte[] bytes = input.readNBytes(262145);
                    if (bytes.length > 262144) throw new IOException("An administrator panel resource is oversized.");
                    assets.put(asset.equals("index.html") ? "/" : "/" + asset, bytes);
                }
            }
            server = new ServerBootstrap().group(network).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 16)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            if (!permits.tryAcquire()) {
                                channel.close();
                                return;
                            }
                            connections.add(channel);
                            var deadline = channel.eventLoop().schedule(() -> channel.close(), 30, TimeUnit.SECONDS);
                            channel.closeFuture().addListener(future -> {
                                permits.release();
                                deadline.cancel(false);
                            });
                            if (secure) {
                                var engine = secrets.tls().createSSLEngine();
                                engine.setUseClientMode(false);
                                engine.setEnabledProtocols(new String[] { "TLSv1.3", "TLSv1.2" });
                                var ssl = new SslHandler(engine);
                                ssl.setHandshakeTimeoutMillis(5000);
                                channel.pipeline().addLast(ssl);
                            }
                            var decoder = new HttpRequestDecoder(2048, 8192, 8192) {
                                @Override
                                protected void handleTransferEncodingChunkedWithContentLength(HttpMessage message) {
                                    throw new IllegalArgumentException("Ambiguous administrator request length.");
                                }
                            };
                            channel.pipeline().addLast(decoder, new HttpResponseEncoder(), new HttpObjectAggregator(SyncManifest.MAX_BYTES), new WriteTimeoutHandler(10),
                                    new SimpleChannelInboundHandler<FullHttpRequest>() {
                                        private boolean handled;

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
                                            if (handled) {
                                                context.close();
                                                return;
                                            }
                                            handled = true;
                                            if (!request.decoderResult().isSuccess() || request.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)
                                                    || request.headers().getAll(HttpHeaderNames.HOST).size() != 1 || request.headers().getAll(HttpHeaderNames.ORIGIN).size() > 1
                                                    || request.headers().getAll(HttpHeaderNames.COOKIE).size() > 1 || request.headers().getAll("X-NeoSync-CSRF").size() > 1) {
                                                error(context, 400, "Invalid request.");
                                                return;
                                            }
                                            byte[] body = new byte[request.content().readableBytes()];
                                            request.content().readBytes(body);
                                            var copy = new Request(request.method().name(), request.uri(), request.headers().get(HttpHeaderNames.HOST, ""),
                                                    request.headers().get(HttpHeaderNames.ORIGIN, ""), request.headers().get(HttpHeaderNames.COOKIE, ""),
                                                    request.headers().get("X-NeoSync-CSRF", ""), request.headers().get(HttpHeaderNames.CONTENT_TYPE, ""), body);
                                            try {
                                                work.execute(() -> handle(context, copy));
                                            } catch (java.util.concurrent.RejectedExecutionException e) {
                                                error(context, 503, "The panel is busy. Try again shortly.");
                                            }
                                        }

                                        @Override
                                        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                                            context.close();
                                        }
                                    });
                        }
                    }).bind(address).syncUninterruptibly().channel();
        } catch (Exception e) {
            connections.close().awaitUninterruptibly();
            network.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
            work.shutdownNow();
            throw new IOException("Could not start the NeoSync administrator panel.", e);
        }
    }

    private void handle(ChannelHandlerContext context, Request request) {
        if (!context.channel().isActive()) return;
        long now = System.nanoTime();
        if (now - window >= TimeUnit.MINUTES.toNanos(1)) {
            window = now;
            requests = 0;
            logins = 0;
        }
        if (++requests > 300) {
            error(context, 429, "Too many requests. Wait one minute.");
            return;
        }
        if (!validHost(context, request.host())) {
            error(context, 400, "Use an IPv4 address and the administrator port.");
            return;
        }
        boolean post = request.method().equals("POST");
        if ((!post && !request.method().equals("GET")) || (!post && request.body().length != 0)) {
            error(context, 405, "Use GET or POST.");
            return;
        }
        if (post && (!request.origin().equals((secure ? "https://" : "http://") + request.host()) || !request.type().equals("application/json"))) {
            error(context, 403, "Use the administrator panel on this server to submit changes.");
            return;
        }
        if (!post && assets.containsKey(request.path())) {
            String type = switch (request.path()) {
                case "/app.js" -> "text/javascript";
                case "/style.css" -> "text/css";
                case "/icon.svg" -> "image/svg+xml";
                default -> "text/html";
            };
            respond(context, 200, assets.get(request.path()), type, null);
            return;
        }
        sessions.entrySet().removeIf(entry -> now >= entry.getValue().expires());
        try {
            if (post && request.path().equals("/api/login")) {
                if (++logins > 10) {
                    error(context, 429, "Too many sign-in attempts. Wait one minute.");
                    return;
                }
                var credentials = SyncJson.object(SyncJson.parse(request.body(), 512), Set.of("password"), Set.of());
                if (!secrets.accepts(SyncJson.string(credentials.get("password"), 128))) {
                    error(context, 401, "The password is incorrect.");
                    return;
                }
                if (sessions.size() >= 16) {
                    error(context, 429, "Too many active administrator sessions. Sign out or wait for expiry.");
                    return;
                }
                String id = AdminSecrets.randomToken();
                sessions.put(id, new Session(AdminSecrets.randomToken(), now + TimeUnit.MINUTES.toNanos(30)));
                respond(context, 200, "{}".getBytes(StandardCharsets.US_ASCII), "application/json", cookie(id, 1800));
                return;
            }
            String id = sessionId(request.cookie());
            var session = sessions.get(id);
            if (session == null) {
                error(context, 401, "Sign in to administer this server.");
                return;
            }
            if (post && !MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.US_ASCII), request.csrf().getBytes(StandardCharsets.UTF_8))) {
                error(context, 403, "The form expired. Reload the panel.");
                return;
            }
            if (!post && request.path().equals("/api/state")) {
                var state = selection.state();
                state.addProperty("csrf", session.csrf());
                respond(context, 200, state.toString().getBytes(StandardCharsets.UTF_8), "application/json", null);
            } else if (post && request.path().equals("/api/selection")) {
                selection.save(request.body());
                respond(context, 200, "{}".getBytes(StandardCharsets.US_ASCII), "application/json", null);
            } else if (post && request.path().equals("/api/logout")) {
                sessions.remove(id);
                respond(context, 200, "{}".getBytes(StandardCharsets.US_ASCII), "application/json", cookie("", 0));
            } else error(context, 404, "Not found.");
        } catch (IOException e) {
            error(context, 400, e.getMessage());
        } catch (RuntimeException e) {
            error(context, 500, "The request could not be completed. Check the local configuration and reload.");
        }
    }

    private static boolean validHost(ChannelHandlerContext context, String host) {
        try {
            URI uri = URI.create("https://" + host);
            var local = (InetSocketAddress) context.channel().localAddress();
            if (uri.getHost() == null || uri.getPort() != local.getPort() || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null)
                return false;
            String name = uri.getHost().replace("[", "").replace("]", "");
            if (name.equals("localhost")) return local.getAddress().isLoopbackAddress();
            if (!InetAddresses.isInetAddress(name)) return false;
            var address = InetAddresses.forString(name);
            return address instanceof Inet4Address && !address.isAnyLocalAddress()
                    && (!address.isLoopbackAddress() || local.getAddress().isLoopbackAddress());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String sessionId(String cookie) {
        String result = "";
        for (String part : cookie.split(";")) {
            String value = part.trim();
            String prefix = secure ? "__Host-neosync=" : "neosync-http=";
            if (value.startsWith(prefix)) {
                if (!result.isEmpty()) return "";
                result = value.substring(prefix.length());
            }
        }
        return result.matches("[A-Za-z0-9_-]{43}") ? result : "";
    }

    private String cookie(String value, int age) {
        return (secure ? "__Host-neosync=" : "neosync-http=") + value + "; Path=/; " + (secure ? "Secure; " : "") + "HttpOnly; SameSite=Strict; Max-Age=" + age;
    }

    private static void error(ChannelHandlerContext context, int status, String message) {
        var json = new JsonObject();
        json.addProperty("error", message);
        respond(context, status, json.toString().getBytes(StandardCharsets.UTF_8), "application/json", null);
    }

    private static void respond(ChannelHandlerContext context, int status, byte[] bytes, String type, @Nullable String cookie) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length).set(HttpHeaderNames.CONTENT_TYPE, type + "; charset=utf-8")
                .set(HttpHeaderNames.CONNECTION, "close").set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                .set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
                .set("X-Content-Type-Options", "nosniff").set("Referrer-Policy", "no-referrer").set("X-Frame-Options", "DENY");
        if (status == 429 || status == 503) response.headers().set(HttpHeaderNames.RETRY_AFTER, "60");
        if (cookie != null) response.headers().set(HttpHeaderNames.SET_COOKIE, cookie);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static Thread daemon(Runnable runnable, String name) {
        var thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    public int port() {
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    @Override
    public void close() {
        server.close().awaitUninterruptibly();
        connections.close().awaitUninterruptibly();
        work.shutdown();
        try {
            if (!work.awaitTermination(30, TimeUnit.SECONDS)) work.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            work.shutdownNow();
        }
        network.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }
}
