package com.scaleguard.server.dns;

import com.scaleguard.server.http.utils.NettyTransport;
import com.scaleguard.server.dns.DnsWire.Rr;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.AbstractDnsOptPseudoRrRecord;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.handler.codec.dns.TcpDnsQueryDecoder;
import io.netty.handler.codec.dns.TcpDnsResponseEncoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ScaleGuard DNS server.
 *
 * <ul>
 *   <li><b>Authoritative</b> for the names in {@link DNSAddressBook}: correct record types, NODATA with SOA,
 *       AA flag, SOA/NS at the zone apex.</li>
 *   <li><b>Closed recursion</b>: other names are forwarded only for clients in {@code dnsRecursionAllow};
 *       everyone else gets REFUSED, so the server can't be used for DNS amplification.</li>
 *   <li><b>Non-blocking forwarding</b> with retries, TCP fallback, TTL cache and query coalescing
 *       ({@link UpstreamResolver}).</li>
 *   <li><b>Per-client rate limiting</b> with TC "slip" ({@link DnsRateLimiter}).</li>
 *   <li><b>EDNS0</b> payload negotiation and correct UDP truncation; TCP with idle timeout and connection cap.</li>
 *   <li>On Linux, one UDP socket per core via SO_REUSEPORT.</li>
 * </ul>
 */
public final class DnsServer {

    private static final Logger logger = LoggerFactory.getLogger(DnsServer.class);
    private static final int MAX_UDP_PAYLOAD = 1232; // DNS Flag Day 2020 recommendation
    private static final int LEGACY_UDP_PAYLOAD = 512;

    private static volatile DnsServer running;

    private final DnsConfig config = DnsConfig.get();
    private final DnsStats stats = new DnsStats();
    private final DnsCache cache = new DnsCache(config.cacheSize);
    private final DnsRateLimiter limiter = new DnsRateLimiter(config.rateLimit, config.rateBurst);
    private final AtomicInteger tcpConnections = new AtomicInteger();
    private final List<Channel> channels = new ArrayList<>();
    private final List<EventLoopGroup> groups = new ArrayList<>();
    private UpstreamResolver upstream;

    public static void main(String[] args) throws Exception {
        new DnsServer().startBlocking();
    }

    /** True when dnsServer=true is set in application.properties, the environment or system properties. */
    public static boolean isEnabled() { return DnsConfig.enabled(); }

    /** Current server, if started — used by the stats endpoint. */
    static DnsServer current() { return running; }

    /** Starts in the background; failures are logged, never thrown into the caller. */
    public void start() {
        Thread t = new Thread(() -> {
            try {
                startBlocking();
            } catch (Exception e) {
                logger.error("DNS server failed to start on port {}: {}", config.port, e.toString(), e);
            }
        }, "sg-dns-start");
        t.setDaemon(true);
        t.start();
    }

    public void startBlocking() throws Exception {
        boolean reusePort = NettyTransport.supportsReusePort();
        int udpThreads = reusePort ? Math.max(1, Runtime.getRuntime().availableProcessors()) : 1;

        EventLoopGroup udpGroup = NettyTransport.newGroup(udpThreads, "sg-dns-udp");
        EventLoopGroup tcpBoss = NettyTransport.newGroup(1, "sg-dns-accept");
        EventLoopGroup tcpWorkers = NettyTransport.newGroup(2, "sg-dns-tcp");
        EventLoopGroup upstreamGroup = NettyTransport.newGroup(2, "sg-dns-upstream");
        groups.addAll(List.of(udpGroup, tcpBoss, tcpWorkers, upstreamGroup));

        upstream = new UpstreamResolver(upstreamGroup, config.upstreams, config.upstreamTimeoutMs, cache, stats);
        QueryHandler handler = new QueryHandler();

        // UDP
        Bootstrap udp = new Bootstrap()
                .group(udpGroup)
                .option(ChannelOption.SO_RCVBUF, 4 << 20)
                .option(ChannelOption.SO_SNDBUF, 4 << 20)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new DatagramDnsQueryDecoder(), new DatagramDnsResponseEncoder(), handler);
                    }
                });
        udp.channel(NettyTransport.datagramChannel());
        if (reusePort) udp.option(NettyTransport.reusePortOption(), true);
        for (int i = 0; i < udpThreads; i++) {
            channels.add(udp.bind(config.port).sync().channel());
        }

        // TCP
        ServerBootstrap tcp = new ServerBootstrap()
                .group(tcpBoss, tcpWorkers)
                .channel(NettyTransport.serverChannel())
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(
                                new IdleStateHandler(10, 10, 0, TimeUnit.SECONDS),
                                new TcpConnectionGuard(),
                                new TcpDnsQueryDecoder(),
                                new TcpDnsResponseEncoder(),
                                handler);
                    }
                });
        channels.add(tcp.bind(config.port).sync().channel());

        // Housekeeping: rate-limiter cleanup + one summary log line per minute.
        udpGroup.next().scheduleAtFixedRate(() -> {
            limiter.sweep();
            String summary = stats.intervalSummary();
            if (summary != null) logger.info(summary);
        }, 60, 60, TimeUnit.SECONDS);

        running = this;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "sg-dns-stop"));
        logger.info("DNS server listening on UDP/TCP {} ({} UDP socket{}, {}) | upstreams={} | recursion={} for {} | rate limit={}/s per client",
                config.port, udpThreads, udpThreads > 1 ? "s" : "", NettyTransport.describe() + (reusePort ? "+SO_REUSEPORT" : ""),
                config.upstreams, config.recursion, config.recursionAllow, config.rateLimit);
    }

    public void stop() {
        channels.forEach(Channel::close);
        if (upstream != null) upstream.close();
        groups.forEach(g -> g.shutdownGracefully(0, 2, TimeUnit.SECONDS));
        if (running == this) running = null;
    }

    Map<String, Object> statsSnapshot() {
        return stats.snapshot(cache.size(), upstream == null ? 0 : upstream.pendingCount(), limiter.trackedClients());
    }

    void clearCache() { cache.clear(); }

    // ------------------------------------------------------------------ request handling

    /** Everything needed to answer, captured before the query buffer is released. */
    private record Request(ChannelHandlerContext ctx, boolean udp, InetSocketAddress client, InetSocketAddress local,
                           int id, boolean rd, String qname, int qtype, int qclass, int ednsPayload) {}

    /** EDNS0 OPT record we add to replies when the client used EDNS. */
    private static final class EdnsOpt extends AbstractDnsOptPseudoRrRecord {
        EdnsOpt(int payload) { super(payload); }
    }

    @ChannelHandler.Sharable
    private final class QueryHandler extends SimpleChannelInboundHandler<DnsQuery> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DnsQuery query) {
            stats.queries.increment();
            boolean udp = query instanceof DatagramDnsQuery;
            InetSocketAddress client;
            InetSocketAddress local = null;
            if (udp) {
                stats.udp.increment();
                client = ((DatagramDnsQuery) query).sender();
                local = ((DatagramDnsQuery) query).recipient();
            } else {
                stats.tcp.increment();
                client = (InetSocketAddress) ctx.channel().remoteAddress();
            }

            if (udp) {
                DnsRateLimiter.Result rl = limiter.check(client.getAddress());
                if (rl != DnsRateLimiter.Result.ALLOW) {
                    stats.rateLimited.increment();
                    if (rl == DnsRateLimiter.Result.SLIP && query.count(DnsSection.QUESTION) == 1) {
                        Request r = toRequest(ctx, query, true, client, local);
                        reply(r, DnsWire.RCODE_NOERROR, false, false, List.of(), List.of(), List.of(), 0, true);
                    }
                    return;
                }
            }

            if (query.opCode() != DnsOpCode.QUERY) {
                stats.notImp.increment();
                replyBare(ctx, udp, client, local, query.id(), query.opCode(), DnsWire.RCODE_NOTIMP);
                return;
            }
            if (query.count(DnsSection.QUESTION) != 1) {
                stats.formErr.increment();
                replyBare(ctx, udp, client, local, query.id(), DnsOpCode.QUERY, DnsWire.RCODE_FORMERR);
                return;
            }

            Request r = toRequest(ctx, query, udp, client, local);
            if (logger.isDebugEnabled()) {
                logger.debug("dns query {} {} from {} ({})", r.qname, DnsRecordType.valueOf(r.qtype).name(), client, udp ? "udp" : "tcp");
            }
            answer(r);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // Malformed packets land here. Never close the shared UDP socket.
            stats.formErr.increment();
            logger.debug("DNS decode error from {}: {}", ctx.channel().remoteAddress(), cause.toString());
            if (!(ctx.channel() instanceof io.netty.channel.socket.DatagramChannel)) ctx.close();
        }
    }

    private static Request toRequest(ChannelHandlerContext ctx, DnsQuery query, boolean udp, InetSocketAddress client, InetSocketAddress local) {
        DnsQuestion q = query.recordAt(DnsSection.QUESTION);
        int edns = -1;
        for (int i = 0, n = query.count(DnsSection.ADDITIONAL); i < n; i++) {
            DnsRecord rec = query.recordAt(DnsSection.ADDITIONAL, i);
            if (rec.type() == DnsRecordType.OPT) { edns = rec.dnsClass(); break; }
        }
        return new Request(ctx, udp, client, local, query.id(), query.isRecursionDesired(),
                DnsWire.normalize(q.name()), q.type().intValue(), q.dnsClass(), edns);
    }

    private void answer(Request r) {
        boolean recursionAllowed = config.recursion && config.recursionAllow.contains(clientAddress(r));

        if (config.isBlocked(r.qname)) {
            stats.refused.increment();
            reply(r, DnsWire.RCODE_REFUSED, false, recursionAllowed, List.of(), List.of(), List.of(), 0, false);
            return;
        }

        // 1. Our own zones
        if (r.qclass == DnsWire.CLASS_IN || r.qclass == DnsWire.CLASS_ANY) {
            DNSAddressBook.AuthAnswer auth = DNSAddressBook.resolve(r.qname, r.qtype);
            if (auth != null) {
                stats.authoritative.increment();
                reply(r, auth.rcode(), true, recursionAllowed, auth.answers(), auth.authority(), List.of(), 0, false);
                return;
            }
        }

        // 2. Everything else: only for allowed clients
        if (!recursionAllowed) {
            stats.refused.increment();
            reply(r, DnsWire.RCODE_REFUSED, false, false, List.of(), List.of(), List.of(), 0, false);
            return;
        }

        String key = DnsCache.key(r.qname, r.qtype, r.qclass);
        long now = System.currentTimeMillis();
        DnsCache.Entry hit = cache.get(key, now);
        if (hit != null) {
            stats.cacheHits.increment();
            reply(r, hit.rcode(), false, true, hit.answers(), hit.authority(), hit.additional(), hit.ageSeconds(now), false);
            return;
        }
        if (!r.rd) { // non-recursive query for something we don't hold
            stats.refused.increment();
            reply(r, DnsWire.RCODE_REFUSED, false, true, List.of(), List.of(), List.of(), 0, false);
            return;
        }

        upstream.resolve(r.qname, r.qtype, r.qclass).whenComplete((m, err) -> {
            if (err != null) {
                stats.servfail.increment();
                logger.debug("upstream failed for {}: {}", r.qname, err.toString());
                reply(r, DnsWire.RCODE_SERVFAIL, false, true, List.of(), List.of(), List.of(), 0, false);
            } else {
                reply(r, m.rcode(), false, true, m.answers, m.authority, m.additional, 0, false);
            }
        });
    }

    private static InetAddress clientAddress(Request r) {
        return r.client == null ? null : r.client.getAddress();
    }

    private void reply(Request r, int rcode, boolean aa, boolean ra, List<Rr> an, List<Rr> ns, List<Rr> ar,
                       long ageSeconds, boolean forceTruncate) {
        DnsResponse resp = newResponse(r.udp, r.local, r.client, r.id, DnsOpCode.QUERY, rcode);
        resp.setAuthoritativeAnswer(aa);
        resp.setRecursionDesired(r.rd);
        resp.setRecursionAvailable(ra);
        resp.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(r.qname, DnsRecordType.valueOf(r.qtype), r.qclass));

        boolean truncate = forceTruncate;
        if (!truncate && r.udp) {
            int limit = r.ednsPayload >= 0 ? Math.max(LEGACY_UDP_PAYLOAD, Math.min(r.ednsPayload, MAX_UDP_PAYLOAD)) : LEGACY_UDP_PAYLOAD;
            truncate = encodedSize(r.qname, an, ns, ar, r.ednsPayload >= 0) > limit;
        }
        if (truncate) {
            stats.truncated.increment();
            resp.setTruncated(true);
        } else {
            addAll(resp, DnsSection.ANSWER, an, ageSeconds);
            addAll(resp, DnsSection.AUTHORITY, ns, ageSeconds);
            addAll(resp, DnsSection.ADDITIONAL, ar, ageSeconds);
        }
        if (r.ednsPayload >= 0) resp.addRecord(DnsSection.ADDITIONAL, new EdnsOpt(MAX_UDP_PAYLOAD));
        r.ctx.writeAndFlush(resp);
    }

    private static void replyBare(ChannelHandlerContext ctx, boolean udp, InetSocketAddress client, InetSocketAddress local,
                                  int id, DnsOpCode op, int rcode) {
        ctx.writeAndFlush(newResponse(udp, local, client, id, op, rcode));
    }

    private static DnsResponse newResponse(boolean udp, InetSocketAddress local, InetSocketAddress client, int id, DnsOpCode op, int rcode) {
        DnsResponseCode code = DnsResponseCode.valueOf(rcode);
        return udp ? new DatagramDnsResponse(local, client, id, op, code) : new DefaultDnsResponse(id, op, code);
    }

    private static void addAll(DnsResponse resp, DnsSection section, List<Rr> rrs, long ageSeconds) {
        for (Rr rr : rrs) {
            resp.addRecord(section, (ageSeconds > 0 ? rr.withTtl(Math.max(0, rr.ttl() - ageSeconds)) : rr).toNetty());
        }
    }

    /** Wire size of the response, to decide on UDP truncation. Names are counted uncompressed (upper bound). */
    private static int encodedSize(String qname, List<Rr> an, List<Rr> ns, List<Rr> ar, boolean edns) {
        int size = 12 + DnsWire.encodeName(qname).length + 4 + (edns ? 11 : 0);
        for (List<Rr> l : List.of(an, ns, ar)) {
            for (Rr rr : l) size += DnsWire.encodeName(rr.name()).length + 10 + rr.rdata().length;
        }
        return size;
    }

    /** Caps concurrent TCP clients. */
    private final class TcpConnectionGuard extends ChannelInboundHandlerAdapter {
        private boolean counted;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            if (tcpConnections.incrementAndGet() > config.tcpMaxConnections) {
                tcpConnections.decrementAndGet();
                ctx.close();
                return;
            }
            counted = true;
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (counted) tcpConnections.decrementAndGet();
            super.channelInactive(ctx);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) ctx.close();
            else super.userEventTriggered(ctx, evt);
        }
    }
}
