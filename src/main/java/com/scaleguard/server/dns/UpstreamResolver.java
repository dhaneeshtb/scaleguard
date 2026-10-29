package com.scaleguard.server.dns;

import com.scaleguard.server.http.utils.NettyTransport;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forwards queries to upstream resolvers without ever blocking a Netty thread.
 *
 * <ul>
 *   <li>UDP first, from a small pool of sockets on random ports with random 16-bit IDs
 *       (spoofing / cache-poisoning resistance). Replies are accepted only from the server queried,
 *       with a matching ID and question.</li>
 *   <li>Per-attempt timeout; on timeout, SERVFAIL or REFUSED the next upstream is tried.</li>
 *   <li>Truncated (TC) replies are retried over TCP.</li>
 *   <li>Identical questions in flight share one upstream query.</li>
 *   <li>Successful and negative answers are cached by TTL.</li>
 * </ul>
 */
final class UpstreamResolver {

    private static final Logger LOG = LoggerFactory.getLogger(UpstreamResolver.class);
    private static final int SOCKETS = 4;
    private static final int EDNS_PAYLOAD = 1232;
    private static final int MAX_PENDING = 20_000;
    private static final long TCP_TIMEOUT_MS = 4_000;

    private final EventLoopGroup group;
    private final List<InetSocketAddress> servers;
    private final long timeoutMs;
    private final int maxAttempts;
    private final DnsCache cache;
    private final DnsStats stats;
    private final SecureRandom random = new SecureRandom();
    private final List<Channel> sockets = new ArrayList<>(SOCKETS);
    private final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<DnsWire.Message>> inflight = new ConcurrentHashMap<>();

    private static final class Pending {
        final String name;
        final int type;
        final int cls;
        final String cacheKey;
        final CompletableFuture<DnsWire.Message> future;
        final int firstServer;
        int attempt;
        int id;
        InetSocketAddress server;
        ScheduledFuture<?> timeout;

        Pending(String name, int type, int cls, String cacheKey, CompletableFuture<DnsWire.Message> f, int firstServer) {
            this.name = name; this.type = type; this.cls = cls; this.cacheKey = cacheKey; this.future = f; this.firstServer = firstServer;
        }
    }

    UpstreamResolver(EventLoopGroup group, List<InetSocketAddress> servers, long timeoutMs, DnsCache cache, DnsStats stats) throws InterruptedException {
        this.group = group;
        this.servers = List.copyOf(servers);
        this.timeoutMs = Math.max(200, timeoutMs);
        this.maxAttempts = Math.max(2, Math.min(4, servers.size() * 2));
        this.cache = cache;
        this.stats = stats;
        if (this.servers.isEmpty()) {
            LOG.warn("No upstream DNS servers configured (dnsProxy); recursion will return SERVFAIL");
            return;
        }
        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NettyTransport.datagramChannel())
                .option(ChannelOption.SO_RCVBUF, 1 << 20)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                                onUdpReply(packet);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                LOG.debug("upstream socket error", cause);
                            }
                        });
                    }
                });
        for (int i = 0; i < SOCKETS; i++) {
            sockets.add(b.bind(0).sync().channel());
        }
    }

    int pendingCount() { return pending.size(); }

    /** Resolves via upstream. Callers check the cache first. */
    CompletableFuture<DnsWire.Message> resolve(String name, int type, int cls) {
        String key = DnsCache.key(name, type, cls);
        CompletableFuture<DnsWire.Message> f = new CompletableFuture<>();
        CompletableFuture<DnsWire.Message> existing = inflight.putIfAbsent(key, f);
        if (existing != null) {
            stats.coalesced.increment();
            return existing;
        }
        f.whenComplete((m, e) -> inflight.remove(key, f));
        if (servers.isEmpty()) {
            f.completeExceptionally(new IllegalStateException("no upstream servers"));
            return f;
        }
        stats.forwarded.increment();
        send(new Pending(name, type, cls, key, f, random.nextInt(servers.size())));
        return f;
    }

    private void send(Pending p) {
        if (pending.size() >= MAX_PENDING) {
            p.future.completeExceptionally(new IllegalStateException("too many upstream queries in flight"));
            return;
        }
        int id;
        do { id = random.nextInt(65536); } while (pending.putIfAbsent(id, p) != null);
        p.id = id;
        p.server = servers.get((p.firstServer + p.attempt) % servers.size());
        Channel ch = sockets.get(random.nextInt(sockets.size()));
        byte[] query = DnsWire.buildQuery(id, p.name, p.type, p.cls, true, EDNS_PAYLOAD);
        final int sentId = id;
        p.timeout = ch.eventLoop().schedule(() -> onTimeout(p, sentId), timeoutMs, TimeUnit.MILLISECONDS);
        ch.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(query), p.server)).addListener(w -> {
            if (!w.isSuccess() && pending.remove(sentId, p)) {
                p.timeout.cancel(false);
                retryOrFail(p, w.cause());
            }
        });
    }

    private void onTimeout(Pending p, int id) {
        if (pending.remove(id, p)) {
            stats.upstreamTimeouts.increment();
            LOG.debug("upstream {} timed out for {} type {}", p.server, p.name, p.type);
            retryOrFail(p, new TimeoutException("upstream " + p.server + " timed out"));
        }
    }

    private void retryOrFail(Pending p, Throwable cause) {
        p.attempt++;
        if (p.attempt < maxAttempts) {
            send(p);
        } else {
            p.future.completeExceptionally(cause);
        }
    }

    private void onUdpReply(DatagramPacket packet) {
        ByteBuf content = packet.content();
        if (content.readableBytes() < 12) return;
        int id = content.getUnsignedShort(content.readerIndex());
        Pending p = pending.get(id);
        if (p == null) return;
        if (!packet.sender().equals(p.server)) {
            stats.upstreamRejected.increment();
            return;
        }
        DnsWire.Message m;
        try {
            m = DnsWire.parse(ByteBufUtil.getBytes(content));
        } catch (DnsWire.FormatException e) {
            stats.upstreamRejected.increment();
            return;
        }
        if (!m.isResponse() || !questionMatches(m, p)) {
            stats.upstreamRejected.increment();
            return;
        }
        if (!pending.remove(id, p)) return;
        p.timeout.cancel(false);
        if (m.truncated()) {
            stats.upstreamTcpFallbacks.increment();
            queryTcp(p);
            return;
        }
        accept(p, m);
    }

    private void accept(Pending p, DnsWire.Message m) {
        int rc = m.rcode();
        if ((rc == DnsWire.RCODE_SERVFAIL || rc == DnsWire.RCODE_REFUSED) && p.attempt + 1 < maxAttempts) {
            retryOrFail(p, new IllegalStateException("upstream rcode " + rc));
            return;
        }
        cache.put(p.cacheKey, m, System.currentTimeMillis());
        p.future.complete(m);
    }

    private static boolean questionMatches(DnsWire.Message m, Pending p) {
        if (m.questions.size() != 1) return false;
        DnsWire.Question q = m.questions.get(0);
        return q.type() == p.type && q.dnsClass() == p.cls && DnsWire.normalize(q.name()).equals(DnsWire.normalize(p.name));
    }

    private void queryTcp(Pending p) {
        AtomicBoolean done = new AtomicBoolean();
        byte[] query = DnsWire.buildQuery(p.id, p.name, p.type, p.cls, true, 0);
        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NettyTransport.socketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) TCP_TIMEOUT_MS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2))
                                .addLast(new LengthFieldPrepender(2))
                                .addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                        ctx.close();
                                        if (!done.compareAndSet(false, true)) return;
                                        try {
                                            DnsWire.Message m = DnsWire.parse(ByteBufUtil.getBytes(msg));
                                            if (m.id != p.id || !questionMatches(m, p)) throw new DnsWire.FormatException("mismatched TCP reply");
                                            accept(p, m);
                                        } catch (Exception e) {
                                            retryOrFail(p, e);
                                        }
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                        ctx.close();
                                        if (done.compareAndSet(false, true)) retryOrFail(p, cause);
                                    }
                                });
                    }
                });
        b.connect(p.server).addListener(cf -> {
            if (!cf.isSuccess()) {
                if (done.compareAndSet(false, true)) retryOrFail(p, cf.cause());
                return;
            }
            Channel ch = ((io.netty.channel.ChannelFuture) cf).channel();
            ch.eventLoop().schedule(() -> {
                if (done.compareAndSet(false, true)) {
                    ch.close();
                    stats.upstreamTimeouts.increment();
                    retryOrFail(p, new TimeoutException("TCP upstream timed out"));
                }
            }, TCP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            ch.writeAndFlush(Unpooled.wrappedBuffer(query));
        });
    }

    void close() {
        sockets.forEach(Channel::close);
        pending.values().forEach(p -> p.future.completeExceptionally(new IllegalStateException("DNS server stopping")));
        pending.clear();
    }
}
