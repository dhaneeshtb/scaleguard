/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.scaleguard.server.http.client;

import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.server.http.router.TargetSystem;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HttpClient {

    private final SourceSystem sourceSystem;
    private Bootstrap b;
    private static Map<String, HttpClient> sourceClientMap = new ConcurrentHashMap<>();

    public static HttpClient getClient(SourceSystem sourceSystem){
        return sourceClientMap.computeIfAbsent(sourceSystem.getId(),(k)->new HttpClient(sourceSystem));
    }
    private HttpClient(SourceSystem sourceSystem){
        this.sourceSystem=sourceSystem;
        bootstrap();
    }

    private void bootstrap()  {
        TargetSystem ts = sourceSystem.getTargetSystem();
        final boolean ssl = "https".equalsIgnoreCase(ts.getScheme());
        final SslContext sslCtx;
        try {
            if (ssl) {
                sslCtx = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            } else {
                sslCtx = null;
            }
            EventLoopGroup group = new NioEventLoopGroup();
            b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new HttpClientInitializer(sslCtx));
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void send(String message) throws Exception {
        HostGroup ts = sourceSystem.getTargetSystem().getHostGroup();
        String url = ts.getScheme()+"://"+ts.getHost()+":"+ts.getPort()+sourceSystem.getTargetSystem().getBasePath();
        URI uri = new URI(url);
        Channel ch = b.connect(ts.getHost(),Integer.parseInt(ts.getPort())).sync().channel();


        System.out.println(ch.id());
        // Prepare the HTTP request.
        ByteBuf bbuf = Unpooled.copiedBuffer(message, StandardCharsets.UTF_8);

        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, uri.getRawPath(),bbuf);
        request.headers().set(HttpHeaderNames.HOST, ts.getHost());
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        request.headers().set(HttpHeaders.Names.CONTENT_LENGTH, bbuf.readableBytes());
// Send the HTTP request.
        // Set some example cookies.
//        request.headers().set(
//                HttpHeaderNames.COOKIE,
//                ClientCookieEncoder.STRICT.encode(
//                        new DefaultCookie("my-cookie", "foo"),
//                        new DefaultCookie("another-cookie", "bar"))
//
//        );

        // Send the HTTP request.
        ch.writeAndFlush(request);
        // Wait for the server to close the connection.
        ch.closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                System.out.println("Channel closed ....");
            }
        });
    }


}
