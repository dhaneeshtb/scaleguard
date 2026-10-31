package com.scaleguard.server.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.cluster.ClusterManager;
import com.scaleguard.server.http.auth.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copies other cluster nodes' access-log rows into this node's analytics store, so every node can answer analytics
 * for the whole cluster. Pull-based: every {@code scaleguard.analytics.replicateMs} (default 2 s) it asks each live
 * peer for "your own rows after seq N" (N = the highest seq already held for that peer), in pages, and appends them.
 * Nothing extra runs on the request path, and no database beyond the cluster's existing Postgres membership table is
 * involved. A node that was down, or joins later, catches up (back-fills up to the retention window) on its own.
 * Off with {@code -Dscaleguard.analytics.replicate=false} (then each node keeps only its own requests).
 */
public final class AnalyticsReplicator {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsReplicator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("scaleguard.analytics.replicate", "true"));
    private static final long INTERVAL_MS = Long.getLong("scaleguard.analytics.replicateMs", 2000);
    private static final int PAGE = Integer.getInteger("scaleguard.analytics.replicatePage", 20_000);
    private static final int MAX_PAGES_PER_TICK = 25;

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static volatile boolean started;
    /** Per peer: last successful pull, rows copied, last error. */
    private static final Map<String, Map<String, Object>> PEERS = new ConcurrentHashMap<>();

    private AnalyticsReplicator() {}

    /** True when this node holds (or is collecting) the whole cluster's access log. */
    public static boolean active() {
        return ENABLED && ClusterManager.enabled() && AccessLogStore.getInstance().isRecording();
    }

    public static synchronized void start() {
        if (started || !ENABLED) return;
        started = true;
        Thread.ofVirtual().name("sg-analytics-replicator").start(AnalyticsReplicator::loop);
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(INTERVAL_MS);
                if (active()) pullAll();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOG.debug("Analytics replication tick failed: {}", t.toString());
            }
        }
    }

    /**
     * With an elected master (the normal case) analytics flow through it: the master collects every node's own rows,
     * and every other node copies the whole cluster's rows from the master alone. While no master is known, nodes fall
     * back to copying straight from each other so no node goes dark.
     */
    private static void pullAll() {
        ClusterManager cm = ClusterManager.get();
        String master = cm.masterNode();
        String self = cm.node();
        Map<String, String> peers = cm.livePeers();
        if (master != null && !master.equals(self) && peers.containsKey(master)) {
            route = "from master " + master;
            pullThroughMaster(master, peers.get(master));
            return;
        }
        route = master != null && master.equals(self) ? "master: collecting from every node" : "no master: copying from every node";
        for (Map.Entry<String, String> peer : peers.entrySet()) pullOrigin(peer.getKey(), peer.getValue(), peer.getKey());
    }

    private static volatile String route = "idle";

    /** Copies every other origin's rows that the master holds. */
    private static void pullThroughMaster(String master, String base) {
        AccessLogStore store = AccessLogStore.getInstance();
        Map<String, Object> st = PEERS.computeIfAbsent(master, k -> new ConcurrentHashMap<>());
        try {
            JsonNode head = fetch(base, master, store.replicatedUpTo(master), 1);
            Map<String, Long> theirs = new java.util.TreeMap<>();
            head.path("cursors").fields().forEachRemaining(e -> theirs.put(e.getKey(), e.getValue().asLong()));
            for (Map.Entry<String, Long> e : theirs.entrySet()) {
                String origin = e.getKey();
                if (origin.equals(ClusterManager.get().node()) || e.getValue() <= store.replicatedUpTo(origin)) continue;
                pullOrigin(master, base, origin);
            }
            st.put("lastPullAt", System.currentTimeMillis());
            st.remove("error");
        } catch (Exception e) {
            st.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            st.put("errorAt", System.currentTimeMillis());
        }
    }

    /** Copies {@code origin}'s rows from the node at {@code base} (the origin itself, or the master). */
    private static void pullOrigin(String via, String base, String origin) {
        AccessLogStore store = AccessLogStore.getInstance();
        Map<String, Object> st = PEERS.computeIfAbsent(origin, k -> new ConcurrentHashMap<>());
        try {
            int copied = 0;
            for (int page = 0; page < MAX_PAGES_PER_TICK; page++) {
                long after = store.replicatedUpTo(origin);
                JsonNode resp = fetch(base, origin, after, PAGE);
                if (!origin.equals(resp.path("node").asText(origin))) {
                    throw new IllegalStateException("node at " + base + " answered for " + resp.path("node").asText());
                }
                JsonNode rows = resp.path("rows");
                copied += store.importRows(origin, rows);
                st.put("theirLatestSeq", resp.path("latestSeq").asLong());
                if (rows.size() < PAGE) break;           // caught up
            }
            st.put("via", via);
            st.put("lastPullAt", System.currentTimeMillis());
            st.put("upToSeq", store.replicatedUpTo(origin));
            st.merge("rowsCopied", (long) copied, (a, b) -> ((Long) a) + ((Long) b));
            st.remove("error");
        } catch (Exception e) {
            st.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            st.put("errorAt", System.currentTimeMillis());
            LOG.debug("Analytics replication of {} via {} failed: {}", origin, via, e.toString());
        }
    }

    private static JsonNode fetch(String base, String origin, long after, int limit) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/analytics/replicate?scaleguard=true&origin="
                        + URLEncoder.encode(origin, StandardCharsets.UTF_8) + "&after=" + after + "&limit=" + limit))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", AuthUtils.createJWT(60_000))
                .header("X-ScaleGuard-Forwarded-By", URLEncoder.encode(ClusterManager.get().node(), StandardCharsets.UTF_8))
                .GET().build();
        HttpResponse<byte[]> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
        return MAPPER.readTree(r.body());
    }

    /** For /analytics/settings and the UI. */
    public static Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", ENABLED);
        m.put("active", active());
        m.put("intervalMs", INTERVAL_MS);
        m.put("route", route);
        m.put("master", ClusterManager.get().masterNode());
        m.put("rowsReplicatedIn", AccessLogStore.getInstance().replicatedIn());
        m.put("cursors", AccessLogStore.getInstance().originCursors());
        m.put("peers", new java.util.TreeMap<>(PEERS));
        return m;
    }
}
