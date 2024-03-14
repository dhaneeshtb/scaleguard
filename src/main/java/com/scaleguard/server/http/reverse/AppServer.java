package com.scaleguard.server.http.reverse;
import com.scaleguard.server.http.dns.tcp.DnsServer;
import com.scaleguard.server.http.router.ConfigManager;
import com.scaleguard.server.http.router.RouteTable;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.server.kafka.KafkaEventsConsumer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;

public class AppServer implements Server{
    private static final int HTTP_PORT = Integer.parseInt(System.getProperty("port", "80"));
    private static final int HTTPS_PORT = Integer.parseInt(System.getProperty("sport", "443"));
    private static final Logger logger
            = LoggerFactory.getLogger(AppServer.class);
    HashSet<String> ports = new HashSet<>();

    EventLoopGroup bossGroup = new NioEventLoopGroup(20);
    EventLoopGroup workerGroup = new NioEventLoopGroup(20);

    EventLoopGroup tcpbossGroup = new NioEventLoopGroup(20);
    EventLoopGroup tcpworkerGroup = new NioEventLoopGroup(20);
    ArrayList<ChannelFuture> futureChannels = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        AppServer server= new AppServer();
        EventSubscriber subscriber = new EventSubscriber(server);
        ConfigManager.getPublisher().subscribe(subscriber);

        server.start();
        server.listen();
    }

    private ChannelFuture startHttpSSL(SourceSystem s, EventLoopGroup bossGroup, EventLoopGroup workerGroup) throws Exception {
        RouteTable.getInstance();
        int port = Integer.parseInt(s.getPort());

        SSLServerInitializer server = new SSLServerInitializer(port);
        try {
            ServerBootstrap httpBootstrap = new ServerBootstrap();
            return httpBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 2048)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(server)
                    .childOption(ChannelOption.AUTO_READ, false)
                    .bind(port).sync();
        } catch (Exception e) {
            return null;
        }
    }

    private ChannelFuture startHttp(SourceSystem s, EventLoopGroup bossGroup, EventLoopGroup workerGroup) throws Exception {
        int port = Integer.parseInt(s.getPort());
        ServerInitializer server = new ServerInitializer(port);
        try {

            ServerBootstrap httpBootstrap = new ServerBootstrap();
            return httpBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 2048)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(server)
                    .childOption(ChannelOption.AUTO_READ, false)
                    .bind(port).sync();
        } catch (Exception e) {
            return null;
        }
    }

    private ChannelFuture startTcp(SourceSystem s, EventLoopGroup bossGroup, EventLoopGroup workerGroup) throws Exception {
        int port = Integer.parseInt(s.getPort());
        TCPServerInitializer server = new TCPServerInitializer(s.getId(),port);
        try {
            ServerBootstrap httpBootstrap = new ServerBootstrap();
            return httpBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 2048)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(server)
                    .childOption(ChannelOption.AUTO_READ, false)
                    .bind(port).sync();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void listen() {
        logger.info("Started listening server");
        futureChannels.forEach(ch -> {
                try {
                    ch.sync().channel().closeFuture().sync();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
        });

    }

    public void start() throws Exception {
        try {

            DnsServer dnsServer = new DnsServer();
            dnsServer.start();

            RouteTable.getInstance().getSourceSystsems().forEach(s -> {
                try {
                    addListener(s);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });


            if(!ports.contains(String.valueOf(HTTP_PORT))){
                SourceSystem temp = new SourceSystem();
                temp.setPort(String.valueOf(HTTP_PORT));
                logger.info("Starting scaleguard base http port on {}",HTTP_PORT);
                futureChannels.add(startHttp(temp, bossGroup, workerGroup));
            }

            if(!ports.contains(String.valueOf(HTTPS_PORT))){
                SourceSystem temp = new SourceSystem();
                temp.setPort(String.valueOf(HTTPS_PORT));
                logger.info("Starting scaleguard base http port on {}",HTTPS_PORT);
                futureChannels.add(startHttpSSL(temp, bossGroup, workerGroup));
            }

        }catch (Exception e){
            logger.error("Failed to start servers",e);
        }
    }
    public void addListener(SourceSystem s) throws Exception {
        if(!ports.contains(s.getPort())) {
            logger.info("Starting scaleguard source http host {} port on {}",s.getHost(),s.getPort());
            switch (s.getScheme()) {
                case "https":
                    try {
                        futureChannels.add(startHttpSSL(s, bossGroup, workerGroup));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    break;
                case "http":
                    try {
                        futureChannels.add(startHttp(s, bossGroup, workerGroup));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    break;
                case "kafka":
                    try {
                        KafkaEventsConsumer kec = new KafkaEventsConsumer(s);
                        kec.onApplicationReady();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    break;
                default:
                    try {
                        futureChannels.add(startTcp(s, tcpbossGroup, tcpworkerGroup));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    break;

            }
            ports.add(s.getPort());
        }
    }

    public void stop(){
        try{
            futureChannels.forEach(channel-> {
                try {
                    channel.channel().closeFuture().sync();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }catch (Exception e){
            logger.error("Failed to close channel",e);

        }finally {
                workerGroup.shutdownGracefully();
                bossGroup.shutdownGracefully();
        }
    }


}