package com.scaleguard.server.http.reverse;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLEngine;

public class SSLServerInitializer extends ChannelInitializer<Channel> {

    private int port=80;
    public SSLServerInitializer(int port){
        this.port=port;
    }
  
    @Override  
    protected void initChannel(Channel ch) {
        CorsConfig corsConfig = CorsConfigBuilder.forAnyOrigin().allowNullOrigin().allowCredentials().build();

        ChannelPipeline pipeline = ch.pipeline();
        SSLEngine engine = ScaleGuardSSLContext.get(port).createSSLEngine();
        engine.setUseClientMode(false);
        ch.pipeline().addLast(
                new SslHandler(engine)
        );
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
       // pipeline.addLast(new CorsHandler(corsConfig));

        pipeline.addLast(new ScaleGuardFrontendHandler(port));

    }  
      
}