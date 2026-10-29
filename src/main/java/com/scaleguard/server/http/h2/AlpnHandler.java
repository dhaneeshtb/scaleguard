package com.scaleguard.server.http.h2;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;

/**
 * TLS listener: after the handshake, installs HTTP/2 when the client and ScaleGuard agreed on "h2" via ALPN,
 * otherwise HTTP/1.1. (Netty's ApplicationProtocolNegotiationHandler only reads ALPN from Netty-built SSL engines;
 * ScaleGuard uses a JDK SSLContext with its own SNI key manager, so the result is read from the engine directly.)
 */
public final class AlpnHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(AlpnHandler.class);
    private final int port;
    private ArrayDeque<Object> pending;   // application data that arrives before we switch pipelines

    public AlpnHandler(int port) {
        this.port = port;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().config().setAutoRead(true);
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (pending == null) pending = new ArrayDeque<>(2);
        pending.add(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SslHandshakeCompletionEvent e) {
            if (!e.isSuccess()) {
                LOG.debug("TLS handshake failed from {}: {}", ctx.channel().remoteAddress(), e.cause() == null ? "" : e.cause().getMessage());
                release();
                ctx.close();
                return;
            }
            SslHandler ssl = ctx.pipeline().get(SslHandler.class);
            String proto = ssl == null ? null : ssl.engine().getApplicationProtocol();
            if ("h2".equals(proto)) {
                ProtocolPipelines.configureHttp2(ctx.pipeline(), port, true);
            } else {
                ProtocolPipelines.configureHttp1(ctx.pipeline(), port, false);
            }
            ctx.pipeline().remove(this);
            if (pending != null) {
                Object m;
                while ((m = pending.poll()) != null) ctx.fireChannelRead(m);
                ctx.fireChannelReadComplete();
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        // pending is drained before removal on the happy path; anything left belongs to a closing channel
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        release();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.debug("TLS connection error from {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        release();
        ctx.close();
    }

    private void release() {
        if (pending != null) {
            pending.forEach(ReferenceCountUtil::release);
            pending.clear();
        }
    }
}
