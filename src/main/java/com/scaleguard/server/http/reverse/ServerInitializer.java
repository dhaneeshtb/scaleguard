package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.h2.CleartextProtocolDetector;
import com.scaleguard.server.http.h2.ProtocolPipelines;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;

/**
 * Plain-text listener. With HTTP/2 enabled (default) the first bytes decide between HTTP/1.1 and h2c
 * (prior knowledge, as used by gRPC); see {@link CleartextProtocolDetector}.
 * Request body cap: -Dscaleguard.maxBodyBytes (default 10 MB).
 */
public class ServerInitializer extends ChannelInitializer<Channel> {
    private final int port;

    public ServerInitializer(int port) {
        this.port = port;
    }

    @Override
    protected void initChannel(Channel ch) {
        if (ProtocolPipelines.http2Enabled()) {
            ch.pipeline().addLast("detect", new CleartextProtocolDetector(port));
        } else {
            ProtocolPipelines.configureHttp1(ch.pipeline(), port, true);
        }
    }
}
