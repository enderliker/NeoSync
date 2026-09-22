/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

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
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.jetbrains.annotations.Nullable;

public final class ManifestService implements AutoCloseable {
    private final NioEventLoopGroup acceptor;
    private final NioEventLoopGroup workers;
    private final DefaultChannelGroup connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final Channel server;

    public ManifestService(InetSocketAddress address, @Nullable SSLContext tls, String path, byte[] manifest) throws IOException {
        this(address, tls, path, manifest, "", Map.of(), HostingPolicy.parse(null));
    }

    public ManifestService(InetSocketAddress address, @Nullable SSLContext tls, String path, byte[] manifest,
            String filesPrefix, Map<String, HostedInventory.Entry> inventory, HostingPolicy policy) throws IOException {
        if (tls == null && !address.getAddress().isLoopbackAddress()) throw new IOException("A plaintext manifest backend must bind to loopback.");
        if (!policy.enabled() && !inventory.isEmpty()) throw new IOException("Server artifact hosting is disabled.");
        byte[] snapshot = manifest.clone();
        var files = Map.copyOf(inventory);
        var limits = new Limits(policy);
        ThreadFactory threads = runnable -> {
            var thread = new Thread(runnable, "NeoSync HTTPS service");
            thread.setDaemon(true);
            return thread;
        };
        acceptor = new NioEventLoopGroup(1, threads);
        workers = new NioEventLoopGroup(2, threads);
        var permits = new Semaphore(32);
        var streams = new Semaphore(policy.concurrentTransfers());
        try {
            server = new ServerBootstrap().group(acceptor, workers).channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 16)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            if (!permits.tryAcquire()) {
                                channel.close();
                                return;
                            }
                            channel.closeFuture().addListener(future -> permits.release());
                            connections.add(channel);
                            var headerDeadline = channel.eventLoop().schedule(() -> channel.close(), 10, TimeUnit.SECONDS);
                            channel.closeFuture().addListener(future -> headerDeadline.cancel(false));
                            if (tls != null) {
                                var engine = tls.createSSLEngine();
                                engine.setUseClientMode(false);
                                var ssl = new SslHandler(engine);
                                ssl.setHandshakeTimeoutMillis(5000);
                                channel.pipeline().addLast(ssl);
                            }
                            var decoder = new HttpRequestDecoder(4096, 8192, 8192) {
                                @Override
                                protected void handleTransferEncodingChunkedWithContentLength(HttpMessage message) {
                                    throw new IllegalArgumentException("Ambiguous request length.");
                                }
                            };
                            channel.pipeline().addLast(decoder, new HttpResponseEncoder(), new HttpObjectAggregator(0), new WriteTimeoutHandler(30),
                                    new SimpleChannelInboundHandler<FullHttpRequest>() {
                                        private boolean handled;

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws IOException {
                                            if (handled) {
                                                context.close();
                                                return;
                                            }
                                            handled = true;
                                            if (!limits.request()) respond(context, HttpResponseStatus.TOO_MANY_REQUESTS, null);
                                            else if (!request.decoderResult().isSuccess()) respond(context, HttpResponseStatus.BAD_REQUEST, null);
                                            else if (!request.method().equals(HttpMethod.GET)) respond(context, HttpResponseStatus.METHOD_NOT_ALLOWED, null);
                                            else if (request.headers().contains(HttpHeaderNames.RANGE) || request.headers().contains(HttpHeaderNames.TRANSFER_ENCODING))
                                                respond(context, HttpResponseStatus.BAD_REQUEST, null);
                                            else if (request.uri().equals(path)) respond(context, HttpResponseStatus.OK, snapshot);
                                            else {
                                                String uri = request.uri();
                                                var entry = uri.startsWith(filesPrefix) ? files.get(uri.substring(filesPrefix.length())) : null;
                                                if (entry == null) {
                                                    respond(context, HttpResponseStatus.NOT_FOUND, null);
                                                    return;
                                                }
                                                if (!streams.tryAcquire()) {
                                                    respond(context, HttpResponseStatus.SERVICE_UNAVAILABLE, null);
                                                    return;
                                                }
                                                FileChannel input;
                                                try {
                                                    input = entry.open();
                                                } catch (IOException e) {
                                                    streams.release();
                                                    respond(context, HttpResponseStatus.SERVICE_UNAVAILABLE, null);
                                                    return;
                                                }
                                                headerDeadline.cancel(false);
                                                var deadline = channel.eventLoop().schedule(() -> channel.close(), 15, TimeUnit.MINUTES);
                                                channel.closeFuture().addListener(future -> {
                                                    deadline.cancel(false);
                                                    try {
                                                        input.close();
                                                    } finally {
                                                        streams.release();
                                                    }
                                                });
                                                var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                                                headers(response, entry.size(), "application/java-archive");
                                                context.writeAndFlush(response).addListener(future -> {
                                                    if (future.isSuccess()) new Transfer(context, input, entry.size(), limits).next();
                                                    else context.close();
                                                });
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
            acceptor.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
            workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
            throw new IOException("Could not bind the manifest service.", e);
        }
    }

    private static void respond(ChannelHandlerContext context, HttpResponseStatus status, byte @Nullable [] body) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body == null ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body));
        headers(response, response.content().readableBytes(), "application/json; charset=utf-8");
        if (status.equals(HttpResponseStatus.METHOD_NOT_ALLOWED)) response.headers().set(HttpHeaderNames.ALLOW, "GET");
        if (status.equals(HttpResponseStatus.TOO_MANY_REQUESTS) || status.equals(HttpResponseStatus.SERVICE_UNAVAILABLE))
            response.headers().set(HttpHeaderNames.RETRY_AFTER, "60");
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void headers(HttpResponse response, long length, String type) {
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, length);
        response.headers().set(HttpHeaderNames.CONNECTION, "close");
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
        response.headers().set("X-Content-Type-Options", "nosniff");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
    }

    private static final class Transfer {
        private final ChannelHandlerContext context;
        private final FileChannel input;
        private final Limits limits;
        private long remaining;
        @Nullable
        private io.netty.util.concurrent.ScheduledFuture<?> scheduled;

        Transfer(ChannelHandlerContext context, FileChannel input, long size, Limits limits) {
            this.context = context;
            this.input = input;
            this.remaining = size;
            this.limits = limits;
            context.channel().closeFuture().addListener(future -> {
                if (scheduled != null) scheduled.cancel(false);
            });
        }

        void next() {
            if (!context.channel().isActive()) return;
            if (remaining == 0) {
                context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            int count = (int) Math.min(16384, remaining);
            scheduled = context.executor().schedule(() -> send(count), limits.reserve(count), TimeUnit.NANOSECONDS);
        }

        private void send(int count) {
            if (!context.channel().isActive()) return;
            try {
                var buffer = ByteBuffer.allocate(count);
                while (buffer.hasRemaining()) {
                    if (input.read(buffer) < 0) throw new IOException("The hosted snapshot was truncated.");
                }
                remaining -= count;
                context.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(buffer.flip()))).addListener(future -> {
                    if (future.isSuccess()) next();
                    else context.close();
                });
            } catch (IOException e) {
                context.close();
            }
        }
    }

    private static final class Limits {
        private final HostingPolicy policy;
        private long window = System.nanoTime();
        private int requests;
        private long nextByteSlot;

        Limits(HostingPolicy policy) {
            this.policy = policy;
        }

        synchronized boolean request() {
            long now = System.nanoTime();
            if (now - window >= TimeUnit.MINUTES.toNanos(1)) {
                window = now;
                requests = 0;
            }
            if (requests >= policy.requestsPerMinute()) return false;
            requests++;
            return true;
        }

        synchronized long reserve(int count) {
            long now = System.nanoTime();
            long slot = Math.max(now, nextByteSlot);
            nextByteSlot = slot + count * TimeUnit.SECONDS.toNanos(1) / policy.bytesPerSecond();
            return slot - now;
        }
    }

    public int port() {
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    @Override
    public void close() {
        server.close().awaitUninterruptibly();
        connections.close().awaitUninterruptibly();
        acceptor.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
        workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }
}
