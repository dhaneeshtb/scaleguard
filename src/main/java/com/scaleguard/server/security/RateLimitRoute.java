package com.scaleguard.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
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
        if ("post".equalsIgnoreCase(method)) {
            JsonNode n = JSON.parse(body);
            if (n == null) return error("Request body must be JSON.");
            RateLimitManager.Settings cur = rl.getSettings();
            boolean enabled = n.has("enabled") ? n.get("enabled").asBoolean() : cur.enabled();
            int perMinute = n.has("perMinute") ? n.get("perMinute").asInt(-1) : cur.perMinute();
            int burst = n.has("burstSeconds") ? n.get("burstSeconds").asInt(-1) : cur.burstSeconds();
            String trusted = n.has("trustedProxies") ? n.get("trustedProxies").asText("") : cur.trustedProxies();

            if (perMinute < 1 || perMinute > 10_000_000) return error("Requests per minute must be between 1 and 10,000,000.");
            if (burst < 1 || burst > 3600) return error("Burst must be between 1 and 3600 seconds.");
            List<String> bad = new ArrayList<>();
            for (String part : trusted.split(",")) {
                String p = part.trim();
                if (!p.isEmpty() && CidrSet.parse(p).isEmpty()) bad.add(p);
            }
            if (!bad.isEmpty()) return error("Not a valid IP or CIDR range: " + String.join(", ", bad));

            rl.update(new RateLimitManager.Settings(enabled, perMinute, burst, trusted));
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
        out.put("settings", settings);
        out.put("stats", rl.stats());
        List<Map<String, Object>> routes = new ArrayList<>();
        for (SourceSystem ss : RouteTable.getInstance().getSourceSystsems()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", ss.getId());
            r.put("name", ss.getName());
            r.put("host", ss.getHost());
            r.put("rateLimit", ss.getRateLimit() == null || ss.getRateLimit() < 0 ? null : ss.getRateLimit());
            routes.add(r);
        }
        out.put("routes", routes);
        return out;
    }

    private static RequestRoutingResponse error(String message) {
        return RequestRoutingResponse.response(400, JSON.toString(Map.of("message", message)));
    }
}
