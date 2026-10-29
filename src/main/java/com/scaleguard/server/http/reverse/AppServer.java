package com.scaleguard.server.http.reverse;
import com.scaleguard.server.certificates.CertificateRenewalWatcher;
import com.scaleguard.server.concurrent.VirtualThreads;
import com.scaleguard.server.db.ConnectionUtil;
import com.scaleguard.server.dns.DnsServer;
import com.scaleguard.server.http.router.ConfigManager;
import com.scaleguard.server.http.router.RouteTable;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.server.kafka.KafkaEventsConsumer;
import com.scaleguard.server.ssh.SshServerWithTunnelListener;
import com.scaleguard.server.system.SystemAdapter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.shredzone.acme4j.AcmeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class AppServer implements Server{
    private static final int HTTP_PORT = Integer.parseInt(System.getProperty("port", "80"));
    private static final int HTTPS_PORT = Integer.parseInt(System.getProperty("sport", "443"));
    private static final Logger logger
            = LoggerFactory.getLogger(AppServer.class);
    HashSet<String> ports = new HashSet<>();

    // ── Native transport detection ────────────────────────────────────────────
    // Epoll (Linux) > KQueue (macOS) > NIO (fallback).
    // Native transports eliminate JNI-free NIO overhead and allow SO_REUSEPORT
    // which lets each worker thread own its own accept socket, removing the
    // single-threaded accept-mutex that caps multi-core throughput.
    private static final boolean USE_EPOLL  = Epoll.isAvailable();
    private static final boolean USE_KQUEUE = !USE_EPOLL && KQueue.isAvailable();

    private static final int CPU = Runtime.getRuntime().availableProcessors();

    // ── Write-buffer watermarks ───────────────────────────────────────────────
    // When the outbound buffer exceeds HIGH, Netty pauses reads on the
    // inbound channel (back-pressure).  When it drains below LOW, reads resume.
    // Prevents a slow backend from filling unbounded memory with un-sent bytes.
    private static final WriteBufferWaterMark WRITE_WATERMARK =
            new WriteBufferWaterMark(32 * 1024, 64 * 1024);  // 32 KB low / 64 KB high

    EventLoopGroup bossGroup   = createBossGroup();
    EventLoopGroup workerGroup = createWorkerGroup();

    EventLoopGroup tcpbossGroup   = createBossGroup();
    EventLoopGroup tcpworkerGroup = createWorkerGroup();
    ArrayList<ChannelFuture> futureChannels = new ArrayList<>();

    private static EventLoopGroup createBossGroup() {
        // 1 boss thread is optimal — it only accepts connections.
        if (USE_EPOLL)  return new EpollEventLoopGroup(1);
        if (USE_KQUEUE) return new KQueueEventLoopGroup(1);
        return new NioEventLoopGroup(1);
    }

    private static EventLoopGroup createWorkerGroup() {
        // Workers: 2 × CPU cores handles hundreds of thousands of concurrent channels.
        if (USE_EPOLL)  return new EpollEventLoopGroup(CPU * 2);
        if (USE_KQUEUE) return new KQueueEventLoopGroup(CPU * 2);
        return new NioEventLoopGroup(CPU * 2);
    }

    private static Class<?> serverChannelClass() {
        if (USE_EPOLL)  return EpollServerSocketChannel.class;
        if (USE_KQUEUE) return KQueueServerSocketChannel.class;
        return NioServerSocketChannel.class;
    }

    public static void main(String[] args) throws Exception {
        // PARANOID mode samples every ByteBuf allocation (full stack trace) — never use in production.
        // SIMPLE (1-in-128 sample) is sufficient to catch leaks without hurting throughput.
        // Override by passing -Dio.netty.leakDetection.level=ADVANCED / PARANOID explicitly.
        if (System.getProperty("io.netty.leakDetection.level") == null) {
            System.setProperty("io.netty.leakDetection.level", "SIMPLE");
        }
        ConnectionUtil.checkDBProperties();

        // Support environment variables as fallback for admin credentials
        String adminUser = System.getProperty("adminUser", System.getenv("SCALEGUARD_ADMIN_USER"));
        String adminPassword = System.getProperty("adminPassword", System.getenv("SCALEGUARD_ADMIN_PASSWORD"));

        if (adminUser == null || adminUser.isEmpty() || adminPassword == null || adminPassword.isEmpty()) {
            logger.warn("⚠️  Admin credentials not configured! Set -DadminUser/-DadminPassword or SCALEGUARD_ADMIN_USER/SCALEGUARD_ADMIN_PASSWORD environment variables.");
            logger.warn("⚠️  The server will start but admin API will not be accessible.");
        } else {
            // Re-set as system properties so downstream code picks them up
            System.setProperty("adminUser", adminUser);
            System.setProperty("adminPassword", adminPassword);
        }

        Map<String, String> parameters = new HashMap<>();

        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                String[] parts = arg.substring(2).split("=", 2);
                if (parts.length == 2) {
                    parameters.put(parts[0], parts[1]);
                }
            }
        }

        // Print parsed key-value pairs
        parameters.forEach((key, value) -> {
            if (!"password".equalsIgnoreCase(key) && !"adminPassword".equalsIgnoreCase(key)) {
                logger.info("{} = {}", key, value);
            }
        });

        AppServer server= new AppServer();
        EventSubscriber subscriber = new EventSubscriber(server);
        ConfigManager.getPublisher().subscribe(subscriber);
        AcmeUtils.getContext();
        // Use a virtual thread for blocking hostname/DNS probing at startup
        VirtualThreads.submit("sg-hostname-probe", () -> {
            if(parameters.containsKey("hostname")){
                String hostname= parameters.get("hostname");
                while (true) {
                    try {
                        SystemAdapter.configure(hostname);
                        break;
                    }catch (Exception e){
                        logger.error("Failed to configure system host : "+e.getMessage());
                        logger.error("The system will not start until the DNS is mapped to the ScaleGuard server hostname-> : "+hostname);
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(ex);
                        }
                    }
                }
            }
        });
        server.start();
        server.listen();
    }

    private ChannelFuture startHttpSSL(SourceSystem s, EventLoopGroup bossGroup, EventLoopGroup workerGroup) throws Exception {
        RouteTable.getInstance();
        int port = Integer.parseInt(s.getPort());

        SSLServerInitializer server = new SSLServerInitializer(port);
        try {
            return newBootstrap(bossGroup, workerGroup, server)
                    .bind(port).sync();
        } catch (Exception e) {
            return null;
        }
    }

    private ChannelFuture startHttp(SourceSystem s, EventLoopGroup bossGroup, EventLoopGroup workerGroup) throws Exception {
        int port = Integer.parseInt(s.getPort());
        ServerInitializer server = new ServerInitializer(port);
        try {
            return newBootstrap(bossGroup, workerGroup, server)
                    .bind(port).sync();
        } catch (Exception e) {
            return null;
        }
    }

    private ChannelFuture startTcp(SourceSystem s, EventLoopGroup bossGroup, EventLoopGroup workerGroup) throws Exception {
        int port = Integer.parseInt(s.getPort());
        TCPServerInitializer server = new TCPServerInitializer(s.getId(), port);
        try {
            return newBootstrap(bossGroup, workerGroup, server)
                    .bind(port).sync();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Shared bootstrap factory — all server sockets use identical high-performance options:
     *
     * <ul>
     *   <li><b>Native transport</b>: Epoll (Linux) or KQueue (macOS) when available.
     *       These replace the JVM NIO selector with an OS-level event queue, cutting
     *       syscall overhead and enabling SO_REUSEPORT.</li>
     *   <li><b>SO_REUSEPORT</b> (Epoll only): each worker binds its own accept socket so
     *       the Linux kernel load-balances new connections across cores — no single-threaded
     *       accept mutex, full multi-core accept throughput.</li>
     *   <li><b>PooledByteBufAllocator</b>: reuses heap/direct buffers instead of allocating
     *       new ones per request — drastically reduces GC pressure at high RPS.</li>
     *   <li><b>WriteBufferWaterMark</b>: pauses reads when the outbound queue exceeds the
     *       HIGH watermark and resumes at LOW — prevents slow backends from exhausting heap.</li>
     *   <li><b>SO_BACKLOG 65535</b>: maximum OS queue depth for pending accepts.</li>
     *   <li><b>TCP_NODELAY</b>: disables Nagle's algorithm — critical for low-latency proxying.</li>
     * </ul>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ServerBootstrap newBootstrap(EventLoopGroup boss, EventLoopGroup worker,
                                         io.netty.channel.ChannelHandler childHandler) {
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
         .channel((Class) serverChannelClass())
         .option(ChannelOption.SO_BACKLOG, 65535)
         .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
         .childOption(ChannelOption.SO_KEEPALIVE, true)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .childOption(ChannelOption.AUTO_READ, false)
         .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
         .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WRITE_WATERMARK)
         .handler(new LoggingHandler(LogLevel.INFO))
         .childHandler(childHandler);

        // SO_REUSEPORT: Linux-only — lets each I/O thread have its own accept socket.
        // The kernel distributes incoming connections across sockets → full CPU scaling.
        if (USE_EPOLL) {
            try {
                // Raw cast needed: EpollChannelOption<Boolean> → ChannelOption<Boolean>
                b.option((ChannelOption<Boolean>) (ChannelOption)
                         io.netty.channel.epoll.EpollChannelOption.SO_REUSEPORT, Boolean.TRUE);
                logger.info("[Netty] SO_REUSEPORT enabled (Epoll/Linux)");
            } catch (Exception ignored) {
                // EpollChannelOption not available in this build — skip silently
            }
        }

        String transport = USE_EPOLL ? "Epoll" : USE_KQUEUE ? "KQueue" : "NIO";
        logger.info("[Netty] Transport: {} | PooledAllocator | WriteWatermark 32K/64K", transport);
        return b;
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

    private boolean isDnsOn=false;
    public void start() throws Exception {
        try {
            if(isDnsOn) {
                DnsServer dnsServer = new DnsServer();
                dnsServer.start();
            }
            try {
                SshServerWithTunnelListener.startServer();
                logger.info("ssh server started");

            }catch (Exception e){
                logger.error("Failed to start SSH server", e);
            }

            // Start certificate auto-renewal watcher
            try {
                CertificateRenewalWatcher.getInstance().start();
            } catch (Exception e) {
                logger.error("Failed to start certificate renewal watcher", e);
            }
            String httpPort=String.valueOf(HTTP_PORT);
            String httpsPort=String.valueOf(HTTPS_PORT);
            if(!ports.contains(httpPort)){
                SourceSystem temp = new SourceSystem();
                temp.setPort(httpPort);
                logger.info("Starting scaleguard base http port on {}",HTTP_PORT);
                futureChannels.add(startHttp(temp, bossGroup, workerGroup));
                ports.add(httpPort);
            }

            if(!ports.contains(httpsPort)){
                SourceSystem temp = new SourceSystem();
                temp.setPort(httpsPort);
                logger.info("Starting scaleguard base http port on {}",HTTPS_PORT);
                futureChannels.add(startHttpSSL(temp, bossGroup, workerGroup));
                ports.add(httpsPort);
            }

            RouteTable.getInstance().getSourceSystsems().forEach(s -> {
                try {
                    addListener(s);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

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