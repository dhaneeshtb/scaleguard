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

    private static String sni(SSLEngine engine) {
        try {
            if (engine.getHandshakeSession() instanceof javax.net.ssl.ExtendedSSLSession s) {
                for (javax.net.ssl.SNIServerName n : s.getRequestedServerNames()) {
                    if (n instanceof javax.net.ssl.SNIHostName h) return h.getAsciiName();
                }
            }
        } catch (Exception ignored) {
            // no SNI
        }
        return null;
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
            // Decide per host name, like nginx's per-server "http2 on|off": ALPN runs after the client's SNI is known,
            // so a route with HTTP/2 turned off gets http/1.1 on its own name while other names on the port keep h2.
            engine.setHandshakeApplicationProtocolSelector((eng, offered) -> {
                String sni = sni(eng);
                boolean h2ok = sni == null || com.scaleguard.server.http.router.RouteTable.getInstance().http2Allowed(sni, port);
                if (h2ok && offered.contains("h2")) return "h2";
                if (offered.contains("http/1.1")) return "http/1.1";
                return "";   // no ALPN: plain HTTP/1.1
            });
        }
        ch.pipeline().addLast("tls", new SslHandler(engine));
        if (h2) {
            ch.pipeline().addLast("alpn", new AlpnHandler(port));
        } else {
            ProtocolPipelines.configureHttp1(ch.pipeline(), port, false);
        }
    }
}
