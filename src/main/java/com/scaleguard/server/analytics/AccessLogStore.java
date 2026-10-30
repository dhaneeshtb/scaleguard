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
    private static final int BATCH = 20_000;

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
            s.execute("""
                    CREATE TABLE IF NOT EXISTS access_log_raw (
                      ts_ms BIGINT, trace_id VARCHAR, client_ip VARCHAR, method VARCHAR, host VARCHAR, path VARCHAR,
                      query VARCHAR, protocol VARCHAR, route_id VARCHAR, route_name VARCHAR, backend VARCHAR,
                      status SMALLINT, grpc_status SMALLINT, bytes_in BIGINT, bytes_out BIGINT, duration_ms DOUBLE,
                      outcome VARCHAR, waf_rule VARCHAR, user_agent VARCHAR, referer VARCHAR, tls BOOLEAN, node VARCHAR)""");
            // ts is TIMESTAMPTZ (an absolute instant), so "ts > now() - INTERVAL 1 HOUR" is right whatever the time zone.
            s.execute("CREATE OR REPLACE VIEW access_log AS SELECT to_timestamp(ts_ms / 1000.0) AS ts, * EXCLUDE (ts_ms) FROM access_log_raw");
            s.execute("SET TimeZone = 'UTC'");
            // Queries from the SQL console can't read or write files, attach databases or load extensions.
            s.execute("SET enable_external_access = false");
            // Analytics must never compete with proxying for CPU: one DuckDB worker (queries stay fast enough for
            // dashboards; override with -Dscaleguard.analytics.threads=N for heavy ad-hoc analysis).
            s.execute("SET threads = " + Math.max(1, Integer.getInteger("scaleguard.analytics.threads", 1)));
            s.execute("SET memory_limit = '" + System.getProperty("scaleguard.analytics.memory", "512MB") + "'");
        }
    }

    private void writerLoop() {
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
                if (queued.get() < BATCH) Thread.sleep(250);   // batch up: one append + flush per quarter second
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
        try (DuckDBAppender a = db.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "access_log_raw")) {
            for (AccessRecord r : rows) {
                a.beginRow();
                a.append(r.tsMillis);
                a.append(r.traceId);
                a.append(r.clientIp);
                a.append(r.method);
                a.append(r.host);
                a.append(r.path);
                a.append(r.query);
                a.append(r.protocol);
                a.append(r.routeId);
                a.append(r.routeName);
                a.append(r.backend);
                a.append((short) r.status);
                a.append((short) r.grpcStatus);
                a.append(r.bytesIn);
                a.append(r.bytesOut);
                a.append(r.durationMs);
                a.append(r.outcome);
                a.append(r.wafRule);
                a.append(AccessRecord.cap(r.userAgent, 512));
                a.append(AccessRecord.cap(r.referer, 1024));
                a.append(r.tls);
                a.append(node);
                a.endRow();
            }
            a.flush();
        }
        written.add(rows.size());
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
            int n = s.executeUpdate("DELETE FROM access_log_raw WHERE ts_ms < " + cutoff);
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
