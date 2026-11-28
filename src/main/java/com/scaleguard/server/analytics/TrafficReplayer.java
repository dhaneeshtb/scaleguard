package com.scaleguard.server.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.utils.Constants;
import com.scaleguard.utils.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * Replays a recording against another server (the system being migrated to) and compares each answer with the recorded
 * one: status, then body — byte-equal, or equal as JSON after dropping {@code ignoreFields} (ids, timestamps…) at any
 * depth. gzip bodies are compared decompressed. Results go to {@code traffic_replay_results}; the run's totals to
 * {@code traffic_replays}. Requests are sent in recorded order, {@code concurrency} at a time.
 */
public final class TrafficReplayer {

    private static final Logger LOG = LoggerFactory.getLogger(TrafficReplayer.class);
    private static final int KEEP_BODY = 64 * 1024;   // actual body kept for mismatches (enough to diff)

    /** Headers the JDK client sets itself, refuses, or that are per-hop / per-proxy. */
    private static final Set<String> SKIP = Set.of("host", "connection", "content-length", "expect", "upgrade",
            "transfer-encoding", "keep-alive", "te", "trailer", "proxy-connection", "http2-settings");

    public record Options(String recordingId, URI target, int concurrency, Set<String> ignoreFields,
                          Map<String, String> setHeaders, Set<String> methods, int timeoutSeconds) {}

    static final class Run {
        final String id = java.util.UUID.randomUUID().toString().substring(0, 8);
        final Options o;
        final long startedMs = System.currentTimeMillis();
        final AtomicLong total = new AtomicLong(), matched = new AtomicLong(), mismatched = new AtomicLong(), errors = new AtomicLong();
        volatile String state = "running";
        volatile boolean cancel;

        Run(Options o) {
            this.o = o;
        }
    }

    static final Map<String, Run> RUNS = new ConcurrentHashMap<>();

    record Result(long seq, String method, String uri, int expected, int actual, String result, String reason,
                  String actualHeaders, byte[] actualBody, double ms) {}

    static Run start(Options o) throws SQLException {
        TrafficRecorder.requireStarted();
        Run run = new Run(o);
        AccessLogStore.getInstance().withDb(db -> {
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO traffic_replays VALUES (?, ?, ?, ?, ?, NULL, 'running', 0, 0, 0, 0)")) {
                ps.setString(1, run.id);
                ps.setString(2, o.recordingId());
                ps.setString(3, o.target().toString());
                ps.setString(4, JSON.toString(Map.of("concurrency", o.concurrency(), "ignoreFields", o.ignoreFields(),
                        "setHeaders", o.setHeaders().keySet(), "methods", o.methods(), "timeoutSeconds", o.timeoutSeconds())));
                ps.setLong(5, run.startedMs);
                ps.executeUpdate();
            }
            return null;
        });
        RUNS.put(run.id, run);
        Thread.ofVirtual().name("sg-replay-" + run.id).start(() -> execute(run));
        return run;
    }

    private static void execute(Run run) {
        Options o = run.o;
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        Semaphore slots = new Semaphore(o.concurrency());
        List<Result> pending = new ArrayList<>();
        try {
            long after = 0;
            while (!run.cancel) {
                List<Object[]> page = page(o.recordingId(), after);
                if (page.isEmpty()) break;
                for (Object[] row : page) {
                    after = (long) row[0];
                    if (run.cancel) break;
                    if (!o.methods().isEmpty() && !o.methods().contains((String) row[1])) continue;
                    slots.acquire();
                    Thread.ofVirtual().start(() -> {
                        try {
                            Result r = replayOne(http, o, row);
                            run.total.incrementAndGet();
                            ("match".equals(r.result()) ? run.matched : "mismatch".equals(r.result()) ? run.mismatched : run.errors).incrementAndGet();
                            synchronized (pending) {
                                pending.add(r);
                            }
                        } finally {
                            slots.release();
                        }
                    });
                }
                flush(run.id, pending);
            }
            slots.acquire(o.concurrency());   // wait for the last requests
            flush(run.id, pending);
            run.state = run.cancel ? "cancelled" : "done";
        } catch (Throwable t) {
            LOG.warn("Replay {} failed: {}", run.id, t.toString());
            run.state = "failed: " + t.getMessage();
        } finally {
            finish(run);
            RUNS.remove(run.id);
        }
    }

    /** Next 500 captures after {@code seq}: {seq, method, uri, reqHeaders, reqBody, reqTruncated, status, respHeaders, respBody, respTruncated}. */
    private static List<Object[]> page(String recordingId, long after) throws SQLException {
        return AccessLogStore.getInstance().withDb(db -> {
            List<Object[]> out = new ArrayList<>();
            try (PreparedStatement ps = db.prepareStatement("SELECT seq, method, uri, req_headers, req_body, req_truncated, status,"
                    + " resp_headers, resp_body, resp_truncated FROM traffic_captures WHERE recording_id = ? AND seq > ? ORDER BY seq LIMIT 500")) {
                ps.setString(1, recordingId);
                ps.setLong(2, after);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBytes(5),
                                rs.getBoolean(6), rs.getInt(7), rs.getString(8), rs.getBytes(9), rs.getBoolean(10)});
                    }
                }
            }
            return out;
        });
    }

    static Result replayOne(HttpClient http, Options o, Object[] row) {
        long seq = (long) row[0];
        String method = (String) row[1], uri = (String) row[2];
        int expected = (int) row[6];
        if ((boolean) row[5]) return new Result(seq, method, uri, expected, 0, "error", "request body was truncated when recorded", null, null, 0);
        long t0 = System.nanoTime();
        try {
            String base = o.target().toString().replaceAll("/+$", "");
            byte[] body = row[4] == null ? new byte[0] : (byte[]) row[4];
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + uri)).timeout(Duration.ofSeconds(o.timeoutSeconds()))
                    .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            for (JsonNode h : JSON.parse((String) row[3])) {
                String name = h.get(0).asText();
                String lower = name.toLowerCase(Locale.ROOT);
                if (SKIP.contains(lower) || lower.startsWith(":") || lower.startsWith("x-http2-") || o.setHeaders().keySet().stream().anyMatch(name::equalsIgnoreCase)) continue;
                try {
                    b.header(name, h.get(1).asText());
                } catch (IllegalArgumentException ignored) {
                    // a header the JDK client refuses: the request goes without it
                }
            }
            o.setHeaders().forEach(b::setHeader);
            HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            double ms = (System.nanoTime() - t0) / 1e6;
            String why = compare(expected, (String) row[7], (byte[]) row[8], (boolean) row[9], resp.statusCode(),
                    resp.headers().firstValue("content-encoding").orElse(null), resp.body(), o.ignoreFields());
            if (why == null) return new Result(seq, method, uri, expected, resp.statusCode(), "match", null, null, null, ms);
            byte[] keep = resp.body().length > KEEP_BODY ? java.util.Arrays.copyOf(resp.body(), KEEP_BODY) : resp.body();
            return new Result(seq, method, uri, expected, resp.statusCode(), "mismatch", why, JSON.toString(resp.headers().map()), keep, ms);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(seq, method, uri, expected, 0, "error", String.valueOf(e), null, null, (System.nanoTime() - t0) / 1e6);
        }
    }

    /** Null when the answers agree, else why not. */
    static String compare(int expStatus, String expHeaders, byte[] expBody, boolean expTruncated,
                          int status, String encoding, byte[] body, Set<String> ignore) {
        if (expStatus != status) return "status " + expStatus + " → " + status;
        if (expTruncated) return null;   // recorded body was cut at maxBodyBytes: status is all we can compare
        byte[] a = decode(expBody, encodingOf(expHeaders)), b = decode(body, encoding);
        if (java.util.Arrays.equals(a, b)) return null;
        JsonNode ja = json(a), jb = json(b);
        if (ja == null || jb == null) return "body differs (" + a.length + " → " + b.length + " bytes)";
        strip(ja, ignore);
        strip(jb, ignore);
        String path = firstDiff(ja, jb, "$");
        return path == null ? null : "JSON differs at " + path;
    }

    private static String encodingOf(String headersJson) {
        if (headersJson == null) return null;
        for (JsonNode h : JSON.parse(headersJson)) if ("content-encoding".equalsIgnoreCase(h.get(0).asText())) return h.get(1).asText();
        return null;
    }

    private static byte[] decode(byte[] b, String encoding) {
        if (b == null) return new byte[0];
        if (encoding == null || !encoding.toLowerCase(Locale.ROOT).contains("gzip")) return b;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
            return in.readAllBytes();
        } catch (Exception e) {
            return b;   // not valid gzip (e.g. truncated): compare as is
        }
    }

    private static JsonNode json(byte[] b) {
        if (b.length == 0) return null;
        try {
            return Constants.OBJECT_MAPPER.readTree(b);
        } catch (Exception e) {
            return null;
        }
    }

    /** Removes fields named in {@code ignore} at any depth. */
    static void strip(JsonNode n, Set<String> ignore) {
        if (ignore.isEmpty() || n == null) return;
        if (n instanceof ObjectNode obj) obj.remove(ignore);
        for (JsonNode c : n) strip(c, ignore);
    }

    /** JSONPath-ish location of the first difference, or null when equal. */
    static String firstDiff(JsonNode a, JsonNode b, String at) {
        if (a.equals(b)) return null;
        if (a.isObject() && b.isObject()) {
            Set<String> names = new java.util.TreeSet<>();
            a.fieldNames().forEachRemaining(names::add);
            b.fieldNames().forEachRemaining(names::add);
            for (String f : names) {
                if (!a.has(f) || !b.has(f)) return at + "." + f + (a.has(f) ? " (missing)" : " (unexpected)");
                String d = firstDiff(a.get(f), b.get(f), at + "." + f);
                if (d != null) return d;
            }
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return at + " (length " + a.size() + " → " + b.size() + ")";
            Iterator<JsonNode> ia = a.elements(), ib = b.elements();
            for (int i = 0; ia.hasNext(); i++) {
                String d = firstDiff(ia.next(), ib.next(), at + "[" + i + "]");
                if (d != null) return d;
            }
        }
        return at;
    }

    private static void flush(String replayId, List<Result> pending) throws SQLException {
        List<Result> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        AccessLogStore.getInstance().withDb(db -> {
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO traffic_replay_results VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Result r : batch) {
                    ps.setString(1, replayId);
                    ps.setLong(2, r.seq());
                    ps.setString(3, r.method());
                    ps.setString(4, r.uri());
                    ps.setShort(5, (short) r.expected());
                    ps.setShort(6, (short) r.actual());
                    ps.setString(7, r.result());
                    ps.setString(8, r.reason());
                    ps.setString(9, r.actualHeaders());
                    ps.setBytes(10, r.actualBody());
                    ps.setDouble(11, r.ms());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    private static void finish(Run run) {
        try {
            AccessLogStore.getInstance().withDb(db -> {
                try (PreparedStatement ps = db.prepareStatement("UPDATE traffic_replays SET finished_ms = ?, state = ?, total = ?,"
                        + " matched = ?, mismatched = ?, errors = ? WHERE id = ?")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setString(2, run.state);
                    ps.setLong(3, run.total.get());
                    ps.setLong(4, run.matched.get());
                    ps.setLong(5, run.mismatched.get());
                    ps.setLong(6, run.errors.get());
                    ps.setString(7, run.id);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            LOG.warn("Could not save replay {} totals: {}", run.id, e.toString());
        }
    }
}
