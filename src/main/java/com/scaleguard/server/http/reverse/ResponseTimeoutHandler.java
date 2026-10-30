package com.scaleguard.server.http.reverse;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * nginx's {@code proxy_read_timeout}: if a backend that owes a response sends nothing for {@code timeoutMillis},
 * fires {@link #TIMEOUT} so the backend handler can answer 504. The clock restarts on every byte the backend sends,
 * so slow but steady responses are fine; idle keep-alive connections with nothing outstanding never time out here.
 */
final class ResponseTimeoutHandler extends ChannelDuplexHandler {

    /** User event fired on the backend channel when the response is overdue. */
    static final Object TIMEOUT = new Object() {
        @Override
        public String toString() {
            return "upstream response timeout";
        }
    };

    private final long timeoutNanos;
    private int outstanding;
    private long lastActivity;
    private ScheduledFuture<?> timer;

    /** Requests sent on this connection that haven't had a complete response yet. */
    int outstanding() {
        return outstanding;
    }

    ResponseTimeoutHandler(long timeoutMillis) {
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpRequest) {
            outstanding++;
            lastActivity = System.nanoTime();
            if (timer == null) schedule(ctx, timeoutNanos);
        }
        ctx.write(msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        lastActivity = System.nanoTime();
        if (msg instanceof LastHttpContent && outstanding > 0) {
            outstanding--;
            if (outstanding == 0) cancel();
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancel();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancel();
    }

    private void schedule(ChannelHandlerContext ctx, long delayNanos) {
        timer = ctx.executor().schedule(() -> {
            timer = null;
            if (outstanding == 0 || !ctx.channel().isActive()) return;
            long idle = System.nanoTime() - lastActivity;
            if (idle >= timeoutNanos) {
                outstanding = 0;
                ctx.fireUserEventTriggered(TIMEOUT);
            } else {
                schedule(ctx, timeoutNanos - idle);
            }
        }, delayNanos, TimeUnit.NANOSECONDS);
    }

    private void cancel() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }
}
