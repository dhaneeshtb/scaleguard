package com.scaleguard.server.http.h3;

import com.scaleguard.server.http.h2.ProtocolPipelines;
import com.scaleguard.server.http.reverse.ScaleGuardFrontendHandler;
import com.scaleguard.server.http.utils.AppProperties;
import com.scaleguard.server.http.utils.NettyTransport;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3FrameToHttpObjectCodec;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicCodecDispatcher;
import io.netty.handler.codec.quic.QuicConnectionIdGenerator;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicTokenHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * HTTP/3 over QUIC (RFC 9114) on the same port numbers as the TLS listeners, over UDP.
 *
 * <p>Each QUIC request stream is converted to ordinary HTTP objects and handed to the same
 * {@link ScaleGuardFrontendHandler} as HTTP/1.1 and HTTP/2 — so routing, load balancing, the WAF, rate limits,
 * caching, analytics and the admin API behave identically. Backends keep speaking HTTP/1.1 (or HTTP/2); only the
 * client side changes. TLS 1.3 certificates come from the same store as the TCP listeners, chosen per SNI name.
 * TLS listeners advertise HTTP/3 with an {@code Alt-Svc} header, so browsers upgrade on their next request.
 *
 * <p>Settings (system property, environment or application.properties):
 * <ul>
 *   <li>{@code scaleguard.http3} — {@code true} (default) / {@code false}</li>
 *   <li>{@code scaleguard.http3.retry} — require QUIC address validation (one extra round trip; default false)</li>
 *   <li>{@code scaleguard.http3.maxStreams} — concurrent requests per connection (default 256)</li>
 *   <li>{@code scaleguard.http3.idleTimeoutSeconds} — default 60</li>
 *   <li>{@code scaleguard.http3.congestion} — {@code cubic} (default), {@code bbr} or {@code reno}</li>
 *   <li>{@code scaleguard.http3.sockets} — UDP sockets per port on Linux (default: one per CPU core)</li>
 * </ul>
 */
public final class Http3Listener {

    private static final Logger LOG = LoggerFactory.getLogger(Http3Listener.class);
    static final long ALT_SVC_MAX_AGE = 86_400;

    private static final Set<Integer> PORTS = ConcurrentHashMap.newKeySet();
    private static volatile String unavailable;

    private Http3Listener() {}

    /** No address validation: new connections start immediately (QUIC's 3× anti-amplification limit still applies). */
    private static final QuicTokenHandler NO_RETRY = new QuicTokenHandler() {
        @Override
        public boolean writeToken(io.netty.buffer.ByteBuf out, io.netty.buffer.ByteBuf dcid, InetSocketAddress address) {
            return false;
        }

        @Override
        public int validateToken(io.netty.buffer.ByteBuf token, InetSocketAddress address) {
            return 0;
        }

        @Override
        public int maxTokenLength() {
            return 0;
        }
    };

    private static String setting(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = AppProperties.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(setting("scaleguard.http3", "true"));
    }

    /** True when the QUIC native library loaded for this OS/CPU. */
    public static boolean available() {
        try {
            if (Quic.isAvailable()) return true;
            Throwable t = Quic.unavailabilityCause();
            unavailable = t == null ? "unknown reason" : t.getMessage();
        } catch (Throwable t) {
            unavailable = t.toString();
        }
        return false;
    }

    /** Ports where HTTP/3 is being served (TLS listeners use this to decide whether to send Alt-Svc). */
    public static boolean listening(int port) {
        return PORTS.contains(port);
    }

    public static Set<Integer> ports() {
        return Set.copyOf(PORTS);
    }

    public static String unavailableReason() {
        return unavailable;
    }

    /**
     * Binds HTTP/3 on UDP {@code port}. Returns null when HTTP/3 is off, unavailable on this platform, or the port is
     * already served.
     */
    public static synchronized ChannelFuture bind(int port, EventLoopGroup group) {
        if (!enabled() || PORTS.contains(port)) return null;
        if (!available()) {
            LOG.warn("HTTP/3 disabled: the QUIC native library isn't available on this platform ({})", unavailable);
            return null;
        }
        int maxStreams = Integer.parseInt(setting("scaleguard.http3.maxStreams", "256"));
        long idle = Long.parseLong(setting("scaleguard.http3.idleTimeoutSeconds", "60"));
        boolean retry = Boolean.parseBoolean(setting("scaleguard.http3.retry", "false"));
        QuicTokenHandler tokens = retry ? new RetryTokenHandler() : NO_RETRY;
        io.netty.handler.codec.quic.QuicSslContext ssl = QuicSslContextBuilder.buildForServerWithSni(new QuicSniContexts(port));
        // Several UDP sockets on the port (SO_REUSEPORT) spread QUIC across I/O threads; the dispatcher encodes the
        // socket in each connection id so packets of one connection always reach the codec that owns it.
        int sockets = NettyTransport.supportsReusePort()
                ? Math.max(1, Integer.parseInt(setting("scaleguard.http3.sockets", String.valueOf(Runtime.getRuntime().availableProcessors()))))
                : 1;
        QuicCodecDispatcher dispatcher = new QuicCodecDispatcher() {
            @Override
            protected void initChannel(Channel channel, int localConnectionIdLength, QuicConnectionIdGenerator idGenerator) {
                channel.pipeline().addLast("quic", Http3.newQuicServerCodecBuilder()
                        .sslContext(ssl)
                        .localConnectionIdLength(localConnectionIdLength)
                        .connectionIdAddressGenerator(idGenerator)
                        .maxIdleTimeout(idle, TimeUnit.SECONDS)
                        .initialMaxData(16L * 1024 * 1024)
                        .initialMaxStreamDataBidirectionalLocal(1024 * 1024)
                        .initialMaxStreamDataBidirectionalRemote(1024 * 1024)
                        .initialMaxStreamsBidirectional(maxStreams)
                        .initialMaxStreamsUnidirectional(16)   // HTTP/3 control + QPACK streams
                        .congestionControlAlgorithm(congestion())
                        .activeMigration(true)
                        .tokenHandler(tokens)
                        .handler(connectionInitializer(port))
                        .build());
            }
        };
        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NettyTransport.datagramChannel())
                .option(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
                .option(ChannelOption.SO_SNDBUF, 4 * 1024 * 1024)
                .handler(dispatcher);
        if (sockets > 1) b.option(NettyTransport.reusePortOption(), Boolean.TRUE);
        ChannelFuture first = null;
        for (int i = 0; i < sockets; i++) {
            ChannelFuture f = b.bind(new InetSocketAddress(port)).awaitUninterruptibly();
            if (!f.isSuccess()) {
                LOG.warn("HTTP/3: could not bind UDP port {}: {}", port, f.cause() == null ? "" : f.cause().getMessage());
                if (first == null) return null;
                break;
            }
            if (first == null) first = f;
        }
        PORTS.add(port);
        LOG.info("HTTP/3 (QUIC) listening on UDP port {} ({} socket{}{})", port, sockets, sockets == 1 ? "" : "s",
                retry ? ", address validation on" : "");
        return first;
    }

    private static ChannelInitializer<QuicChannel> connectionInitializer(int port) {
        return new ChannelInitializer<QuicChannel>() {
            @Override
            protected void initChannel(QuicChannel ch) {
                ch.pipeline().addLast("h3", new Http3ServerConnectionHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel stream) {
                        stream.pipeline().addLast("h3-to-http", new Http3FrameToHttpObjectCodec(true));
                        stream.pipeline().addLast("http-aggregator", new HttpObjectAggregator(ProtocolPipelines.maxBodyBytes()));
                        stream.pipeline().addLast("frontend", new ScaleGuardFrontendHandler(port));
                    }
                }));
            }
        };
    }

    private static QuicCongestionControlAlgorithm congestion() {
        return switch (setting("scaleguard.http3.congestion", "cubic").toLowerCase(Locale.ROOT)) {
            case "bbr" -> QuicCongestionControlAlgorithm.BBR;
            case "reno" -> QuicCongestionControlAlgorithm.RENO;
            default -> QuicCongestionControlAlgorithm.CUBIC;
        };
    }

    /** The real client address of a channel, including QUIC streams (whose own address is a stream id). */
    public static InetSocketAddress peer(Channel ch) {
        if (ch instanceof QuicStreamChannel s && s.parent() != null && s.parent().remoteSocketAddress() instanceof InetSocketAddress a) {
            return a;
        }
        return ch.remoteAddress() instanceof InetSocketAddress a ? a : null;
    }

    public static boolean isHttp3(Channel ch) {
        return ch instanceof QuicStreamChannel;
    }
}
