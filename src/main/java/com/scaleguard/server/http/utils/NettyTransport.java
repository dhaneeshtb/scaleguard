package com.scaleguard.server.http.utils;

import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringDatagramChannel;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.channel.uring.IoUringSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * One place that picks the fastest Netty 4.2 transport available on this machine, so every server,
 * proxy connection and client uses matching event loops and channel types.
 *
 * <p>Order with {@code scaleguard.transport=auto} (the default): io_uring on Linux kernels that support it,
 * then epoll, kqueue on macOS/BSD, otherwise NIO. Netty probes io_uring at startup, so kernels or container
 * seccomp profiles that don't allow it fall back to epoll automatically. In ScaleGuard's proxy benchmark
 * io_uring served ~45% more requests per second than epoll with lower latency.
 * Force a transport with {@code -Dscaleguard.transport=io_uring|epoll|kqueue|nio}; if it isn't
 * available, the best available one is used and a warning is logged.
 *
 * <p>Channels must run on an event loop of the same transport — mixing e.g. NioSocketChannel with an
 * epoll loop fails with "incompatible event loop type". Always use the classes from here.
 */
public final class NettyTransport {

    private static final Logger LOG = LoggerFactory.getLogger(NettyTransport.class);

    public enum Kind { IO_URING, EPOLL, KQUEUE, NIO }

    public static final Kind KIND = select();

    private NettyTransport() {}

    private static Kind select() {
        String want = System.getProperty("scaleguard.transport");
        if (want == null || want.isBlank()) want = AppProperties.get("scaleguard.transport");
        want = want == null || want.isBlank() ? "auto" : want.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        Kind k = switch (want) {
            case "io_uring", "iouring", "uring" -> available(Kind.IO_URING) ? Kind.IO_URING : fallback("io_uring", IoUring.unavailabilityCause());
            case "epoll" -> available(Kind.EPOLL) ? Kind.EPOLL : fallback("epoll", Epoll.unavailabilityCause());
            case "kqueue" -> available(Kind.KQUEUE) ? Kind.KQUEUE : fallback("kqueue", KQueue.unavailabilityCause());
            case "nio" -> Kind.NIO;
            default -> best();
        };
        LOG.info("[Netty] Transport: {} (requested: {})", k, want);
        return k;
    }

    private static boolean available(Kind k) {
        try {
            return switch (k) {
                case IO_URING -> IoUring.isAvailable();
                case EPOLL -> Epoll.isAvailable();
                case KQUEUE -> KQueue.isAvailable();
                case NIO -> true;
            };
        } catch (Throwable t) { // native library missing for this OS/arch
            return false;
        }
    }

    private static Kind best() {
        if (available(Kind.IO_URING)) return Kind.IO_URING;
        if (available(Kind.EPOLL)) return Kind.EPOLL;
        if (available(Kind.KQUEUE)) return Kind.KQUEUE;
        return Kind.NIO;
    }

    private static Kind fallback(String requested, Throwable cause) {
        Kind k = best();
        LOG.warn("[Netty] {} transport unavailable ({}); using {}", requested, cause == null ? "unknown reason" : cause.getMessage(), k);
        return k;
    }

    public static IoHandlerFactory ioHandlerFactory() {
        return switch (KIND) {
            case IO_URING -> IoUringIoHandler.newFactory();
            case EPOLL -> EpollIoHandler.newFactory();
            case KQUEUE -> KQueueIoHandler.newFactory();
            case NIO -> NioIoHandler.newFactory();
        };
    }

    /** A new event loop group for the selected transport. {@code threads <= 0} means Netty's default (2 × cores). */
    public static EventLoopGroup newGroup(int threads, String name) {
        DefaultThreadFactory tf = new DefaultThreadFactory(name, true);
        return threads > 0
                ? new MultiThreadIoEventLoopGroup(threads, tf, ioHandlerFactory())
                : new MultiThreadIoEventLoopGroup(0, tf, ioHandlerFactory());
    }

    public static Class<? extends ServerChannel> serverChannel() {
        return switch (KIND) {
            case IO_URING -> IoUringServerSocketChannel.class;
            case EPOLL -> EpollServerSocketChannel.class;
            case KQUEUE -> KQueueServerSocketChannel.class;
            case NIO -> NioServerSocketChannel.class;
        };
    }

    public static Class<? extends SocketChannel> socketChannel() {
        return switch (KIND) {
            case IO_URING -> IoUringSocketChannel.class;
            case EPOLL -> EpollSocketChannel.class;
            case KQUEUE -> KQueueSocketChannel.class;
            case NIO -> NioSocketChannel.class;
        };
    }

    public static Class<? extends DatagramChannel> datagramChannel() {
        return switch (KIND) {
            case IO_URING -> IoUringDatagramChannel.class;
            case EPOLL -> EpollDatagramChannel.class;
            case KQUEUE -> KQueueDatagramChannel.class;
            case NIO -> NioDatagramChannel.class;
        };
    }

    /** SO_REUSEPORT lets several sockets bind the same port so the kernel spreads load across cores (Linux epoll/io_uring). */
    public static boolean supportsReusePort() {
        return KIND == Kind.EPOLL || KIND == Kind.IO_URING;
    }

    public static ChannelOption<Boolean> reusePortOption() {
        return io.netty.channel.unix.UnixChannelOption.SO_REUSEPORT;
    }

    public static String describe() {
        return KIND.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
