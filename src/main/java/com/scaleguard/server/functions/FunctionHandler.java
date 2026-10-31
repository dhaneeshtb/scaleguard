package com.scaleguard.server.functions;

import com.scaleguard.server.analytics.AccessRecord;
import com.scaleguard.server.http.router.RouteTarget;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The function target in the request pipeline: by the time a request gets here the route has already passed IP lists,
 * rate limiting, the WAF and client/OAuth authentication, exactly as for a proxied route. Replaces "connect to a host group".
 */
public final class FunctionHandler {

    private FunctionHandler() {}

    public static void handle(ChannelHandlerContext ctx, Object msg, RouteTarget ts, AccessRecord rec, String clientIp,
                              Map<String, String> extraHeaders, com.scaleguard.server.http.cache.CachedResponse cache) {
        if (!(msg instanceof FullHttpRequest req)) {
            write(ctx, rec, error(400, "bad_request"), true, extraHeaders, false, null, null);
            return;
        }
        final boolean keepAlive = HttpUtil.isKeepAlive(req);
        final boolean head = req.method().name().equals("HEAD");
        FunctionRuntime rt = FunctionRuntime.get();
        String id = ts.getTargetSystem().getHost();
        FunctionDef d = rt.find(id);
        if (d != null && rec != null) rec.backend = "function:" + d.name;
        int size = req.content().readableBytes();
        if (d != null && size > d.maxRequestBytes) {
            write(ctx, rec, error(413, "request_too_large"), keepAlive, extraHeaders, head, null, null);
            return;
        }
        byte[] body = new byte[size];
        req.content().getBytes(req.content().readerIndex(), body);
        String uri = req.uri();
        int q = uri.indexOf('?');
        FunctionRuntime.Request fr = new FunctionRuntime.Request(req.method().name(), q < 0 ? uri : uri.substring(0, q), q < 0 ? "" : uri.substring(q + 1),
                clientIp, req.headers().get(HttpHeaderNames.HOST), FunctionRuntime.headersOf(req.headers()), body);
        rt.invoke(id, fr).whenComplete((r, e) -> ctx.executor().execute(() ->
                write(ctx, rec, r != null ? r : error(500, "error"), keepAlive, extraHeaders, head, cache, ts.getTargetSystem())));
    }

    private static FunctionRuntime.Result error(int status, String code) {
        FunctionRuntime.Result r = new FunctionRuntime.Result();
        r.status = status;
        r.error = code;
        r.headers.put("Content-Type", "application/json");
        r.body = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return r;
    }

    /** Only a clean 2xx the function itself did not mark private / uncacheable is stored. */
    static boolean cacheable(FunctionRuntime.Result r) {
        if (r.error != null || r.status < 200 || r.status >= 300) return false;
        for (Map.Entry<String, String> h : r.headers.entrySet()) {
            String k = h.getKey().toLowerCase(java.util.Locale.ROOT), v = String.valueOf(h.getValue()).toLowerCase(java.util.Locale.ROOT);
            if (k.equals("set-cookie")) return false;
            if (k.equals("cache-control") && (v.contains("no-store") || v.contains("no-cache") || v.contains("private"))) return false;
        }
        return true;
    }

    private static void write(ChannelHandlerContext ctx, AccessRecord rec, FunctionRuntime.Result r, boolean keepAlive,
                              Map<String, String> extra, boolean head, com.scaleguard.server.http.cache.CachedResponse cache,
                              com.scaleguard.server.http.router.TargetSystem target) {
        if (cache != null && cache.getKey() != null && cache.getResource() != null && cacheable(r)) {
            FullHttpResponse copy = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(r.status), Unpooled.copiedBuffer(r.body));
            r.headers.forEach((k, v) -> { if (v != null) copy.headers().set(k, v); });
            copy.headers().set(HttpHeaderNames.CONTENT_LENGTH, r.body.length);
            com.scaleguard.server.http.cache.CacheManagers.get().saveFresh(target, cache.getKey(), copy, cache.getResource().getTtl());
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(r.status),
                Unpooled.wrappedBuffer(head ? new byte[0] : r.body));
        r.headers.forEach((k, v) -> {
            if (v != null) resp.headers().set(k, v);
        });
        extra.forEach((k, v) -> resp.headers().set(k, v));
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, r.body.length);
        if (rec != null) {
            rec.bytesOut = r.body.length;
            rec.finish(r.status, "function");
        }
        io.netty.channel.ChannelFuture f = ctx.writeAndFlush(resp);
        if (keepAlive) {
            f.addListener(done -> {
                if (done.isSuccess()) ctx.channel().read();
                else ctx.close();
            });
        } else {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
