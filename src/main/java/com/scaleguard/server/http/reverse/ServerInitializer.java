package com.scaleguard.server.http.reverse;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;

public class ServerInitializer extends ChannelInitializer<Channel> {
    private int port=80;
    public ServerInitializer(int port){
        this.port=port;
    }
    @Override  
    protected void initChannel(Channel ch) {
        // Default 10 MB request body cap — prevents a single large upload from exhausting heap.
        // Tune via -Dscaleguard.maxBodyBytes (e.g. 104857600 for 100 MB).
        int maxBody = Integer.getInteger("scaleguard.maxBodyBytes", 10 * 1024 * 1024);
        CorsConfig corsConfig = CorsConfigBuilder.forAnyOrigin()
                .allowNullOrigin()
                .allowCredentials()
                .allowedRequestHeaders("*")
                .allowedRequestMethods(HttpMethod.DELETE,HttpMethod.GET,HttpMethod.PATCH,HttpMethod.POST,HttpMethod.PUT,HttpMethod.OPTIONS)
                .build();
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(maxBody));
        pipeline.addLast(new CorsHandler(corsConfig));
        pipeline.addLast(new ScaleGuardFrontendHandler(port));

    }  
      
}