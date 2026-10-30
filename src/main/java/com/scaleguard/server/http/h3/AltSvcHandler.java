package com.scaleguard.server.http.h3;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Adds {@code Alt-Svc: h3=":<port>"} to responses on a TLS listener that also serves HTTP/3, which is how browsers
 * discover HTTP/3: the first visit uses HTTP/1.1 or HTTP/2, later requests switch to QUIC. A backend's own Alt-Svc is
 * replaced, since it describes the backend, not this gateway.
 */
@ChannelHandler.Sharable
public final class AltSvcHandler extends ChannelOutboundHandlerAdapter {

    private final String value;

    public AltSvcHandler(int port) {
        this.value = "h3=\":" + port + "\"; ma=" + Http3Listener.ALT_SVC_MAX_AGE;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse r) r.headers().set("alt-svc", value);
        ctx.write(msg, promise);
    }
}
