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
import com.scaleguard.server.http.cache.RequestCacheInfo;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpResponse;

public class ScaleGuardBackendHandler extends ChannelInboundHandlerAdapter {

    private final Channel inboundChannel;
    private  RequestCacheInfo cacheInfo;
    private String cacheKey;
    private CacheManager cacheManager = InMemoryCacheLooker.getInstance();

    public ScaleGuardBackendHandler(Channel inboundChannel) {
        this.inboundChannel = inboundChannel;
    }
    public ScaleGuardBackendHandler(Channel inboundChannel, RequestCacheInfo cacheInfo,String cacheKey) {
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
        if(cacheKey!=null){
            if(msg instanceof ByteBuf){
                cachedObject = ((ByteBuf) msg).duplicate().retain();
            }else if(msg instanceof FullHttpResponse){
                cachedObject = ((FullHttpResponse) msg).duplicate().retain();
            }else{
                cachedObject = msg;
            }
        }else{
            cachedObject=null;
        }
        inboundChannel.writeAndFlush(msg).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                    ctx.channel().read();
                    if(cacheKey!=null && cachedObject!=null) {
                        cacheManager.saveFresh(cacheInfo, cacheKey, cachedObject);
                    }
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
