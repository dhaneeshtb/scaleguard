package com.scaleguard.server.http.h2;

import com.scaleguard.server.http.reverse.ScaleGuardFrontendHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;

/**
 * Builds the request-processing pipeline for each protocol.
 *
 * <p>HTTP/1.1: codec → aggregator → CORS → {@link ScaleGuardFrontendHandler} (unchanged behaviour).
 * <br>HTTP/2: frame codec → multiplexer; every stream gets its own child channel whose first handler,
 * {@link StreamDispatcher}, sends gRPC streams to {@link GrpcProxyHandler} (frame-by-frame, end-to-end HTTP/2)
 * and everything else through the same aggregator + frontend handler as HTTP/1.1 — so routing, rate limits,
 * the WAF, caching and the admin API behave identically on both protocols.
 */
public final class ProtocolPipelines {

    private ProtocolPipelines() {}

    public static int maxBodyBytes() {
        return Integer.getInteger("scaleguard.maxBodyBytes", 10 * 1024 * 1024);
    }

    /** HTTP/2 on by default; disable with -Dscaleguard.http2=false. */
    public static boolean http2Enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("scaleguard.http2", "true"));
    }

    static CorsConfig corsConfig() {
        return CorsConfigBuilder.forAnyOrigin()
                .allowNullOrigin()
                .allowCredentials()
                .allowedRequestHeaders("*")
                .allowedRequestMethods(HttpMethod.DELETE, HttpMethod.GET, HttpMethod.PATCH, HttpMethod.POST, HttpMethod.PUT, HttpMethod.OPTIONS)
                .build();
    }

    /** HTTP/1.1 handlers, appended to the pipeline. {@code cors} matches the previous per-listener behaviour. */
    public static void configureHttp1(ChannelPipeline p, int port, boolean cors) {
        p.addLast("http1-codec", new HttpServerCodec());
        if (p.get(io.netty.handler.ssl.SslHandler.class) != null && com.scaleguard.server.http.h3.Http3Listener.listening(port)) {
            p.addLast("alt-svc", new com.scaleguard.server.http.h3.AltSvcHandler(port));   // TLS: advertise HTTP/3
        }
        p.addLast("http1-aggregator", new HttpObjectAggregator(maxBodyBytes()));
        if (cors) p.addLast("cors", new CorsHandler(corsConfig()));
        p.addLast("frontend", new ScaleGuardFrontendHandler(port));
    }

    /** HTTP/2 connection handlers, appended to the pipeline. */
    public static void configureHttp2(ChannelPipeline p, int port, boolean tls) {
        Http2Settings settings = Http2Settings.defaultSettings()
                .maxConcurrentStreams(Integer.getInteger("scaleguard.http2.maxConcurrentStreams", 250))
                .initialWindowSize(1 << 20)          // 1 MiB per stream: fewer WINDOW_UPDATE round trips for uploads
                .maxHeaderListSize(64 * 1024L);
        p.addLast("h2-codec", Http2FrameCodecBuilder.forServer()
                .initialSettings(settings)
                .validateHeaders(true)
                .build());                           // Netty enforces RST-flood (CVE-2023-44487) limits by default
        p.addLast("h2-multiplex", new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
            @Override
            protected void initChannel(Http2StreamChannel ch) {
                ch.pipeline().addLast("dispatch", new StreamDispatcher(port, tls));
            }
        }));
    }

    /** Pipeline for one non-gRPC HTTP/2 stream: frames ⇄ HTTP objects, then the normal HTTP handlers. */
    static void configureHttpStream(Channel stream, int port, boolean cors) {
        ChannelPipeline p = stream.pipeline();
        p.addLast("h2-to-http", new io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec(true));
    if (!cors && com.scaleguard.server.http.h3.Http3Listener.listening(port)) {
        p.addLast("alt-svc", new com.scaleguard.server.http.h3.AltSvcHandler(port));   // TLS: advertise HTTP/3
    }
        p.addLast("http-aggregator", new HttpObjectAggregator(maxBodyBytes()));
        if (cors) p.addLast("cors", new CorsHandler(corsConfig()));
        p.addLast("frontend", new ScaleGuardFrontendHandler(port));
    }

    /** True when the channel (or, for an HTTP/2 stream, its connection) is TLS. */
    public static boolean isTls(Channel ch) {
        if (com.scaleguard.server.http.h3.Http3Listener.isHttp3(ch)) return true;   // QUIC is always TLS 1.3
        if (ch.pipeline().get(io.netty.handler.ssl.SslHandler.class) != null) return true;
        Channel parent = ch.parent();
        return parent != null && parent.pipeline().get(io.netty.handler.ssl.SslHandler.class) != null;
    }
}
