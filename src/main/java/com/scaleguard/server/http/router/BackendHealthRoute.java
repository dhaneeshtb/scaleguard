package com.scaleguard.server.http.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.utils.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin API for backend health (active polling + passive ejection).
 * <pre>
 * GET  /backends                  settings and per-host state
 * POST /backends/settings         {"enabled":true,"consecutiveFailures":5,"baseEjectionSeconds":10,
 *                                  "maxEjectionSeconds":300,"failureStatuses":"502,503,504"}
 * POST /backends/{hostId}/reset   put an ejected host back into rotation now
 * </pre>
 */
public class BackendHealthRoute implements RequestRoute {

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        String[] parts = uri.split("\\?")[0].replaceAll("^/+|/+$", "").split("/");
        PassiveHealthManager pm = PassiveHealthManager.getInstance();
        if ("get".equalsIgnoreCase(method)) {
            return RequestRoutingResponse.succes(JSON.toString(view()));
        }
        if (!"post".equalsIgnoreCase(method)) return RequestRoutingResponse.response(405, "{}");

        if (parts.length == 2 && "settings".equals(parts[1])) {
            JsonNode n = JSON.parse(body);
            if (n == null) return error("Request body must be JSON.");
            PassiveHealthManager.Settings cur = pm.getSettings();
            boolean enabled = n.has("enabled") ? n.get("enabled").asBoolean() : cur.enabled();
            int failures = n.has("consecutiveFailures") ? n.get("consecutiveFailures").asInt(-1) : cur.consecutiveFailures();
            int base = n.has("baseEjectionSeconds") ? n.get("baseEjectionSeconds").asInt(-1) : cur.baseEjectionSeconds();
            int max = n.has("maxEjectionSeconds") ? n.get("maxEjectionSeconds").asInt(-1) : cur.maxEjectionSeconds();
            if (failures < 1 || failures > 1000) return error("Consecutive failures must be between 1 and 1000.");
            if (base < 1 || base > 3600) return error("Ejection time must be between 1 and 3600 seconds.");
            if (max < base || max > 86_400) return error("Maximum ejection time must be at least the ejection time and at most 86400 seconds.");
            Set<Integer> statuses = cur.failureStatuses();
            if (n.has("failureStatuses")) {
                try {
                    statuses = PassiveHealthManager.Settings.parseStatuses(n.get("failureStatuses").asText(""));
                } catch (Exception e) {
                    return error("Failure statuses must be HTTP status codes separated by commas, e.g. 502,503,504.");
                }
            }
            pm.update(new PassiveHealthManager.Settings(enabled, failures, base, max, statuses));
            return RequestRoutingResponse.succes(JSON.toString(view()));
        }
        if (parts.length == 3 && "reset".equals(parts[2])) {
            for (HostGroup hg : RouteTable.getInstance().getHostGroups()) {
                if (parts[1].equals(hg.getId())) {
                    pm.reset(hg);
                    return RequestRoutingResponse.succes(JSON.toString(view()));
                }
            }
            return RequestRoutingResponse.response(404, JSON.toString(Map.of("message", "No backend host with id " + parts[1])));
        }
        return RequestRoutingResponse.response(404, JSON.toString(Map.of("message", "Unknown endpoint")));
    }

    static Map<String, Object> view() {
        PassiveHealthManager.Settings s = PassiveHealthManager.getInstance().getSettings();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("enabled", s.enabled());
        settings.put("consecutiveFailures", s.consecutiveFailures());
        settings.put("baseEjectionSeconds", s.baseEjectionSeconds());
        settings.put("maxEjectionSeconds", s.maxEjectionSeconds());
        settings.put("failureStatuses", s.statusesCsv());
        settings.put("activeIntervalSeconds", Long.getLong("host.health.interval.seconds", 10));

        Map<String, List<String>> targetsByGroup = new LinkedHashMap<>();
        RouteTable.getInstance().getTargetSystems().values().forEach(t -> {
            if (t.getHostGroupId() != null)
                targetsByGroup.computeIfAbsent(t.getHostGroupId(), k -> new ArrayList<>()).add(t.getName() != null ? t.getName() : t.getId());
        });

        List<Map<String, Object>> hosts = new ArrayList<>();
        int ejected = 0, down = 0;
        for (HostGroup hg : RouteTable.getInstance().getHostGroups()) {
            HostGroup.PassiveState p = hg.getPassive();
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("id", hg.getId());
            h.put("groupId", hg.getGroupId());
            h.put("address", hg.getScheme() + "://" + hg.getHost() + ":" + hg.getPort());
            h.put("healthUrl", hg.getHealth());
            h.put("targets", targetsByGroup.getOrDefault(hg.getGroupId(), List.of()));
            String state = !hg.isActivelyHealthy() ? "down" : hg.isEjected() ? "ejected" : p.ejections > 0 ? "probing" : "healthy";
            if ("down".equals(state)) down++;
            if ("ejected".equals(state)) ejected++;
            h.put("state", state);
            h.put("activeCheck", hg.getHealth() == null || hg.getHealth().isEmpty() ? "none" : hg.isActivelyHealthy() ? "passing" : "failing");
            h.put("ejectedForSeconds", PassiveHealthManager.secondsLeft(hg));
            h.put("ejections", p.ejections);
            h.put("totalEjections", p.totalEjections.sum());
            h.put("consecutiveFailures", p.consecutiveFailures.get());
            h.put("requests", p.requests.sum());
            h.put("failures", p.failures.sum());
            h.put("lastFailure", p.lastFailure);
            h.put("lastFailureAt", p.lastFailureMillis == 0 ? null : p.lastFailureMillis);
            h.put("activeConnections", hg.getActiveConnections().get());
            h.put("enabled", hg.isActive());            // false = nginx "down": takes no traffic
            h.put("backup", hg.isBackup());
            h.put("maxConns", hg.getMaxConns() != null && hg.getMaxConns() > 0 ? hg.getMaxConns() : null);
            h.put("atCapacity", hg.isAtCapacity());
            hosts.add(h);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings);
        out.put("summary", Map.of("hosts", hosts.size(), "ejected", ejected, "down", down));
        out.put("hosts", hosts);
        return out;
    }

    private static RequestRoutingResponse error(String message) {
        return RequestRoutingResponse.response(400, JSON.toString(Map.of("message", message)));
    }
}
