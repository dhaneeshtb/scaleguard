package com.scaleguard.server.dns;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client token bucket (IPv4 /32, IPv6 /64).
 *
 * Over-limit clients mostly get no reply, but every second over-limit query receives an empty
 * truncated (TC) reply — the "slip" technique from Response Rate Limiting. Real resolvers behind a
 * shared address then retry over TCP and still get answers, while spoofed-source floods gain nothing.
 */
final class DnsRateLimiter {

    enum Result { ALLOW, DROP, SLIP }

    private static final class Bucket {
        double tokens;
        long lastNanos;
        int overflow;
    }

    private final double ratePerSec;
    private final double burst;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    DnsRateLimiter(int ratePerSec, int burst) {
        this.ratePerSec = ratePerSec;
        this.burst = Math.max(1, burst);
    }

    Result check(InetAddress addr) {
        if (ratePerSec <= 0 || addr == null) return Result.ALLOW;
        if (addr.isLoopbackAddress()) return Result.ALLOW;
        long now = System.nanoTime();
        Bucket b = buckets.computeIfAbsent(key(addr), k -> {
            Bucket n = new Bucket();
            n.tokens = burst;
            n.lastNanos = now;
            return n;
        });
        synchronized (b) {
            double elapsed = (now - b.lastNanos) / 1e9;
            b.lastNanos = now;
            b.tokens = Math.min(burst, b.tokens + elapsed * ratePerSec);
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return Result.ALLOW;
            }
            return (++b.overflow & 1) == 0 ? Result.SLIP : Result.DROP;
        }
    }

    /** Drops buckets that have been idle long enough to be full again. */
    void sweep() {
        long now = System.nanoTime();
        long idle = (long) ((burst / Math.max(1, ratePerSec)) * 1e9) + 10_000_000_000L;
        buckets.entrySet().removeIf(e -> now - e.getValue().lastNanos > idle);
    }

    int trackedClients() { return buckets.size(); }

    private static String key(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) b = Arrays.copyOf(b, 8);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
