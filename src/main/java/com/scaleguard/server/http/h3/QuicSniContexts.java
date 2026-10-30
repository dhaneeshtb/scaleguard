package com.scaleguard.server.http.h3;

import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.CertificateStore;
import io.netty.util.Mapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Picks the QUIC TLS context for a connection from its SNI name, using the same certificate store as the TCP
 * listeners — so ACME-issued and uploaded certificates work for HTTP/3 with no extra configuration.
 * One BoringSSL context is built per certificate and reused until the certificate is replaced (renewal).
 */
final class QuicSniContexts implements Mapping<String, QuicSslContext> {

    private static final Logger LOG = LoggerFactory.getLogger(QuicSniContexts.class);
    /** Same fallback as the TCP listeners when the client sends no SNI or an unknown name. */
    static final String DEFAULT_ALIAS = System.getProperty("scaleguard.defaultCertificate", "test1.example.com");

    private final Map<CertificateStore.CertificateInfo, QuicSslContext> cache = new WeakHashMap<>();

    @Override
    public QuicSslContext map(String sni) {
        CertificateStore.CertificateInfo info = lookup(sni);
        if (info == null) info = lookup(DEFAULT_ALIAS);
        if (info == null) info = CertificateStore.any();   // no SNI (e.g. a bare IP): any certificate beats a failed handshake
        if (info == null || info.getKey() == null || info.getKeyCertChain() == null) {
            LOG.debug("HTTP/3: no certificate for '{}'", sni);
            return null;   // handshake fails, the client falls back to HTTP/2 over TCP
        }
        synchronized (cache) {
            QuicSslContext ctx = cache.get(info);
            if (ctx == null) {
                ctx = QuicSslContextBuilder.forServer(info.getKey(), null, info.getKeyCertChain())
                        .applicationProtocols(Http3.supportedApplicationProtocols())
                        .earlyData(false)
                        .build();
                cache.put(info, ctx);
            }
            return ctx;
        }
    }

    private static CertificateStore.CertificateInfo lookup(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return CertificateStore.get(name.toLowerCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }
}
