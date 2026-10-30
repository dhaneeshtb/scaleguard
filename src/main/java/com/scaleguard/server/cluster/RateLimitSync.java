package com.scaleguard.server.cluster;

import com.scaleguard.server.http.router.RateLimitManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cluster-wide rate limits ({@code ratelimit.clusterMode = shared}).
 *
 * <p>Every node keeps its own token bucket per (client, route) key — so the hot path never waits on the network —
 * and on each tick (default 500 ms):
 * <ol>
 *   <li>publishes the <i>cumulative</i> number of tokens it has spent on each key that saw traffic, one row per
 *       (key, node) in {@code sg_ratelimit_usage};</li>
 *   <li>reads the rows other nodes changed since the last tick and deducts the difference from its own buckets.</li>
 * </ol>
 * Publishing running totals instead of increments makes the exchange idempotent: a missed or repeated read can't
 * double-count. The limit therefore holds for the cluster as a whole; between two ticks each node may admit up to
 * one tick's worth of extra requests, so the overshoot is bounded by {@code nodes × rate × tick}.
 */
final class RateLimitSync {

    static final long RETAIN_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_KEYS_PER_TICK = Integer.getInteger("scaleguard.cluster.rateSyncMaxKeys", 20_000);

    /** (key \0 node) -> {cumulative consumed, local millis last seen}. */
    private final Map<String, double[]> lastSeen = new HashMap<>();
    private long watermark = -1;            // database ms of the newest row read
    private long lastPrune = System.currentTimeMillis();

    private final LongAdder publishedRows = new LongAdder();
    private final LongAdder appliedTokens = new LongAdder();
    private volatile long lastTickMs;
    private volatile long lastTickAt;
    private volatile boolean active;
    private volatile int tracked;

    void sync(Connection c, String node) throws SQLException {
        RateLimitManager rl = RateLimitManager.getInstance();
        active = rl.clusterShared();
        if (!active) {
            watermark = -1;   // re-prime when switched back on
            lastSeen.clear();
            return;
        }
        long t0 = System.nanoTime();
        List<RateLimitManager.Usage> usage = rl.drainUsage(MAX_KEYS_PER_TICK);
        if (!usage.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sg_ratelimit_usage(rkey, node, consumed, lim, uts) VALUES (?,?,?,?," + ClusterManager.NOW_MS + ") "
                            + "ON CONFLICT (rkey, node) DO UPDATE SET consumed = EXCLUDED.consumed, lim = EXCLUDED.lim, uts = EXCLUDED.uts")) {
                for (RateLimitManager.Usage u : usage) {
                    ps.setString(1, u.key());
                    ps.setString(2, node);
                    ps.setDouble(3, u.consumed());
                    ps.setInt(4, u.limitPerMinute());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                rl.restoreUsage(usage);   // publish again next tick
                throw e;
            }
            publishedRows.add(usage.size());
        }

        boolean priming = watermark < 0;
        long now = System.currentTimeMillis();
        long newest = watermark;
        try (PreparedStatement ps = c.prepareStatement(priming
                ? "SELECT rkey, node, consumed, lim, uts FROM sg_ratelimit_usage WHERE node <> ? AND uts > " + ClusterManager.NOW_MS + " - ?"
                : "SELECT rkey, node, consumed, lim, uts FROM sg_ratelimit_usage WHERE node <> ? AND uts >= ?")) {
            ps.setString(1, node);
            // Re-read a 2 s overlap: rows commit out of order across nodes, and lastSeen de-duplicates.
            ps.setLong(2, priming ? RETAIN_MS : watermark - 2000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String other = rs.getString(2);
                    double consumed = rs.getDouble(3);
                    int limit = rs.getInt(4);
                    newest = Math.max(newest, rs.getLong(5));
                    double[] prev = lastSeen.get(key + '\0' + other);
                    double delta;
                    if (prev == null) delta = consumed;
                    else if (consumed < prev[0]) delta = consumed;     // that node evicted and recreated the bucket
                    else delta = consumed - prev[0];
                    lastSeen.put(key + '\0' + other, new double[]{consumed, now});
                    // On the first read after start-up just learn the totals: history isn't this node's debt.
                    if (!priming && delta > 0) {
                        rl.applyRemote(key, delta, limit);
                        appliedTokens.add((long) Math.ceil(delta));
                    }
                }
            }
        }
        watermark = Math.max(newest, priming ? 0 : watermark);
        if (now - lastPrune > TimeUnit.MINUTES.toMillis(1)) {
            for (Iterator<double[]> it = lastSeen.values().iterator(); it.hasNext(); ) {
                if (now - it.next()[1] > RETAIN_MS + 60_000) it.remove();
            }
            lastPrune = now;
        }
        tracked = lastSeen.size();
        lastTickMs = (System.nanoTime() - t0) / 1_000_000;
        lastTickAt = now;
    }

    Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", active);
        m.put("publishedRows", publishedRows.sum());
        m.put("appliedRemoteTokens", appliedTokens.sum());
        m.put("trackedRemoteKeys", tracked);
        m.put("lastSyncMs", lastTickMs);
        m.put("lastSyncAt", lastTickAt);
        return m;
    }
}
