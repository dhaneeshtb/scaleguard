package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.RouteTarget;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.server.http.router.TargetSystem;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The proxy in front of a (slow) WebSocket echo backend, like a Next.js HMR socket behind an SSH tunnel. Loopback only. */
public class WebSocketProxyTest {

    private NioEventLoopGroup group;
    private EventLoopGroup proxyGroup;
    private Channel backend, proxy;
    private volatile int backendDelayMs, backendStatus;
    private volatile String backendProtocol;
    private final AtomicReference<String> backendSawHost = new AtomicReference<>();

    @Before
    public void start() throws Exception {
        group = new NioEventLoopGroup(2);
        proxyGroup = com.scaleguard.server.http.utils.NettyTransport.newGroup(2, "ws-test");
        backend = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65536), new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        FullHttpRequest req = (FullHttpRequest) msg;
                        backendSawHost.set(req.headers().get("Host"));
                        Runnable go = () -> {
                            if (backendStatus != 0) {
                                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(backendStatus)))
                                        .addListener(ChannelFutureListener.CLOSE);
                                req.release();
                                return;
                            }
                            WebSocketServerHandshaker hs = new WebSocketServerHandshakerFactory("ws://x/", backendProtocol, false).newHandshaker(req);
                            hs.handshake(ctx.channel(), req);
                            req.release();
                            ctx.pipeline().replace(this, "echo", new SimpleChannelInboundHandler<WebSocketFrame>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext c, WebSocketFrame f) {
                                    if (f instanceof TextWebSocketFrame t) c.writeAndFlush(new TextWebSocketFrame("echo:" + t.text()));
                                    else if (f instanceof CloseWebSocketFrame) c.close();
                                }
                            });
                            ctx.channel().writeAndFlush(new TextWebSocketFrame("hello-from-backend"));    // right after the 101
                        };
                        if (backendDelayMs > 0) ctx.executor().schedule(go, backendDelayMs, TimeUnit.MILLISECONDS);
                        else go.run();
                    }
                });
            }
        }).bind(0).sync().channel();

        int backendPort = ((InetSocketAddress) backend.localAddress()).getPort();
        HostGroup hg = new HostGroup();
        hg.setHost("127.0.0.1");
        hg.setPort(String.valueOf(backendPort));
        hg.setScheme("http");
        hg.setReachable(true);
        TargetSystem target = new TargetSystem();
        target.setScheme("http");
        target.setHostGroups(List.of(hg));
        RouteTarget rt = new RouteTarget(new SourceSystem(), target);
        rt.setUri("/_next/webpack-hmr?id=1");

        proxy = new ServerBootstrap().group(proxyGroup).channel(com.scaleguard.server.http.utils.NettyTransport.serverChannel()).childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65536), new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        new ScaleguardWebsocketHandler().handleWebSocketProxy(rt, this, ctx, (FullHttpRequest) msg);
                    }
                });
            }
        }).bind(0).sync().channel();
    }

    @After
    public void stop() {
        proxy.close();
        backend.close();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        proxyGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    private int proxyPort() {
        return ((InetSocketAddress) proxy.localAddress()).getPort();
    }

    private static final class Listener implements WebSocket.Listener {
        final CompletableFuture<String> first = new CompletableFuture<>(), second = new CompletableFuture<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private int n;

        @Override
        public void onOpen(WebSocket ws) {
            ws.sendText("ping-at-open", true);          // like a browser: the first frame goes out the moment the socket opens
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            (n++ == 0 ? first : second).complete(data.toString());
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed.complete(code);
            return null;
        }
    }

    @Test
    public void framesSentTheMomentTheSocketOpensSurviveASlowBackend() throws Exception {
        backendDelayMs = 400;            // the backend (e.g. behind an SSH tunnel) is slow to answer its handshake
        Listener l = new Listener();
        HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + proxyPort() + "/_next/webpack-hmr?id=1"), l).get(10, TimeUnit.SECONDS);
        // the backend's own first frame (sent right after its 101) and the echo of the client's first frame, in either order
        String a = l.first.get(5, TimeUnit.SECONDS);
        String b = l.second.get(5, TimeUnit.SECONDS);
        assertEquals(java.util.Set.of("hello-from-backend", "echo:ping-at-open"), java.util.Set.of(a, b));
        assertEquals("backend sees its own host, as for proxied HTTP", "127.0.0.1", backendSawHost.get());
    }

    @Test
    public void aBackendThatRefusesTheUpgradeGivesTheClientAnErrorNotAnOpenSocket() throws Exception {
        backendStatus = 403;
        Listener l = new Listener();
        try {
            HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + proxyPort() + "/x"), l).get(10, TimeUnit.SECONDS);
            org.junit.Assert.fail("socket must not open");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertEquals(403, ((java.net.http.WebSocketHandshakeException) expected.getCause()).getResponse().statusCode());
        }
    }

    @Test
    public void subProtocolComesFromTheBackendAndClosingOneSideClosesTheOther() throws Exception {
        backendProtocol = "graphql-ws";
        Listener l = new Listener();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols("graphql-ws", "other")
                .buildAsync(URI.create("ws://127.0.0.1:" + proxyPort() + "/x"), l).get(10, TimeUnit.SECONDS);
        assertEquals("graphql-ws", ws.getSubprotocol());
        l.first.get(10, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
        l.closed.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void firefoxStyleConnectionHeaderIsAnUpgradeToo() throws Exception {
        try (Socket s = new Socket("127.0.0.1", proxyPort())) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(("GET /x HTTP/1.1\r\nHost: t\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            byte[] buf = new byte[12];
            int n = in.readNBytes(buf, 0, 12);
            assertEquals("HTTP/1.1 101", new String(buf, 0, n, StandardCharsets.US_ASCII));
        }
        FullHttpRequest r = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x");
        r.headers().set("Upgrade", "websocket").set("Connection", "keep-alive, Upgrade").set("Sec-WebSocket-Key", "k");
        assertTrue(ScaleguardWebsocketHandler.isWebSocketRequest(r));
    }
}
