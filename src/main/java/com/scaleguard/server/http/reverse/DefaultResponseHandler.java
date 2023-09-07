package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.router.SourceSystem;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors;

public class DefaultResponseHandler {

  private static String version=null;

  public void handle(final ChannelHandlerContext ctx, SourceSystem sourceSystem, Object msg){
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      if (request.uri().equalsIgnoreCase("/health")) {
        handleHealth(ctx,sourceSystem);
        return;
      }else if (request.uri().equalsIgnoreCase("/info")) {
        handleInfo(ctx,sourceSystem);
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

  public void handleInfo(final ChannelHandlerContext ctx, SourceSystem sourceSystem){
    if(version==null){
      String content = "{}";
      try {
        content = Files.lines(Paths.get("version.json"))
                .collect(Collectors.joining(System.lineSeparator()));
      } catch (IOException e) {
        e.printStackTrace();
      }
      version=content;
    }
    ByteBuf content = Unpooled.copiedBuffer(version, CharsetUtil.UTF_8);
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
