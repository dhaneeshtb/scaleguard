package com.scaleguard.server.http.reverse;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLEngine;

public class SSLServerInitializer extends ChannelInitializer<Channel> {

    private int port=80;
    public SSLServerInitializer(int port){
        this.port=port;
    }
  
    @Override  
    protected void initChannel(Channel ch) {
//        CorsConfig corsConfig = CorsConfigBuilder.forAnyOrigin().allowNullOrigin().allowCredentials().build();

        // Default 10 MB request body cap — prevents a single large upload from exhausting heap.
        // Tune via -Dscaleguard.maxBodyBytes (e.g. 104857600 for 100 MB).
        int maxBody = Integer.getInteger("scaleguard.maxBodyBytes", 10 * 1024 * 1024);

        ChannelPipeline pipeline = ch.pipeline();
        SSLEngine engine = ScaleGuardSSLContext.get().createSSLEngine();
        engine.setUseClientMode(false);
        ch.pipeline().addLast(
                new SslHandler(engine)
        );
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(maxBody));
       // pipeline.addLast(new CorsHandler(corsConfig));

        pipeline.addLast(new ScaleGuardFrontendHandler(port));

    }  
      
}