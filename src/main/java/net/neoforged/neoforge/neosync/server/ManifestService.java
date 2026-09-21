/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
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
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.net.InetSocketAddress;
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
        if (tls == null && !address.getAddress().isLoopbackAddress()) throw new IOException("A plaintext manifest backend must bind to loopback.");
        byte[] snapshot = manifest.clone();
        ThreadFactory threads = runnable -> {
            var thread = new Thread(runnable, "NeoSync manifest service");
            thread.setDaemon(true);
            return thread;
        };
        acceptor = new NioEventLoopGroup(1, threads);
        workers = new NioEventLoopGroup(2, threads);
        var permits = new Semaphore(32);
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
                            var deadline = channel.eventLoop().schedule(() -> {
                                channel.close();
                            }, 10, TimeUnit.SECONDS);
                            channel.closeFuture().addListener(future -> deadline.cancel(false));
                            if (tls != null) {
                                var engine = tls.createSSLEngine();
                                engine.setUseClientMode(false);
                                var ssl = new SslHandler(engine);
                                ssl.setHandshakeTimeoutMillis(5000);
                                channel.pipeline().addLast(ssl);
                            }
                            channel.pipeline().addLast(new HttpServerCodec(4096, 8192, 8192), new HttpObjectAggregator(0), new WriteTimeoutHandler(10),
                                    new SimpleChannelInboundHandler<FullHttpRequest>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
                                            HttpResponseStatus status;
                                            if (!request.decoderResult().isSuccess()) status = HttpResponseStatus.BAD_REQUEST;
                                            else if (!request.method().equals(HttpMethod.GET)) status = HttpResponseStatus.METHOD_NOT_ALLOWED;
                                            else if (!request.uri().equals(path)) status = HttpResponseStatus.NOT_FOUND;
                                            else status = HttpResponseStatus.OK;
                                            var body = status.equals(HttpResponseStatus.OK) ? Unpooled.wrappedBuffer(snapshot) : Unpooled.EMPTY_BUFFER;
                                            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body);
                                            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
                                            response.headers().set(HttpHeaderNames.CONNECTION, "close");
                                            if (status.equals(HttpResponseStatus.OK)) {
                                                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
                                                response.headers().set("X-Content-Type-Options", "nosniff");
                                                response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
                                            } else if (status.equals(HttpResponseStatus.METHOD_NOT_ALLOWED)) {
                                                response.headers().set(HttpHeaderNames.ALLOW, "GET");
                                            }
                                            context.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
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
