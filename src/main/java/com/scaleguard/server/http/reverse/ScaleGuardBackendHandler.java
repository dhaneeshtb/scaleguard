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

    /** Streaming: a response whose head has been forwarded but whose last content hasn't arrived yet. */
    private boolean midResponse;
    /** Streaming: its analytics record, finished when the last content arrives. */
    private com.scaleguard.server.analytics.AccessRecord streamRec;
    private int streamStatus;
    /** Streaming: a head was written in this read batch and needs a flush; a read was already requested. */
    private boolean pendingFlush, readRequested;
    /** Response-time stats for this connection's route and backend (fixed for its lifetime), looked up once. */
    private RouteLogger.RouteStats stats;

    private void logResponseTime() {
        if (rt == null) return;
        if (stats == null) stats = RouteLogger.stats(rt);
        stats.addRT(System.currentTimeMillis() - rt.getStartTime());
    }

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

        if (!(msg instanceof FullHttpResponse) && msg instanceof io.netty.handler.codec.http.HttpObject) {
            stream(ctx, msg);   // no aggregator in the pipeline: forward head and body as they arrive
            return;
        }
        if(msg instanceof FullHttpResponse) {
            FullHttpResponse response= (FullHttpResponse) msg;
            com.scaleguard.server.analytics.AccessRecord rec = onHead(ctx, response);
            if (rec != null) {
                rec.bytesOut = response.content().readableBytes();
                rec.finish(response.status().code());
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
                    logResponseTime();
                } else {
                    logger.debug("Could not deliver the response to the client: {}", future.cause() == null ? "closed" : future.cause().toString());
                    future.channel().close();
                }
            }
        });


    }


    /** Per response head: passive health, analytics trace header, redirect rewriting. Returns the analytics record. */
    private com.scaleguard.server.analytics.AccessRecord onHead(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpResponse response) {
        PassiveHealthManager.getInstance().recordStatus(host, response.status().code());
        com.scaleguard.server.analytics.AccessRecord rec = com.scaleguard.server.analytics.AccessLog.nextResponse(ctx.channel());
        if (rec != null && rec.traceId != null && com.scaleguard.server.analytics.TraceContext.HEADERS) response.headers().set("X-Trace-Id", rec.traceId);
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
        return rec;
    }

    /**
     * Streaming relay (routes without response caching): the head and each body chunk go to the client as they arrive
     * instead of being collected into one message first — less copying, and large bodies never sit in memory whole.
     * Back-pressure is unchanged: the backend is read again only after the client accepted what was written.
     */
    private void stream(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof io.netty.handler.codec.http.HttpResponse head) {
            if (head.status().code() == 100) {           // 100 Continue: pass on, the real response follows
                inboundChannel.write(msg);
                pendingFlush = true;
                return;
            }
            streamRec = onHead(ctx, head);
            streamStatus = head.status().code();
            midResponse = true;
            inboundChannel.write(head);
            pendingFlush = true;
        }
        if (msg instanceof io.netty.handler.codec.http.HttpContent content) {
            if (streamRec != null) streamRec.bytesOut += content.content().readableBytes();
            if (content instanceof io.netty.handler.codec.http.LastHttpContent) {
                midResponse = false;
                com.scaleguard.server.analytics.AccessRecord rec = streamRec;
                int status = streamStatus;
                streamRec = null;
                pendingFlush = false;
                readRequested = true;
                inboundChannel.writeAndFlush(content).addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        if (rec != null) rec.finish(status);
                        ctx.channel().read();
                        logResponseTime();
                    } else {
                        logger.debug("Could not deliver the response to the client: {}", f.cause() == null ? "closed" : f.cause().toString());
                        f.channel().close();
                        ctx.close();
                    }
                });
            } else {
                // a body chunk: queue it; everything from this socket read is flushed together in channelReadComplete
                inboundChannel.write(content);
                pendingFlush = true;
            }
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        boolean flushed = pendingFlush;
        if (pendingFlush) {          // head and/or body chunks queued during this read: one flush for all of them
            pendingFlush = false;
            inboundChannel.flush();
        }
        if (midResponse && !readRequested) {   // still owe the rest of the response
            if (!flushed || inboundChannel.isWritable()) {
                ctx.read();
            } else {
                // the client is behind: read on once what we just queued has gone out (back-pressure)
                // (an empty content object: HTTP/1.1 writes nothing for it, HTTP/2 and /3 an empty DATA frame)
                inboundChannel.writeAndFlush(new io.netty.handler.codec.http.DefaultHttpContent(io.netty.buffer.Unpooled.EMPTY_BUFFER))
                        .addListener((ChannelFutureListener) f -> { if (f.isSuccess()) ctx.read(); else ctx.close(); });
            }
        }
        readRequested = false;
        ctx.fireChannelReadComplete();
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
        if (midResponse) {
            // part of the backend's response is already on its way to the client: an error response can't follow it,
            // so end the client connection (the client sees a truncated response, as with nginx)
            midResponse = false;
            if (streamRec != null) { streamRec.finish(502, com.scaleguard.server.analytics.AccessRecord.ERROR); streamRec = null; }
            ScaleGuardFrontendHandler.closeOnFlush(inboundChannel);
            return;
        }
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
