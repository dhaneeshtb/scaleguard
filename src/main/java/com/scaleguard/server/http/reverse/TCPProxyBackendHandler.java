package com.scaleguard.server.http.reverse;


import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TCPProxyBackendHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(TCPProxyBackendHandler.class);


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
        logger.error("Channel exception caught", cause);
        TCPChannelFrontendHandler.closeOnFlush(ctx.channel());
    }
}
