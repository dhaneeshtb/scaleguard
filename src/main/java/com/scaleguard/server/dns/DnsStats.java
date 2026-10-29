package com.scaleguard.server.dns;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Counters exposed at GET /dns/stats and summarised in the log once a minute. */
final class DnsStats {

    final LongAdder queries = new LongAdder();
    final LongAdder udp = new LongAdder();
    final LongAdder tcp = new LongAdder();
    final LongAdder authoritative = new LongAdder();
    final LongAdder cacheHits = new LongAdder();
    final LongAdder forwarded = new LongAdder();
    final LongAdder coalesced = new LongAdder();
    final LongAdder refused = new LongAdder();
    final LongAdder rateLimited = new LongAdder();
    final LongAdder servfail = new LongAdder();
    final LongAdder formErr = new LongAdder();
    final LongAdder notImp = new LongAdder();
    final LongAdder truncated = new LongAdder();
    final LongAdder upstreamTimeouts = new LongAdder();
    final LongAdder upstreamTcpFallbacks = new LongAdder();
    final LongAdder upstreamRejected = new LongAdder();
    final long startedAtMs = System.currentTimeMillis();

    private long lastLoggedQueries;

    Map<String, Object> snapshot(int cacheSize, int pendingUpstream, int rateTracked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptimeSeconds", (System.currentTimeMillis() - startedAtMs) / 1000);
        m.put("queries", queries.sum());
        m.put("udp", udp.sum());
        m.put("tcp", tcp.sum());
        m.put("authoritative", authoritative.sum());
        m.put("cacheHits", cacheHits.sum());
        m.put("forwarded", forwarded.sum());
        m.put("coalesced", coalesced.sum());
        m.put("refused", refused.sum());
        m.put("rateLimited", rateLimited.sum());
        m.put("servfail", servfail.sum());
        m.put("formErr", formErr.sum());
        m.put("notImplemented", notImp.sum());
        m.put("truncated", truncated.sum());
        m.put("upstreamTimeouts", upstreamTimeouts.sum());
        m.put("upstreamTcpFallbacks", upstreamTcpFallbacks.sum());
        m.put("upstreamSpoofRejected", upstreamRejected.sum());
        m.put("cacheEntries", cacheSize);
        m.put("pendingUpstream", pendingUpstream);
        m.put("rateLimitedClientsTracked", rateTracked);
        return m;
    }

    /** One INFO line per interval, only when there was traffic. */
    String intervalSummary() {
        long q = queries.sum();
        long delta = q - lastLoggedQueries;
        lastLoggedQueries = q;
        if (delta == 0) return null;
        return String.format("DNS last interval: %d queries | totals: auth=%d cache=%d fwd=%d refused=%d ratelimited=%d servfail=%d timeouts=%d",
                delta, authoritative.sum(), cacheHits.sum(), forwarded.sum(), refused.sum(), rateLimited.sum(), servfail.sum(), upstreamTimeouts.sum());
    }
}
