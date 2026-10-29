package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.h2.AlpnHandler;
import com.scaleguard.server.http.h2.ProtocolPipelines;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/**
 * TLS listener. Certificates are picked per SNI name by ScaleGuardKeyManager. With HTTP/2 enabled (default)
 * ALPN offers "h2" and "http/1.1" and {@link AlpnHandler} installs the negotiated protocol.
 */
public class SSLServerInitializer extends ChannelInitializer<Channel> {

    private final int port;

    public SSLServerInitializer(int port) {
        this.port = port;
    }

    @Override
    protected void initChannel(Channel ch) {
        SSLEngine engine = ScaleGuardSSLContext.get().createSSLEngine();
        engine.setUseClientMode(false);
        boolean h2 = ProtocolPipelines.http2Enabled();
        if (h2) {
            SSLParameters params = engine.getSSLParameters();
            params.setApplicationProtocols(new String[]{"h2", "http/1.1"});
            params.setUseCipherSuitesOrder(true);
            engine.setSSLParameters(params);
        }
        ch.pipeline().addLast("tls", new SslHandler(engine));
        if (h2) {
            ch.pipeline().addLast("alpn", new AlpnHandler(port));
        } else {
            ProtocolPipelines.configureHttp1(ch.pipeline(), port, false);
        }
    }
}
