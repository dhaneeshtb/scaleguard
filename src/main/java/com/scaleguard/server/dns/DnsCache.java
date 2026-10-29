package com.scaleguard.server.dns;

import com.scaleguard.server.dns.DnsWire.Rr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LRU cache of upstream answers, honouring record TTLs (RFC 1035) and negative-caching rules (RFC 2308).
 */
final class DnsCache {

    static final long MAX_TTL = 86_400;     // 1 day
    static final long MAX_NEG_TTL = 3_600;  // 1 hour
    static final long DEFAULT_NEG_TTL = 60;

    record Entry(int rcode, List<Rr> answers, List<Rr> authority, List<Rr> additional, long storedAtMs, long expiresAtMs) {
        /** Seconds this entry has been in the cache — subtracted from every TTL when served. */
        long ageSeconds(long nowMs) { return Math.max(0, (nowMs - storedAtMs) / 1000); }
    }

    private final int maxEntries;
    private final LinkedHashMap<String, Entry> map;

    DnsCache(int maxEntries) {
        this.maxEntries = Math.max(0, maxEntries);
        this.map = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > DnsCache.this.maxEntries;
            }
        };
    }

    static String key(String name, int type, int cls) {
        return DnsWire.normalize(name) + '|' + type + '|' + cls;
    }

    synchronized Entry get(String key, long nowMs) {
        Entry e = map.get(key);
        if (e == null) return null;
        if (nowMs >= e.expiresAtMs) {
            map.remove(key);
            return null;
        }
        return e;
    }

    void put(String key, DnsWire.Message m, long nowMs) {
        if (maxEntries == 0) return;
        long ttl = ttlFor(m);
        if (ttl <= 0) return;
        Entry e = new Entry(m.rcode(), List.copyOf(m.answers), List.copyOf(m.authority), List.copyOf(m.additional), nowMs, nowMs + ttl * 1000);
        synchronized (this) {
            map.put(key, e);
        }
    }

    synchronized int size() { return map.size(); }

    synchronized void clear() { map.clear(); }

    /** How long a response may be cached, in seconds. 0 means don't cache. */
    static long ttlFor(DnsWire.Message m) {
        int rcode = m.rcode();
        if (m.truncated()) return 0;
        if (rcode == DnsWire.RCODE_NOERROR && !m.answers.isEmpty()) {
            long min = MAX_TTL;
            for (Rr r : m.answers) min = Math.min(min, r.ttl());
            return Math.max(0, min);
        }
        if (rcode == DnsWire.RCODE_NXDOMAIN || rcode == DnsWire.RCODE_NOERROR) {
            for (Rr r : m.authority) {
                if (r.type() == DnsWire.TYPE_SOA) {
                    long minField = DnsWire.soaMinimum(r.rdata());
                    long t = minField < 0 ? r.ttl() : Math.min(r.ttl(), minField);
                    return Math.min(MAX_NEG_TTL, Math.max(0, t));
                }
            }
            return DEFAULT_NEG_TTL;
        }
        return 0; // SERVFAIL, REFUSED, etc. are never cached
    }
}
