package com.scaleguard.server.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.server.http.router.ConfigManager;
import com.scaleguard.server.http.router.RouteTable;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.server.http.router.TargetSystem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Admin API for cloud functions (docs/API.md → Cloud functions).
 * <pre>
 * GET    /functions                 list + gateway secret names + limits
 * POST   /functions                 create        (body = function definition)
 * GET    /functions/{id}            definition, attached routes, counters
 * POST   /functions/{id}            update
 * DELETE /functions/{id}            delete (refused while a route uses it)
 * POST   /functions/check           {"code"} → compile errors, nothing saved
 * POST   /functions/{id}/test       {"method","path","query","headers","body"} → runs the saved function
 * GET    /functions/{id}/runs       last runs with logs (this node)
 * POST   /functions/{id}/attach     {"sourceId"} → makes the function that route's target
 * POST   /functions/{id}/cache      {"enabled","ttl","methods","pattern","keyHeaders"} → response caching on the function's target(s)
 * PUT    /functions/secrets/{name}  {"value"} (write-only)      DELETE /functions/secrets/{name}
 * </pre>
 */
public class FunctionRoute implements RequestRoute {

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    private static RequestRoutingResponse ok(Object o) {
        return RequestRoutingResponse.succes(FunctionApi.toJson(o));
    }

    private static RequestRoutingResponse err(int status, String msg) {
        return RequestRoutingResponse.response(status, FunctionApi.toJson(Map.of("message", msg)));
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        String[] p = uri.split("\\?")[0].replaceAll("^/+|/+$", "").split("/");   // functions, {id}, {action}
        FunctionRuntime rt = FunctionRuntime.get();
        String m = method.toUpperCase();
        if (p.length == 1) {
            if (m.equals("GET")) return ok(overview(rt));
            if (m.equals("POST")) return save(rt, null, body);
        } else if (p[1].equals("secrets")) {
            if (p.length == 3 && (m.equals("PUT") || m.equals("POST"))) {
                JsonNode n = FunctionApi.JSON.readTree(body);
                if (n == null || !n.hasNonNull("value")) return err(400, "send {\"value\": \"…\"}");
                try {
                    FunctionSecrets.set(p[2], n.get("value").asText());
                } catch (IllegalArgumentException e) {
                    return err(400, e.getMessage());
                }
                return ok(Map.of("secrets", FunctionSecrets.names()));
            }
            if (p.length == 3 && m.equals("DELETE")) {
                FunctionSecrets.delete(p[2]);
                return ok(Map.of("secrets", FunctionSecrets.names()));
            }
        } else if (p[1].equals("check") && m.equals("POST")) {
            JsonNode n = FunctionApi.JSON.readTree(body);
            String e = n != null && n.hasNonNull("code") ? rt.check(n.get("code").asText()) : "send {\"code\": \"…\"}";
            return ok(Map.of("ok", e == null, "error", e == null ? "" : e));
        } else {
            String id = p[1];
            FunctionDef d = rt.find(id);
            if (p.length == 2) {
                if (m.equals("POST") || m.equals("PUT")) return save(rt, id, body);
                if (d == null) return err(404, "function not found");
                if (m.equals("GET")) return ok(detail(rt, d));
                if (m.equals("DELETE")) {
                    List<Map<String, String>> used = routes(id);
                    if (!used.isEmpty()) return err(409, "still used by route " + used.get(0).get("host") + used.get(0).get("path") + "; point it at another target first");
                    rt.delete(id);
                    return ok(overview(rt));
                }
            } else if (d == null) {
                return err(404, "function not found");
            } else if (p[2].equals("runs") && m.equals("GET")) {
                return ok(Map.of("runs", rt.runs(id), "stats", rt.stats(id)));
            } else if (p[2].equals("test") && m.equals("POST")) {
                return test(rt, id, body);
            } else if (p[2].equals("cache") && m.equals("POST")) {
                return cache(d, body);
            } else if (p[2].equals("attach") && m.equals("POST")) {
                return attach(d, body);
            }
        }
        return err(404, "unknown functions endpoint");
    }

    private Map<String, Object> overview(FunctionRuntime rt) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (FunctionDef d : rt.all()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", d.id);
            s.put("name", d.name);
            s.put("description", d.description);
            s.put("enabled", d.enabled);
            s.put("networkEnabled", d.networkEnabled);
            s.put("methods", d.methods);
            s.put("updated", d.updated);
            s.put("routes", routes(d.id).size());
            s.putAll(rt.stats(d.id));
            list.add(s);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("enabled", FunctionRuntime.enabled());
        o.put("functions", list);
        o.put("secrets", FunctionSecrets.names());
        o.put("limits", Map.of("maxTimeoutMs", FunctionDef.MAX_TIMEOUT_MS, "maxBodyBytes", FunctionDef.MAX_BODY));
        o.put("defaults", FunctionApi.JSON.convertValue(new FunctionDef(), Map.class));
        return o;
    }

    private Map<String, Object> detail(FunctionRuntime rt, FunctionDef d) {
        Map<String, Object> o = FunctionApi.JSON.convertValue(d, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        o.put("routes", routes(d.id));
        o.put("stats", rt.stats(d.id));
        o.put("cache", cacheView(d.id));
        return o;
    }

    /** Routes whose target is this function. */
    private List<Map<String, String>> routes(String fnId) {
        List<Map<String, String>> out = new ArrayList<>();
        RouteTable t = RouteTable.getInstance();
        for (SourceSystem s : t.getSourceSystsems()) {
            TargetSystem ts = s.getTarget() == null ? null : t.getTargetSystems().get(s.getTarget());
            if (ts != null && "function".equalsIgnoreCase(ts.getScheme()) && fnId.equals(ts.getHost())) {
                Map<String, String> r = new LinkedHashMap<>();
                r.put("sourceId", s.getId());
                r.put("name", s.getName());
                r.put("host", s.getHost());
                r.put("port", s.getPort());
                r.put("path", s.getBasePath() == null ? "" : s.getBasePath());
                out.add(r);
            }
        }
        return out;
    }

    private RequestRoutingResponse save(FunctionRuntime rt, String id, String body) throws Exception {
        FunctionDef d;
        try {
            d = FunctionApi.JSON.readValue(body, FunctionDef.class);
        } catch (Exception e) {
            return err(400, "invalid function definition");
        }
        if (id != null) {
            if (rt.find(id) == null) return err(404, "function not found");
            d.id = id;
        } else {
            d.id = null;
        }
        String bad = rt.save(d);
        if (bad != null) return err(400, bad);
        return ok(detail(rt, rt.find(d.id)));
    }

    private RequestRoutingResponse test(FunctionRuntime rt, String id, String body) throws Exception {
        JsonNode n = body == null || body.isBlank() ? FunctionApi.JSON.createObjectNode() : FunctionApi.JSON.readTree(body);
        Map<String, String> headers = new LinkedHashMap<>();
        if (n.has("headers")) n.get("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
        FunctionRuntime.Request req = new FunctionRuntime.Request(n.path("method").asText("GET"), n.path("path").asText("/"), n.path("query").asText(""),
                "127.0.0.1", "test", headers, n.path("body").asText("").getBytes(StandardCharsets.UTF_8));
        return RequestRoutingResponse.later(() -> {
            FunctionRuntime.Result r = rt.invoke(id, req).get(FunctionDef.MAX_TIMEOUT_MS + 5000L, TimeUnit.MILLISECONDS);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("status", r.status);
            o.put("headers", r.headers);
            o.put("body", new String(r.body, 0, Math.min(r.body.length, 65536), StandardCharsets.UTF_8));
            o.put("bodyBytes", r.body.length);
            o.put("error", r.error);
            o.put("detail", r.detail);
            o.put("logs", r.logs);
            o.put("ms", r.ms);
            o.put("runId", r.runId);
            return ok(o);
        });
    }

    private List<TargetSystem> targetsOf(String fnId) {
        List<TargetSystem> l = new ArrayList<>();
        for (TargetSystem t : RouteTable.getInstance().getTargetSystems().values()) {
            if ("function".equalsIgnoreCase(t.getScheme()) && fnId.equals(t.getHost())) l.add(t);
        }
        return l;
    }

    private Map<String, Object> cacheView(String fnId) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", false);
        c.put("ttl", 60);
        c.put("methods", List.of("GET"));
        c.put("pattern", "/");
        c.put("keyHeaders", List.of());
        List<TargetSystem> ts = targetsOf(fnId);
        if (!ts.isEmpty() && ts.get(0).isEnableCache() && ts.get(0).getCachedResources() != null && !ts.get(0).getCachedResources().isEmpty()) {
            var first = ts.get(0).getCachedResources().get(0);
            c.put("enabled", true);
            c.put("ttl", first.getTtl());
            c.put("pattern", first.getPattern());
            c.put("methods", ts.get(0).getCachedResources().stream().map(x -> x.getMethod()).distinct().toList());
            c.put("keyHeaders", first.getKeyLookupHeaders() == null ? List.of() : List.of(first.getKeyLookupHeaders()));
        }
        return c;
    }

    /** Response caching for a function = the same cache rules a proxied target has, written to the function's target system(s). */
    private RequestRoutingResponse cache(FunctionDef d, String body) throws Exception {
        JsonNode n = FunctionApi.JSON.readTree(body);
        boolean enabled = n.path("enabled").asBoolean(false);
        int ttl = n.path("ttl").asInt(60);
        if (ttl < 1 || ttl > 86400) return err(400, "ttl must be 1..86400 seconds");
        String pattern = n.path("pattern").asText("/");
        if (!pattern.startsWith("/")) return err(400, "pattern must start with /");
        List<String> methods = new ArrayList<>();
        for (JsonNode x : n.path("methods")) {
            if (!x.asText().matches("(?i)GET|HEAD|POST|PUT|PATCH|DELETE")) return err(400, "unsupported method " + x.asText());
            methods.add(x.asText().toUpperCase());
        }
        if (methods.isEmpty()) methods.add("GET");
        List<String> keyHeaders = new ArrayList<>();
        for (JsonNode x : n.path("keyHeaders")) if (!x.asText().isBlank()) keyHeaders.add(x.asText().trim());
        List<TargetSystem> targets = targetsOf(d.id);
        if (targets.isEmpty()) return err(409, "attach the function to a route first; caching is a setting of its target");
        for (TargetSystem t : targets) {
            List<com.scaleguard.server.http.cache.CachedResource> rules = new ArrayList<>();
            if (enabled) {
                for (String m : methods) {
                    var r = new com.scaleguard.server.http.cache.CachedResource();
                    r.setPattern(pattern);
                    r.setMethod(m);
                    r.setTtl(ttl);
                    r.setCached(true);
                    r.setKeyLookupHeaders(keyHeaders.toArray(new String[0]));
                    rules.add(r);
                }
            }
            t.setEnableCache(enabled);
            t.setCachedResources(rules);
            ConfigManager.update(t);        // the live object: copy() skips only nulls, so nothing else on the target changes
        }
        return ok(Map.of("cache", cacheView(d.id)));
    }

    private RequestRoutingResponse attach(FunctionDef d, String body) throws Exception {
        JsonNode n = FunctionApi.JSON.readTree(body);
        String sourceId = n == null ? null : n.path("sourceId").asText(null);
        SourceSystem src = RouteTable.getInstance().getSourceSystsems().stream().filter(s -> s.getId() != null && s.getId().equals(sourceId)).findFirst().orElse(null);
        if (src == null) return err(404, "route not found");
        TargetSystem target = RouteTable.getInstance().getTargetSystems().values().stream()
                .filter(t -> "function".equalsIgnoreCase(t.getScheme()) && d.id.equals(t.getHost())).findFirst().orElse(null);
        if (target == null) {
            target = new TargetSystem();
            target.setName("function: " + d.name);
            target.setGroupId("fn-" + d.id);
            target.setScheme("function");
            target.setHost(d.id);
            ConfigManager.update(target);
        }
        src.setTarget(target.getGroupId());
        ConfigManager.update(src);     // the live object itself: ConfigManager.copy only skips nulls, so nothing else on the route changes
        return ok(Map.of("routes", routes(d.id)));
    }
}
