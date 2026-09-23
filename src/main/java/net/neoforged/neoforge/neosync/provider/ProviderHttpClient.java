/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.provider;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import net.neoforged.neoforge.neosync.protocol.DiscoveryCancellation;
import net.neoforged.neoforge.neosync.protocol.SyncEndpoint;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

/** Fixed-origin metadata transport. Never follows redirects or includes response bodies in errors. */
public final class ProviderHttpClient implements ProviderTransport {
    private final String curseForgeKey;

    public ProviderHttpClient() {
        this.curseForgeKey = "";
    }

    private ProviderHttpClient(String curseForgeKey) {
        this.curseForgeKey = curseForgeKey;
    }

    /** Only the server process reads the administrator's environment. The key is never sent to clients. */
    public static ProviderHttpClient forServer() throws IOException {
        return new ProviderHttpClient(ProviderCredentials.serverCurseForge());
    }

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final NioEventLoopGroup NETWORK = new NioEventLoopGroup(2, runnable -> {
        var thread = new Thread(runnable, "NeoSync provider HTTPS");
        thread.setDaemon(true);
        return thread;
    });
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
        var thread = new Thread(runnable, "NeoSync provider lookup");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final Map<Request, Lookup> IN_FLIGHT = new HashMap<>();
    private static final Map<Service, Instant> COOLDOWNS = new HashMap<>();
    private static final ModrinthMetadataCache MODRINTH_CACHE = new ModrinthMetadataCache();

    public enum Service {
        MODRINTH("api.modrinth.com"),
        CURSEFORGE("api.curseforge.com");

        private final String host;

        Service(String host) {
            this.host = host;
        }
    }

    private record Request(Service service, String path, String body) {}

    private static final class Lookup {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final DiscoveryCancellation cancellation = new DiscoveryCancellation();
        int subscribers;
    }

    @Override
    public boolean available(Service service) throws IOException {
        return service != Service.CURSEFORGE || !curseForgeKey.isEmpty();
    }

    @Override
    public byte[] request(Service service, String path, String body, DiscoveryCancellation token) throws IOException {
        token.check();
        if (!available(service)) throw new IOException("CurseForge lookup needs the server administrator's NEOSYNC_CURSEFORGE_API_KEY; clients never supply it.");
        if (!path.startsWith(service == Service.MODRINTH ? "/v2/" : "/v1/") || path.length() > 8192
                || !path.matches("/[A-Za-z0-9_/?=&%.,-]+") || body.length() > 65536)
            throw new IOException("Invalid provider request.");
        byte[] cached = MODRINTH_CACHE.get(service, path, body);
        if (cached != null) {
            token.check();
            return cached;
        }
        var request = new Request(service, path, body);
        Lookup lookup;
        synchronized (IN_FLIGHT) {
            lookup = IN_FLIGHT.get(request);
            if (lookup == null) {
                if (IN_FLIGHT.size() >= 18) throw new IOException("Provider lookups are busy. Try again shortly.");
                lookup = new Lookup();
                IN_FLIGHT.put(request, lookup);
                Lookup submitted = lookup;
                try {
                    WORK.execute(() -> {
                        try {
                            byte[] response = fetch(request, submitted.cancellation);
                            submitted.cancellation.check();
                            MODRINTH_CACHE.put(request.service, request.path, request.body, response);
                            submitted.result.complete(response);
                        } catch (Exception e) {
                            // TLS stacks and third-party error bodies must not expose request headers or credentials.
                            submitted.result.completeExceptionally(new IOException(e instanceof ProviderFailure ? e.getMessage() : "The provider metadata request failed. Try again later."));
                        } finally {
                            submitted.cancellation.close();
                            synchronized (IN_FLIGHT) {
                                IN_FLIGHT.remove(request, submitted);
                            }
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    IN_FLIGHT.remove(request);
                    throw new IOException("Provider lookups are busy. Try again shortly.");
                }
            }
            lookup.subscribers++;
        }
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (true) {
                token.check();
                if (System.nanoTime() >= deadline) throw new IOException("The provider lookup deadline expired.");
                try {
                    byte[] result = lookup.result.get(100, TimeUnit.MILLISECONDS);
                    token.check();
                    return result.clone();
                } catch (java.util.concurrent.TimeoutException ignored) {}
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Provider lookup was cancelled.");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(e.getCause().getMessage());
        } finally {
            synchronized (IN_FLIGHT) {
                if (--lookup.subscribers == 0) {
                    lookup.cancellation.close();
                    IN_FLIGHT.remove(request, lookup);
                }
            }
        }
    }

    private byte[] fetch(Request request, DiscoveryCancellation token) throws Exception {
        token.check();
        synchronized (COOLDOWNS) {
            if (COOLDOWNS.getOrDefault(request.service, Instant.MIN).isAfter(Instant.now()))
                throw new ProviderFailure("The provider requested a pause. Retry later; no alternate source was selected.");
        }
        String credential = request.service == Service.CURSEFORGE ? curseForgeKey : "";
        if (request.service == Service.CURSEFORGE && credential.isEmpty())
            throw new ProviderFailure("CurseForge lookup needs the server administrator's API key.");
        var addresses = InetAddress.getAllByName(request.service.host);
        token.check();
        if (addresses.length == 0) throw new IOException();
        for (var address : addresses) if (!SyncEndpoint.isPublic(address)) throw new IOException();
        return fetchPinned(request.service, URI.create("https://" + request.service.host + request.path), request.body,
                credential, new InetSocketAddress(addresses[0], 443), SSLContext.getDefault(), token);
    }

    /** Internal transport primitive. The caller must validate the pinned address; API origin and TLS identity are still enforced here. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static byte[] fetchPinned(Service service, URI uri, String body, String credential, InetSocketAddress address,
            SSLContext tls, DiscoveryCancellation token) throws Exception {
        var result = new CompletableFuture<byte[]>();
        var bootstrap = new Bootstrap().group(NETWORK).channel(NioSocketChannel.class).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        var engine = tls.createSSLEngine(uri.getHost(), 443);
                        engine.setUseClientMode(true);
                        var parameters = engine.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        engine.setSSLParameters(parameters);
                        var ssl = new SslHandler(engine);
                        ssl.setHandshakeTimeoutMillis(5000);
                        var decoder = new HttpResponseDecoder() {
                            @Override
                            protected void handleTransferEncodingChunkedWithContentLength(HttpMessage message) {
                                throw new IllegalArgumentException("Ambiguous provider response length.");
                            }
                        };
                        channel.pipeline().addLast(ssl, decoder, new HttpRequestEncoder(), new HttpObjectAggregator(MAX_BYTES), new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            public void channelActive(ChannelHandlerContext context) throws IOException {
                                token.check();
                                if (!uri.getHost().equals(service.host) || !uri.getScheme().equals("https") || uri.getPort() != -1)
                                    throw new IOException("Invalid provider origin.");
                                var bytes = body.getBytes(StandardCharsets.UTF_8);
                                var message = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, body.isEmpty() ? HttpMethod.GET : HttpMethod.POST,
                                        uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()), Unpooled.wrappedBuffer(bytes));
                                message.headers().set(HttpHeaderNames.HOST, service.host);
                                message.headers().set(HttpHeaderNames.USER_AGENT, "enderliker/NeoSync/" + SyncManifest.NEOSYNC_VERSION + " (https://github.com/enderliker/NeoSync)");
                                message.headers().set(HttpHeaderNames.ACCEPT, "application/json");
                                message.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "identity");
                                message.headers().set(HttpHeaderNames.CONNECTION, "close");
                                if (service == Service.CURSEFORGE) message.headers().set("x-api-key", credential);
                                if (!body.isEmpty()) {
                                    message.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                                    message.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
                                }
                                context.writeAndFlush(message);
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext context, FullHttpResponse response) throws IOException {
                                token.check();
                                int status = response.status().code();
                                if (status == 429 || "0".equals(response.headers().get("X-Ratelimit-Remaining"))) {
                                    Instant resume = retryAfter(response.headers().get("Retry-After"), response.headers().get("X-Ratelimit-Reset"), Instant.now());
                                    synchronized (COOLDOWNS) {
                                        COOLDOWNS.merge(service, resume, (a, b) -> a.isAfter(b) ? a : b);
                                    }
                                }
                                if (status == 404) {
                                    result.complete(new byte[0]);
                                } else if (status != 200) {
                                    result.completeExceptionally(new ProviderFailure("Provider metadata returned HTTP " + status + ". No author restriction or alternative source was inferred."));
                                } else if (!response.decoderResult().isSuccess() || !response.headers().get(HttpHeaderNames.CONTENT_ENCODING, "identity").equalsIgnoreCase("identity")
                                        || !response.headers().get(HttpHeaderNames.CONTENT_TYPE, "").toLowerCase(Locale.ROOT).split(";", 2)[0].trim().equals("application/json")) {
                                            result.completeExceptionally(new IOException());
                                        } else {
                                            byte[] bytes = new byte[response.content().readableBytes()];
                                            response.content().readBytes(bytes);
                                            result.complete(bytes);
                                        }
                                context.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                                result.completeExceptionally(new IOException());
                                context.close();
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext context) {
                                result.completeExceptionally(new IOException());
                            }
                        });
                    }
                });
        var connection = bootstrap.connect(address);
        try {
            token.attach(() -> {
                connection.channel().close();
                result.cancel(false);
            });
            try {
                return result.get(15, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof ProviderFailure failure) throw failure;
                throw new IOException();
            }
        } finally {
            connection.channel().close();
            token.detach();
        }
    }

    public static Instant retryAfter(String header, String reset, Instant now) {
        String value = header == null ? reset : header;
        if (value != null && value.length() <= 128) {
            try {
                if (value.matches("[0-9]{1,9}")) return now.plusSeconds(Long.parseLong(value));
                Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                if (date.isAfter(now)) return date;
            } catch (RuntimeException ignored) {}
        }
        return now.plusSeconds(60);
    }

    private static final class ProviderFailure extends IOException {
        ProviderFailure(String message) {
            super(message);
        }
    }
}
