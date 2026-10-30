package com.scaleguard.server.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.db.ConnectionUtil;
import com.scaleguard.server.http.utils.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Multi-node clustering over the shared PostgreSQL configuration database.
 *
 * <p>Every ScaleGuard node pointed at the same PostgreSQL database (see {@code postgres.properties}) forms a cluster;
 * there is nothing else to run. Three things are shared:
 * <ol>
 *   <li><b>Configuration.</b> Each write through {@code DBManager} bumps a per-scope version in
 *       {@code sg_cluster_config}. Every node polls that table (default every 2 s) and reloads only the subsystems
 *       whose scope changed — routes, WAF, rate limits, health settings, DNS, certificates — in place, so
 *       backend health and connection counts survive the reload.</li>
 *   <li><b>Membership.</b> Nodes heartbeat into {@code sg_cluster_nodes} with a small metrics snapshot; the admin UI's
 *       Cluster page reads it. One node holds a PostgreSQL advisory lock and is the <i>leader</i>: it alone runs
 *       singleton jobs such as ACME certificate renewal. If it dies its session ends, the lock is released and another
 *       node takes over on its next heartbeat.</li>
 *   <li><b>Rate limits.</b> See {@link RateLimitSync}: each node publishes how many tokens it spent per client key and
 *       deducts what the others spent, so a limit holds across the cluster rather than per node.</li>
 * </ol>
 *
 * <p>With SQLite (the default single-node store) clustering is off and every node is its own leader.
 * Disable explicitly with {@code -Dscaleguard.cluster=false}.
 */
public final class ClusterManager {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** PostgreSQL LISTEN/NOTIFY channel that makes configuration changes propagate within one tick. */
    private static final String CHANNEL = "sg_cluster_config";
    static final String NOW_MS = "(extract(epoch from clock_timestamp()) * 1000)::bigint";

    private static final long TICK_MS = Math.max(100, Long.getLong("scaleguard.cluster.rateSyncMs", 500));
    private static final long POLL_MS = Math.max(250, Long.getLong("scaleguard.cluster.pollMs", 2000));
    private static final long HEARTBEAT_MS = Math.max(500, Long.getLong("scaleguard.cluster.heartbeatMs", 3000));
    static final long NODE_TIMEOUT_MS = Math.max(3000, Long.getLong("scaleguard.cluster.nodeTimeoutMs", 15000));
    private static final long HOUSEKEEPING_MS = TimeUnit.MINUTES.toMillis(1);
    /** Advisory-lock key for leadership ("SCALEGRD"). */
    private static final long LEADER_LOCK = 0x5343414C45475244L;

    private static final ClusterManager INSTANCE = new ClusterManager();

    public static ClusterManager get() {
        return INSTANCE;
    }

    private final boolean enabled;
    private final String disabledReason;
    private final String node = NodeIdentity.name();
    private final String address = NodeIdentity.adminUrl();
    private final long startedAt = System.currentTimeMillis();
    private final AtomicBoolean started = new AtomicBoolean();

    private volatile boolean leader;
    private volatile long leaderSince;
    private volatile int liveNodes = 1;
    private volatile boolean schemaReady;

    // ---- configuration sync state
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private volatile boolean primed;
    private volatile List<Map<String, Object>> configVersions = List.of();
    private final Map<String, List<Runnable>> listeners = new ConcurrentHashMap<>();
    private final LongAdder reloads = new LongAdder();
    private final LongAdder published = new LongAdder();
    private volatile long lastReloadAt;
    private volatile String lastReloadScopes;
    private volatile String lastReloadFrom;
    private volatile String lastError;
    private volatile long lastErrorAt;
    private volatile long lastPollAt;
    private volatile long lastHeartbeatAt;
    private long lastHousekeeping;

    // ---- heartbeat metrics provider (wired by AppServer so this package stays free of proxy imports)
    private volatile Supplier<Map<String, Object>> metrics = Map::of;

    private final RateLimitSync rateSync = new RateLimitSync();

    /** Owned by the cluster thread only: it holds the leader advisory lock for this session. */
    private Connection conn;
    private ScheduledExecutorService ticker;
    private final ExecutorService reloader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sg-cluster-reload");
        t.setDaemon(true);
        return t;
    });

    private ClusterManager() {
        String flag = System.getProperty("scaleguard.cluster");
        if (flag == null || flag.isBlank()) flag = AppProperties.get("scaleguard.cluster");
        boolean off = "false".equalsIgnoreCase(flag == null ? "" : flag.trim());
        if (off) {
            enabled = false;
            disabledReason = "Disabled with scaleguard.cluster=false";
        } else if (!ConnectionUtil.isPostgres()) {
            enabled = false;
            disabledReason = "Single node: configuration is stored in the local SQLite database. "
                    + "Point every node at the same PostgreSQL database (postgres.properties) to form a cluster.";
        } else {
            enabled = true;
            disabledReason = null;
        }
    }

    // ------------------------------------------------------------------ public API

    /** True when this node shares its configuration with other nodes through PostgreSQL. */
    public static boolean enabled() {
        return INSTANCE.enabled;
    }

    /** True when this node should run singleton jobs (always true outside a cluster). */
    public static boolean isLeader() {
        return !INSTANCE.enabled || INSTANCE.leader;
    }

    /** Nodes that heartbeated recently (at least 1). */
    public static int liveNodes() {
        return INSTANCE.enabled ? Math.max(1, INSTANCE.liveNodes) : 1;
    }

    public String node() {
        return node;
    }

    /** Registers a reload hook for a scope: a table name, or {@code system_properties/<prefix>}. */
    public void on(String scope, Runnable reload) {
        listeners.computeIfAbsent(scope, k -> new CopyOnWriteArrayList<>()).add(reload);
    }

    /** Supplies the metrics published with each heartbeat. */
    public void metrics(Supplier<Map<String, Object>> supplier) {
        this.metrics = supplier == null ? Map::of : supplier;
    }

    /**
     * Called after a configuration write commits: bumps the scopes' versions so other nodes reload them.
     * No-op outside a cluster.
     */
    public static void changed(Collection<String> scopes) {
        if (!INSTANCE.enabled || scopes == null || scopes.isEmpty()) return;
        INSTANCE.bump(scopes);
    }

    public static void changed(String scope) {
        changed(List.of(scope));
    }

    public synchronized void start() {
        if (!enabled) {
            LOG.info("Cluster: off — {}", disabledReason);
            return;
        }
        if (!started.compareAndSet(false, true)) return;
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sg-cluster");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::leave, "sg-cluster-leave"));
        LOG.info("Cluster: node '{}' ({}) joining; config poll {} ms, heartbeat {} ms, rate-limit sync {} ms",
                node, address, POLL_MS, HEARTBEAT_MS, TICK_MS);
    }

    /** Reloads every registered subsystem from the database now (the UI's "Resync" button). */
    public void resyncAll() {
        Set<String> all = new LinkedHashSet<>(listeners.keySet());
        reloader.submit(() -> fire(all, "manual resync"));
    }

    // ------------------------------------------------------------------ cluster thread

    private void tick() {
        long now = System.currentTimeMillis();
        try {
            Connection c = connection();
            rateSync.sync(c, node);
            if (now - lastPollAt >= POLL_MS || notified(c)) {
                poll(c);
                lastPollAt = now;
            }
            if (now - lastHeartbeatAt >= HEARTBEAT_MS) {
                heartbeat(c);
                lastHeartbeatAt = now;
            }
            if (now - lastHousekeeping >= HOUSEKEEPING_MS) {
                housekeeping(c);
                lastHousekeeping = now;
            }
        } catch (Throwable e) {
            error("cluster sync failed: " + e.getMessage(), e);
            closeConnection();
        }
    }

    private Connection connection() throws Exception {
        if (conn != null) {
            try {
                if (!conn.isClosed()) return conn;
            } catch (SQLException ignored) {
                // reopen
            }
        }
        ensureSchema();
        Connection c = ConnectionUtil.getConnection();
        c.setAutoCommit(true);
        if (!"PostgreSQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName())) {
            c.close();
            throw new SQLException("PostgreSQL is unreachable (got a local SQLite connection instead)");
        }
        try (Statement st = c.createStatement()) {
            st.execute("LISTEN " + CHANNEL);
        }
        conn = c;
        lastPollAt = 0;   // after a reconnect, catch up on anything missed
        return conn;
    }

    /** True when another node announced a configuration change since the last tick. */
    private static boolean notified(Connection c) {
        try {
            org.postgresql.PGNotification[] n = c.unwrap(org.postgresql.PGConnection.class).getNotifications(1);
            return n != null && n.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void closeConnection() {
        if (leader) {
            leader = false;
            LOG.warn("Cluster: lost the database session — giving up leadership until it reconnects");
        }
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // already broken
            }
            conn = null;
        }
    }

    private void poll(Connection c) throws SQLException {
        Set<String> changed = new LinkedHashSet<>();
        Set<String> from = new LinkedHashSet<>();
        List<Map<String, Object>> versions = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT scope, version, updated_by, uts FROM sg_cluster_config ORDER BY scope")) {
            while (rs.next()) {
                String scope = rs.getString(1);
                long v = rs.getLong(2);
                String by = rs.getString(3);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("scope", scope);
                m.put("version", v);
                m.put("updatedBy", by);
                m.put("updatedAt", rs.getLong(4));
                versions.add(m);
                boolean[] isNew = {false};
                seen.compute(scope, (k, old) -> {
                    if (old == null || v > old) {
                        isNew[0] = old != null || primed;
                        return v;
                    }
                    return old;
                });
                if (isNew[0]) {
                    changed.add(scope);
                    if (by != null) from.add(by);
                }
            }
        }
        configVersions = List.copyOf(versions);
        primed = true;
        if (!changed.isEmpty()) {
            String who = String.join(", ", from);
            reloader.submit(() -> fire(changed, who));
        }
    }

    private void fire(Set<String> scopes, String from) {
        Set<Runnable> run = new LinkedHashSet<>();
        for (String s : scopes) {
            List<Runnable> l = listeners.get(s);
            if (l != null) run.addAll(l);
            int slash = s.indexOf('/');
            if (slash > 0) {
                List<Runnable> any = listeners.get(s.substring(0, slash) + "/*");
                if (any != null) run.addAll(any);
            }
        }
        if (run.isEmpty()) return;
        long t0 = System.nanoTime();
        for (Runnable r : run) {
            try {
                r.run();
            } catch (Throwable e) {
                error("reloading " + scopes + " failed: " + e.getMessage(), e);
            }
        }
        reloads.increment();
        lastReloadAt = System.currentTimeMillis();
        lastReloadScopes = String.join(", ", scopes);
        lastReloadFrom = from;
        LOG.info("Cluster: applied configuration change ({}) from {} in {} ms", lastReloadScopes,
                from == null || from.isBlank() ? "another node" : from, (System.nanoTime() - t0) / 1_000_000);
    }

    private void heartbeat(Connection c) throws Exception {
        if (!leader) {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, LEADER_LOCK);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        leader = true;
                        leaderSince = System.currentTimeMillis();
                        LOG.info("Cluster: this node ('{}') is now the leader (runs certificate renewal)", node);
                    }
                }
            }
        }
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            info.putAll(metrics.get());
        } catch (Throwable e) {
            LOG.debug("cluster metrics failed: {}", e.toString());
        }
        info.put("version", ClusterManager.class.getPackage().getImplementationVersion());
        info.put("cpus", Runtime.getRuntime().availableProcessors());
        Runtime rt = Runtime.getRuntime();
        info.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        info.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        info.put("configReloads", reloads.sum());
        info.put("rateLimitSync", rateSync.stats());
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sg_cluster_nodes(node, address, started_ms, heartbeat_ms, leader, info) VALUES (?,?,?," + NOW_MS + ",?,?) "
                        + "ON CONFLICT (node) DO UPDATE SET address = EXCLUDED.address, started_ms = EXCLUDED.started_ms, "
                        + "heartbeat_ms = EXCLUDED.heartbeat_ms, leader = EXCLUDED.leader, info = EXCLUDED.info")) {
            ps.setString(1, node);
            ps.setString(2, address);
            ps.setLong(3, startedAt);
            ps.setBoolean(4, leader);
            ps.setString(5, MAPPER.writeValueAsString(info));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM sg_cluster_nodes WHERE heartbeat_ms > " + NOW_MS + " - ?")) {
            ps.setLong(1, NODE_TIMEOUT_MS);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int n = Math.max(1, rs.getInt(1));
                    if (n != liveNodes) LOG.info("Cluster: {} live node(s)", n);
                    liveNodes = n;
                }
            }
        }
    }

    private void housekeeping(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM sg_cluster_nodes WHERE heartbeat_ms < " + NOW_MS + " - " + TimeUnit.DAYS.toMillis(1));
            st.executeUpdate("DELETE FROM sg_ratelimit_usage WHERE uts < " + NOW_MS + " - " + RateLimitSync.RETAIN_MS);
        }
    }

    /** Marks this node gone on a clean shutdown so the others stop counting it immediately. */
    private void leave() {
        try (Connection c = ConnectionUtil.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE sg_cluster_nodes SET heartbeat_ms = 0, leader = false WHERE node = ?")) {
            ps.setString(1, node);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.debug("cluster leave failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ change publication

    private void bump(Collection<String> scopes) {
        try {
            ensureSchema();
        } catch (Exception e) {
            error("could not create cluster tables: " + e.getMessage(), e);
            return;
        }
        try (Connection c = ConnectionUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO sg_cluster_config(scope, version, updated_by, uts) VALUES (?, 1, ?, " + NOW_MS + ") "
                             + "ON CONFLICT (scope) DO UPDATE SET version = sg_cluster_config.version + 1, "
                             + "updated_by = EXCLUDED.updated_by, uts = EXCLUDED.uts RETURNING version")) {
            for (String scope : new LinkedHashSet<>(scopes)) {
                ps.setString(1, scope);
                ps.setString(2, node);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) continue;
                    long v = rs.getLong(1);
                    // Our own change is already applied locally: skip the reload unless another node also wrote in between.
                    seen.compute(scope, (k, old) -> (old != null && old == v - 1) || (old == null && v == 1) ? v : old);
                    published.increment();
                }
            }
            try (Statement st = c.createStatement()) {
                st.execute("NOTIFY " + CHANNEL);   // wake the other nodes now instead of at their next poll
            }
        } catch (Exception e) {
            error("could not publish configuration change " + scopes + ": " + e.getMessage(), e);
        }
    }

    private void ensureSchema() throws Exception {
        if (schemaReady) return;
        synchronized (this) {
            if (schemaReady) return;
            String[] ddl = {
                    "CREATE TABLE IF NOT EXISTS sg_cluster_config (scope text PRIMARY KEY, version bigint NOT NULL, updated_by text, uts bigint)",
                    "CREATE TABLE IF NOT EXISTS sg_cluster_nodes (node text PRIMARY KEY, address text, started_ms bigint, heartbeat_ms bigint, leader boolean, info text)",
                    "CREATE TABLE IF NOT EXISTS sg_ratelimit_usage (rkey text NOT NULL, node text NOT NULL, consumed double precision NOT NULL, "
                            + "lim integer, uts bigint NOT NULL, PRIMARY KEY (rkey, node))",
                    "CREATE INDEX IF NOT EXISTS sg_ratelimit_usage_uts ON sg_ratelimit_usage (uts)"
            };
            Exception last = null;
            for (int attempt = 0; attempt < 3; attempt++) {   // two nodes creating the tables at once can collide
                try (Connection c = ConnectionUtil.getConnection(); Statement st = c.createStatement()) {
                    for (String s : ddl) st.execute(s);
                    schemaReady = true;
                    return;
                } catch (Exception e) {
                    last = e;
                    Thread.sleep(200L * (attempt + 1));
                }
            }
            throw last;
        }
    }

    private void error(String msg, Throwable e) {
        long now = System.currentTimeMillis();
        boolean quiet = msg.equals(lastError) && now - lastErrorAt < 60_000;
        lastError = msg;
        lastErrorAt = now;
        if (!quiet) LOG.warn("Cluster: {}", msg, LOG.isDebugEnabled() ? e : null);
    }

    // ------------------------------------------------------------------ view for the admin API

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        if (!enabled) m.put("reason", disabledReason);
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("node", node);
        self.put("address", address);
        self.put("leader", isLeader());
        if (leader) self.put("leaderSince", leaderSince);
        self.put("startedAt", startedAt);
        m.put("self", self);
        m.put("liveNodes", liveNodes());

        List<Map<String, Object>> nodes = new ArrayList<>();
        if (enabled) {
            try (Connection c = ConnectionUtil.getConnection();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT node, address, started_ms, heartbeat_ms, leader, info, " + NOW_MS
                         + " FROM sg_cluster_nodes ORDER BY node")) {
                while (rs.next()) {
                    long hb = rs.getLong(4);
                    long dbNow = rs.getLong(7);
                    if (hb == 0 && !node.equals(rs.getString(1)) && dbNow - rs.getLong(3) > TimeUnit.HOURS.toMillis(1)) continue;
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("node", rs.getString(1));
                    n.put("address", rs.getString(2));
                    n.put("startedAt", rs.getLong(3));
                    n.put("lastSeenMs", hb == 0 ? null : Math.max(0, dbNow - hb));
                    n.put("alive", hb > 0 && dbNow - hb < NODE_TIMEOUT_MS);
                    n.put("leader", rs.getBoolean(5) && hb > 0 && dbNow - hb < NODE_TIMEOUT_MS);
                    n.put("self", node.equals(rs.getString(1)));
                    String info = rs.getString(6);
                    n.put("info", info == null ? Map.of() : MAPPER.readValue(info, Map.class));
                    nodes.add(n);
                }
            } catch (Exception e) {
                m.put("error", "Could not read cluster members: " + e.getMessage());
            }
        }
        if (nodes.isEmpty()) {
            Map<String, Object> n = new LinkedHashMap<>(self);
            n.put("alive", true);
            n.put("self", true);
            n.put("lastSeenMs", 0);
            Map<String, Object> info = new LinkedHashMap<>();
            try {
                info.putAll(metrics.get());
            } catch (Throwable ignored) {
                // best effort
            }
            n.put("info", info);
            nodes.add(n);
        }
        m.put("nodes", nodes);

        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("pollMs", POLL_MS);
        sync.put("heartbeatMs", HEARTBEAT_MS);
        sync.put("rateSyncMs", TICK_MS);
        sync.put("nodeTimeoutMs", NODE_TIMEOUT_MS);
        sync.put("changesPublished", published.sum());
        sync.put("reloads", reloads.sum());
        if (lastReloadAt > 0) {
            sync.put("lastReloadAt", lastReloadAt);
            sync.put("lastReloadScopes", lastReloadScopes);
            sync.put("lastReloadFrom", lastReloadFrom);
        }
        if (lastError != null && System.currentTimeMillis() - lastErrorAt < TimeUnit.MINUTES.toMillis(10)) {
            sync.put("lastError", lastError);
            sync.put("lastErrorAt", lastErrorAt);
        }
        sync.put("rateLimit", rateSync.stats());
        m.put("sync", sync);
        m.put("config", configVersions);
        return m;
    }

    /** Address of a live member, or null. Used to forward per-node admin queries (e.g. analytics). */
    public String addressOf(String nodeName) {
        if (nodeName == null || nodeName.equals(node)) return null;
        if (!enabled) return null;
        try (Connection c = ConnectionUtil.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT address FROM sg_cluster_nodes WHERE node = ? AND heartbeat_ms > "
                     + NOW_MS + " - ?")) {
            ps.setString(1, nodeName);
            ps.setLong(2, NODE_TIMEOUT_MS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Scope names currently known, for tests. */
    Map<String, Long> seenVersions() {
        return new TreeMap<>(seen);
    }
}
