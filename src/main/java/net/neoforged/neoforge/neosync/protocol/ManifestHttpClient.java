/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;

public final class ManifestHttpClient {
    private static final NioEventLoopGroup NETWORK = new NioEventLoopGroup(1, runnable -> {
        var thread = new Thread(runnable, "NeoSync HTTPS");
        thread.setDaemon(true);
        return thread;
    });

    private ManifestHttpClient() {}

    public static byte[] fetch(SyncEndpoint endpoint, InetAddress approvedAddress, SSLContext tls,
            DiscoveryCancellation cancellation) throws IOException {
        cancellation.check();
        var uri = endpoint.manifestUri();
        var result = new CompletableFuture<byte[]>();
        var bootstrap = new Bootstrap().group(NETWORK).channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        var engine = tls.createSSLEngine(endpoint.host(), endpoint.httpsPort());
                        engine.setUseClientMode(true);
                        var parameters = engine.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        engine.setSSLParameters(parameters);
                        var ssl = new SslHandler(engine);
                        ssl.setHandshakeTimeoutMillis(5000);
                        channel.pipeline().addLast(ssl, new HttpClientCodec(), new HttpObjectAggregator(SyncManifest.MAX_BYTES),
                                new SimpleChannelInboundHandler<FullHttpResponse>() {
                                    @Override
                                    public void channelActive(ChannelHandlerContext context) throws IOException {
                                        cancellation.check();
                                        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
                                        request.headers().set(HttpHeaderNames.HOST, uri.getRawAuthority());
                                        request.headers().set(HttpHeaderNames.ACCEPT, "application/json");
                                        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "identity");
                                        request.headers().set(HttpHeaderNames.CONNECTION, "close");
                                        context.writeAndFlush(request);
                                    }

                                    @Override
                                    protected void channelRead0(ChannelHandlerContext context, FullHttpResponse response) {
                                        String type = response.headers().get(HttpHeaderNames.CONTENT_TYPE, "").toLowerCase(Locale.ROOT);
                                        String encoding = response.headers().get(HttpHeaderNames.CONTENT_ENCODING, "identity");
                                        if (!response.decoderResult().isSuccess() || response.status().code() != 200
                                                || !type.split(";", 2)[0].trim().equals("application/json") || !encoding.equalsIgnoreCase("identity")) {
                                            result.completeExceptionally(new IOException("The server did not return an uncompressed JSON manifest (HTTP " + response.status().code() + ")."));
                                        } else {
                                            byte[] bytes = new byte[response.content().readableBytes()];
                                            response.content().readBytes(bytes);
                                            result.complete(bytes);
                                        }
                                        context.close();
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                                        result.completeExceptionally(new IOException("The HTTPS manifest connection failed.", cause));
                                        context.close();
                                    }

                                    @Override
                                    public void channelInactive(ChannelHandlerContext context) {
                                        result.completeExceptionally(new IOException("The HTTPS manifest response was incomplete."));
                                    }
                                });
                    }
                });
        // Connect to the approved address while TLS still verifies the logical hostname.
        var connection = bootstrap.connect(new InetSocketAddress(approvedAddress, endpoint.httpsPort()));
        connection.addListener(future -> {
            if (!future.isSuccess()) result.completeExceptionally(new IOException("Could not connect to the HTTPS manifest service.", future.cause()));
        });
        try {
            cancellation.attach(() -> {
                connection.channel().close();
                result.cancel(false);
            });
            byte[] bytes = result.get(10, TimeUnit.SECONDS);
            cancellation.check();
            if (!SyncManifest.sha256(bytes).equals(endpoint.digest())) throw new IOException("The manifest does not match the advertised SHA-256 hash.");
            return bytes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Discovery was cancelled.", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | java.util.concurrent.CancellationException e) {
            throw new IOException("Could not retrieve a verified HTTPS manifest.", e);
        } finally {
            connection.channel().close();
            cancellation.detach();
        }
    }
}
