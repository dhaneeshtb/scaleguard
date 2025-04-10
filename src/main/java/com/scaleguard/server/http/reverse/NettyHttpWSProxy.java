package com.scaleguard.server.http.reverse;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.net.URI;

public class NettyHttpWSProxy {
    private final int port;
    private final String wsBackendUrl;

    public NettyHttpWSProxy(int port, String wsBackendUrl) {
        this.port = port;
        this.wsBackendUrl = wsBackendUrl;
    }

    public void start() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(65536))
                                    .addLast(new ChunkedWriteHandler())
                                    .addLast(new HttpProxyServerHandler(wsBackendUrl));
                        }
                    });

            Channel channel = bootstrap.bind(port).sync().channel();
            System.out.println("Proxy server started on port " + port);
            channel.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }


}

class HttpProxyServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final String wsBackendUrl;
    Channel remoteChannel;

    public HttpProxyServerHandler(String wsBackendUrl) {
        this.wsBackendUrl = wsBackendUrl;
    }

    private boolean isWebSocketRequest(FullHttpRequest request) {
        HttpHeaders headers = request.headers();
        return "websocket".equalsIgnoreCase(headers.get(HttpHeaderNames.UPGRADE))
                && "Upgrade".equalsIgnoreCase(headers.get(HttpHeaderNames.CONNECTION))
                && headers.contains(HttpHeaderNames.SEC_WEBSOCKET_KEY);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (isWebSocketRequest(request)) {
            handleWebSocketProxy(ctx, request);
        } else {
            handleHttpProxy(ctx);
        }
    }

    private void handleWebSocketProxy(ChannelHandlerContext ctx, FullHttpRequest request) {
        URI uri = URI.create(wsBackendUrl);
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(wsBackendUrl, null, false);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(request);

        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedWebSocketVersionResponse(ctx.channel());
            return;
        }

        handshaker.handshake(ctx.channel(), request).addListener(future -> {
            if (future.isSuccess()) {
                WebSocketClientHandshaker clientHandshaker = WebSocketClientHandshakerFactory.newHandshaker(
                        uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders());

                Bootstrap bootstrap = new Bootstrap()
                        .group(ctx.channel().eventLoop())
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline()
                                        .addLast(new HttpClientCodec())
                                        .addLast(new HttpObjectAggregator(8192))
                                        .addLast(new HttpWebSocketProxyBackendHandler(ctx.channel(), clientHandshaker));
                            }
                        });

                bootstrap.connect(uri.getHost(), uri.getPort()).addListener((ChannelFutureListener) futureReverse -> {
                    if (!futureReverse.isSuccess()) {
                        ctx.close();
                    }else{
                        remoteChannel=futureReverse.channel();
                    }
                });
            } else {
                System.err.println("WebSocket handshake failed");
            }
        });

        ctx.pipeline().replace(this, "websocketFrameHandler", new SimpleChannelInboundHandler<WebSocketFrame>(){
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
                remoteChannel.writeAndFlush(frame.retain());
//                if (frame instanceof TextWebSocketFrame) {
//                    // Handle text frame
//                    String message = ((TextWebSocketFrame) frame).text();
//                    System.out.println("Received: " + message);
//
//                    remoteChannel.writeAndFlush(frame.retain());
//
//                    //ctx.channel().writeAndFlush(new TextWebSocketFrame("Server received: " + message));
//
//                } else if (frame instanceof BinaryWebSocketFrame) {
//                    // Handle binary frame
//                    System.out.println("Received binary frame of length: " + frame.content().readableBytes());
//                    remoteChannel.writeAndFlush(frame.retain());
//
//
//                } else if (frame instanceof CloseWebSocketFrame) {
//                    // Handle close frame
//                    System.out.println("WebSocket closing");
////                    ctx.channel().close();
//
//                    remoteChannel.close();
////                    remoteChannel.writeAndFlush(frame);
//
//
//                } else if (frame instanceof PingWebSocketFrame) {
//                    // Handle ping frame
//                   // ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
//                    remoteChannel.writeAndFlush(frame.retain());
//
//                } else {
//                    System.out.println("Unsupported WebSocket frame received");
//                }
            }

        });
    }

    private void handleHttpProxy(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.content().writeBytes("HTTP request processed".getBytes());
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}

class HttpWebSocketProxyBackendHandler extends SimpleChannelInboundHandler<Object> {
    private final Channel clientChannel;
    private final WebSocketClientHandshaker handshaker;

    public HttpWebSocketProxyBackendHandler(Channel clientChannel, WebSocketClientHandshaker handshaker) {
        this.clientChannel = clientChannel;
        this.handshaker = handshaker;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        handshaker.handshake(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (!handshaker.isHandshakeComplete() && msg instanceof FullHttpResponse) {
            handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
            clientChannel.pipeline().addLast(new WebSocketFrameAggregator(65536));
            return;
        }

        if (msg instanceof WebSocketFrame) {
            clientChannel.writeAndFlush(((WebSocketFrame) msg).retain()).addListener(future -> {
                if (!future.isSuccess()) {
                    future.cause().printStackTrace();
                }
            });
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
