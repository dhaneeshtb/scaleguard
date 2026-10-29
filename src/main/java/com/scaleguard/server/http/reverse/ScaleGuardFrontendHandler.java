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

import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.RateLimitManager;
import com.scaleguard.server.http.router.RouteTarget;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;


public class ScaleGuardFrontendHandler extends ChannelInboundHandlerAdapter {

  private static final Logger logger
          = LoggerFactory.getLogger(ScaleGuardFrontendHandler.class);

  private static final RateLimitManager rateLimitManager = new RateLimitManager();

  private Channel outboundChannel;

  private RouteTarget targetSystem;

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
      ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    ctx.channel().config().setAutoRead(true);
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, Object msg) {
    String inAddress = "unknown";
    boolean forwarded = false;
    try {
      inAddress = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();

      RouteTarget ts = inboundHandler.matchTarget(ctx, msg, port);

      // rateLimitManager.checkRate handles ts=null gracefully; log & drop if limited
      if (!rateLimitManager.checkRate(ts, inAddress)) {
        logger.info("Request from {} discarded — rate limit exceeded", inAddress);
        DefaultResponseHandler.getInstance().handleFailure(ctx, "rate limit exceeded", HttpResponseStatus.TOO_MANY_REQUESTS);
        return;
      }

      if (ts == null) {
        // No matching route — serve internal response (admin UI, health, ACME, etc.)
        DefaultResponseHandler.getInstance().handle(ctx, null, msg);
      } else {
        if (!ScaleguardClientAuthHandler.checkClientAuth(ts)) {
          DefaultResponseHandler.getInstance().handleFailure(ctx, "invalid auth credentials", HttpResponseStatus.FORBIDDEN);
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
    } else if (outboundChannel == null || !outboundChannel.isActive() || !isSameSourceAndTarget(ts)) {
      handleNewOutboundChannel(ts, ctx, msg, messageKey);
    } else {
      targetSystem.setStartTime(System.currentTimeMillis());
      handleExistingOutboundChannel(ctx, msg, ts);
    }
  }

  private void handleNewOutboundChannel(RouteTarget ts, final ChannelHandlerContext ctx, Object msg, String messageKey) {
    final Channel inboundChannel = ctx.channel();
    Bootstrap b = new Bootstrap();
    b.group(inboundChannel.eventLoop())
        .channel(ctx.channel().getClass())
        .handler(new SecureProxyInitializer(ts, inboundChannel,
                "https".equalsIgnoreCase(ts.getTargetSystem().getScheme()), messageKey))
        .option(ChannelOption.AUTO_READ, false);

    HostGroup hg = ts.getTargetSystem().getHostGroup();
    ChannelFuture f;
    if (hg != null) {
      ts.setTargetHost(hg.getHost() + ":" + hg.getPort());
      // Use thread-safe overload — passes targetHost as a parameter instead of mutating
      // the shared inboundHandler.host field, which would race across Netty I/O threads.
      inboundHandler.reset(msg, hg.getHost(), ts.getTargetSystem().getIncludeHeaders());
      logger.debug("Connecting to {}:{}", hg.getHost(), hg.getPort());
      f = b.connect(hg.getHost(), Integer.parseInt(hg.getPort()));
    } else {
      ts.setTargetHost(ts.getTargetSystem().getHost() + ":" + ts.getTargetSystem().getPort());
      // Thread-safe: pass host as parameter instead of writing shared inboundHandler.host field
      inboundHandler.reset(msg, ts.getTargetSystem().getHost(), ts.getTargetSystem().getIncludeHeaders());
      try {
        f = b.connect(ts.getTargetSystem().getHost(), Integer.parseInt(ts.getTargetSystem().getPort()));
      } catch (Exception e) {
        // Port parse failure or bootstrap misconfiguration — reject immediately
        logger.error("Invalid backend configuration for [{}]: {}", ts.getTargetSystem().getGroupId(), e.getMessage(), e);
        handleConnectionError(ts, ctx);
        return;
      }
    }

    outboundChannel = f.channel();
    targetSystem = ts;

    f.addListener((ChannelFutureListener) future -> {
      if (future.isSuccess()) {
        inboundChannel.config().setAutoRead(true);
        outboundChannel.writeAndFlush(msg);
      } else {
        // Use future.cause() — NOT future.get().
        // Calling get() inside operationComplete() re-throws as ExecutionException,
        // which Netty's DefaultPromise catches and logs as a noisy WARN per connection.
        // future.cause() returns the failure directly without throwing.
        Throwable cause = future.cause();
        logger.warn("Failed to connect to backend [{}]: {}",
                ts.getTargetSystem().getGroupId(),
                cause != null ? cause.getMessage() : "unknown error");
        handleConnectionError(ts, ctx);
        inboundChannel.close();
      }
    });
  }

  private void handleConnectionError(RouteTarget ts, final ChannelHandlerContext ctx) {
    String target;
    HostGroup hg = ts.getTargetSystem().getHostGroup();
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
    // Thread-safe: pass host as parameter instead of setting the shared inboundHandler.host field
    HostGroup hg = ts.getTargetSystem().getHostGroup();
    String targetHost = hg != null ? hg.getHost() : ts.getTargetSystem().getHost();
    inboundHandler.reset(msg, targetHost, ts.getTargetSystem().getIncludeHeaders());
    outboundChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
      if (future.isSuccess()) {
        ctx.channel().read();
      } else {
        logger.warn("Failed to write to existing outbound channel for [{}]: {}",
                ts.getTargetSystem().getGroupId(),
                future.cause() != null ? future.cause().getMessage() : "unknown");
        future.channel().close();
      }
    });
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
