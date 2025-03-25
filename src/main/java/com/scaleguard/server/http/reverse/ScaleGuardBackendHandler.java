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
import com.scaleguard.server.http.cache.InMemoryCacheLooker;
import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.RouteLogger;
import com.scaleguard.server.http.router.RouteTarget;
import com.scaleguard.server.http.router.TargetSystem;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;

public class ScaleGuardBackendHandler extends ChannelInboundHandlerAdapter {

    private final Channel inboundChannel;
    private  TargetSystem cacheInfo;
    private String cacheKey;
    private CacheManager cacheManager = InMemoryCacheLooker.getInstance();

    private RouteTarget rt;
    public ScaleGuardBackendHandler(RouteTarget rt, Channel inboundChannel, String cacheKey) {
        this.inboundChannel = inboundChannel;
        this.rt=rt;
        this.cacheKey=cacheKey;

    }
    public ScaleGuardBackendHandler(Channel inboundChannel, TargetSystem cacheInfo, String cacheKey) {
        this.inboundChannel = inboundChannel;
        this.cacheInfo=cacheInfo;
        this.cacheKey=cacheKey;
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
                        HostGroup hg = rt.getTargetSystem().getHostGroup();
                        if(hg!=null) {
                            String tfqdn = hg.getScheme() + "://" + hg.getHost();
                            int dport = Integer.valueOf(hg.getPort());
                            if (dport != 80 &&  dport != 443) {
                                tfqdn = tfqdn + ":" + dport;
                            }
                            String cLocation = location.replace(tfqdn, sfqdn);

                            response.headers().set(HttpHeaderNames.LOCATION, cLocation);
                            System.out.println("Redirecting to: " + location);
                        }
                    }

//                    request.setUri(location);
//                    followRedirects(ctx, request, redirectCount + 1);
//                    return;
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

                        cacheManager.saveFresh(cacheInfo, cacheKey, cachedObject);
                    }
                    RouteLogger.log(rt);
                } else {
                    future.channel().close();
                }
            }
        });


    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ScaleGuardFrontendHandler.closeOnFlush(inboundChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ScaleGuardFrontendHandler.closeOnFlush(ctx.channel());
    }


}
