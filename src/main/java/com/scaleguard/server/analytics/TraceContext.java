package com.scaleguard.server.analytics;

import java.util.concurrent.ThreadLocalRandom;

/**
 * W3C Trace Context ({@code traceparent}) without any tracing SDK: ScaleGuard keeps the caller's trace id,
 * or starts a trace, and passes {@code traceparent} upstream with its own span id. The trace id is stored with
 * every access-log row and returned to clients as {@code X-Trace-Id}, so one request can be followed from the
 * client, through the gateway's logs, into the backend's logs.
 */
public final class TraceContext {

    private TraceContext() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Trace id from an incoming traceparent ("00-<32 hex>-<16 hex>-<2 hex>"), or a new random one. */
    public static String traceId(String traceparent) {
        if (traceparent != null && traceparent.length() >= 55 && traceparent.charAt(2) == '-' && traceparent.charAt(35) == '-') {
            String id = traceparent.substring(3, 35);
            if (isHex(id) && !id.equals("00000000000000000000000000000000")) return id;
        }
        return randomHex(16);
    }

    /** traceparent to send upstream: same trace, new span (this hop), sampled. */
    public static String traceparent(String traceId) {
        return "00-" + traceId + "-" + randomHex(8) + "-01";
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** {@code bytes} random bytes as lowercase hex (8 or 16 bytes), from one or two random longs. */
    private static String randomHex(int bytes) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        char[] out = new char[bytes * 2];
        int pos = 0;
        for (int w = 0; w < bytes / 8; w++) {
            long v = r.nextLong();
            for (int shift = 60; shift >= 0; shift -= 4) out[pos++] = HEX[(int) (v >>> shift) & 15];
        }
        return new String(out);
    }

    /**
     * Whether ScaleGuard adds {@code traceparent} upstream and {@code X-Trace-Id} to responses
     * ({@code -Dscaleguard.traceHeaders=false} to keep requests and responses byte-for-byte unchanged).
     */
    public static final boolean HEADERS = !"false".equalsIgnoreCase(System.getProperty("scaleguard.traceHeaders", "true"));
}
