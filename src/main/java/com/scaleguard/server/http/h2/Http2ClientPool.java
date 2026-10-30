package com.scaleguard.server.http.h2;

import com.scaleguard.server.http.utils.NettyTransport;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared HTTP/2 connections to backends, multiplexing many gRPC calls over one TCP connection per
 * backend and event loop (h2c with prior knowledge, or TLS + ALPN "h2"). A connection is replaced when it closes or
 * receives GOAWAY. Backend certificates are not verified, matching the HTTP/1.1 proxy path.
 */
public final class Http2ClientPool {

    private static final Logger LOG = LoggerFactory.getLogger(Http2ClientPool.class);
    private static final Http2ClientPool INSTANCE = new Http2ClientPool();
    private static final int CONNECT_TIMEOUT_MS = Integer.getInteger("scaleguard.http2.connectTimeoutMs", 5_000);

    private final ConcurrentHashMap<String, Future<Channel>> connections = new ConcurrentHashMap<>();
    private final SslContext tlsContext;

    private Http2ClientPool() {
        try {
            tlsContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create HTTP/2 client TLS context", e);
        }
    }

    public static Http2ClientPool getInstance() {
        return INSTANCE;
    }

    public int connectionCount() {
        return connections.size();
    }

    /** Opens a new stream to {@code host:port}; {@code handler} receives the backend's frames. */
    /** Per-target TLS and connect settings (grpc_ssl_verify, grpc_ssl_name, grpc_connect_timeout). */
    public record Options(boolean verify, String serverName, int connectTimeoutMs) {
        static final Options DEFAULT = new Options(false, null, CONNECT_TIMEOUT_MS);
    }

    public Future<Http2StreamChannel> openStream(EventLoop loop, String host, int port, boolean tls, ChannelHandler handler) {
        return openStream(loop, host, port, tls, Options.DEFAULT, handler);
    }

    public Future<Http2StreamChannel> openStream(EventLoop loop, String host, int port, boolean tls, Options opts, ChannelHandler handler) {
        Promise<Http2StreamChannel> result = loop.newPromise();
        connection(loop, host, port, tls, opts == null ? Options.DEFAULT : opts).addListener(cf -> {
            if (!cf.isSuccess()) {
                result.tryFailure(cf.cause());
                return;
            }
            Channel parent = (Channel) cf.getNow();
            new Http2StreamChannelBootstrap(parent).handler(handler).open().addListener(sf -> {
                if (sf.isSuccess()) result.trySuccess((Http2StreamChannel) sf.getNow());
                else result.tryFailure(sf.cause());
            });
        });
        return result;
    }

    private Future<Channel> connection(EventLoop loop, String host, int port, boolean tls, Options opts) {
        // One connection per backend per event loop: the client stream and its backend stream then share a
        // thread, so the relay needs no locking and frames never hop between threads.
        String key = host + ":" + port + (tls ? ":tls" + (opts.verify() ? "+verify" : "") + ":" + opts.serverName() : ":h2c")
                + "@" + System.identityHashCode(loop);
        while (true) {
            Future<Channel> f = connections.get(key);
            if (f != null) {
                if (!f.isDone()) return f;
                if (f.isSuccess() && usable(f.getNow())) return f;
                connections.remove(key, f);
                continue;
            }
            Promise<Channel> p = loop.newPromise();
            if (connections.putIfAbsent(key, p) != null) continue;
            connect(loop, host, port, tls, opts, key, p);
            return p;
        }
    }

    private static boolean usable(Channel ch) {
        return ch.isActive() && !Boolean.TRUE.equals(ch.attr(GoAwayWatcher.GOAWAY).get());
    }

    private void connect(EventLoop loop, String host, int port, boolean tls, Options opts, String key, Promise<Channel> p) {
        Bootstrap b = new Bootstrap()
                .group(loop)
                .channel(NettyTransport.socketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, opts.connectTimeoutMs() > 0 ? opts.connectTimeoutMs() : CONNECT_TIMEOUT_MS)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        if (tls) {
                            String sni = opts.serverName() != null ? opts.serverName() : host;
                            ch.pipeline().addLast(opts.verify() || opts.serverName() != null
                                    ? com.scaleguard.server.http.reverse.UpstreamTls.http2(opts.verify(), sni, ch.alloc(), port)
                                    : tlsContext.newHandler(ch.alloc(), host, port));
                        }
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient()
                                .initialSettings(Http2Settings.defaultSettings().initialWindowSize(1 << 20).pushEnabled(false))
                                .build());
                        ch.pipeline().addLast(new Http2MultiplexHandler(new RejectPushHandler()));
                        ch.pipeline().addLast(new GoAwayWatcher());
                    }
                });
        ChannelFuture cf = b.connect(host, port);
        cf.addListener(c -> {
            if (c.isSuccess()) {
                Channel ch = cf.channel();
                ch.closeFuture().addListener(x -> connections.remove(key, p));
                p.trySuccess(ch);
                LOG.debug("HTTP/2 backend connection open: {}", key);
            } else {
                connections.remove(key, p);
                p.tryFailure(c.cause());
            }
        });
    }

    /** Server push is disabled in SETTINGS; drop any stream a misbehaving backend opens anyway. */
    @ChannelHandler.Sharable
    private static final class RejectPushHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ReferenceCountUtil.release(msg);
            ctx.close();
        }
    }

    /** Marks the connection unusable for new streams once the backend sends GOAWAY; existing streams finish. */
    static final class GoAwayWatcher extends ChannelInboundHandlerAdapter {
        static final io.netty.util.AttributeKey<Boolean> GOAWAY = io.netty.util.AttributeKey.valueOf("sg.h2.goaway");

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof io.netty.handler.codec.http2.Http2GoAwayFrame) ctx.channel().attr(GOAWAY).set(true);
            ReferenceCountUtil.release(msg);
        }
    }
}
