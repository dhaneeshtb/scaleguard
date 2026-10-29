package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.utils.NettyTransport;
import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.RouteTarget;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScaleguardWebsocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(ScaleguardWebsocketHandler.class);

    private Channel remoteChannel;

    public static boolean isWebSocketRequest(FullHttpRequest request) {
        HttpHeaders headers = request.headers();
        return "websocket".equalsIgnoreCase(headers.get(HttpHeaderNames.UPGRADE))
                && "Upgrade".equalsIgnoreCase(headers.get(HttpHeaderNames.CONNECTION))
                && headers.contains(HttpHeaderNames.SEC_WEBSOCKET_KEY);
    }

    public static void setHeaders(Map<String, String> includeHeaders, HttpHeaders originalHeaders){
        if(includeHeaders!=null){
            includeHeaders.forEach((k,v)->originalHeaders.set(k, v));
        }
    }

    public void handleWebSocketProxy(RouteTarget ts,ChannelHandler handler, ChannelHandlerContext ctx, FullHttpRequest request) {

        HostGroup hg = ts.getTargetSystem().getHostGroup();
        String url = "";
        if (hg.getScheme().equalsIgnoreCase("https")) {
            url = "wss://";
        } else {
            url = "ws://";

        }
        url += hg.getHost() + ":" + hg.getPort() + ts.getUri();

        URI uri = URI.create(url);
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(url, null, false);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(request);

        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }

        HttpHeaders originalHeaders = request.headers();
        setHeaders(ts.getTargetSystem().getIncludeHeaders(), originalHeaders);

        handshaker.handshake(ctx.channel(), request).addListener(future -> {
            if (future.isSuccess()) {
                WebSocketClientHandshaker clientHandshaker = WebSocketClientHandshakerFactory.newHandshaker(
                        uri, WebSocketVersion.V13, null, true, originalHeaders);

                Bootstrap bootstrap = new Bootstrap()
                        .group(ctx.channel().eventLoop())
                        // Must match the inbound loop's transport: NioSocketChannel on an epoll loop fails with
                        // "incompatible event loop type", which broke WebSocket proxying on Linux.
                        .channel(NettyTransport.socketChannel())
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                if (hg.getScheme().equalsIgnoreCase("https")) {
                                    try {
                                        SslContext sslContext = SslContextBuilder.forClient().build();
                                        ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), uri.getHost(), uri.getPort()));
                                    } catch (Exception e) {
                                        logger.error("Failed to build SSL context for WebSocket backend", e);
                                    }
                                }
                                ch.pipeline()
                                        .addLast(new HttpClientCodec())
                                        .addLast(new HttpObjectAggregator(8192))
                                        .addLast(new HttpWebSocketProxyBackendHandler(ctx.channel(), clientHandshaker));
                            }
                        });

                bootstrap.connect(uri.getHost(), uri.getPort()).addListener((ChannelFutureListener) futureReverse -> {
                    if (!futureReverse.isSuccess()) {
                        ctx.close();
                    } else {
                        remoteChannel = futureReverse.channel();
                    }
                });
            } else {
                logger.error("WebSocket handshake failed");
            }
        });

        ctx.pipeline().replace(handler, "websocketFrameHandler", new SimpleChannelInboundHandler<WebSocketFrame>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
                remoteChannel.writeAndFlush(frame.retain());
            }

        });
    }








}
