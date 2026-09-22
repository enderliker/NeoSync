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
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import javax.net.ssl.SSLContext;
import org.jetbrains.annotations.Nullable;

public final class ArtifactHttpClient {
    private static final Semaphore STREAMS = new Semaphore(4);
    private static final NioEventLoopGroup NETWORK = new NioEventLoopGroup(4, runnable -> {
        var thread = new Thread(runnable, "NeoSync artifact HTTPS");
        thread.setDaemon(true);
        return thread;
    });

    private ArtifactHttpClient() {}

    public static void download(InstallationPlan plan, InstallationPlan.Consent consent, InstallationPlan.File file,
            Path target, DiscoveryCancellation cancellation, LongConsumer progress) throws IOException {
        consent.require(plan);
        if (!plan.files().contains(file)) throw new IOException("The artifact was not included in the installation review.");
        cancellation.check();
        if (!STREAMS.tryAcquire()) throw new IOException("NeoSync downloads are busy. Try again shortly.");
        try {
            URI current = file.source();
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
            for (int redirects = 0;; redirects++) {
                current = InstallationPlan.externalSource(current);
                cancellation.check();
                InetAddress[] addresses;
                if (file.providedByServer() && plan.approvedAddress() != null) {
                    if (!current.equals(InstallationPlan.serverSource(plan.identity(), file.artifact().sha256())))
                        throw new IOException("The hosted artifact source changed after review.");
                    addresses = new InetAddress[] { plan.approvedAddress() };
                } else {
                    addresses = InetAddress.getAllByName(SyncEndpoint.normalizeHost(current.getHost()));
                    if (addresses.length == 0) throw new IOException("The download source could not be resolved.");
                    for (var address : addresses) {
                        if (!SyncEndpoint.isPublic(address)) throw new IOException("The download source resolves to a blocked network destination. Review this server endpoint again if it is on your local network.");
                    }
                }
                cancellation.check();
                URI redirect;
                try {
                    redirect = fetchPinned(current, addresses[0], SSLContext.getDefault(), file.artifact(), target, cancellation, progress, deadline);
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IOException("The system TLS configuration is unavailable.", e);
                }
                if (redirect == null) return;
                if (file.providedByServer()) throw new IOException("Server artifact endpoints must not redirect. No alternative source was downloaded.");
                if (redirects == 3) throw new IOException("The artifact source exceeded the redirect limit.");
                redirect = InstallationPlan.externalSource(redirect);
                if (!InstallationPlan.origin(current).equals(InstallationPlan.origin(redirect))) {
                    throw new IOException("The download redirects to a different source (" + InstallationPlan.origin(redirect)
                            + "). Ask the administrator to configure its direct URL, then review the new source before downloading.");
                }
                current = redirect;
            }
        } finally {
            STREAMS.release();
        }
    }

    /** Internal transport primitive: callers must validate destination policy and consent before connecting. */
    @org.jetbrains.annotations.ApiStatus.Internal
    @Nullable
    public static URI fetchPinned(URI uri, InetAddress address, SSLContext tls, SyncManifest.Artifact artifact,
            Path target, DiscoveryCancellation cancellation, LongConsumer progress, long deadline) throws IOException {
        cancellation.check();
        if (artifact.size() < 1 || artifact.size() > SyncManifest.MAX_FILE_BYTES || !artifact.sha256().matches(SyncManifest.HASH_PATTERN)) {
            throw new IOException("Invalid artifact limits.");
        }
        var result = new CompletableFuture<URI>();
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        String host = SyncEndpoint.normalizeHost(uri.getHost());
        boolean complete = false;
        boolean created = false;
        try (var output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            created = true;
            var bootstrap = new Bootstrap().group(NETWORK).channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            var engine = tls.createSSLEngine(host, port);
                            engine.setUseClientMode(true);
                            var parameters = engine.getSSLParameters();
                            parameters.setEndpointIdentificationAlgorithm("HTTPS");
                            engine.setSSLParameters(parameters);
                            var ssl = new SslHandler(engine);
                            ssl.setHandshakeTimeoutMillis(5000);
                            var decoder = new HttpResponseDecoder() {
                                @Override
                                protected void handleTransferEncodingChunkedWithContentLength(HttpMessage message) {
                                    // Netty normally discards Content-Length here, before the application can reject ambiguity.
                                    throw new IllegalArgumentException("Ambiguous artifact response length.");
                                }
                            };
                            channel.pipeline().addLast(ssl, new ReadTimeoutHandler(30), decoder, new HttpRequestEncoder(), new SimpleChannelInboundHandler<HttpObject>() {
                                private final java.security.MessageDigest digest = SyncManifest.sha256Digest();
                                private long received;
                                private boolean headers;

                                @Override
                                public void channelActive(ChannelHandlerContext context) throws IOException {
                                    cancellation.check();
                                    String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                                    var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
                                    request.headers().set(HttpHeaderNames.HOST, uri.getRawAuthority());
                                    request.headers().set(HttpHeaderNames.ACCEPT, "application/java-archive, application/octet-stream");
                                    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "identity");
                                    request.headers().set(HttpHeaderNames.CONNECTION, "close");
                                    context.writeAndFlush(request);
                                }

                                @Override
                                protected void channelRead0(ChannelHandlerContext context, HttpObject message) throws IOException {
                                    if (result.isDone()) return;
                                    cancellation.check();
                                    if (System.nanoTime() >= deadline) throw new IOException("The artifact download deadline expired.");
                                    if (!message.decoderResult().isSuccess()) throw new IOException("Invalid artifact HTTP response.");
                                    if (message instanceof HttpResponse response) {
                                        if (headers) throw new IOException("Repeated artifact HTTP response.");
                                        int status = response.status().code();
                                        if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                                            String location = response.headers().get(HttpHeaderNames.LOCATION);
                                            if (location == null || location.length() > 2048) throw new IOException("Invalid artifact redirect.");
                                            try {
                                                result.complete(uri.resolve(location));
                                            } catch (IllegalArgumentException e) {
                                                throw new IOException("Invalid artifact redirect.", e);
                                            }
                                            context.close();
                                            return;
                                        }
                                        if (status != 200 || !response.headers().get(HttpHeaderNames.CONTENT_ENCODING, "identity").equalsIgnoreCase("identity")) {
                                            throw new IOException("The source did not return an uncompressed artifact (HTTP " + status + ").");
                                        }
                                        if (response.headers().getAll(HttpHeaderNames.CONTENT_LENGTH).size() > 1
                                                || response.headers().contains(HttpHeaderNames.CONTENT_LENGTH) && response.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)) {
                                            throw new IOException("Ambiguous artifact response length.");
                                        }
                                        if (response.headers().contains(HttpHeaderNames.CONTENT_LENGTH) && HttpUtil.getContentLength(response) != artifact.size()) {
                                            throw new IOException("The artifact Content-Length differs from the reviewed size.");
                                        }
                                        headers = true;
                                    }
                                    if (message instanceof HttpContent content) {
                                        if (!headers) throw new IOException("The artifact response has no valid headers.");
                                        int size = content.content().readableBytes();
                                        if (size > artifact.size() - received) throw new IOException("The artifact exceeds the reviewed size.");
                                        byte[] bytes = new byte[size];
                                        content.content().readBytes(bytes);
                                        digest.update(bytes);
                                        var buffer = java.nio.ByteBuffer.wrap(bytes);
                                        while (buffer.hasRemaining()) {
                                            cancellation.check();
                                            output.write(buffer);
                                        }
                                        received += size;
                                        progress.accept(received);
                                        if (content instanceof LastHttpContent) {
                                            if (received != artifact.size() || !HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256())) {
                                                throw new IOException("The downloaded file does not match the reviewed size and SHA-256.");
                                            }
                                            output.force(true);
                                            result.complete(null);
                                            context.close();
                                        }
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                                    result.completeExceptionally(new IOException("The artifact HTTPS transfer failed verification.", cause));
                                    context.close();
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext context) {
                                    result.completeExceptionally(new IOException("The artifact response was incomplete."));
                                }
                            });
                        }
                    });
            var connection = bootstrap.connect(new InetSocketAddress(address, port));
            connection.addListener(future -> {
                if (!future.isSuccess()) result.completeExceptionally(new IOException("Could not connect to the artifact source.", future.cause()));
            });
            try {
                cancellation.attach(() -> {
                    connection.channel().close();
                    result.cancel(false);
                });
                URI redirect = result.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                cancellation.check();
                complete = redirect == null;
                return redirect;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Artifact download was cancelled.", e);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | java.util.concurrent.CancellationException e) {
                throw new IOException("Could not download a verified artifact. No profile was activated.", e);
            } finally {
                connection.channel().close().awaitUninterruptibly();
                cancellation.detach();
            }
        } finally {
            // CREATE_NEW failures must not remove a pre-existing file belonging to another operation.
            if (created && !complete) Files.deleteIfExists(target);
        }
    }
}
