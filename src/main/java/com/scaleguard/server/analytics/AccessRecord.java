package com.scaleguard.server.analytics;

/**
 * One access-log row. Filled in on the Netty I/O thread (cheap field writes only) and handed to
 * {@link AccessLogStore#submit}, which writes it to DuckDB in batches on a background thread.
 */
public final class AccessRecord {

    /** Why the request ended the way it did. */
    public static final String PROXIED = "proxied", RATE_LIMITED = "rate_limited", WAF_BLOCKED = "waf_blocked",
            AUTH_DENIED = "auth_denied", NO_BACKEND = "no_backend", NO_ROUTE = "no_route", ERROR = "error";

    public long tsMillis = System.currentTimeMillis();
    public final long startNanos = System.nanoTime();
    public String traceId;
    public String clientIp;
    public String method;
    public String host;
    public String path;
    public String query;
    public String protocol;      // HTTP/1.1, HTTP/2, gRPC
    public String routeId;
    public String routeName;
    public String backend;       // host:port that served it
    public int status;
    public int grpcStatus = -1;  // -1 = not gRPC / unknown
    public long bytesIn;
    public long bytesOut;
    public double durationMs;
    public String outcome = PROXIED;
    public String wafRule;
    public String userAgent;
    public String referer;
    public boolean tls;

    /** Stamps the duration and queues the record. Safe to call once; later calls are ignored. */
    private boolean done;

    public void finish(int status) {
        if (done) return;
        done = true;
        this.status = status;
        this.durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        AccessLogStore.getInstance().submit(this);
    }

    public void finish(int status, String outcome) {
        this.outcome = outcome;
        finish(status);
    }

    public boolean isDone() {
        return done;
    }

    /** Splits "/a/b?x=1" into path and query (query capped, it's for analysis not replay). */
    public void setUri(String uri) {
        if (uri == null) return;
        int q = uri.indexOf('?');
        path = cap(q < 0 ? uri : uri.substring(0, q), 2048);
        query = q < 0 ? null : cap(uri.substring(q + 1), 2048);
    }

    static String cap(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
