package com.scaleguard.server.http.reverse;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.UnpooledUnsafeDirectByteBuf;
import io.netty.channel.*;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class TCPProxyBackendHandler extends ChannelInboundHandlerAdapter {


    private final Channel inboundChannel;

    public TCPProxyBackendHandler(Channel inboundChannel) {
        this.inboundChannel = inboundChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (!inboundChannel.isActive()) {
            TCPChannelFrontendHandler.closeOnFlush(ctx.channel());
        } else {
            ctx.read();
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) {


        inboundChannel.writeAndFlush(msg).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                    ctx.channel().read();
                } else {
                    future.channel().close();
                }
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        TCPChannelFrontendHandler.closeOnFlush(inboundChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        TCPChannelFrontendHandler.closeOnFlush(ctx.channel());
    }
}
