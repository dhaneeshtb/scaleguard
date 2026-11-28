package com.scaleguard.server.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.utils.JSON;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin API for traffic record &amp; replay (system migrations). Data lives in the analytics DuckDB; these tables are
 * also queryable from the analytics SQL console. Per node: a recording sees the traffic this node proxies.
 * <pre>
 * GET    /traffic                                   recordings (with capture counts), recent replays, recorder stats
 * POST   /traffic/recordings                        {"name","routeId"?,"pattern"? (regex on path),"methods"? "GET,POST","maxBodyBytes"?,"maxRequests"?}
 * POST   /traffic/recordings/{id}/stop
 * DELETE /traffic/recordings/{id}                   with its captures and replays
 * GET    /traffic/recordings/{id}/captures?after=&amp;limit=
 * POST   /traffic/replays                           {"recordingId","target":"http://new:8080","concurrency"?,"ignoreFields"? [],"setHeaders"? {},"methods"?,"timeoutSeconds"?}
 * GET    /traffic/replays/{id}?result=mismatch|error|match&amp;after=&amp;limit=
 * POST   /traffic/replays/{id}/cancel
 * </pre>
 */
public class TrafficRoute implements RequestRoute {

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public boolean receivesQueryString() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) {
        String[] pq = uri.split("\\?", 2);
        String[] p = pq[0].replaceAll("^/+|/+$", "").split("/");
        Map<String, String> q = AnalyticsRoute.params(pq.length > 1 ? pq[1] : "");
        String m = method.toUpperCase();
        String area = p.length > 1 ? p[1] : "";
        String id = p.length > 2 ? p[2] : null;
        String action = p.length > 3 ? p[3] : null;
        return RequestRoutingResponse.later(() -> {
            try {
                TrafficRecorder.requireStarted();
                if (area.isEmpty() && m.equals("GET")) return ok(overview());
                if (area.equals("recordings")) {
                    if (id == null && m.equals("POST")) return startRecording(JSON.parse(body == null || body.isBlank() ? "{}" : body));
                    if (id != null && "stop".equals(action) && m.equals("POST")) return TrafficRecorder.stop(id) ? ok(Map.of("stopped", id)) : notFound();
                    if (id != null && action == null && m.equals("DELETE")) return TrafficRecorder.delete(id) ? ok(Map.of("deleted", id)) : notFound();
                    if (id != null && "captures".equals(action) && m.equals("GET")) return ok(captures(id, q));
                }
                if (area.equals("replays")) {
                    if (id == null && m.equals("POST")) return startReplay(JSON.parse(body == null || body.isBlank() ? "{}" : body));
                    if (id != null && "cancel".equals(action) && m.equals("POST")) {
                        TrafficReplayer.Run run = TrafficReplayer.RUNS.get(id);
                        if (run == null) return notFound();
                        run.cancel = true;
                        return ok(Map.of("cancelling", id));
                    }
                    if (id != null && action == null && m.equals("GET")) return replay(id, q);
                }
                return RequestRoutingResponse.response(404, JSON.toString(Map.of("message", "Unknown traffic endpoint.")));
            } catch (IllegalArgumentException e) {   // includes PatternSyntaxException
                return error(e.getMessage());
            }
        });
    }

    private static RequestRoutingResponse startRecording(JsonNode n) throws Exception {
        String name = n.path("name").asText("").trim();
        if (name.isEmpty()) return error("name is required.");
        int maxBody = n.path("maxBodyBytes").asInt(1 << 20);
        if (maxBody < 0 || maxBody > 16 << 20) return error("maxBodyBytes must be between 0 and 16777216.");
        long maxRequests = n.path("maxRequests").asLong(100_000);
        if (maxRequests < 0) return error("maxRequests must be 0 (no limit) or more.");
        String id = TrafficRecorder.start(name, text(n, "routeId"), text(n, "pattern"), text(n, "methods"), maxBody, maxRequests);
        return ok(Map.of("id", id));
    }

    private static RequestRoutingResponse startReplay(JsonNode n) throws Exception {
        String rec = text(n, "recordingId");
        if (rec == null) return error("recordingId is required.");
        URI target;
        try {
            target = URI.create(n.path("target").asText(""));
        } catch (IllegalArgumentException e) {
            return error("target must be a URL like http://new-server:8080");
        }
        if (target.getHost() == null || !Set.of("http", "https").contains(String.valueOf(target.getScheme()).toLowerCase())) {
            return error("target must be a URL like http://new-server:8080");
        }
        int concurrency = n.path("concurrency").asInt(4);
        if (concurrency < 1 || concurrency > 64) return error("concurrency must be between 1 and 64.");
        int timeout = n.path("timeoutSeconds").asInt(30);
        if (timeout < 1 || timeout > 600) return error("timeoutSeconds must be between 1 and 600.");
        Set<String> ignore = new java.util.HashSet<>();
        n.path("ignoreFields").forEach(f -> ignore.add(f.asText()));
        Map<String, String> set = new LinkedHashMap<>();
        n.path("setHeaders").fields().forEachRemaining(e -> set.put(e.getKey(), e.getValue().asText()));
        long count = (long) one("SELECT count(*) FROM traffic_captures WHERE recording_id = ?", rec);
        if (count == 0) return error("Recording " + rec + " has no captured requests.");
        TrafficReplayer.Run run = TrafficReplayer.start(new TrafficReplayer.Options(rec, target, concurrency, Set.copyOf(ignore),
                set, TrafficRecorder.methodSet(text(n, "methods")), timeout));
        return ok(Map.of("id", run.id, "requests", count));
    }

    private static Map<String, Object> overview() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recorder", TrafficRecorder.stats());
        m.put("recordings", rows("SELECT r.id, r.name, r.route_id, r.pattern, r.methods, r.max_body, r.max_requests, r.started_ms, r.stopped_ms,"
                + " (SELECT count(*) FROM traffic_captures c WHERE c.recording_id = r.id) AS captures"
                + " FROM traffic_recordings r ORDER BY r.started_ms DESC"));
        List<Map<String, Object>> replays = rows("SELECT * FROM traffic_replays ORDER BY started_ms DESC LIMIT 50");
        for (Map<String, Object> r : replays) live(r);
        m.put("replays", replays);
        return m;
    }

    /** A running replay's totals come from memory (the row is written when it ends). */
    private static void live(Map<String, Object> r) {
        TrafficReplayer.Run run = TrafficReplayer.RUNS.get((String) r.get("id"));
        if (run == null) return;
        r.put("total", run.total.get());
        r.put("matched", run.matched.get());
        r.put("mismatched", run.mismatched.get());
        r.put("errors", run.errors.get());
    }

    private static List<Map<String, Object>> captures(String id, Map<String, String> q) throws Exception {
        return rows("SELECT seq, ts_ms, trace_id, method, host, uri, req_headers, req_body, req_truncated, status, resp_headers,"
                + " resp_body, resp_truncated, duration_ms, backend FROM traffic_captures WHERE recording_id = ? AND seq > ?"
                + " ORDER BY seq LIMIT ?", id, longParam(q, "after", 0), Math.min(500, (int) longParam(q, "limit", 50)));
    }

    private static RequestRoutingResponse replay(String id, Map<String, String> q) throws Exception {
        List<Map<String, Object>> head = rows("SELECT * FROM traffic_replays WHERE id = ?", id);
        if (head.isEmpty()) return notFound();
        Map<String, Object> m = head.get(0);
        live(m);
        String result = q.get("result");
        String sql = "SELECT seq, method, uri, expected_status, actual_status, result, reason, actual_headers, actual_body, duration_ms"
                + " FROM traffic_replay_results WHERE replay_id = ? AND seq > ?" + (result != null ? " AND result = ?" : "")
                + " ORDER BY seq LIMIT ?";
        int limit = Math.min(500, (int) longParam(q, "limit", 100));
        m.put("results", result != null ? rows(sql, id, longParam(q, "after", 0), result, limit) : rows(sql, id, longParam(q, "after", 0), limit));
        return ok(m);
    }

    // ------------------------------------------------------------------ helpers

    private static final Set<String> BODIES = Set.of("req_body", "resp_body", "actual_body");

    private static List<Map<String, Object>> rows(String sql, Object... params) throws Exception {
        return AccessLogStore.getInstance().withDb(db -> {
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            String col = md.getColumnLabel(i);
                            if (BODIES.contains(col)) r.put(col, bodyView(rs.getBytes(i)));
                            else if (col.endsWith("_headers") && rs.getString(i) != null) r.put(col, JSON.parse(rs.getString(i)));
                            else r.put(col, rs.getObject(i));
                        }
                        out.add(r);
                    }
                    return out;
                }
            }
        });
    }

    private static Object one(String sql, Object... params) throws Exception {
        List<Map<String, Object>> r = rows(sql, params);
        Object v = r.isEmpty() ? null : r.get(0).values().iterator().next();
        return v instanceof Number num ? num.longValue() : v;
    }

    /** Body as text when it is UTF-8, else base64. */
    static Object bodyView(byte[] b) {
        if (b == null) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return Map.of("base64", java.util.Base64.getEncoder().encodeToString(b));
        }
    }

    private static String text(JsonNode n, String k) {
        String v = n.path(k).asText("").trim();
        return v.isEmpty() ? null : v;
    }

    private static long longParam(Map<String, String> q, String k, long def) {
        try { return Long.parseLong(q.get(k)); } catch (Exception e) { return def; }
    }

    private static RequestRoutingResponse ok(Object o) {
        return RequestRoutingResponse.succes(JSON.toString(o));
    }

    private static RequestRoutingResponse error(String msg) {
        return RequestRoutingResponse.response(400, JSON.toString(Map.of("message", msg)));
    }

    private static RequestRoutingResponse notFound() {
        return RequestRoutingResponse.response(404, JSON.toString(Map.of("message", "Not found.")));
    }
}
