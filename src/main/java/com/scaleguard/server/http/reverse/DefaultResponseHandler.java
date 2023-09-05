package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.router.SourceSystem;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

public class DefaultResponseHandler {

  public void handle(final ChannelHandlerContext ctx, SourceSystem sourceSystem, Object msg){
    System.out.println("default handler");

    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      if (request.uri().equalsIgnoreCase("/health")) {
        handleHealth(ctx,sourceSystem);
        return;
      }
    }
    ByteBuf content = Unpooled.copiedBuffer("Welcome to Scaleguard!", CharsetUtil.UTF_8);
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html");
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
    ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        //It is successful here, and does not represent the success of the customer, and brush out the data success default representative has completed
        if (future.isSuccess()) {
          ctx.channel().read();
        } else {
          future.channel().close();
        }
      }
    });
  }

  public void handleHealth(final ChannelHandlerContext ctx, SourceSystem sourceSystem){
    ByteBuf content = Unpooled.copiedBuffer("{\"status\":\"healthy\"}", CharsetUtil.UTF_8);
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
    ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        //It is successful here, and does not represent the success of the customer, and brush out the data success default representative has completed
        if (future.isSuccess()) {
          ctx.channel().read();
        } else {
          future.channel().close();
        }
      }
    });
  }
}
