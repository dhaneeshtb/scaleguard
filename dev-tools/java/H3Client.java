import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.*;
import io.netty.handler.codec.quic.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Minimal HTTP/3 client for testing ScaleGuard's QUIC listener (Netty's HTTP/3 codec, certificates not verified).
 * One request prints the status, headers and body; several print throughput.
 *
 *   javac -d /tmp/sgdev -cp $SG_JAR java/H3Client.java
 *   java -cp /tmp/sgdev:$SG_JAR H3Client localhost 8443 /            # one request
 *   java -cp /tmp/sgdev:$SG_JAR H3Client localhost 8443 / 5000 50    # 5000 requests, 50 concurrent streams
 *   java -cp /tmp/sgdev:$SG_JAR H3Client 127.0.0.1 8443 / 1 1 api.example.com:8443   # :authority (route host)
 *
 * Usage: H3Client host port path [requests] [concurrency] [authority]
 */
public class H3Client {
    public static void main(String[] a) throws Exception {
        String host = a[0]; int port = Integer.parseInt(a[1]); String path = a[2];
        int n = a.length > 3 ? Integer.parseInt(a[3]) : 1; int conc = a.length > 4 ? Integer.parseInt(a[4]) : 1;
        String authority = a.length > 5 ? a[5] : host + ":" + port;
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        QuicSslContext ctx = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();
        ChannelHandler codec = Http3.newQuicClientCodecBuilder().sslEngineProvider(q -> ctx.newEngine(q.alloc(), host, port)).maxIdleTimeout(10, TimeUnit.SECONDS)
                .initialMaxData(64 << 20).initialMaxStreamDataBidirectionalLocal(4 << 20).build();
        Channel ch = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(codec).bind(0).sync().channel();
        long t0 = System.nanoTime();
        QuicChannel quic = QuicChannel.newBootstrap(ch).handler(new Http3ClientConnectionHandler())
                .remoteAddress(new InetSocketAddress(host, port)).connect().get();
        System.out.printf("connected in %.1f ms%n", (System.nanoTime() - t0) / 1e6);
        AtomicInteger ok = new AtomicInteger(), bad = new AtomicInteger(); AtomicLong bytes = new AtomicLong();
        Semaphore sem = new Semaphore(conc); CountDownLatch done = new CountDownLatch(n);
        boolean verbose = n == 1;
        long start = System.nanoTime();
        java.util.Map<Long, int[]> inflight = new java.util.concurrent.ConcurrentHashMap<>();
        for (int i = 0; i < n; i++) {
            sem.acquire();
            QuicStreamChannel stream = Http3.newRequestStream(quic, new Http3RequestStreamInboundHandler() {
                int status;
                @Override protected void channelRead(ChannelHandlerContext c, Http3HeadersFrame f) {
                    status = Integer.parseInt(f.headers().status().toString());
                    int[] st = inflight.get(((QuicStreamChannel) c.channel()).streamId()); if (st != null) st[0] = status;
                    if (verbose) { System.out.println("status " + status); f.headers().forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue())); }
                    ReferenceCountUtil.release(f);
                }
                @Override protected void channelRead(ChannelHandlerContext c, Http3DataFrame f) {
                    bytes.addAndGet(f.content().readableBytes());
                    int[] st = inflight.get(((QuicStreamChannel) c.channel()).streamId()); if (st != null) st[1] += f.content().readableBytes();
                    if (verbose) System.out.print(f.content().toString(CharsetUtil.UTF_8));
                    ReferenceCountUtil.release(f);
                }
                @Override public void exceptionCaught(ChannelHandlerContext c, Throwable t) {
                    System.out.println("stream error: " + t); bad.incrementAndGet(); c.close(); sem.release(); done.countDown();
                }
                @Override public void userEventTriggered(ChannelHandlerContext c, Object e) {
                    if (verbose) System.out.println("event " + e);
                    try { super.userEventTriggered(c, e); } catch (Exception ex) { throw new RuntimeException(ex); }
                }
                @Override public void channelInactive(ChannelHandlerContext c) throws Exception {
                    if (status == 0) { System.out.println("stream closed before a response, id=" + ((QuicStreamChannel) c.channel()).streamId()); }
                    super.channelInactive(c);
                }
                @Override protected void channelInputClosed(ChannelHandlerContext c) {
                    inflight.remove(((QuicStreamChannel) c.channel()).streamId());
                    if (status == 200) ok.incrementAndGet(); else bad.incrementAndGet();
                    c.close(); sem.release(); done.countDown();
                }
            }).sync().getNow();
            inflight.put(stream.streamId(), new int[2]);
            Http3HeadersFrame req = new DefaultHttp3HeadersFrame();
            req.headers().method("GET").path(path).authority(authority).scheme("https").add("user-agent", "h3client");
            stream.writeAndFlush(req).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }
        if (!done.await(15, TimeUnit.SECONDS)) { System.out.println("STUCK: ok=" + ok + " bad=" + bad + " outstanding=" + done.getCount());
            inflight.forEach((id, st) -> System.out.println("  stream " + id + " status=" + st[0] + " bytes=" + st[1])); System.exit(1); }
        double s = (System.nanoTime() - start) / 1e9;
        if (!verbose) System.out.printf("%d ok, %d failed, %.0f req/s, %.1f MB/s%n", ok.get(), bad.get(), ok.get() / s, bytes.get() / s / 1e6);
        quic.close().sync(); ch.close().sync(); group.shutdownGracefully();
    }
}
