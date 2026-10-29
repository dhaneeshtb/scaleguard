package com.scaleguard.server.http.h2;

import com.scaleguard.server.http.reverse.InboundMessageHandler;
import com.scaleguard.server.http.reverse.ScaleguardClientAuthHandler;
import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.PassiveHealthManager;
import com.scaleguard.server.http.router.RateLimitManager;
import com.scaleguard.server.http.router.RouteTarget;
import com.scaleguard.server.http.router.TargetSystem;
import com.scaleguard.server.security.waf.WafEngine;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * Proxies one gRPC call (one HTTP/2 stream) to an HTTP/2 backend, frame by frame.
 *
 * <p>Unary, client-streaming, server-streaming and bidirectional calls all work because nothing is aggregated:
 * HEADERS and DATA frames are relayed as they arrive, and the backend's trailers ({@code grpc-status}) are passed
 * back unchanged. Routing, client auth, rate limiting and the WAF (headers and path) run on the request headers
 * before the backend is contacted; refusals are answered as gRPC "trailers-only" responses with the matching status:
 * UNIMPLEMENTED (12) no route, RESOURCE_EXHAUSTED (8) rate limited, PERMISSION_DENIED (7) WAF/auth,
 * UNAVAILABLE (14) backend unreachable.
 *
 * <p>Flow control: while the backend stream isn't writable the client stream stops reading, so HTTP/2 window
 * updates stop and the client is slowed down end to end (and vice versa).
 */
final class GrpcProxyHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcProxyHandler.class);
    private static final InboundMessageHandler INBOUND = new InboundMessageHandler();

    static final int UNIMPLEMENTED = 12, RESOURCE_EXHAUSTED = 8, PERMISSION_DENIED = 7, UNAVAILABLE = 14, INTERNAL = 13;

    private final int port;
    private Channel inbound;
    private Http2StreamChannel outbound;
    private ArrayDeque<Http2StreamFrame> queued = new ArrayDeque<>();
    private boolean started;      // first HEADERS handled
    private boolean refused;      // answered locally; discard the rest of the request
    private boolean responseStarted;
    private boolean responseEnded;
    private HostGroup backendHost;
    private boolean healthRecorded;

    GrpcProxyHandler(int port) {
        this.port = port;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (refused) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (!started) {
            started = true;
            inbound = ctx.channel();
            if (!(msg instanceof Http2HeadersFrame h)) {
                ReferenceCountUtil.release(msg);
                ctx.close();
                return;
            }
            start(ctx, h);
            return;
        }
        if (msg instanceof Http2ResetFrame) {
            ReferenceCountUtil.release(msg);
            closeOutbound();
            return;
        }
        if (msg instanceof Http2DataFrame d) {
            relayUp(new DefaultHttp2DataFrame(d.content(), d.isEndStream(), d.padding()));   // content ownership moves
        } else if (msg instanceof Http2HeadersFrame h) {
            relayUp(new DefaultHttp2HeadersFrame(h.headers(), h.isEndStream()));              // client trailers (rare)
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    private void start(ChannelHandlerContext ctx, Http2HeadersFrame frame) {
        Http2Headers h2 = frame.headers();
        HttpRequest req;
        try {
            req = HttpConversionUtil.toHttpRequest(0, h2, true);
        } catch (Exception e) {
            refuse(ctx, INTERNAL, "malformed request headers");
            return;
        }
        RouteTarget rt = INBOUND.matchTarget(ctx, req, port);
        String peer = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        String clientIp = rt != null && rt.getClientIp() != null ? rt.getClientIp() : peer;

        RateLimitManager.Decision d = RateLimitManager.getInstance().check(rt, clientIp);
        if (!d.allowed()) {
            refuse(ctx, RESOURCE_EXHAUSTED, "rate limit exceeded; retry after " + d.retryAfterSeconds() + "s");
            return;
        }
        if (rt == null || rt.getTargetSystem() == null) {
            refuse(ctx, UNIMPLEMENTED, "no route for " + h2.authority() + h2.path());
            return;
        }
        if (rt.getSourceSystem() != null) {
            FullHttpRequest probe = new DefaultFullHttpRequest(req.protocolVersion(), req.method(), req.uri(),
                    Unpooled.EMPTY_BUFFER, req.headers(), io.netty.handler.codec.http.EmptyHttpHeaders.INSTANCE);
            WafEngine.Verdict v = WafEngine.getInstance().inspect(rt.getSourceSystem().getId(),
                    rt.getSourceSystem().getName() != null ? rt.getSourceSystem().getName() : rt.getSourceSystem().getHost(),
                    clientIp, probe);
            if (v.blocked()) {
                refuse(ctx, PERMISSION_DENIED, "blocked by the web application firewall; incident " + v.incidentId());
                return;
            }
        }
        if (!ScaleguardClientAuthHandler.checkClientAuth(rt)) {
            refuse(ctx, PERMISSION_DENIED, "invalid auth credentials");
            return;
        }

        TargetSystem ts = rt.getTargetSystem();
        HostGroup hg = ts.getHostGroup();
        backendHost = hg;
        rt.setSelectedHost(hg);
        String host = hg != null ? hg.getHost() : ts.getHost();
        String portStr = hg != null ? hg.getPort() : ts.getPort();
        int backendPort;
        try {
            backendPort = Integer.parseInt(portStr);
        } catch (Exception e) {
            refuse(ctx, UNAVAILABLE, "invalid backend port");
            return;
        }
        boolean tls = "https".equalsIgnoreCase(ts.getScheme()) || (hg != null && "https".equalsIgnoreCase(hg.getScheme()));

        // Forwarding headers computed by matchTarget (client IP chain, original host/proto) + target overrides.
        for (String name : new String[]{"X-Forwarded-For", "X-Real-IP", "X-Forwarded-Host", "X-Forwarded-Proto"}) {
            String v = req.headers().get(name);
            if (v != null) h2.set(name.toLowerCase(java.util.Locale.ROOT), v);
        }
        Map<String, String> include = ts.getIncludeHeaders();
        if (include != null) include.forEach((k, v) -> h2.set(k.toLowerCase(java.util.Locale.ROOT), v));
        h2.authority(host + ":" + backendPort);
        rt.setTargetHost(host + ":" + backendPort);

        queued.add(new DefaultHttp2HeadersFrame(h2, frame.isEndStream()));
        inbound.config().setAutoRead(false);    // hold client DATA until the backend stream is open

        Http2ClientPool.getInstance()
                .openStream(inbound.eventLoop(), host, backendPort, tls, new BackendRelay())
                .addListener(f -> {
                    if (f.isSuccess()) {
                        onOutboundOpen((Http2StreamChannel) f.getNow());
                    } else {
                        health(false, "connect failed");
                        LOG.warn("gRPC backend {}:{} unreachable: {}", host, backendPort,
                                f.cause() == null ? "unknown" : f.cause().getMessage());
                        inbound.eventLoop().execute(() -> {
                            drainQueued();
                            refuse(ctx, UNAVAILABLE, "backend unavailable");
                        });
                    }
                });
    }

    private void onOutboundOpen(Http2StreamChannel ch) {
        inbound.eventLoop().execute(() -> {
            if (!inbound.isActive()) {
                drainQueued();
                ch.close();
                return;
            }
            outbound = ch;
            Http2StreamFrame f;
            while ((f = queued.poll()) != null) outbound.write(f);
            outbound.flush();
            queued = null;
            inbound.config().setAutoRead(outbound.isWritable());
        });
    }

    private void relayUp(Http2StreamFrame f) {
        if (outbound == null) {
            if (queued != null) queued.add(f);
            else ReferenceCountUtil.release(f);
            return;
        }
        outbound.writeAndFlush(f);
        if (!outbound.isWritable()) inbound.config().setAutoRead(false);
    }

    /** Trailers-only gRPC response: the call is answered here and never reaches a backend. */
    private void refuse(ChannelHandlerContext ctx, int status, String message) {
        refused = true;
        if (responseStarted) {
            ctx.close();
            return;
        }
        responseStarted = responseEnded = true;
        Http2Headers h = new DefaultHttp2Headers()
                .status("200")
                .set("content-type", "application/grpc")
                .setInt("grpc-status", status)
                .set("grpc-message", grpcMessage(message));
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(h, true));
        ctx.channel().config().setAutoRead(true);   // keep consuming (and discarding) whatever the client still sends
    }

    /** gRPC spec: percent-encode only '%' and bytes outside printable ASCII. */
    static String grpcMessage(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 0x20 && c <= 0x7e && c != '%') sb.append((char) c);
            else sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return sb.toString();
    }

    private void drainQueued() {
        if (queued != null) {
            queued.forEach(ReferenceCountUtil::release);
            queued.clear();
        }
    }

    private void closeOutbound() {
        if (outbound != null) outbound.close();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (outbound != null) outbound.config().setAutoRead(ctx.channel().isWritable());
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        drainQueued();
        closeOutbound();      // client went away: cancel the backend call (RST_STREAM CANCEL)
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.debug("gRPC client stream error: {}", cause.getMessage());
        ctx.close();
    }

    /** Backend side of the call: relays response HEADERS, DATA and trailers to the client stream. */
    private final class BackendRelay extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Http2StreamFrame out;
            if (msg instanceof Http2HeadersFrame h) {
                out = new DefaultHttp2HeadersFrame(h.headers(), h.isEndStream());
                CharSequence st = h.headers().status();
                if (st != null) {
                    int code = parse(st);
                    if (PassiveHealthManager.getInstance().getSettings().failureStatuses().contains(code)) health(false, "HTTP " + code);
                }
                if (h.isEndStream()) {
                    responseEnded = true;
                    CharSequence gs = h.headers().get("grpc-status");
                    if (gs != null && "14".contentEquals(gs)) health(false, "grpc-status 14 UNAVAILABLE");
                    else health(true, null);
                }
            } else if (msg instanceof Http2DataFrame d) {
                out = new DefaultHttp2DataFrame(d.content(), d.isEndStream(), d.padding());
                if (d.isEndStream()) {
                    responseEnded = true;
                    health(true, null);
                }
            } else {
                if (msg instanceof Http2ResetFrame) inbound.close();
                ReferenceCountUtil.release(msg);
                return;
            }
            responseStarted = true;
            inbound.writeAndFlush(out);
            if (!inbound.isWritable()) ctx.channel().config().setAutoRead(false);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            // backend stream drained: let the client send more
            if (inbound != null && !refused) inbound.config().setAutoRead(ctx.channel().isWritable());
            super.channelWritabilityChanged(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // Backend stream closed before its trailers: tell the client (RST or UNAVAILABLE status).
            if (!responseEnded) {
                if (!refused && inbound.isActive()) health(false, "backend reset the stream");
                inbound.eventLoop().execute(() -> {
                    if (responseStarted) inbound.close();
                    else refuseLater(UNAVAILABLE, "backend closed the stream");
                });
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.debug("gRPC backend stream error: {}", cause.getMessage());
            ctx.close();
        }
    }

    /** One passive-health signal per call. */
    private void health(boolean ok, String reason) {
        if (healthRecorded || backendHost == null) return;
        healthRecorded = true;
        if (ok) PassiveHealthManager.getInstance().recordSuccess(backendHost);
        else PassiveHealthManager.getInstance().recordFailure(backendHost, reason);
    }

    private static int parse(CharSequence s) {
        try { return Integer.parseInt(s.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private void refuseLater(int status, String message) {
        ChannelHandlerContext ctx = inbound.pipeline().context(this);
        if (ctx != null) refuse(ctx, status, message);
        else inbound.close();
    }
}
