package com.scaleguard.server.http.reverse;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;


public class TCPServerInitializer extends ChannelInitializer<Channel> {
    private int port=80;
    private String sourceId=null;

    public TCPServerInitializer(String sourceId,int port){

        this.port=port;
        this.sourceId=sourceId;
    }
    @Override  
    protected void initChannel(Channel ch) {



                ch.pipeline().addLast(
                        new LoggingHandler(LogLevel.INFO),
                        new TCPChannelFrontendHandler(sourceId, port));
    }  
      
}