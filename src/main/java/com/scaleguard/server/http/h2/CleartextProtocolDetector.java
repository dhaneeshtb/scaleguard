package com.scaleguard.server.http.h2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http2.Http2CodecUtil;

import java.util.List;

/**
 * Plain-text listener: HTTP/2 with prior knowledge (h2c — what gRPC clients use without TLS) starts with the
 * connection preface {@code PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n}; anything else is HTTP/1.x. Reads just enough bytes
 * to decide, installs the matching pipeline and removes itself, replaying the bytes it saw.
 */
public final class CleartextProtocolDetector extends ByteToMessageDecoder {

    private static final ByteBuf PREFACE = Http2CodecUtil.connectionPrefaceBuf();
    private final int port;

    public CleartextProtocolDetector(int port) {
        this.port = port;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().config().setAutoRead(true);   // listeners set AUTO_READ=false on child channels
        super.channelActive(ctx);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int n = Math.min(in.readableBytes(), PREFACE.readableBytes());
        boolean prefix = ByteBufUtil.equals(in, in.readerIndex(), PREFACE, PREFACE.readerIndex(), n);
        if (prefix && n < PREFACE.readableBytes()) return;          // could still be HTTP/2; wait for more
        if (prefix) {
            ProtocolPipelines.configureHttp2(ctx.pipeline(), port, false);
        } else {
            ProtocolPipelines.configureHttp1(ctx.pipeline(), port, true);
        }
        ctx.pipeline().remove(this);                                 // ByteToMessageDecoder forwards buffered bytes
    }
}
