package com.scaleguard.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.server.http.router.AccessLists;
import com.scaleguard.server.http.router.RateLimitManager;
import com.scaleguard.server.http.router.RouteTable;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.server.http.utils.CidrSet;
import com.scaleguard.utils.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API for HTTP rate limiting.
 * <pre>
 * GET  /ratelimit   current settings, live stats, per-route overrides
 * POST /ratelimit   {"enabled":true,"perMinute":6000,"burstSeconds":10,"trustedProxies":"127.0.0.1/32,..."}
 * </pre>
 */
public class RateLimitRoute implements RequestRoute {

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        RateLimitManager rl = RateLimitManager.getInstance();
        if ("get".equalsIgnoreCase(method)) {
            return RequestRoutingResponse.succes(JSON.toString(view(rl)));
        }
        String[] parts = uri.split("\\?")[0].replaceAll("^/+|/+$", "").split("/");
        if ("post".equalsIgnoreCase(method) && parts.length >= 2 && "access".equals(parts[1])) {
            // POST /ratelimit/access          {"list":"blocked"|"exempt","values":"203.0.113.7, 10.0.0.0/8","note":"…","ttlSeconds":3600}
            // POST /ratelimit/access/remove   {"list":"blocked"|"exempt","value":"10.0.0.0/8"}
            JsonNode n = JSON.parse(body);
            if (n == null) return error("Request body must be JSON.");
            String list = n.path("list").asText("");
            AccessLists acl = AccessLists.get();
            try {
                if (parts.length == 3 && "remove".equals(parts[2])) {
                    if (!acl.remove(list, n.path("value").asText(""))) return RequestRoutingResponse.response(404, JSON.toString(Map.of("message", "Not on the list.")));
                } else if (parts.length == 2) {
                    String values = n.hasNonNull("values") ? n.get("values").asText("") : n.path("value").asText("");
                    long ttl = n.path("ttlSeconds").asLong(0);
                    if (ttl < 0 || ttl > 3650L * 86400) return error("ttlSeconds must be between 0 (permanent) and 10 years.");
                    acl.add(list, values, n.hasNonNull("note") ? n.get("note").asText() : null, ttl);
                } else {
                    return RequestRoutingResponse.response(404, "{}");
                }
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
            return RequestRoutingResponse.succes(JSON.toString(view(rl)));
        }
        if ("post".equalsIgnoreCase(method)) {
            JsonNode n = JSON.parse(body);
            if (n == null) return error("Request body must be JSON.");
            RateLimitManager.Settings cur = rl.getSettings();
            boolean enabled = n.has("enabled") ? n.get("enabled").asBoolean() : cur.enabled();
            int perMinute = n.has("perMinute") ? n.get("perMinute").asInt(-1) : cur.perMinute();
            int burst = n.has("burstSeconds") ? n.get("burstSeconds").asInt(-1) : cur.burstSeconds();
            String trusted = n.has("trustedProxies") ? n.get("trustedProxies").asText("") : cur.trustedProxies();
            String clusterMode = n.has("clusterMode") ? n.get("clusterMode").asText("") : cur.clusterMode();
            if (!List.of("local", "shared", "divided", "strict").contains(clusterMode)) {
                return error("clusterMode must be local, shared, divided or strict.");
            }

            if (perMinute < 1 || perMinute > 10_000_000) return error("Requests per minute must be between 1 and 10,000,000.");
            if (burst < 1 || burst > 3600) return error("Burst must be between 1 and 3600 seconds.");
            List<String> bad = new ArrayList<>();
            for (String part : trusted.split(",")) {
                String p = part.trim();
                if (!p.isEmpty() && CidrSet.parse(p).isEmpty()) bad.add(p);
            }
            if (!bad.isEmpty()) return error("Not a valid IP or CIDR range: " + String.join(", ", bad));

            rl.update(new RateLimitManager.Settings(enabled, perMinute, burst, trusted, clusterMode));
            return RequestRoutingResponse.succes(JSON.toString(view(rl)));
        }
        return RequestRoutingResponse.response(405, "{}");
    }

    private static Map<String, Object> view(RateLimitManager rl) {
        RateLimitManager.Settings s = rl.getSettings();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("enabled", s.enabled());
        settings.put("perMinute", s.perMinute());
        settings.put("burstSeconds", s.burstSeconds());
        settings.put("trustedProxies", s.trustedProxies());
        settings.put("clusterMode", s.clusterMode());
        out.put("settings", settings);
        out.put("stats", rl.stats());
        out.put("access", AccessLists.get().view());
        List<Map<String, Object>> routes = new ArrayList<>();
        for (SourceSystem ss : RouteTable.getInstance().getSourceSystsems()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", ss.getId());
            r.put("name", ss.getName());
            r.put("host", ss.getHost());
            r.put("rateLimit", ss.getRateLimit() == null || ss.getRateLimit() < 0 ? null : ss.getRateLimit());
            r.put("ipAllow", ss.getIpAllow() == null || ss.getIpAllow().isBlank() ? null : ss.getIpAllow());
            r.put("ipDeny", ss.getIpDeny() == null || ss.getIpDeny().isBlank() ? null : ss.getIpDeny());
            routes.add(r);
        }
        out.put("routes", routes);
        return out;
    }

    private static RequestRoutingResponse error(String message) {
        return RequestRoutingResponse.response(400, JSON.toString(Map.of("message", message)));
    }
}
