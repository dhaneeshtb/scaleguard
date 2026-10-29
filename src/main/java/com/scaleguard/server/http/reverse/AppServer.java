package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.utils.NettyTransport;import com.scaleguard.server.certificates.CertificateRenewalWatcher;
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

    // ── Transport ─────────────────────────────────────────────────────────────
    // Chosen once in NettyTransport (Netty 4.2): io_uring (opt-in) / epoll / kqueue / NIO.
    // On epoll and io_uring each listening port is bound once per acceptor thread with
    // SO_REUSEPORT, so the kernel spreads new connections across several accept sockets.
    private static final int ACCEPTORS = NettyTransport.supportsReusePort()
            ? Math.max(1, Integer.getInteger("scaleguard.acceptors", Math.min(4, Runtime.getRuntime().availableProcessors())))
            : 1;

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
        // Accept threads only: one per SO_REUSEPORT acceptor socket.
        return NettyTransport.newGroup(ACCEPTORS, "sg-accept");
    }

    private static EventLoopGroup createWorkerGroup() {
        // Workers: 2 × CPU cores handles hundreds of thousands of concurrent channels.
        return NettyTransport.newGroup(CPU * 2, "sg-io");
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
                long backoffMs = 10_000;
                while (true) {
                    try {
                        SystemAdapter.configure(hostname);
                        break;
                    }catch (Exception e){
                        logger.error("Failed to configure system host : "+e.getMessage());
                        logger.error("The system will not start until the DNS is mapped to the ScaleGuard server hostname-> : "+hostname+" (retrying in "+(backoffMs/1000)+"s)");
                        try {
                            Thread.sleep(backoffMs);
                            backoffMs = Math.min(backoffMs * 2, 300_000); // back off up to 5 minutes
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
            return bindAll(newBootstrap(bossGroup, workerGroup, server), port);
        } catch (Exception e) {
            return null;
        }
    }

    private ChannelFuture startHttp(SourceSystem s, EventLoopGroup bossGroup, EventLoopGroup workerGroup) throws Exception {
        int port = Integer.parseInt(s.getPort());
        ServerInitializer server = new ServerInitializer(port);
        try {
            return bindAll(newBootstrap(bossGroup, workerGroup, server), port);
        } catch (Exception e) {
            return null;
        }
    }

    private ChannelFuture startTcp(SourceSystem s, EventLoopGroup bossGroup, EventLoopGroup workerGroup) throws Exception {
        int port = Integer.parseInt(s.getPort());
        TCPServerInitializer server = new TCPServerInitializer(s.getId(), port);
        try {
            return bindAll(newBootstrap(bossGroup, workerGroup, server), port);
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
         .channel(NettyTransport.serverChannel())
         .option(ChannelOption.SO_BACKLOG, 65535)
         .option(ChannelOption.SO_REUSEADDR, true)
         .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
         .childOption(ChannelOption.SO_KEEPALIVE, true)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .childOption(ChannelOption.AUTO_READ, false)
         .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
         .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WRITE_WATERMARK)
         // DEBUG, not INFO: at INFO this logged every accepted connection.
         .handler(new LoggingHandler(LogLevel.DEBUG))
         .childHandler(childHandler);

        if (NettyTransport.supportsReusePort()) {
            b.option(NettyTransport.reusePortOption(), Boolean.TRUE);
        }
        if (!transportLogged) {
            transportLogged = true;
            logger.info("[Netty 4.2] Transport: {} | {} acceptor socket(s) per port{} | {} I/O threads | PooledAllocator | WriteWatermark 32K/64K",
                    NettyTransport.describe(), ACCEPTORS, ACCEPTORS > 1 ? " (SO_REUSEPORT)" : "", CPU * 2);
        }
        return b;
    }

    private static volatile boolean transportLogged;

    /**
     * Binds the port once per acceptor. With SO_REUSEPORT the kernel load-balances incoming
     * connections across the sockets; the extra listeners are tracked so stop() closes them too.
     */
    private ChannelFuture bindAll(ServerBootstrap b, int port) throws InterruptedException {
        ChannelFuture first = b.bind(port).sync();
        for (int i = 1; i < ACCEPTORS; i++) {
            futureChannels.add(b.bind(port).sync());
        }
        return first;
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
            // Enable with dnsServer=true (application.properties, env var or -DdnsServer=true).
            if(DnsServer.isEnabled()) {
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