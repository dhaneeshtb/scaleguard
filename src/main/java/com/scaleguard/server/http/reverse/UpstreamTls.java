package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.router.TargetSystem;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * TLS to backends, per target — the nginx {@code proxy_ssl_*} / {@code grpc_ssl_*} model:
 * <ul>
 *   <li>SNI is always sent ({@code proxy_ssl_server_name on}): the backend's host name, or the target's
 *       {@code tlsServerName} ({@code proxy_ssl_name}) when a backend is addressed by IP or serves another name.</li>
 *   <li>Certificate checks are off by default, as in nginx ({@code proxy_ssl_verify off}); with {@code tlsVerify} the
 *       backend must present a certificate trusted by the JVM and valid for that name.</li>
 * </ul>
 */
public final class UpstreamTls {

    private UpstreamTls() {}

    private static final SslContext H1_INSECURE = build(false, false);
    private static final SslContext H1_VERIFY = build(true, false);
    private static final SslContext H2_INSECURE = build(false, true);
    private static final SslContext H2_VERIFY = build(true, true);

    private static SslContext build(boolean verify, boolean h2) {
        try {
            SslContextBuilder b = SslContextBuilder.forClient();
            if (verify) {
                b.endpointIdentificationAlgorithm("HTTPS");
            } else {
                b.trustManager(InsecureTrustManagerFactory.INSTANCE).endpointIdentificationAlgorithm(null);
            }
            if (h2) {
                b.applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2));
            }
            return b.build();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create backend TLS context", e);
        }
    }

    public static boolean verify(TargetSystem t) {
        return t != null && Boolean.TRUE.equals(t.getTlsVerify());
    }

    /** The name sent as SNI and checked against the certificate: tlsServerName, else the backend host. */
    public static String serverName(TargetSystem t, String backendHost) {
        String n = t == null ? null : t.getTlsServerName();
        return n != null && !n.isBlank() ? n.trim() : backendHost;
    }

    /** TLS handler for an HTTP/1.1 backend connection. */
    public static SslHandler http1(TargetSystem t, ByteBufAllocator alloc, String backendHost, int port) {
        return (verify(t) ? H1_VERIFY : H1_INSECURE).newHandler(alloc, serverName(t, backendHost), port);
    }

    /** TLS handler for an HTTP/2 (gRPC) backend connection; offers ALPN h2. */
    public static SslHandler http2(boolean verify, String serverName, ByteBufAllocator alloc, int port) {
        return (verify ? H2_VERIFY : H2_INSECURE).newHandler(alloc, serverName, port);
    }
}
