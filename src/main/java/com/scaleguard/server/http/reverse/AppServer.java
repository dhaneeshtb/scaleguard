package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.router.RouteTable;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class AppServer {
  private static final int HTTP_PORT = Integer.parseInt(System.getProperty("port","8080"));
  public static void main(String[] args) throws Exception {
    new AppServer().run();
  }
  public void run() throws Exception {
    RouteTable.getInstance();
    SecureProxyInitializer.createSSLContext();
    EventLoopGroup bossGroup = new NioEventLoopGroup(20);
    EventLoopGroup workerGroup = new NioEventLoopGroup(20);
    ServerInitializer server=new ServerInitializer();
    try {
      ServerBootstrap httpBootstrap = new ServerBootstrap();
      httpBootstrap.group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .option(ChannelOption.SO_BACKLOG, 2048)
          .childOption(ChannelOption.SO_KEEPALIVE, true)
          .handler(new LoggingHandler(LogLevel.INFO))
          .childHandler(server)
          .childOption(ChannelOption.AUTO_READ, false)
          .bind(HTTP_PORT).sync().channel().closeFuture().sync();
      ChannelFuture httpChannel = httpBootstrap.bind(HTTP_PORT).sync();
      httpChannel.channel().closeFuture().sync();
    } finally {
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
    }
  }

}