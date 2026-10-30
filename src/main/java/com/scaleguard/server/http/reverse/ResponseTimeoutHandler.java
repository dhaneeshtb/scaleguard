package com.scaleguard.server.http.reverse;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * proxy_read_timeout: fires {@link #TIMEOUT} when a backend that owes a response sends nothing for the configured time.
 *
 * <p>Hot-path cost is kept to a counter increment: reads and writes don't look at the clock. A timer ticks every quarter
 * of the timeout while a response is owed; four ticks in a row without activity mean the backend has been silent for
 * at least the timeout (at most 1.25× it). The timer is left running between keep-alive requests instead of being
 * cancelled and re-created for each one.
 */
final class ResponseTimeoutHandler extends ChannelDuplexHandler {

    static final Object TIMEOUT = new Object() {
        @Override
        public String toString() {
            return "upstream response timeout";
        }
    };

    private static final int TICKS = 4;
    private final long tickNanos;
    private int outstanding;
    private long activity;          // bumped on every read and request write
    private long seenActivity = -1;
    private int idleTicks;
    private ScheduledFuture<?> timer;

    int outstanding() {
        return outstanding;
    }

    ResponseTimeoutHandler(long timeoutMillis) {
        this.tickNanos = Math.max(1, TimeUnit.MILLISECONDS.toNanos(timeoutMillis) / TICKS);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpRequest) {
            outstanding++;
            activity++;
            if (timer == null) schedule(ctx);
        }
        ctx.write(msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        activity++;
        if (msg instanceof LastHttpContent && outstanding > 0) outstanding--;
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

    private void schedule(ChannelHandlerContext ctx) {
        seenActivity = activity;
        idleTicks = 0;
        timer = ctx.executor().schedule(() -> tick(ctx), tickNanos, TimeUnit.NANOSECONDS);
    }

    private void tick(ChannelHandlerContext ctx) {
        timer = null;
        if (outstanding == 0 || !ctx.channel().isActive()) return;   // nothing owed: stop until the next request
        if (activity != seenActivity) {
            seenActivity = activity;
            idleTicks = 0;
        } else if (++idleTicks >= TICKS) {
            outstanding = 0;
            ctx.fireUserEventTriggered(TIMEOUT);
            return;
        }
        timer = ctx.executor().schedule(() -> tick(ctx), tickNanos, TimeUnit.NANOSECONDS);
    }

    private void cancel() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }
}
