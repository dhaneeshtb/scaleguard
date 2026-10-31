package com.scaleguard.server.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.utils.JSON;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Admin API for request analytics and access logs (DuckDB). Queries run off the I/O thread.
 * <pre>
 * GET  /analytics/summary?range=1h[&route=id]          KPIs, time series, top routes/paths/clients/backends, status codes
 * GET  /analytics/logs?range=&route=&status=&outcome=&ip=&q=&trace=&method=&limit=&before=   newest first
 * GET  /analytics/export?format=csv|ndjson&...same filters   up to 100,000 rows
 * POST /analytics/query {"sql":"SELECT ... FROM access_log ..."}   read-only SQL console (1,000 rows, 15 s)
 * GET  /analytics/settings · POST /analytics/settings {"enabled","retentionDays"}
 * </pre>
 */
public class AnalyticsRoute implements RequestRoute {

    static final Map<String, long[]> RANGES = new LinkedHashMap<>();   // range → {window ms, bucket ms}
    static {
        RANGES.put("15m", new long[]{15 * 60_000L, 15_000L});
        RANGES.put("1h", new long[]{3_600_000L, 60_000L});
        RANGES.put("6h", new long[]{6 * 3_600_000L, 5 * 60_000L});
        RANGES.put("24h", new long[]{24 * 3_600_000L, 20 * 60_000L});
        RANGES.put("7d", new long[]{7 * 24 * 3_600_000L, 3 * 3_600_000L});
        RANGES.put("30d", new long[]{30 * 24 * 3_600_000L, 12 * 3_600_000L});
    }

    private static final Pattern READ_ONLY_START = Pattern.compile(
            "^(select|with|from|summarize|describe|pivot|unpivot|values|show|table)\\b", Pattern.CASE_INSENSITIVE);

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
        String[] parts = pq[0].replaceAll("^/+|/+$", "").split("/");
        Map<String, String> q = params(pq.length > 1 ? pq[1] : "");
        String action = parts.length > 1 ? parts[1] : "summary";
        AccessLogStore store = AccessLogStore.getInstance();

        // Peers pull this node's own rows from here (cluster analytics replication).
        if ("replicate".equals(action)) {
            long after = q.containsKey("after") ? Long.parseLong(q.get("after")) : 0;
            int limit = Math.max(1, Math.min(50_000, intParam(q, "limit", 20_000)));
            String origin = q.getOrDefault("origin", store.node());
            return RequestRoutingResponse.later(() -> {
                AccessLogStore.Table t = store.exportRows(origin, after, limit);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("node", origin);
                out.put("servedBy", store.node());
                out.put("latestSeq", store.replicatedUpTo(origin));
                Map<String, Long> cursors = new java.util.TreeMap<>(store.originCursors());
                cursors.put(store.node(), store.replicatedUpTo(store.node()));
                out.put("cursors", cursors);
                out.put("columns", t.columns());
                out.put("rows", t.rows());
                return ok(out);
            });
        }

        // With replication every node holds the whole cluster's access log: ?node=<name> just filters it.
        // Without (replication off), each node keeps only its own and ?node=<name> reads that member's.
        String node = q.get("node");
        boolean replicated = AnalyticsReplicator.active();
        if (!replicated && node != null && !node.isBlank() && !node.equals(store.node())) {
            return com.scaleguard.server.cluster.NodeForwarder.forward(node, method, uri, body);
        }
        if (!replicated) q.remove("node");   // single node / own log only: nothing to filter

        if ("settings".equals(action)) {
            if ("post".equalsIgnoreCase(method)) {
                JsonNode n = JSON.parse(body);
                if (n == null) return error("Request body must be JSON.");
                AccessLogStore.Settings cur = store.getSettings();
                int days = n.has("retentionDays") ? n.get("retentionDays").asInt(-1) : cur.retentionDays();
                if (days < 1 || days > 3650) return error("Retention must be between 1 and 3650 days.");
                try {
                    store.update(new AccessLogStore.Settings(n.has("enabled") ? n.get("enabled").asBoolean() : cur.enabled(), days));
                } catch (Exception e) {
                    return error("Couldn't save the settings: " + e.getMessage());
                }
            }
            return RequestRoutingResponse.succes(JSON.toString(settingsView(store)));
        }
        if ("query".equals(action)) {
            if (!"post".equalsIgnoreCase(method)) return RequestRoutingResponse.response(405, "{}");
            JsonNode n = JSON.parse(body);
            String sql = n != null && n.hasNonNull("sql") ? n.get("sql").asText().trim() : "";
            while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
            if (sql.isEmpty()) return error("Enter a query.");
            if (sql.contains(";")) return error("Run one statement at a time.");
            if (!READ_ONLY_START.matcher(sql).find())
                return error("Only read queries are allowed (SELECT, WITH, FROM, SUMMARIZE, DESCRIBE, PIVOT).");
            final String stmt = sql;
            return RequestRoutingResponse.later(() -> {
                try {
                    return ok(store.query(stmt, null, 1000, 15).toMap());
                } catch (java.sql.SQLException e) {
                    return error(cleanSqlError(e.getMessage()));
                }
            });
        }
        if (!"get".equalsIgnoreCase(method)) return RequestRoutingResponse.response(405, "{}");

        String range = RANGES.containsKey(q.get("range")) ? q.get("range") : "1h";
        return switch (action) {
            case "summary" -> RequestRoutingResponse.later(() -> ok(summary(store, range, q.get("route"), q.get("node"))));
            case "logs" -> RequestRoutingResponse.later(() -> ok(logs(store, range, q, Math.min(500, Math.max(1, intParam(q, "limit", 100))))));
            case "export" -> RequestRoutingResponse.later(() -> export(store, range, q));
            default -> RequestRoutingResponse.response(404, JSON.toString(Map.of("message", "Unknown analytics endpoint")));
        };
    }

    // ------------------------------------------------------------------ summary

    static Map<String, Object> summary(AccessLogStore store, String range, String route, String node) throws Exception {
        long[] rb = RANGES.get(range);
        long now = System.currentTimeMillis();
        long from = now - rb[0];
        long bucket = rb[1];
        StringBuilder where = new StringBuilder(" WHERE ts_ms >= ?");
        List<Object> p = new ArrayList<>(List.of(from));
        if (route != null && !route.isBlank()) {
            where.append(" AND route_id = ?");
            p.add(route);
        }
        if (node != null && !node.isBlank()) {
            where.append(" AND node = ?");
            p.add(node);
        }
        String w = where.toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", range);
        out.put("from", from);
        out.put("to", now);
        out.put("bucketMs", bucket);

        AccessLogStore.Table totals = store.query("""
                SELECT count(*) AS requests,
                       count_if(status >= 500) AS errors5xx,
                       count_if(status >= 400 AND status < 500) AS errors4xx,
                       count_if(outcome = 'waf_blocked') AS wafBlocked,
                       count_if(outcome = 'rate_limited') AS rateLimited,
                       count(DISTINCT client_ip) AS clients,
                       coalesce(sum(bytes_out), 0) AS bytesOut,
                       coalesce(sum(bytes_in), 0) AS bytesIn,
                       avg(duration_ms) AS avgMs,
                       quantile_cont(duration_ms, 0.5) AS p50,
                       quantile_cont(duration_ms, 0.95) AS p95,
                       quantile_cont(duration_ms, 0.99) AS p99
                FROM access_log_raw""" + w, p, 1, 15);
        Map<String, Object> t = totals.records().get(0);
        long requests = ((Number) t.get("requests")).longValue();
        t.put("rps", requests / (rb[0] / 1000.0));
        out.put("totals", t);

        List<Object> sp = new ArrayList<>(p);
        sp.add(0, bucket);
        sp.add(1, bucket);
        out.put("series", store.query("""
                SELECT (ts_ms // ?) * ? AS t, count(*) AS total,
                       count_if(status < 400) AS ok, count_if(status >= 400 AND status < 500) AS c4xx,
                       count_if(status >= 500) AS c5xx, count_if(outcome IN ('waf_blocked', 'rate_limited')) AS blocked,
                       quantile_cont(duration_ms, 0.5) AS p50, quantile_cont(duration_ms, 0.95) AS p95
                FROM access_log_raw""" + w + " GROUP BY 1 ORDER BY 1", sp, 2000, 15).records());

        out.put("routes", store.query("""
                SELECT coalesce(route_name, route_id, '(no route)') AS name, route_id AS id, count(*) AS requests,
                       count_if(status >= 500) AS errors, quantile_cont(duration_ms, 0.95) AS p95
                FROM access_log_raw""" + w + " GROUP BY ALL ORDER BY requests DESC LIMIT 10", p, 10, 15).records());
        out.put("paths", store.query("""
                SELECT host, path, count(*) AS requests, count_if(status >= 400) AS errors,
                       quantile_cont(duration_ms, 0.95) AS p95
                FROM access_log_raw""" + w + " GROUP BY ALL ORDER BY requests DESC LIMIT 10", p, 10, 15).records());
        out.put("clients", store.query("""
                SELECT client_ip AS ip, count(*) AS requests,
                       count_if(outcome IN ('waf_blocked', 'rate_limited')) AS blocked, any_value(user_agent) AS userAgent
                FROM access_log_raw""" + w + " GROUP BY client_ip ORDER BY requests DESC LIMIT 10", p, 10, 15).records());
        out.put("backends", store.query("""
                SELECT backend, count(*) AS requests, count_if(status >= 500) AS errors,
                       quantile_cont(duration_ms, 0.95) AS p95
                FROM access_log_raw""" + w + " AND backend IS NOT NULL GROUP BY backend ORDER BY requests DESC LIMIT 10", p, 10, 15).records());
        out.put("statuses", store.query("SELECT status, count(*) AS requests FROM access_log_raw" + w
                + " GROUP BY status ORDER BY requests DESC LIMIT 12", p, 12, 15).records());
        out.put("outcomes", store.query("SELECT outcome, count(*) AS requests FROM access_log_raw" + w
                + " GROUP BY outcome ORDER BY requests DESC", p, 20, 15).records());
        out.put("protocols", store.query("SELECT protocol, count(*) AS requests FROM access_log_raw" + w
                + " GROUP BY protocol ORDER BY requests DESC", p, 10, 15).records());
        out.put("wafRules", store.query("SELECT waf_rule AS rule, count(*) AS requests FROM access_log_raw" + w
                + " AND waf_rule IS NOT NULL GROUP BY waf_rule ORDER BY requests DESC LIMIT 8", p, 8, 15).records());
        out.put("status", store.status());
        return out;
    }

    // ------------------------------------------------------------------ logs & export

    private static final String LOG_COLUMNS = "ts_ms AS ts, trace_id, client_ip, method, host, path, query, protocol, route_id, route_name, "
            + "backend, status, grpc_status, bytes_in, bytes_out, round(duration_ms, 3) AS duration_ms, outcome, waf_rule, user_agent, referer, tls, node";

    /** WHERE clause + params for the log filters. */
    static String filters(String range, Map<String, String> q, List<Object> p) {
        StringBuilder w = new StringBuilder(" WHERE ts_ms >= ?");
        p.add(System.currentTimeMillis() - RANGES.get(range)[0]);
        if (notBlank(q.get("before"))) { w.append(" AND ts_ms < ?"); p.add(Long.parseLong(q.get("before"))); }
        if (notBlank(q.get("route"))) { w.append(" AND route_id = ?"); p.add(q.get("route")); }
        if (notBlank(q.get("node"))) { w.append(" AND node = ?"); p.add(q.get("node")); }
        if (notBlank(q.get("ip"))) { w.append(" AND client_ip = ?"); p.add(q.get("ip").trim()); }
        if (notBlank(q.get("trace"))) { w.append(" AND trace_id = ?"); p.add(q.get("trace").trim().toLowerCase(Locale.ROOT)); }
        if (notBlank(q.get("method"))) { w.append(" AND method = ?"); p.add(q.get("method").trim().toUpperCase(Locale.ROOT)); }
        if (notBlank(q.get("outcome"))) { w.append(" AND outcome = ?"); p.add(q.get("outcome").trim()); }
        if (notBlank(q.get("q"))) { w.append(" AND (path ILIKE ? OR host ILIKE ?)"); String like = "%" + q.get("q").trim() + "%"; p.add(like); p.add(like); }
        String status = q.get("status");
        if (notBlank(status)) {
            status = status.trim().toLowerCase(Locale.ROOT);
            if (status.matches("[1-5]xx")) {
                int c = status.charAt(0) - '0';
                w.append(" AND status >= ? AND status < ?");
                p.add(c * 100);
                p.add(c * 100 + 100);
            } else if (status.matches("\\d{3}")) {
                w.append(" AND status = ?");
                p.add(Integer.parseInt(status));
            }
        }
        return w.toString();
    }

    static Map<String, Object> logs(AccessLogStore store, String range, Map<String, String> q, int limit) throws Exception {
        List<Object> p = new ArrayList<>();
        String w = filters(range, q, p);
        p.add(limit);
        AccessLogStore.Table t = store.query("SELECT " + LOG_COLUMNS + " FROM access_log_raw" + w + " ORDER BY ts_ms DESC LIMIT ?", p, limit, 15);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = t.records();
        out.put("rows", rows);
        out.put("ms", t.millis());
        out.put("next", rows.size() == limit ? rows.get(rows.size() - 1).get("ts") : null);
        return out;
    }

    static RequestRoutingResponse export(AccessLogStore store, String range, Map<String, String> q) throws Exception {
        List<Object> p = new ArrayList<>();
        String w = filters(range, q, p);
        AccessLogStore.Table t = store.query("SELECT " + LOG_COLUMNS + " FROM access_log_raw" + w + " ORDER BY ts_ms DESC LIMIT 100000", p, 100_000, 60);
        boolean csv = !"ndjson".equalsIgnoreCase(q.get("format"));
        StringBuilder sb = new StringBuilder(t.rows().size() * 200);
        if (csv) {
            sb.append(String.join(",", t.columns())).append('\n');
            for (List<Object> r : t.rows()) {
                for (int i = 0; i < r.size(); i++) {
                    if (i > 0) sb.append(',');
                    Object v = r.get(i);
                    if (v != null) {
                        String s = String.valueOf(v);
                        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) sb.append('"').append(s.replace("\"", "\"\"")).append('"');
                        else sb.append(s);
                    }
                }
                sb.append('\n');
            }
        } else {
            for (Map<String, Object> r : t.records()) sb.append(JSON.toString(r)).append('\n');
        }
        return new RequestRoutingResponse(200, sb.toString(), csv ? "text/csv; charset=UTF-8" : "application/x-ndjson");
    }

    // ------------------------------------------------------------------ helpers

    static Map<String, Object> settingsView(AccessLogStore store) {
        Map<String, Object> m = new LinkedHashMap<>();
        AccessLogStore.Settings s = store.getSettings();
        m.put("enabled", s.enabled());
        m.put("retentionDays", s.retentionDays());
        m.put("status", store.status());
        m.put("node", store.node());
        m.put("replication", AnalyticsReplicator.status());
        return m;
    }

    private static final Set<String> NOISE = Set.of("java.sql.SQLException: ", "Binder Error: ", "Parser Error: ", "Catalog Error: ");

    static String cleanSqlError(String msg) {
        if (msg == null) return "Query failed.";
        String m = msg;
        for (String n : NOISE) m = m.replace(n, "");
        return m.length() > 600 ? m.substring(0, 600) + "…" : m;
    }

    private static RequestRoutingResponse ok(Object o) {
        return RequestRoutingResponse.succes(JSON.toString(o));
    }

    private static RequestRoutingResponse error(String message) {
        return RequestRoutingResponse.response(400, JSON.toString(Map.of("message", message)));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static int intParam(Map<String, String> q, String k, int def) {
        try { return Integer.parseInt(q.get(k)); } catch (Exception e) { return def; }
    }

    static Map<String, String> params(String query) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String kv : query.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }
}
