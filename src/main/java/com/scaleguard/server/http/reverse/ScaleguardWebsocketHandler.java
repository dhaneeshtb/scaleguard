package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.utils.NettyTransport;
import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.RouteTarget;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket proxying. The backend handshake is completed first and only then is the client answered with 101 (so the
 * client's status and sub-protocol are the backend's, and a refusing backend gives the client a clean HTTP error). That
 * order matters: clients send frames the moment they see 101 (Next.js HMR does), and over a slow link, such as an SSH tunnel,
 * the backend connection is not ready for several round trips. Frames are then relayed both ways with back-pressure (the
 * backend is read again only after the client accepted the previous frame), and closing either side closes the other.
 */
public class ScaleguardWebsocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(ScaleguardWebsocketHandler.class);

    private static final int MAX_FRAME = Integer.getInteger("scaleguard.websocket.maxFrameBytes", 16 * 1024 * 1024);
    private static final int CONNECT_MS = Integer.getInteger("scaleguard.websocket.connectTimeoutMs", 5_000);
    private static final int HANDSHAKE_SECONDS = Integer.getInteger("scaleguard.websocket.handshakeTimeoutSeconds", 15);

    /** Not forwarded to the backend: the proxy makes its own handshake, and these are per-connection. */
    private static final Set<String> NOT_FORWARDED = Set.of("connection", "upgrade", "sec-websocket-key", "sec-websocket-version",
            "sec-websocket-extensions", "sec-websocket-protocol", "sec-websocket-accept", "content-length", "transfer-encoding", "te",
            "keep-alive", "proxy-connection", "host");

    public static boolean isWebSocketRequest(FullHttpRequest request) {
        HttpHeaders headers = request.headers();
        return "websocket".equalsIgnoreCase(headers.get(HttpHeaderNames.UPGRADE))
                && headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true)     // "keep-alive, Upgrade" (Firefox) too
                && headers.contains(HttpHeaderNames.SEC_WEBSOCKET_KEY);
    }

    public static void setHeaders(Map<String, String> includeHeaders, HttpHeaders originalHeaders){
        if(includeHeaders!=null){
            includeHeaders.forEach((k,v)->originalHeaders.set(k, v));
        }
    }

    private static void fail(Channel client, FullHttpRequest request, HttpResponseStatus status) {
        ReferenceCountUtil.release(request);
        if (client.isActive()) {
            FullHttpResponse r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
            r.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0).set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            client.writeAndFlush(r).addListener(ChannelFutureListener.CLOSE);
        }
    }

    public void handleWebSocketProxy(RouteTarget ts, ChannelHandler handler, ChannelHandlerContext ctx, FullHttpRequest request) {
        final Channel client = ctx.channel();
        HostGroup hg = ts.getTargetSystem().getHostGroup();
        if (hg == null) {
            fail(client, request, HttpResponseStatus.SERVICE_UNAVAILABLE);
            return;
        }
        final boolean tls = "https".equalsIgnoreCase(hg.getScheme());
        final URI uri = URI.create((tls ? "wss://" : "ws://") + hg.getHost() + ":" + hg.getPort() + ts.getUri());

        // what the backend gets: the client's headers minus the handshake ones, Host as for proxied HTTP, plus the target's own headers
        HttpHeaders forward = new DefaultHttpHeaders();
        for (Map.Entry<String, String> e : request.headers()) {
            if (!NOT_FORWARDED.contains(e.getKey().toLowerCase(Locale.ROOT))) forward.add(e.getKey(), e.getValue());
        }
        forward.set(HttpHeaderNames.HOST, hg.getHost());
        setHeaders(ts.getTargetSystem().getIncludeHeaders(), forward);
        final String wantedProtocols = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);

        final WebSocketClientHandshaker backendHandshake = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, wantedProtocols, false, forward, MAX_FRAME);
        final Backend relay = new Backend(client, request, backendHandshake, handler, uri.toString());

        Bootstrap bootstrap = new Bootstrap()
                .group(client.eventLoop())
                // Must match the inbound loop's transport: NioSocketChannel on an epoll loop fails with "incompatible event loop type"
                .channel(NettyTransport.socketChannel())
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_MS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        if (tls) {
                            SslContext ssl = SslContextBuilder.forClient().build();
                            ch.pipeline().addLast(ssl.newHandler(ch.alloc(), uri.getHost(), uri.getPort()));
                        }
                        ch.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(65536), relay);
                    }
                });
        ChannelFuture connect = bootstrap.connect(uri.getHost(), uri.getPort());
        connect.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                logger.debug("WebSocket backend {} unreachable: {}", uri, f.cause() == null ? "" : f.cause().toString());
                fail(client, request, HttpResponseStatus.BAD_GATEWAY);
            }
        });
        // a backend that accepts the connection but never answers the handshake
        client.eventLoop().schedule(() -> {
            if (!relay.done) {
                connect.channel().close();
                fail(client, request, HttpResponseStatus.GATEWAY_TIMEOUT);
            }
        }, HANDSHAKE_SECONDS, TimeUnit.SECONDS);
        client.closeFuture().addListener(f -> connect.channel().close());
    }

    /** Backend side: finishes the handshake, then relays backend frames to the client. */
    private static final class Backend extends SimpleChannelInboundHandler<Object> {
        private final Channel client;
        private final FullHttpRequest request;
        private final WebSocketClientHandshaker handshake;
        private final ChannelHandler frontend;
        private final String url;
        private final List<WebSocketFrame> pending = new ArrayList<>();
        volatile boolean done;
        private boolean clientReady;
        private Channel backend;

        Backend(Channel client, FullHttpRequest request, WebSocketClientHandshaker handshake, ChannelHandler frontend, String url) {
            this.client = client;
            this.request = request;
            this.handshake = handshake;
            this.frontend = frontend;
            this.url = url;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            backend = ctx.channel();
            handshake.handshake(backend);
            ctx.read();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (!handshake.isHandshakeComplete()) {
                FullHttpResponse response = (FullHttpResponse) msg;
                try {
                    handshake.finishHandshake(backend, response);
                } catch (Exception e) {
                    // the backend refused the upgrade: tell the client what it said
                    done = true;
                    int code = response.status().code();
                    fail(client, request, code >= 400 && code < 600 ? response.status() : HttpResponseStatus.BAD_GATEWAY);
                    ctx.close();
                    return;
                }
                done = true;
                // now (and only now) accept the client's upgrade, with the sub-protocol the backend picked
                WebSocketServerHandshaker server = new WebSocketServerHandshakerFactory(url, handshake.actualSubprotocol(), false, MAX_FRAME).newHandshaker(request);
                if (server == null) {
                    WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(client);
                    ReferenceCountUtil.release(request);
                    ctx.close();
                    return;
                }
                ChannelFuture f = server.handshake(client, request);         // installs the frame codec synchronously
                client.pipeline().replace(frontend, "websocketFrameHandler", new Client(backend));
                f.addListener((ChannelFutureListener) done -> {
                    ReferenceCountUtil.release(request);
                    if (!done.isSuccess()) {
                        backend.close();
                        return;
                    }
                    clientReady = true;
                    for (WebSocketFrame frame : pending) forward(frame);       // frames the backend sent right after its 101
                    pending.clear();
                    backend.read();
                });
                return;
            }
            if (msg instanceof WebSocketFrame frame) {
                if (!clientReady) {
                    pending.add(frame.retain());
                    return;
                }
                forward(frame.retain());
            }
        }

        private void forward(WebSocketFrame frame) {
            boolean close = frame instanceof CloseWebSocketFrame;
            client.writeAndFlush(frame).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess() || close) {
                    client.close();
                    backend.close();
                } else {
                    backend.read();        // back-pressure: read the next one only after the client took this one
                }
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            for (WebSocketFrame f : pending) f.release();
            pending.clear();
            if (clientReady) client.close();
            else if (!done) {
                done = true;
                fail(client, request, HttpResponseStatus.BAD_GATEWAY);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("WebSocket backend error: {}", cause.toString());
            ctx.close();
            if (clientReady) client.close();
        }
    }

    /** Client side after the upgrade: client frames go to the backend. */
    private static final class Client extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final Channel backend;

        Client(Channel backend) {
            super(false);
            this.backend = backend;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (!backend.isActive()) {
                frame.release();
                ctx.close();
                return;
            }
            boolean close = frame instanceof CloseWebSocketFrame;
            backend.writeAndFlush(frame).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess() || close) {
                    ctx.close();
                    backend.close();
                }
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            backend.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("WebSocket client error: {}", cause.toString());
            ctx.close();
            backend.close();
        }
    }
}
