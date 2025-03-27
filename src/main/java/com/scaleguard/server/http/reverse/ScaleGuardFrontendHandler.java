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

import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.RateLimitManager;
import com.scaleguard.server.http.router.RouteTarget;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;


public class ScaleGuardFrontendHandler extends ChannelInboundHandlerAdapter {

  private static final Logger logger
          = LoggerFactory.getLogger(ScaleGuardFrontendHandler.class);

  private static RateLimitManager rateLimitManager=new RateLimitManager();

  private Channel outboundChannel;

  private RouteTarget targetSystem;

  private int port=80;

  private static InboundMessageHandler inboundHandler =  new InboundMessageHandler();

  public ScaleGuardFrontendHandler() {
  }

  public ScaleGuardFrontendHandler(int port) {
    this.port=port;
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
    inboundChannel.config().setAutoRead(true);
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, Object msg) {
    String inAddress= ((InetSocketAddress)ctx.channel().remoteAddress()).getAddress().getHostAddress();
    try {
      RouteTarget ts = inboundHandler.matchTarget(ctx, msg, port);
      if (!rateLimitManager.checkRate(ts, inAddress)) {
        logger.info("Discard due to rate exceeded....");
        return;
      }

      if (ts == null) {
        new DefaultResponseHandler().handle(ctx, null, msg);
      } else {

        if(!ScaleguardClientAuthHandler.checkClientAuth(ts)){
          new DefaultResponseHandler().handleFailure(ctx, "invalid auth credentials", HttpResponseStatus.FORBIDDEN);
          return;
        }
        if (ts.getTargetSystem().isEnableCache()) {
          inboundHandler.handle(ctx, msg, ts, key -> proeedToTarget(ts, ctx, msg, key == null ? null : key.getKey()));
        } else {
          proeedToTarget(ts, ctx, msg, null);
        }
      }
    }catch (Exception e){
      e.printStackTrace();
    }
  }

  private boolean isSameSourceAndTarget(RouteTarget ts){
    if(ts.getTargetSystem().getId().equalsIgnoreCase(targetSystem.getTargetSystem().getId())
            && ts.getSourceSystem().getId().equalsIgnoreCase(targetSystem.getSourceSystem().getId())
    ){
      return true;
    }else{
      return false;
    }
  }

  private void proeedToTarget(RouteTarget ts, final ChannelHandlerContext ctx, Object msg, String messageKey){
    if (outboundChannel == null || !outboundChannel.isActive() || targetSystem==null || !isSameSourceAndTarget(ts)) {
      handleNewOutboundChannel(ts,ctx,msg,messageKey);
    }else{
      targetSystem.setStartTime(System.currentTimeMillis());
      handleExistingOutboundChannel(ctx,msg,ts);
    }
  }

  private void handleNewOutboundChannel(RouteTarget ts,final ChannelHandlerContext ctx, Object msg,String messageKey){
    final Channel inboundChannel = ctx.channel();
    Bootstrap b = new Bootstrap();
    b.group(inboundChannel.eventLoop())
        .channel(ctx.channel().getClass())
            //.handler(new ScaleGuardBackendHandler(inboundChannel,null,messageKey))
            .handler(new SecureProxyInitializer(ts,inboundChannel,"https".equalsIgnoreCase(ts.getTargetSystem().getScheme()),messageKey))
        .option(ChannelOption.AUTO_READ, false);
    HostGroup hg = ts.getTargetSystem().getHostGroup();
    ChannelFuture f;
    if(hg!=null){
      ts.setTargetHost(hg.getHost()+":"+hg.getPort());
      inboundHandler.setHost(hg.getHost());
      inboundHandler.reset(msg,ts.getTargetSystem().getIncludeHeaders());
      f= b.connect(hg.getHost(), Integer.valueOf(hg.getPort()));
      logger.debug("Connecting to "+hg.getHost()+" "+ Integer.valueOf(hg.getPort()));
    }else{
      ts.setTargetHost(ts.getTargetSystem().getHost()+":"+ts.getTargetSystem().getPort());
      inboundHandler.setHost(ts.getTargetSystem().getHost());
      try {
        f = b.connect(ts.getTargetSystem().getHost(), Integer.valueOf(ts.getTargetSystem().getPort()));
      }catch (Exception e){
        handleConnectionError(ts,ctx);
        return;
      }
    }
    outboundChannel = f.channel();
    targetSystem = ts;
    f.addListener((ChannelFutureListener) future -> {
      if (future.isSuccess()) {
        inboundChannel.config().setAutoRead(true);
        outboundChannel.writeAndFlush(msg);
      } else {
        try {
          Object obj = future.get();
          if (obj instanceof Exception) {
            logger.error(((Exception) obj).getMessage());
          }
        }finally {
          handleConnectionError(ts,ctx);
          inboundChannel.close();
        }
      }
    });
  }

  private void handleConnectionError(RouteTarget ts,final ChannelHandlerContext ctx){
    if(ts.getTargetSystem().getHostGroup()!=null) {
      new DefaultResponseHandler().handleFailure(ctx, "failed to connect to " + ts.getTargetSystem().getHostGroup().getHost() + ":" + ts.getTargetSystem().getHostGroup().getPort(),
              HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }else{
      new DefaultResponseHandler().handleFailure(ctx, "failed to connect to " + ts.getTargetSystem().getGroupId(),
              HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }
  }

  public void handleExistingOutboundChannel(final ChannelHandlerContext ctx, Object msg, RouteTarget ts) {
      inboundHandler.reset(msg, ts.getTargetSystem().getIncludeHeaders());
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
