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

import com.scaleguard.server.http.cache.CachedResource;
import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.TargetSystem;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import java.util.UUID;

public class ScaleGuardFrontendHandler extends ChannelInboundHandlerAdapter {

  private Channel outboundChannel;
  private static InboundMessageHandler inboundHandler =  new InboundMessageHandler();

  public ScaleGuardFrontendHandler() {
  }
  /**
   * Closes the specified channel after all queued write requests are flushed.
   */
  static void closeOnFlush(Channel ch) {
    if (ch.isActive()) {
      ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    final Channel inboundChannel = ctx.channel();
    //inboundChannel.read();
    inboundChannel.config().setAutoRead(true);
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, Object msg) {
    TargetSystem ts = inboundHandler.matchTarget(ctx,msg);
    if(ts==null){
      new DefaultResponseHandler().handle(ctx,null);
    }else {
      if(ts.isEnableCache()) {
        inboundHandler.handle(ctx, msg,ts, key -> proeedToTarget(ts, ctx, msg, key==null?null: key.getKey(),key==null?null:key.getResource()));
      }else{
        proeedToTarget(ts, ctx, msg, null,null);
      }
    }
  }

  private void proeedToTarget(TargetSystem ts, final ChannelHandlerContext ctx, Object msg, String messageKey, CachedResource cr){
    if (outboundChannel == null || !outboundChannel.isActive()) {
      handleNewOutboundChannel(ts,ctx,msg,messageKey,cr);
    }else{
      handleExistingOutboundChannel(ctx,msg,cr);
    }
  }

  private void handleNewOutboundChannel(TargetSystem ts,final ChannelHandlerContext ctx, Object msg,String messageKey,CachedResource cr){
    final Channel inboundChannel = ctx.channel();
    Bootstrap b = new Bootstrap();
    b.group(inboundChannel.eventLoop())
        .channel(ctx.channel().getClass())
            //.handler(new ScaleGuardBackendHandler(inboundChannel,null,messageKey))
            .handler(new SecureProxyInitializer(inboundChannel, true,null,messageKey))
        .option(ChannelOption.AUTO_READ, false);
    HostGroup hg = ts.getHostGroup();
    ChannelFuture f;
    if(hg!=null){
      inboundHandler.setHost(hg.getHost());
      inboundHandler.reset(msg);
      f= b.connect(hg.getHost(), Integer.valueOf(hg.getPort()));
      System.out.println("Connecting to "+hg.getHost()+" "+ Integer.valueOf(hg.getPort()));
    }else{
      inboundHandler.setHost(ts.getHost());
      f = b.connect(ts.getHost(), Integer.valueOf(ts.getPort()));
    }
    outboundChannel = f.channel();
    f.addListener((ChannelFutureListener) future -> {
      if (future.isSuccess()) {
        inboundChannel.config().setAutoRead(true);
        outboundChannel.writeAndFlush(msg);
      } else {
        Object obj = future.get();
        if(obj instanceof Exception){
          System.out.println(((Exception) obj).getMessage());
        }
        inboundChannel.close();
      }
    });
  }

  public void handleExistingOutboundChannel(final ChannelHandlerContext ctx, Object msg,CachedResource cr) {
      inboundHandler.reset(msg);
      outboundChannel.writeAndFlush(msg)
        .addListener((ChannelFutureListener) future -> {
          if (future.isSuccess()) {
            ctx.channel().read();
          } else {
            future.channel().close();
          }
        });
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    if (outboundChannel != null) {
      closeOnFlush(outboundChannel);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    cause.printStackTrace();
    closeOnFlush(ctx.channel());
  }
}
