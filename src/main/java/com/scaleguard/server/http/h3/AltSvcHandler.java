package com.scaleguard.server.http.h3;

import com.scaleguard.server.http.router.RouteTable;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Adds {@code Alt-Svc: h3=":<port>"; ma=86400} to responses on a TLS listener that also serves HTTP/3, which is how
 * browsers discover HTTP/3 (nginx: {@code add_header Alt-Svc 'h3=":443"; ma=86400'}): the first visit uses HTTP/1.1 or
 * HTTP/2, later requests switch to QUIC. Only for hosts whose route has HTTP/3 on. A backend's own Alt-Svc is
 * replaced, since it describes the backend, not this gateway.
 */
public final class AltSvcHandler extends ChannelDuplexHandler {

    private final int port;
    private final String value;
    private boolean advertise = true;   // decided per request from its Host

    public AltSvcHandler(int port) {
        this.port = port;
        this.value = "h3=\":" + port + "\"; ma=" + Http3Listener.ALT_SVC_MAX_AGE;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest req) {
            String host = req.headers().get(HttpHeaderNames.HOST);
            if (host != null) {
                int colon = host.lastIndexOf(':');
                if (colon > 0 && host.indexOf(']') < colon) host = host.substring(0, colon);
                if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            }
            advertise = host == null || RouteTable.getInstance().http3Allowed(host, port);
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse r) {
            if (advertise) r.headers().set("alt-svc", value);
            else r.headers().remove("alt-svc");
        }
        ctx.write(msg, promise);
    }
}
