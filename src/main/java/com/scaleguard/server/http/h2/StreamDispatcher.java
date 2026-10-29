package com.scaleguard.server.http.h2;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.ReferenceCountUtil;

/**
 * First handler on every inbound HTTP/2 stream. Looks at the request headers once and installs the right pipeline:
 * gRPC ({@code content-type: application/grpc*}, except gRPC-Web which is plain HTTP) is relayed frame by frame
 * to an HTTP/2 backend so streaming and trailers survive; everything else goes through the regular HTTP handlers.
 */
final class StreamDispatcher extends ChannelInboundHandlerAdapter {

    private final int port;
    private final boolean tls;

    StreamDispatcher(int port, boolean tls) {
        this.port = port;
        this.tls = tls;
    }

    static boolean isGrpc(CharSequence contentType) {
        if (contentType == null) return false;
        String ct = contentType.toString().toLowerCase(java.util.Locale.ROOT);
        return ct.startsWith("application/grpc") && !ct.startsWith("application/grpc-web");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof Http2HeadersFrame headers)) {
            // A stream must start with HEADERS; anything else is a protocol error from the client.
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }
        if (isGrpc(headers.headers().get("content-type"))) {
            ctx.pipeline().addAfter(ctx.name(), "grpc", new GrpcProxyHandler(port));
        } else {
            ProtocolPipelines.configureHttpStream(ctx.channel(), port, !tls);
        }
        ctx.fireChannelRead(msg);
        ctx.pipeline().remove(this);
    }
}
