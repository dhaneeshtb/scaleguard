package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.utils.NettyTransport;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.*;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton outbound connection pool.
 *
 * One FixedChannelPool per unique "host:port:scheme" key.
 * Channels are reused across requests — no new TCP handshake per request.
 *
 * Max connections per upstream = MAX_CONNECTIONS_PER_HOST (default 512).
 * All pools share a single NioEventLoopGroup (2 × CPU threads).
 *
 * Zero-breakage: drop-in replacement used only inside ScaleGuardFrontendHandler.
 * Existing paths (TCP proxy, WebSocket, async) are unaffected.
 */
public final class OutboundConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(OutboundConnectionPool.class);

    /** Max concurrent connections to any single upstream host. */
    private static final int MAX_CONNECTIONS_PER_HOST =
            Integer.getInteger("scaleguard.pool.maxConns", 512);

    /** Shared event loop for ALL outbound connections. */
    public static final EventLoopGroup SHARED_WORKER =
            NettyTransport.newGroup(Runtime.getRuntime().availableProcessors() * 2, "sg-outbound");

    /** Pool map: "host:port:ssl" → FixedChannelPool */
    private static final ConcurrentHashMap<String, FixedChannelPool> POOLS =
            new ConcurrentHashMap<>();

    private OutboundConnectionPool() {}

    /**
     * Acquire a channel from the pool for the given host/port/ssl combination.
     * If no pool exists for this key, one is created lazily.
     *
     * @param host          upstream hostname or IP
     * @param port          upstream port
     * @param ssl           whether to use HTTPS to the upstream
     * @param inboundLoop   the inbound channel's EventLoop (for same-thread affinity)
     * @return a Future that yields a connected, pipeline-ready Channel
     */
    public static Future<Channel> acquire(String host, int port, boolean ssl, EventLoop inboundLoop) {
        String key = host + ":" + port + ":" + ssl;
        FixedChannelPool pool = POOLS.computeIfAbsent(key, k -> createPool(host, port, ssl));
        return pool.acquire();
    }

    /**
     * Release a channel back to the pool.
     * Must be called when the response has been fully written to the inbound channel.
     */
    public static Future<Void> release(Channel ch) {
        String host = poolKeyForChannel(ch);
        if (host != null) {
            FixedChannelPool pool = POOLS.get(host);
            if (pool != null) {
                return pool.release(ch);
            }
        }
        // Fallback: just close
        ch.close();
        return ch.closeFuture();
    }

    // ── private helpers ────────────────────────────────────────────────────

    private static FixedChannelPool createPool(String host, int port, boolean ssl) {
        log.info("[Pool] Creating connection pool for {}:{} ssl={} maxConns={}",
                host, port, ssl, MAX_CONNECTIONS_PER_HOST);

        Bootstrap bootstrap = new Bootstrap()
                .group(SHARED_WORKER)
                .channel(NettyTransport.socketChannel())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .remoteAddress(host, port);

        return new FixedChannelPool(
                bootstrap,
                new PoolChannelHandler(ssl, host, port),
                ChannelHealthChecker.ACTIVE,
                FixedChannelPool.AcquireTimeoutAction.FAIL,
                5_000,                   // acquireTimeoutMillis
                MAX_CONNECTIONS_PER_HOST,
                MAX_CONNECTIONS_PER_HOST  // maxPendingAcquires = maxConns
        );
    }

    private static String poolKeyForChannel(Channel ch) {
        // Channels store their pool key as an attribute
        io.netty.util.Attribute<String> attr =
                ch.attr(io.netty.util.AttributeKey.valueOf("sg.pool.key"));
        return attr.get();
    }

    // ── Channel handler that initialises freshly-created pool channels ──────

    static class PoolChannelHandler extends AbstractChannelPoolHandler {

        private final boolean ssl;
        private final String host;
        private final int port;

        PoolChannelHandler(boolean ssl, String host, int port) {
            this.ssl = ssl;
            this.host = host;
            this.port = port;
        }

        @Override
        public void channelCreated(Channel ch) throws Exception {
            // Tag the channel with its pool key so we can release it later
            String key = host + ":" + port + ":" + ssl;
            ch.attr(io.netty.util.AttributeKey.<String>valueOf("sg.pool.key")).set(key);

            ChannelPipeline p = ch.pipeline();
            if (ssl) {
                p.addLast(SecureProxyInitializer.sslContext.newHandler(ch.alloc(), host, port));
            }
            p.addLast(new HttpClientCodec());
            p.addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
            // ScaleGuardBackendHandler is added per-request (not here),
            // so we leave a placeholder pipeline. The actual handler is
            // inserted dynamically in ScaleGuardFrontendHandler after acquire().
        }

        @Override
        public void channelAcquired(Channel ch) {
            // Remove any leftover per-request handler from the previous use
            if (ch.pipeline().get(ScaleGuardBackendHandler.class) != null) {
                ch.pipeline().remove(ScaleGuardBackendHandler.class);
            }
        }

        @Override
        public void channelReleased(Channel ch) {
            // Nothing to reset – codec is stateless between requests
        }
    }
}
