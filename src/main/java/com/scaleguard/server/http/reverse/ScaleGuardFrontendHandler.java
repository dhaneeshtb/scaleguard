/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.scaleguard.server.http.reverse;

import com.scaleguard.server.analytics.AccessLog;
import com.scaleguard.server.analytics.AccessLogStore;
import com.scaleguard.server.analytics.AccessRecord;
import com.scaleguard.server.analytics.TraceContext;
import com.scaleguard.server.http.router.AccessLists;
import com.scaleguard.server.http.router.ClientIpResolver;
import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.PassiveHealthManager;
import com.scaleguard.server.http.router.RateLimitManager;
import com.scaleguard.server.http.router.RouteTarget;
import com.scaleguard.server.security.waf.WafEngine;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;


public class ScaleGuardFrontendHandler extends ChannelInboundHandlerAdapter {

  private static final Logger logger
          = LoggerFactory.getLogger(ScaleGuardFrontendHandler.class);

  private static final RateLimitManager rateLimitManager = RateLimitManager.getInstance();

  private Channel outboundChannel;

  private RouteTarget targetSystem;

  /** Access-log record of the request being handled (set per request in channelRead). */
  private AccessRecord current;

  private static final AccessLogStore ACCESS_LOG = AccessLogStore.getInstance();

  /**
   * Starts an access-log record for a routed request and propagates a W3C traceparent upstream.
   * Admin/internal requests (no route) aren't logged.
   */
  private static AccessRecord startRecord(ChannelHandlerContext ctx, Object msg, RouteTarget ts, String clientIp) {
    if (ts == null || !(msg instanceof HttpRequest req) || !ACCESS_LOG.isRecording()) return null;
    io.netty.handler.codec.http.HttpHeaders h = req.headers();
    AccessRecord r = new AccessRecord();
    r.traceId = TraceContext.traceId(h.get("traceparent"));
    if (TraceContext.HEADERS) h.set("traceparent", TraceContext.traceparent(r.traceId));
    r.clientIp = clientIp;
    r.method = req.method().name();
    r.host = h.get(io.netty.handler.codec.http.HttpHeaderNames.HOST);
    r.setUri(req.uri());
    r.protocol = ctx.channel() instanceof io.netty.handler.codec.http2.Http2StreamChannel ? "HTTP/2"
            : com.scaleguard.server.http.h3.Http3Listener.isHttp3(ctx.channel()) ? "HTTP/3" : req.protocolVersion().text();
    r.tls = com.scaleguard.server.http.h2.ProtocolPipelines.isTls(ctx.channel());
    r.userAgent = h.get(io.netty.handler.codec.http.HttpHeaderNames.USER_AGENT);
    r.referer = h.get(io.netty.handler.codec.http.HttpHeaderNames.REFERER);
    if (msg instanceof FullHttpRequest full) r.bytesIn = full.content().readableBytes();
    if (ts.getSourceSystem() != null) {
      r.routeId = ts.getSourceSystem().getId();
      r.routeName = ts.getSourceSystem().getName() != null ? ts.getSourceSystem().getName() : ts.getSourceSystem().getHost();
    }
    return r;
  }

  /** Adds X-Trace-Id to a locally generated error response so it can be found in the access log. */
  private static java.util.Map<String, String> withTrace(AccessRecord rec, java.util.Map<String, String> headers) {
    if (rec == null || rec.traceId == null || !TraceContext.HEADERS) return headers;
    java.util.Map<String, String> m = new java.util.HashMap<>(headers);
    m.put("X-Trace-Id", rec.traceId);
    return m;
  }

  private int port = 80;

  private static final InboundMessageHandler inboundHandler = new InboundMessageHandler();

  public ScaleGuardFrontendHandler() {
  }

  public ScaleGuardFrontendHandler(int port) {
    this.port = port;
  }

  /**
   * Closes the specified channel after all queued write requests are flushed.
   */
  static void closeOnFlush(Channel ch) {
    if (ch != null && ch.isActive()) {
      if (ch.parent() != null && !(ch instanceof io.netty.channel.socket.SocketChannel)) {
        // HTTP/2 / HTTP/3 stream: its codec only accepts HTTP objects, so flush what's queued and close the stream
        ch.flush();
        ch.close();
        return;
      }
      ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    ctx.channel().config().setAutoRead(true);
  }

  private String portText;

  private String portText() {
    String p = portText;
    return p != null ? p : (portText = String.valueOf(port));
  }

  /** The connection's peer address never changes, so it is turned into a string once per connection, not per request. */
  private String cachedPeerIp;

  private String peerIp(ChannelHandlerContext ctx) {
    String p = cachedPeerIp;
    if (p == null) {
      InetSocketAddress peer = com.scaleguard.server.http.h3.Http3Listener.peer(ctx.channel());
      p = cachedPeerIp = peer != null ? peer.getAddress().getHostAddress() : "unknown";
    }
    return p;
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, Object msg) {
    String inAddress = "unknown";
    boolean forwarded = false;
    try {
      String peerIp = peerIp(ctx);
      // Real client IP: X-Forwarded-For is only honoured when the peer is a trusted proxy.
      String xff = msg instanceof HttpRequest ? ((HttpRequest) msg).headers().get(InboundMessageHandler.X_FORWARDED_FOR) : null;
      inAddress = ClientIpResolver.resolve(peerIp, xff);

      RouteTarget ts = inboundHandler.matchTarget(ctx, msg, portText(), peerIp, xff, inAddress);
      // Access log / analytics record for routed traffic (null when analytics is off or it's an admin request)
      AccessRecord rec = startRecord(ctx, msg, ts, inAddress);
      current = rec;

      // Per-route protocols (nginx "http2 off" / "http3 off" on a server): a request that arrived over a protocol
      // its route turned off gets 421 Misdirected Request, which tells the client to retry on a new connection —
      // where ALPN / Alt-Svc no longer offer that protocol for this host.
      if (ts != null && ts.getSourceSystem() != null) {
        boolean h2 = ctx.channel() instanceof io.netty.handler.codec.http2.Http2StreamChannel;
        boolean h3 = com.scaleguard.server.http.h3.Http3Listener.isHttp3(ctx.channel());
        if ((h2 && !ts.getSourceSystem().http2Enabled()) || (h3 && !ts.getSourceSystem().http3Enabled())) {
          if (rec != null) rec.finish(421, AccessRecord.MISDIRECTED);
          DefaultResponseHandler.getInstance().handleFailure(ctx,
                  (h3 ? "HTTP/3" : "HTTP/2") + " is turned off for this route; retry over " + (h3 ? "HTTP/2 or HTTP/1.1" : "HTTP/1.1"),
                  HttpResponseStatus.MISDIRECTED_REQUEST);
          return;
        }
      }

      // IP access lists: the gateway blocklist (Rate limiting page), then the route's own allow/deny. Routed traffic only —
      // the admin API isn't subject to them, so an operator can't lock themselves out.
      if (ts != null && (AccessLists.get().isBlocked(peerIp, inAddress)
              || (ts.getSourceSystem() != null && ts.getSourceSystem().refusesClient(peerIp, inAddress)))) {
        if (rec != null) rec.finish(403, AccessRecord.IP_BLOCKED);
        DefaultResponseHandler.getInstance().handleFailure(ctx, "Access denied", HttpResponseStatus.FORBIDDEN, withTrace(rec, java.util.Map.of()));
        return;
      }

      RateLimitManager.Decision decision = rateLimitManager.check(ts, inAddress);
      if (!decision.allowed()) {
        // Logged (throttled) inside RateLimitManager — no per-request log line here.
        if (rec != null) rec.finish(429, AccessRecord.RATE_LIMITED);
        DefaultResponseHandler.getInstance().handleFailure(ctx, "rate limit exceeded", HttpResponseStatus.TOO_MANY_REQUESTS,
                withTrace(rec, java.util.Map.of("Retry-After", String.valueOf(decision.retryAfterSeconds()))));
        return;
      }

      // Web application firewall (per-route policy; a no-op when the route has it switched off)
      if (ts != null && msg instanceof FullHttpRequest && ts.getSourceSystem() != null) {
        WafEngine.Verdict verdict = WafEngine.getInstance().inspect(ts.getSourceSystem().getId(),
                ts.getSourceSystem().getName() != null ? ts.getSourceSystem().getName() : ts.getSourceSystem().getHost(),
                inAddress, (FullHttpRequest) msg);
        if (rec != null && !verdict.matches().isEmpty()) rec.wafRule = verdict.matches().get(0).ruleId();
        if (verdict.blocked()) {
          if (rec != null) rec.finish(403, AccessRecord.WAF_BLOCKED);
          DefaultResponseHandler.getInstance().handleFailure(ctx,
                  "Request blocked by the web application firewall. Incident " + verdict.incidentId(),
                  HttpResponseStatus.FORBIDDEN, withTrace(rec, java.util.Map.of("X-ScaleGuard-Incident", verdict.incidentId())));
          return;
        }
      }

      if (ts == null) {
        // No matching route — serve internal response (admin UI, health, ACME, etc.)
        DefaultResponseHandler.getInstance().handle(ctx, null, msg);
      } else {
        com.scaleguard.server.security.oauth.OAuthGuard.Result auth = ScaleguardClientAuthHandler.check(ts);
        if (!auth.ok()) {
          if (rec != null) rec.finish(auth.httpStatus(), AccessRecord.AUTH_DENIED);
          DefaultResponseHandler.getInstance().handleResponse(ctx,
                  new RequestRoutingResponse(auth.httpStatus(), auth.body())
                          .header("WWW-Authenticate", auth.challenge()).header("Cache-Control", "no-store"));
          return;
        }

        String scheme = ts.getTargetSystem().getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
          if (ts.getTargetSystem().isEnableCache()) {
            final boolean[] fwd = {false};
            inboundHandler.handle(ctx, msg, ts, key -> {
              fwd[0] = true;
              proeedToTarget(ts, ctx, msg, key == null ? null : key.getKey());
            });
            forwarded = fwd[0];
          } else if (ts.getSourceSystem().isAsync()) {
            inboundHandler.handleAsync(ctx, msg, ts, null);
          } else {
            forwarded = true;
            proeedToTarget(ts, ctx, msg, null);
          }
        } else if ("tcp".equalsIgnoreCase(scheme)) {
          forwarded = true;
          proeedToTarget(ts, ctx, msg, null);
        } else {
          inboundHandler.handleSubSystem(ctx, msg, ts, null);
        }
      }
    } catch (Exception e) {
      logger.error("Unexpected error handling inbound request from {}: {}", inAddress, e.getMessage(), e);
      // Best-effort: send 500 to the client so the connection is cleanly terminated
      try {
        DefaultResponseHandler.getInstance().handleFailure(ctx, "internal server error", HttpResponseStatus.INTERNAL_SERVER_ERROR);
      } catch (Exception suppressed) {
        logger.debug("Could not send error response to {}: {}", inAddress, suppressed.getMessage());
        closeOnFlush(ctx.channel());
      }
    } finally {
      // Release the request ByteBuf for non-proxy paths (internal responses).
      // Proxy paths forward msg to the outbound channel which takes ownership.
      if (!forwarded && msg instanceof io.netty.util.ReferenceCounted) {
        io.netty.util.ReferenceCountUtil.release(msg);
      }
    }
  }

  private boolean isSameSourceAndTarget(RouteTarget ts) {
    // Guard: targetSystem may be null if this handler handled an internal-only request before
    if (targetSystem == null) return false;
    return ts.getTargetSystem().getId().equalsIgnoreCase(targetSystem.getTargetSystem().getId())
            && ts.getSourceSystem().getId().equalsIgnoreCase(targetSystem.getSourceSystem().getId());
  }

  private void proeedToTarget(RouteTarget ts, final ChannelHandlerContext ctx, Object msg, String messageKey) {
    if (!(msg instanceof FullHttpRequest)) {
      logger.warn("Unexpected message type {} from {}, skipping proxy", msg.getClass().getSimpleName(),
              ctx.channel().remoteAddress());
      return;
    }
    boolean isWebsocket = ScaleguardWebsocketHandler.isWebSocketRequest((FullHttpRequest) msg);
    if (isWebsocket) {
      new ScaleguardWebsocketHandler().handleWebSocketProxy(ts, this, ctx, (FullHttpRequest) msg);
    } else if (outboundChannel == null || !outboundChannel.isActive() || !isSameSourceAndTarget(ts)
            || (targetSystem.getSelectedHost() != null && !targetSystem.getSelectedHost().isReachable())) {
      // (a kept-alive connection to a host that has since failed its health checks is not reused)
      handleNewOutboundChannel(ts, ctx, msg, messageKey);
    } else {
      targetSystem.setStartTime(ts.getStartTime());   // the request's own start (one clock read per request)
      handleExistingOutboundChannel(ctx, msg, ts);
    }
  }

  private void handleNewOutboundChannel(RouteTarget ts, final ChannelHandlerContext ctx, Object msg, String messageKey) {
    handleNewOutboundChannel(ts, ctx, msg, messageKey, null, current);
  }

  /** Connect retries: nothing has been sent yet, so a failed connect is retried once on a different backend. */

  private void handleNewOutboundChannel(RouteTarget ts, final ChannelHandlerContext ctx, Object msg, String messageKey,
                                        java.util.Set<HostGroup> failed, AccessRecord rec) {
    final Channel inboundChannel = ctx.channel();
    Bootstrap b = new Bootstrap();
    b.group(inboundChannel.eventLoop())
        .channel(com.scaleguard.server.http.utils.NettyTransport.socketChannel())   // HTTP/2 stream channels aren't socket types
        .handler(new SecureProxyInitializer(ts, inboundChannel,
                "https".equalsIgnoreCase(ts.getTargetSystem().getScheme()), messageKey))
        .option(ChannelOption.AUTO_READ, false)
        .option(ChannelOption.ALLOCATOR, AppServer.ALLOCATOR)
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ts.getTargetSystem().connectTimeoutMillis());   // proxy_connect_timeout

    HostGroup hg = ts.getTargetSystem().getHostGroup();
    if (failed != null) {
      // retry: prefer a host we haven't just failed to reach
      for (int i = 0; i < 4 && hg != null && failed.contains(hg); i++) hg = ts.getTargetSystem().getHostGroup();
      if (hg == null || failed.contains(hg)) {   // no other backend to try
        if (rec != null) rec.finish(502, AccessRecord.NO_BACKEND);
        handleConnectionError(ts, ctx);
        io.netty.util.ReferenceCountUtil.release(msg);
        inboundChannel.close();
        return;
      }
    }
    ts.setSelectedHost(hg);
    final HostGroup chosen = hg;
    ChannelFuture f;
    if (hg != null) {
      ts.setTargetHost(hg.getHost() + ":" + hg.getPort());
      // Use thread-safe overload — passes targetHost as a parameter instead of mutating
      // the shared inboundHandler.host field, which would race across Netty I/O threads.
      inboundHandler.reset(msg, hg.getHost(), ts.getTargetSystem().getIncludeHeaders());
      logger.debug("Connecting to {}:{}", hg.getHost(), hg.getPort());
      f = b.connect(hg.getHost(), Integer.parseInt(hg.getPort()));
    } else if (ts.getTargetSystem().getHost() == null || ts.getTargetSystem().getPort() == null) {
      // Every backend in the group is down (BestHostSelector logs that, throttled) and the target has no
      // fallback address of its own: a clean 503 — not a NumberFormatException with a stack trace per request.
      if (rec != null) rec.finish(503, AccessRecord.NO_BACKEND);
      DefaultResponseHandler.getInstance().handleFailure(ctx,
              "No backend available for " + ts.getTargetSystem().getGroupId() + " (all down, ejected or at their connection limit)", HttpResponseStatus.SERVICE_UNAVAILABLE);
      io.netty.util.ReferenceCountUtil.release(msg);
      return;
    } else {
      ts.setTargetHost(ts.getTargetSystem().getHost() + ":" + ts.getTargetSystem().getPort());
      // Thread-safe: pass host as parameter instead of writing shared inboundHandler.host field
      inboundHandler.reset(msg, ts.getTargetSystem().getHost(), ts.getTargetSystem().getIncludeHeaders());
      try {
        f = b.connect(ts.getTargetSystem().getHost(), Integer.parseInt(ts.getTargetSystem().getPort()));
      } catch (Exception e) {
        // Port parse failure or bootstrap misconfiguration — reject immediately
        logger.error("Invalid backend configuration for [{}]: {}", ts.getTargetSystem().getGroupId(), e.getMessage(), e);
        if (rec != null) rec.finish(502, AccessRecord.NO_BACKEND);
        handleConnectionError(ts, ctx);
        return;
      }
    }

    outboundChannel = f.channel();
    targetSystem = ts;
    if (rec != null) {
      rec.backend = ts.getTargetHost();
      AccessLog.expectResponse(outboundChannel, rec);   // the next response on this connection completes it
    }

    f.addListener((ChannelFutureListener) future -> {
      if (future.isSuccess()) {
        if (chosen != null) {
          // open backend connections per host — what LEAST_CONNECTIONS balances on
          chosen.incrementConnections();
          future.channel().closeFuture().addListener(c -> chosen.decrementConnections());
        }
        inboundChannel.config().setAutoRead(true);
        outboundChannel.writeAndFlush(msg);
      } else {
        // Use future.cause() — NOT future.get().
        // Calling get() inside operationComplete() re-throws as ExecutionException,
        // which Netty's DefaultPromise catches and logs as a noisy WARN per connection.
        // future.cause() returns the failure directly without throwing.
        Throwable cause = future.cause();
        PassiveHealthManager.getInstance().recordFailure(chosen, "connect failed: " + (cause != null ? cause.getMessage() : "unknown"));
        logger.debug("Failed to connect to backend {} [{}]: {}", ts.getTargetHost(),
                ts.getTargetSystem().getGroupId(), cause != null ? cause.getMessage() : "unknown error");
        java.util.Set<HostGroup> tried = failed == null ? new java.util.HashSet<>() : failed;
        if (chosen != null) tried.add(chosen);
        AccessLog.withdraw(future.channel(), rec);
        if (chosen != null && tried.size() <= ts.getTargetSystem().connectRetries() && inboundChannel.isActive()) {
          handleNewOutboundChannel(ts, ctx, msg, messageKey, tried, rec);
          return;
        }
        if (rec != null) rec.finish(502, AccessRecord.NO_BACKEND);
        handleConnectionError(ts, ctx);
        io.netty.util.ReferenceCountUtil.release(msg);
        inboundChannel.close();
      }
    });
  }

  private void handleConnectionError(RouteTarget ts, final ChannelHandlerContext ctx) {
    String target;
    HostGroup hg = ts.getSelectedHost();   // the host that actually failed (selecting again could name another)
    if (hg != null) {
      target = hg.getHost() + ":" + hg.getPort();
    } else {
      target = ts.getTargetSystem().getGroupId();
    }
    DefaultResponseHandler.getInstance().handleFailure(ctx,
            "Service unavailable — could not connect to backend: " + target,
            HttpResponseStatus.BAD_GATEWAY);
  }

  public void handleExistingOutboundChannel(final ChannelHandlerContext ctx, Object msg, RouteTarget ts) {
    // Same backend as the kept-alive connection (selecting again could pick a different host than we're connected to)
    HostGroup hg = targetSystem.getSelectedHost() != null ? targetSystem.getSelectedHost() : ts.getTargetSystem().getHostGroup();
    ts.setSelectedHost(hg);
    String targetHost = hg != null ? hg.getHost() : ts.getTargetSystem().getHost();
    inboundHandler.reset(msg, targetHost, ts.getTargetSystem().getIncludeHeaders());
    if (current != null) {
      current.backend = hg != null ? hg.getHost() + ":" + hg.getPort() : targetHost;
      AccessLog.expectResponse(outboundChannel, current);
    }
    // The client connection reads on its own (auto-read), so nothing has to happen after a successful write; a failed
    // write closes the backend connection, whose handler then answers the client. One shared listener, no allocation.
    outboundChannel.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    closeOnFlush(outboundChannel);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    // Connection-reset and peer-closed events are normal; log at DEBUG to avoid noise
    if (cause instanceof java.io.IOException) {
      logger.debug("Channel I/O exception (client likely disconnected): {}", cause.getMessage());
    } else {
      logger.error("Unexpected channel exception, closing connection: {}", cause.getMessage(), cause);
    }
    closeOnFlush(ctx.channel());
  }
}
