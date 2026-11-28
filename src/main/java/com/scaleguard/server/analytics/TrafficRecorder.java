package com.scaleguard.server.analytics;

import com.scaleguard.utils.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * Traffic recording for system migrations: while a recording runs, proxied requests that match it (route, path regex,
 * methods) are stored with their full request and the backend's response in the analytics DuckDB
 * ({@code traffic_captures}), so {@link TrafficReplayer} can send them to a new system and compare the answers.
 *
 * <p>Hot path: one volatile read ({@link #ACTIVE}) while nothing is recorded. A matched request copies its body on the
 * I/O thread; the response is copied chunk by chunk as it streams; rows are written by a background thread. Bodies are
 * capped per recording ({@code maxBodyBytes}); a bounded byte budget drops captures (counted) instead of growing memory.
 */
public final class TrafficRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(TrafficRecorder.class);

    /** True while at least one recording runs. */
    public static volatile boolean ACTIVE;
    private static volatile List<Session> sessions = List.of();

    private static final long QUEUE_BYTES = Long.getLong("scaleguard.traffic.queueBytes", 256L << 20);
    private static final ConcurrentLinkedQueue<AccessRecord> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicLong queuedBytes = new AtomicLong();
    private static final LongAdder dropped = new LongAdder();
    private static volatile boolean started;

    private TrafficRecorder() {}

    public static final class Session {
        final String id, name, routeId;
        final Pattern pattern;
        final Set<String> methods;     // upper case; empty = all
        final int maxBody;
        final long maxRequests;
        final long startedMs;
        final AtomicLong seq;

        Session(String id, String name, String routeId, String pattern, Set<String> methods, int maxBody, long maxRequests,
                long startedMs, long seq) {
            this.id = id;
            this.name = name;
            this.routeId = routeId;
            this.pattern = pattern == null || pattern.isEmpty() ? null : Pattern.compile(pattern);
            this.methods = methods;
            this.maxBody = maxBody;
            this.maxRequests = maxRequests;
            this.startedMs = startedMs;
            this.seq = new AtomicLong(seq);
        }

        boolean matches(String route, String method, String path) {
            return (routeId == null || routeId.equals(route))
                    && (methods.isEmpty() || methods.contains(method))
                    && (pattern == null || pattern.matcher(path).find());
        }
    }

    /** One recorded exchange, filled on the connection's event loop. */
    public static final class Capture {
        final Session session;
        final long seq;
        final String uri, reqHeaders;
        final byte[] reqBody;
        final boolean reqTruncated;
        int status;
        String respHeaders;            // null = no backend response seen
        ByteArrayOutputStream respBody;
        boolean respTruncated;

        Capture(Session s, long seq, HttpRequest req) {
            this.session = s;
            this.seq = seq;
            this.uri = req.uri();
            this.reqHeaders = headersJson(req.headers());
            ByteBuf b = req instanceof FullHttpRequest f ? f.content() : null;
            int n = b == null ? 0 : b.readableBytes();
            this.reqTruncated = n > s.maxBody;
            this.reqBody = new byte[Math.min(n, s.maxBody)];
            if (b != null) b.getBytes(b.readerIndex(), reqBody);
        }

        public void head(HttpResponse r) {
            status = r.status().code();
            respHeaders = headersJson(r.headers());
            respBody = new ByteArrayOutputStream();
        }

        public void body(ByteBuf b) {
            if (respBody == null) return;
            int n = b.readableBytes(), room = session.maxBody - respBody.size();
            if (n > room) {
                respTruncated = true;
                n = Math.max(room, 0);
            }
            if (n == 0) return;
            byte[] tmp = new byte[n];
            b.getBytes(b.readerIndex(), tmp);
            respBody.write(tmp, 0, n);
        }

        long size() {
            return reqBody.length + (respBody == null ? 0 : respBody.size()) + reqHeaders.length() + 256;
        }
    }

    static String headersJson(HttpHeaders h) {
        List<List<String>> out = new ArrayList<>(h.size());
        h.iteratorAsString().forEachRemaining(e -> out.add(List.of(e.getKey(), e.getValue())));
        return JSON.toString(out);
    }

    /** A capture when a running recording wants this request, else null. Called on the I/O thread while ACTIVE. */
    public static Capture capture(String routeId, HttpRequest req) {
        String uri = req.uri();
        int q = uri.indexOf('?');
        String path = q < 0 ? uri : uri.substring(0, q);
        String method = req.method().name();
        for (Session s : sessions) {
            if (!s.matches(routeId, method, path)) continue;
            long seq = s.seq.incrementAndGet();
            if (s.maxRequests > 0 && seq > s.maxRequests) {
                Thread.ofVirtual().start(() -> stopQuietly(s.id));   // limit reached
                continue;
            }
            return new Capture(s, seq, req);
        }
        return null;
    }

    /** From {@link AccessRecord#finish}: queue the exchange for writing (only real backend answers are kept). */
    static void complete(AccessRecord r) {
        Capture c = r.capture;
        r.capture = null;
        if (!AccessRecord.PROXIED.equals(r.outcome) || c.respHeaders == null) return;
        long size = c.size();
        if (queuedBytes.addAndGet(size) > QUEUE_BYTES) {
            queuedBytes.addAndGet(-size);
            dropped.increment();
            return;
        }
        r.capture = c;
        QUEUE.offer(r);
    }

    // ------------------------------------------------------------------ storage

    public static synchronized void init() {
        if (started || !AccessLogStore.getInstance().isAvailable()) return;
        try {
            AccessLogStore.getInstance().withDb(db -> {
                try (Statement s = db.createStatement()) {
                    s.execute("""
                            CREATE TABLE IF NOT EXISTS traffic_recordings (id VARCHAR, name VARCHAR, route_id VARCHAR,
                              pattern VARCHAR, methods VARCHAR, max_body INTEGER, max_requests BIGINT, started_ms BIGINT,
                              stopped_ms BIGINT)""");
                    s.execute("""
                            CREATE TABLE IF NOT EXISTS traffic_captures (recording_id VARCHAR, seq BIGINT, ts_ms BIGINT,
                              trace_id VARCHAR, method VARCHAR, host VARCHAR, uri VARCHAR, req_headers VARCHAR, req_body BLOB,
                              req_truncated BOOLEAN, status SMALLINT, resp_headers VARCHAR, resp_body BLOB,
                              resp_truncated BOOLEAN, duration_ms DOUBLE, backend VARCHAR)""");
                    s.execute("""
                            CREATE TABLE IF NOT EXISTS traffic_replays (id VARCHAR, recording_id VARCHAR, target VARCHAR,
                              options VARCHAR, started_ms BIGINT, finished_ms BIGINT, state VARCHAR, total BIGINT,
                              matched BIGINT, mismatched BIGINT, errors BIGINT)""");
                    s.execute("""
                            CREATE TABLE IF NOT EXISTS traffic_replay_results (replay_id VARCHAR, seq BIGINT, method VARCHAR,
                              uri VARCHAR, expected_status SMALLINT, actual_status SMALLINT, result VARCHAR, reason VARCHAR,
                              actual_headers VARCHAR, actual_body BLOB, duration_ms DOUBLE)""");
                    // a restart ends replays that were running
                    s.execute("UPDATE traffic_replays SET state = 'interrupted' WHERE state = 'running'");
                }
                return null;
            });
            started = true;
            reloadSessions();
            Thread t = new Thread(TrafficRecorder::writerLoop, "sg-traffic-writer");
            t.setDaemon(true);
            t.start();
            Runtime.getRuntime().addShutdownHook(new Thread(TrafficRecorder::drain, "sg-traffic-flush"));
        } catch (SQLException e) {
            LOG.warn("Traffic recording unavailable: {}", e.toString());
        }
    }

    static void requireStarted() throws SQLException {
        if (!started) init();
        if (!started) throw new SQLException("Traffic recording needs analytics storage (DuckDB), which is not available.");
    }

    /** Running recordings, from the table (seq continues after the highest stored one). */
    private static void reloadSessions() throws SQLException {
        List<Session> list = AccessLogStore.getInstance().withDb(db -> {
            List<Session> out = new ArrayList<>();
            try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery(
                    "SELECT r.id, r.name, r.route_id, r.pattern, r.methods, r.max_body, r.max_requests, r.started_ms,"
                            + " (SELECT coalesce(max(seq), 0) FROM traffic_captures c WHERE c.recording_id = r.id)"
                            + " FROM traffic_recordings r WHERE r.stopped_ms IS NULL")) {
                while (rs.next()) {
                    out.add(new Session(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            methodSet(rs.getString(5)), rs.getInt(6), rs.getLong(7), rs.getLong(8), rs.getLong(9)));
                }
            }
            return out;
        });
        sessions = List.copyOf(list);
        ACTIVE = !list.isEmpty();
    }

    static Set<String> methodSet(String csv) {
        Set<String> m = new java.util.HashSet<>();
        if (csv != null) for (String p : csv.split(",")) if (!p.isBlank()) m.add(p.trim().toUpperCase(Locale.ROOT));
        return Set.copyOf(m);
    }

    /** Starts a recording; returns its id. Validates the pattern. */
    static String start(String name, String routeId, String pattern, String methods, int maxBody, long maxRequests) throws SQLException {
        requireStarted();
        if (pattern != null && !pattern.isEmpty()) Pattern.compile(pattern);   // throws PatternSyntaxException → 400
        String id = java.util.UUID.randomUUID().toString().substring(0, 8);
        AccessLogStore.getInstance().withDb(db -> {
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO traffic_recordings VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, routeId);
                ps.setString(4, pattern);
                ps.setString(5, String.join(",", methodSet(methods)));
                ps.setInt(6, maxBody);
                ps.setLong(7, maxRequests);
                ps.setLong(8, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
        reloadSessions();
        return id;
    }

    static boolean stop(String id) throws SQLException {
        requireStarted();
        int n = AccessLogStore.getInstance().withDb(db -> {
            try (PreparedStatement ps = db.prepareStatement("UPDATE traffic_recordings SET stopped_ms = ? WHERE id = ? AND stopped_ms IS NULL")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, id);
                return ps.executeUpdate();
            }
        });
        reloadSessions();
        return n > 0;
    }

    private static void stopQuietly(String id) {
        try {
            if (stop(id)) LOG.info("Traffic recording {} reached its request limit and stopped", id);
        } catch (SQLException e) {
            LOG.warn("Could not stop traffic recording {}: {}", id, e.toString());
        }
    }

    /** Deletes a recording with its captures and the replays made from it. */
    static boolean delete(String id) throws SQLException {
        requireStarted();
        int n = AccessLogStore.getInstance().withDb(db -> {
            int c;
            try (PreparedStatement ps = db.prepareStatement("DELETE FROM traffic_recordings WHERE id = ?")) {
                ps.setString(1, id);
                c = ps.executeUpdate();
            }
            for (String sql : new String[]{
                    "DELETE FROM traffic_captures WHERE recording_id = ?",
                    "DELETE FROM traffic_replay_results WHERE replay_id IN (SELECT id FROM traffic_replays WHERE recording_id = ?)",
                    "DELETE FROM traffic_replays WHERE recording_id = ?"}) {
                try (PreparedStatement ps = db.prepareStatement(sql)) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            }
            return c;
        });
        reloadSessions();
        return n > 0;
    }

    public static Map<String, Object> stats() {
        return Map.of("active", sessions.size(), "queuedBytes", Math.max(0, queuedBytes.get()), "dropped", dropped.sum());
    }

    private static void writerLoop() {
        while (true) {
            try {
                drain();
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOG.warn("Traffic capture write failed: {}", t.toString());
            }
        }
    }

    private static void drain() {
        List<AccessRecord> batch = new ArrayList<>();
        AccessRecord r;
        long bytes = 0;
        while (batch.size() < 1000 && (r = QUEUE.poll()) != null) {
            batch.add(r);
            bytes += r.capture.size();
        }
        if (batch.isEmpty()) return;
        try {
            AccessLogStore.getInstance().withDb(db -> {
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO traffic_captures VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (AccessRecord a : batch) {
                        Capture c = a.capture;
                        ps.setString(1, c.session.id);
                        ps.setLong(2, c.seq);
                        ps.setLong(3, a.tsMillis);
                        ps.setString(4, a.traceId);
                        ps.setString(5, a.method);
                        ps.setString(6, a.host);
                        ps.setString(7, c.uri);
                        ps.setString(8, c.reqHeaders);
                        ps.setBytes(9, c.reqBody);
                        ps.setBoolean(10, c.reqTruncated);
                        ps.setShort(11, (short) c.status);
                        ps.setString(12, c.respHeaders);
                        ps.setBytes(13, c.respBody.toByteArray());
                        ps.setBoolean(14, c.respTruncated);
                        ps.setDouble(15, a.durationMs);
                        ps.setString(16, a.backend);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            });
        } catch (SQLException e) {
            dropped.add(batch.size());
            LOG.warn("Traffic capture write failed ({} lost): {}", batch.size(), e.toString());
        } finally {
            queuedBytes.addAndGet(-bytes);
        }
    }
}
