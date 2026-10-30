package com.scaleguard.server.analytics;

import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import com.scaleguard.server.licencing.licensing.LicenceUtil;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Request analytics and access logs, stored in an embedded DuckDB file ({@code ~/.scaleguard/analytics.duckdb}).
 *
 * <p><b>Hot path cost:</b> the proxy only fills an {@link AccessRecord} and offers it to a bounded queue — no I/O,
 * no locks beyond the queue. If the queue is full (DuckDB can't keep up) records are dropped and counted rather than
 * slowing traffic. A single background thread drains the queue every 250 ms and writes rows with DuckDB's appender
 * (hundreds of thousands of rows per second).
 *
 * <p><b>Querying:</b> everything is exposed as the view {@code access_log} (ts TIMESTAMP + one column per field), used
 * by the summary/logs/export endpoints and by the read-only SQL console in the admin UI. Old rows are deleted after
 * {@code retentionDays}.
 *
 * <p>Settings (UI or {@code POST /analytics/settings}) persist in system_properties as {@code analytics.*}.
 * Disable entirely with {@code -Dscaleguard.analytics=false}; move the file with {@code -Dscaleguard.analytics.path}.
 */
public final class AccessLogStore {

    private static final Logger LOG = LoggerFactory.getLogger(AccessLogStore.class);

    static final String P_ENABLED = "analytics.enabled", P_RETENTION = "analytics.retentionDays";
    private static final int QUEUE_CAPACITY = Integer.getInteger("scaleguard.analytics.queue", 200_000);
    private static final int BATCH = Integer.getInteger("scaleguard.analytics.batch", 50_000);
    /** How long the writer lets rows collect before appending them (fewer, larger DuckDB commits). */
    private static final long FLUSH_MS = Long.getLong("scaleguard.analytics.flushMs", 1000);

    public record Settings(boolean enabled, int retentionDays) {
        public Settings {
            retentionDays = Math.max(1, Math.min(3650, retentionDays));
        }
    }

    // Lock-free multi-producer queue (Netty I/O threads) with a size bound; one consumer (the writer thread).
    private final java.util.concurrent.ConcurrentLinkedQueue<AccessRecord> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();
    private final LongAdder written = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final String node = nodeName();
    private volatile Settings settings;
    private volatile boolean available;      // DuckDB opened successfully
    private volatile String unavailableReason;
    private volatile String path;
    private DuckDBConnection db;              // owned by the writer thread
    private long lastRetentionRun;

    private AccessLogStore() {
        settings = loadSettings();
        if ("false".equalsIgnoreCase(System.getProperty("scaleguard.analytics", "true"))) {
            unavailableReason = "disabled with -Dscaleguard.analytics=false";
            return;
        }
        try {
            open();
            Thread t = new Thread(this::writerLoop, "sg-access-log-writer");
            t.setDaemon(true);
            t.start();
            Runtime.getRuntime().addShutdownHook(new Thread(this::flushOnShutdown, "sg-access-log-flush"));
            available = true;
            LOG.info("Request analytics: {} (retention {} days)", path, settings.retentionDays());
        } catch (Throwable e) {
            unavailableReason = e.toString();
            LOG.warn("Request analytics disabled — could not open DuckDB: {}", e.toString());
        }
    }

    /** Lazy holder: built after all static fields are initialised. */
    private static final class Holder {
        static final AccessLogStore INSTANCE = new AccessLogStore();
    }

    public static AccessLogStore getInstance() {
        return Holder.INSTANCE;
    }

    /** Whether the proxy should build records at all (cheap check on the hot path). */
    public boolean isRecording() {
        return available && settings.enabled();
    }

    public Settings getSettings() {
        return settings;
    }

    /** Never blocks: drops the record (and counts it) when the writer is behind. */
    public void submit(AccessRecord r) {
        if (!isRecording()) return;
        if (queued.incrementAndGet() > QUEUE_CAPACITY) {
            queued.decrementAndGet();
            dropped.increment();
            return;
        }
        queue.offer(r);
    }

    // ------------------------------------------------------------------ storage

    private void open() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        path = System.getProperty("scaleguard.analytics.path", LicenceUtil.dataDir() + File.separator + "analytics.duckdb");
        db = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + path);
        try (Statement s = db.createStatement()) {
            createSchema(s);
            s.execute("SET TimeZone = 'UTC'");
            // Queries from the SQL console can't read or write files, attach databases or load extensions.
            s.execute("SET enable_external_access = false");
            // Analytics must never compete with proxying for CPU: one DuckDB worker (queries stay fast enough for
            // dashboards; override with -Dscaleguard.analytics.threads=N for heavy ad-hoc analysis).
            s.execute("SET threads = " + Math.max(1, Integer.getInteger("scaleguard.analytics.threads", 1)));
            s.execute("SET memory_limit = '" + System.getProperty("scaleguard.analytics.memory", "512MB") + "'");
        }
    }

    /**
     * Writer thread CPU priority, -Dscaleguard.analytics.writerPriority=normal (default) | low (nice 10) | idle (SCHED_IDLE).
     * Measured on a saturated proxy, normal is best: a lower-priority writer falls behind, and the backlog of queued
     * records costs more in garbage collection than the priority saves. low/idle suit servers where analytics may lag
     * (rows are dropped and counted if the queue overflows; traffic is never slowed).
     */
    static void lowerWriterPriority() {
        String mode = System.getProperty("scaleguard.analytics.writerPriority", "normal").toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("normal")) return;
        try {
            java.nio.file.Path self = java.nio.file.Files.readSymbolicLink(java.nio.file.Path.of("/proc/thread-self"));
            String tid = self.getFileName().toString();
            String[] cmd = mode.equals("idle") ? new String[]{"chrt", "--idle", "-p", "0", tid} : new String[]{"renice", "-n", "10", "-p", tid};
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) LOG.info("Request analytics writer runs at {} CPU priority", mode);
            else LOG.debug("Could not lower the analytics writer's priority ({})", String.join(" ", cmd));
        } catch (Throwable e) {
            LOG.debug("Analytics writer priority unchanged: {}", e.toString());   // not Linux, or tools missing
        }
    }

    private void writerLoop() {
        lowerWriterPriority();
        List<AccessRecord> batch = new ArrayList<>(BATCH);
        while (true) {
            try {
                AccessRecord r;
                while (batch.size() < BATCH && (r = queue.poll()) != null) batch.add(r);
                if (!batch.isEmpty()) {
                    queued.addAndGet(-batch.size());
                    write(batch);
                    batch.clear();
                }
                if (queued.get() < BATCH) Thread.sleep(FLUSH_MS);   // batch up: one append + commit per second
                long now = System.currentTimeMillis();
                if (now - lastRetentionRun > TimeUnit.HOURS.toMillis(1)) {
                    lastRetentionRun = now;
                    applyRetention();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOG.warn("Access log write failed ({} rows lost): {}", batch.size(), t.toString());
                dropped.add(batch.size());
                batch.clear();
            }
        }
    }

    private synchronized void write(List<AccessRecord> rows) throws SQLException {
        // New dimension values first (rare: a new route, host, backend…), so the view can resolve every code.
        for (AccessRecord r : rows) {
            code(r.method); code(r.host); code(r.protocol); code(r.routeId); code(r.routeName); code(r.backend); code(r.outcome);
        }
        code(node);
        if (!newDims.isEmpty()) {
            try (DuckDBAppender d = db.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "access_log_dims")) {
                for (Map.Entry<String, Integer> e : newDims.entrySet()) {
                    d.beginRow();
                    d.append(e.getValue());
                    d.append(e.getKey());
                    d.endRow();
                }
                d.flush();
            }
            newDims.clear();
        }
        int nodeCode = code(node);
        try (DuckDBAppender a = db.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "access_log_rows")) {
            for (AccessRecord r : rows) {
                a.beginRow();
                a.append(r.tsMillis);
                String t = r.traceId;
                if (t != null && t.length() == 32) {
                    a.append(Long.parseUnsignedLong(t, 0, 16, 16));
                    a.append(Long.parseUnsignedLong(t, 16, 32, 16));
                } else {
                    a.append(0L);
                    a.append(0L);
                }
                a.append(r.clientIp);
                a.append(code(r.method));
                a.append(code(r.host));
                a.append(r.path);
                a.append(r.query);
                a.append(code(r.protocol));
                a.append(code(r.routeId));
                a.append(code(r.routeName));
                a.append(code(r.backend));
                a.append((short) r.status);
                a.append((short) r.grpcStatus);
                a.append(r.bytesIn);
                a.append(r.bytesOut);
                a.append(r.durationMs);
                a.append(code(r.outcome));
                a.append(r.wafRule);
                a.append(AccessRecord.cap(r.userAgent, 512));
                a.append(AccessRecord.cap(r.referer, 1024));
                a.append(r.tls);
                a.append(nodeCode);
                a.endRow();
            }
            a.flush();
        }
        written.add(rows.size());
    }

    // ------------------------------------------------------------------ compact storage

    /*
     * Rows are stored compactly: repeated text (method, host, protocol, route, backend, outcome, node) as integer codes
     * into access_log_dims, the trace id as two 64-bit integers. That makes each row ~2x cheaper for DuckDB to append
     * and compress than storing every value as text. The view access_log_raw puts the text back, with exactly the
     * columns of the original table, so dashboards, exports and SQL console queries are unchanged.
     */
    private final Map<String, Integer> dimIds = new java.util.HashMap<>();
    private final Map<String, Integer> newDims = new java.util.LinkedHashMap<>();
    private int nextDim = 1;

    /** Code for a text value (0 = null); new values are queued for the dims table. */
    private int code(String v) {
        if (v == null) return 0;
        Integer id = dimIds.get(v);
        if (id != null) return id;
        if (v.length() > 512) v = v.substring(0, 512);
        id = dimIds.get(v);
        if (id != null) return id;
        int nid = nextDim++;
        dimIds.put(v, nid);
        newDims.put(v, nid);
        return nid;
    }

    private void createSchema(Statement s) throws SQLException {
        s.execute("CREATE TABLE IF NOT EXISTS access_log_dims (id INTEGER, value VARCHAR)");
        s.execute("""
                CREATE TABLE IF NOT EXISTS access_log_rows (
                  ts_ms BIGINT, trace_hi BIGINT, trace_lo BIGINT, client_ip VARCHAR, method INTEGER, host INTEGER, path VARCHAR,
                  query VARCHAR, protocol INTEGER, route_id INTEGER, route_name INTEGER, backend INTEGER,
                  status SMALLINT, grpc_status SMALLINT, bytes_in BIGINT, bytes_out BIGINT, duration_ms DOUBLE,
                  outcome INTEGER, waf_rule VARCHAR, user_agent VARCHAR, referer VARCHAR, tls BOOLEAN, node INTEGER)""");
        // Data written by earlier versions lived in a table called access_log_raw: keep it, under another name.
        boolean legacy = false;
        try (ResultSet rs = s.executeQuery("SELECT table_type FROM information_schema.tables WHERE table_name = 'access_log_raw'")) {
            if (rs.next() && "BASE TABLE".equalsIgnoreCase(rs.getString(1))) {
                s.execute("DROP VIEW IF EXISTS access_log");
                s.execute("ALTER TABLE access_log_raw RENAME TO access_log_v1");
                LOG.info("Request analytics: existing rows kept as access_log_v1 (new rows use compact storage)");
            }
        }
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_name = 'access_log_v1'")) {
            legacy = rs.next() && rs.getLong(1) > 0;
        }
        s.execute("CREATE OR REPLACE VIEW access_log_raw AS " + rawViewSql(legacy));
        s.execute("CREATE OR REPLACE VIEW access_log AS SELECT to_timestamp(ts_ms / 1000.0) AS ts, * EXCLUDE (ts_ms) FROM access_log_raw");
        try (ResultSet rs = s.executeQuery("SELECT id, value FROM access_log_dims")) {
            while (rs.next()) {
                dimIds.put(rs.getString(2), rs.getInt(1));
                nextDim = Math.max(nextDim, rs.getInt(1) + 1);
            }
        }
        hasLegacy = legacy;
    }

    private boolean hasLegacy;

    /** access_log_raw: the compact rows with their text put back, in the column order of the original table. */
    private static String rawViewSql(boolean legacy) {
        return "SELECT r.ts_ms,"
                + " CASE WHEN r.trace_hi = 0 AND r.trace_lo = 0 THEN NULL"
                + "  ELSE lower(lpad(to_hex(r.trace_hi), 16, '0') || lpad(to_hex(r.trace_lo), 16, '0')) END AS trace_id,"
                + " r.client_ip, dm.value AS method, dh.value AS host, r.path, r.query, dp.value AS protocol,"
                + " dr.value AS route_id, dn.value AS route_name, db.value AS backend, r.status, r.grpc_status, r.bytes_in,"
                + " r.bytes_out, r.duration_ms, dout.value AS outcome, r.waf_rule, r.user_agent, r.referer, r.tls, dnode.value AS node"
                + " FROM access_log_rows r"
                + " LEFT JOIN access_log_dims dm ON dm.id = r.method LEFT JOIN access_log_dims dh ON dh.id = r.host"
                + " LEFT JOIN access_log_dims dp ON dp.id = r.protocol LEFT JOIN access_log_dims dr ON dr.id = r.route_id"
                + " LEFT JOIN access_log_dims dn ON dn.id = r.route_name LEFT JOIN access_log_dims db ON db.id = r.backend"
                + " LEFT JOIN access_log_dims dout ON dout.id = r.outcome LEFT JOIN access_log_dims dnode ON dnode.id = r.node"
                + (legacy ? " UNION ALL SELECT * FROM access_log_v1" : "");
    }

    private void flushOnShutdown() {
        List<AccessRecord> rest = new ArrayList<>();
        AccessRecord r;
        while ((r = queue.poll()) != null) rest.add(r);
        try {
            if (!rest.isEmpty()) write(rest);
            synchronized (this) {
                try (Statement s = db.createStatement()) { s.execute("CHECKPOINT"); }
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void applyRetention() {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(settings.retentionDays());
        try (Statement s = db.createStatement()) {
            int n = s.executeUpdate("DELETE FROM access_log_rows WHERE ts_ms < " + cutoff);
            if (hasLegacy) {
                n += s.executeUpdate("DELETE FROM access_log_v1 WHERE ts_ms < " + cutoff);
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM access_log_v1")) {
                    if (rs.next() && rs.getLong(1) == 0) {     // old rows aged out: drop the legacy table
                        s.execute("CREATE OR REPLACE VIEW access_log_raw AS " + rawViewSql(false));
                        s.execute("DROP TABLE access_log_v1");
                        hasLegacy = false;
                    }
                }
            }
            if (n > 0) LOG.info("Access log retention: removed {} rows older than {} days", n, settings.retentionDays());
        } catch (SQLException e) {
            LOG.warn("Access log retention failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ settings

    public synchronized void update(Settings s) throws Exception {
        long now = System.currentTimeMillis();
        List<SystemProperty> props = new ArrayList<>();
        props.add(prop(P_ENABLED, String.valueOf(s.enabled()), now));
        props.add(prop(P_RETENTION, String.valueOf(s.retentionDays()), now));
        SystemPropertyDB.getInstance().save(props);
        boolean shorter = s.retentionDays() < settings.retentionDays();
        settings = s;
        if (shorter && available) lastRetentionRun = 0;   // apply a shorter retention on the next writer tick
    }

    /** Re-reads the settings from the database (another cluster node changed them). */
    public synchronized void reloadSettings() {
        Settings s = loadSettings();
        boolean shorter = s.retentionDays() < settings.retentionDays();
        settings = s;
        if (shorter && available) lastRetentionRun = 0;
    }

    private static Settings loadSettings() {
        boolean enabled = true;
        int retention = Integer.getInteger("scaleguard.analytics.retentionDays", 7);
        try {
            for (SystemProperty p : SystemPropertyDB.getInstance().readAll()) {
                if (p.getName() == null || p.getValue() == null) continue;
                switch (p.getName()) {
                    case P_ENABLED -> enabled = Boolean.parseBoolean(p.getValue());
                    case P_RETENTION -> retention = Integer.parseInt(p.getValue());
                    default -> { }
                }
            }
        } catch (Exception e) {
            LOG.debug("analytics settings: using defaults ({})", e.toString());
        }
        return new Settings(enabled, retention);
    }

    private static SystemProperty prop(String name, String value, long now) {
        SystemProperty p = new SystemProperty();
        p.setId(name);
        p.setName(name);
        p.setGroupname("analytics");
        p.setValue(value);
        p.setMts(now);
        p.setUts(now);
        return p;
    }

    private static String nodeName() {
        return com.scaleguard.server.cluster.NodeIdentity.name();
    }

    public String node() {
        return node;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available);
        if (!available) m.put("reason", unavailableReason);
        m.put("path", path);
        m.put("written", written.sum());
        m.put("dropped", dropped.sum());
        m.put("queued", Math.max(0, queued.get()));
        File f = path == null ? null : new File(path);
        m.put("fileBytes", f != null && f.exists() ? f.length() : 0);
        return m;
    }

    // ------------------------------------------------------------------ reads

    /** Result of a read query: column names and rows (values as JSON-friendly objects). */
    public record Table(List<String> columns, List<List<Object>> rows, boolean truncated, long millis) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("columns", columns);
            m.put("rows", rows);
            m.put("truncated", truncated);
            m.put("ms", millis);
            return m;
        }

        /** Rows as maps (column → value); convenient for fixed queries. */
        public List<Map<String, Object>> records() {
            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (List<Object> r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) m.put(columns.get(i), r.get(i));
                out.add(m);
            }
            return out;
        }
    }

    /**
     * Runs a read query on its own connection (DuckDB connections aren't shared across threads) inside a transaction
     * that is always rolled back — so even a statement that slipped past validation can't change anything.
     */
    public Table query(String sql, List<Object> params, int maxRows, int timeoutSeconds) throws SQLException {
        if (!available) throw new SQLException("Analytics storage is not available: " + unavailableReason);
        long t0 = System.nanoTime();
        Connection c;
        synchronized (this) {
            c = db.duplicate();
        }
        try (c) {
            try (Statement tz = c.createStatement()) { tz.execute("SET TimeZone = 'UTC'"); }   // results shown in UTC
            c.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; params != null && i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                java.util.concurrent.ScheduledFuture<?> cancel = TIMEOUTS.schedule(() -> {
                    try { ps.cancel(); } catch (Exception ignored) { }
                }, timeoutSeconds, TimeUnit.SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    List<String> cols = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
                    List<List<Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= maxRows) { truncated = true; break; }
                        List<Object> row = new ArrayList<>(n);
                        for (int i = 1; i <= n; i++) row.add(jsonValue(rs.getObject(i)));
                        rows.add(row);
                    }
                    return new Table(cols, rows, truncated, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                } finally {
                    cancel.cancel(false);
                }
            } finally {
                try { c.rollback(); } catch (Exception ignored) { }
            }
        }
    }

    private static final java.util.concurrent.ScheduledExecutorService TIMEOUTS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sg-analytics-timeout");
                t.setDaemon(true);
                return t;
            });

    private static Object jsonValue(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) {
            if (v instanceof Double d && (d.isNaN() || d.isInfinite())) return null;
            if (v instanceof java.math.BigInteger b) return b.longValue();
            if (v instanceof java.math.BigDecimal b) return b.doubleValue();
            return v;
        }
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString();
        if (v instanceof java.time.temporal.TemporalAccessor t) return t.toString();
        return String.valueOf(v);
    }
}
