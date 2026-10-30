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
package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.cache.CacheManager;
import com.scaleguard.server.http.cache.CacheManagers;
import com.scaleguard.server.http.cache.CachedResource;
import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.PassiveHealthManager;
import com.scaleguard.server.http.router.RouteLogger;
import com.scaleguard.server.http.router.RouteTarget;
import com.scaleguard.server.http.router.TargetSystem;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScaleGuardBackendHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ScaleGuardBackendHandler.class);

    private final Channel inboundChannel;
    private  TargetSystem cacheInfo;
    private String cacheKey;
    private CacheManager cacheManager = CacheManagers.get();

    private RouteTarget rt;
    private boolean timedOut;
    private String tlsFailure;
    /** The backend this connection goes to (fixed for the connection's lifetime). */
    private final HostGroup host;

    public ScaleGuardBackendHandler(RouteTarget rt, Channel inboundChannel, String cacheKey) {
        this.inboundChannel = inboundChannel;
        this.rt=rt;
        this.host = rt != null ? rt.getSelectedHost() : null;
        this.cacheKey=cacheKey;

    }
    public ScaleGuardBackendHandler(Channel inboundChannel, TargetSystem cacheInfo, String cacheKey) {
        this.inboundChannel = inboundChannel;
        this.cacheInfo=cacheInfo;
        this.cacheKey=cacheKey;
        this.host = null;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) {
        Object cachedObject;
        int statusCode;

        if(msg instanceof FullHttpResponse) {
            FullHttpResponse response= (FullHttpResponse) msg;
            PassiveHealthManager.getInstance().recordStatus(host, response.status().code());
            com.scaleguard.server.analytics.AccessRecord rec = com.scaleguard.server.analytics.AccessLog.nextResponse(ctx.channel());
            if (rec != null) {
                rec.bytesOut = response.content().readableBytes();
                if (rec.traceId != null && com.scaleguard.server.analytics.TraceContext.HEADERS) response.headers().set("X-Trace-Id", rec.traceId);
                rec.finish(response.status().code());
            }
            if (response.status().code() >= 300 && response.status().code() < 400) {
                // Handle Redirect
                String location = response.headers().get(HttpHeaderNames.LOCATION);
                if (location != null) {
                    if(rt!=null){
                       String sfqdn =  rt.getSourceSystem().getScheme()+"://"+rt.getSourceSystem().getHost();
                       int port = Integer.valueOf(rt.getSourceSystem().getPort());
                       if(port!=80 && port!=443){
                           sfqdn=sfqdn+":"+port;
                       }
                        HostGroup hg = host != null ? host : rt.getTargetSystem().getHostGroup();
                        if(hg!=null) {
                            String tfqdn = hg.getScheme() + "://" + hg.getHost();
                            int dport = Integer.valueOf(hg.getPort());
                            if (dport != 80 &&  dport != 443) {
                                tfqdn = tfqdn + ":" + dport;
                            }
                            String cLocation = location.replace(tfqdn, sfqdn);
                            response.headers().set(HttpHeaderNames.LOCATION, cLocation);
                            logger.debug("Redirecting to: {}", location);
                        }
                    }
                }
            }
        }
        if(cacheKey!=null){
            if(msg instanceof ByteBuf){
                cachedObject = ((ByteBuf) msg).duplicate().retain();
                statusCode=-1;
            }else if(msg instanceof FullHttpResponse){
                cachedObject = ((FullHttpResponse) msg).duplicate().retain();
                statusCode = ((FullHttpResponse) msg).status().code();
            }else{
                cachedObject = msg;
                statusCode=-1;
            }
        }else{
            cachedObject=null;
            statusCode=-1;
        }
        inboundChannel.writeAndFlush(msg).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                    ctx.channel().read();
                    if(cacheKey!=null && cachedObject!=null && statusCode>=200 && statusCode<300) {
                        // Use per-route TTL from the first matching CachedResource if available
                        int ttl = 300;
                        if (cacheInfo != null && cacheInfo.getCachedResources() != null
                                && !cacheInfo.getCachedResources().isEmpty()) {
                            ttl = cacheInfo.getCachedResources().get(0).getTtl();
                        }
                        cacheManager.saveFresh(cacheInfo, cacheKey, cachedObject, ttl);
                    }
                    RouteLogger.log(rt);
                } else {
                    logger.debug("Could not deliver the response to the client: {}", future.cause() == null ? "closed" : future.cause().toString());
                    future.channel().close();
                }
            }
        });


    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // backend closed before answering: those requests failed
        com.scaleguard.server.analytics.AccessLog.failPending(ctx.channel(), 502, com.scaleguard.server.analytics.AccessRecord.ERROR);
        ResponseTimeoutHandler rth = ctx.pipeline().get(ResponseTimeoutHandler.class);
        if (!timedOut && rth != null && rth.outstanding() > 0 && inboundChannel.isActive()) {
            // The backend went away (or its TLS handshake failed) before answering: tell the client with a 502
            // instead of dropping its connection.
            PassiveHealthManager.getInstance().recordFailure(host, tlsFailure != null ? "TLS handshake failed: " + tlsFailure : "connection closed before a response");
            sendError(io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY, tlsFailure != null
                    ? "TLS handshake with the upstream server failed: " + tlsFailure
                    : "The upstream server closed the connection before responding");
            return;
        }
        if (!timedOut) ScaleGuardFrontendHandler.closeOnFlush(inboundChannel);   // after a 504 the client connection stays usable
    }

    /** proxy_read_timeout reached: answer 504 for the overdue request and drop this backend connection. */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof io.netty.handler.ssl.SslHandshakeCompletionEvent hs && !hs.isSuccess()) {
            Throwable c = hs.cause();
            while (c != null && c.getCause() != null && c.getCause() != c) c = c.getCause();
            tlsFailure = c == null ? "unknown error" : c.getMessage();
        }
        if (evt != ResponseTimeoutHandler.TIMEOUT) {
            super.userEventTriggered(ctx, evt);
            return;
        }
        timedOut = true;
        PassiveHealthManager.getInstance().recordFailure(host, "response timeout");
        com.scaleguard.server.analytics.AccessRecord rec = com.scaleguard.server.analytics.AccessLog.nextResponse(ctx.channel());
        if (rec != null) rec.finish(504, com.scaleguard.server.analytics.AccessRecord.TIMEOUT);
        logger.debug("Backend {} didn't answer in time", host == null ? ctx.channel().remoteAddress() : host.getHost() + ":" + host.getPort());
        sendError(io.netty.handler.codec.http.HttpResponseStatus.GATEWAY_TIMEOUT, "The upstream server didn't respond in time");
        ctx.close();
    }

    private void sendError(io.netty.handler.codec.http.HttpResponseStatus status, String message) {
        if (!inboundChannel.isActive()) return;
        io.netty.buffer.ByteBuf body = io.netty.buffer.Unpooled.copiedBuffer(
                com.scaleguard.utils.JSON.toString(java.util.Map.of("message", message)), io.netty.util.CharsetUtil.UTF_8);
        io.netty.handler.codec.http.FullHttpResponse r = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1, status, body);
        r.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, "application/json");
        r.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        inboundChannel.writeAndFlush(r);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        PassiveHealthManager.getInstance().recordFailure(host, "connection error: " + cause.getMessage());
        logger.debug("Backend connection error: {}", cause.toString());
        ScaleGuardFrontendHandler.closeOnFlush(ctx.channel());
    }


}
