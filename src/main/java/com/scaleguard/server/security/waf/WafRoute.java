package com.scaleguard.server.security.waf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.server.http.router.RouteTable;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.utils.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API for the web application firewall.
 * <pre>
 * GET    /waf                      rules, per-route policies, stats, recent events
 * POST   /waf/policy/{sourceId}    save a route's policy  {"enabled":true,"mode":"block","rules":{"sqli-union":{"enabled":true,"action":"block"}}}
 * POST   /waf/rules                add/update a custom rule {"id"?, "name","description","pattern","targets":["QUERY","BODY"],"action":"BLOCK"}
 * DELETE /waf/rules/{id}           delete a custom rule
 * POST   /waf/test                 {"sourceId"?, "uri","headers":{},"body"} → which rules would match
 * </pre>
 */
public class WafRoute implements RequestRoute {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        String path = uri.split("\\?")[0];
        String[] parts = path.replaceAll("^/+|/+$", "").split("/"); // waf, policy, {id}
        WafEngine waf = WafEngine.getInstance();
        try {
            if ("get".equalsIgnoreCase(method)) {
                return ok(view(waf));
            }
            if ("post".equalsIgnoreCase(method) && parts.length == 3 && parts[1].equals("policy")) {
                String sourceId = parts[2];
                if (RouteTable.getInstance().getSourceSystsems().stream().noneMatch(s -> sourceId.equals(s.getId()))) {
                    return error(404, "Route " + sourceId + " not found.");
                }
                WafPolicy p = MAPPER.readValue(body, WafPolicy.class);
                waf.savePolicy(sourceId, p);
                return ok(view(waf));
            }
            if ("post".equalsIgnoreCase(method) && parts.length == 2 && parts[1].equals("rules")) {
                JsonNode n = JSON.parse(body);
                List<WafRule.Target> targets = new ArrayList<>();
                if (n.has("targets")) for (JsonNode t : n.get("targets")) targets.add(WafRule.Target.valueOf(t.asText().toUpperCase()));
                WafRule.Action action = "log".equalsIgnoreCase(text(n, "action")) ? WafRule.Action.LOG : WafRule.Action.BLOCK;
                waf.saveCustomRule(text(n, "id"), text(n, "name"), text(n, "description"), text(n, "pattern"), targets, action);
                return ok(view(waf));
            }
            if ("post".equalsIgnoreCase(method) && parts.length == 2 && parts[1].equals("rulesets")) {
                JsonNode n = JSON.parse(body);
                if (n == null || !n.has("files")) return error(400, "Send {\"name\": \"…\", \"files\": {\"file name\": \"content\"}}.");
                Map<String, String> files = new LinkedHashMap<>();
                n.get("files").fields().forEachRemaining(e -> files.put(e.getKey(), e.getValue().asText()));
                final String setName = text(n, "name");
                // compiling a few hundred large regexes takes a moment: do it off the I/O thread
                return RequestRoutingResponse.later(() -> {
                    try {
                        return importResponse(waf, waf.importRuleSet(setName, files));
                    } catch (IllegalArgumentException e) {
                        return error(400, e.getMessage());
                    }
                });
            }
            if ("delete".equalsIgnoreCase(method) && parts.length == 3 && parts[1].equals("rulesets")) {
                String name = java.net.URLDecoder.decode(parts[2], java.nio.charset.StandardCharsets.UTF_8);
                if (!waf.deleteRuleSet(name)) return error(404, "Rule set " + name + " not found.");
                return ok(view(waf));
            }
            if ("delete".equalsIgnoreCase(method) && parts.length == 3 && parts[1].equals("rules")) {
                if (!waf.deleteCustomRule(parts[2])) return error(404, "Custom rule " + parts[2] + " not found.");
                return ok(view(waf));
            }
            if ("post".equalsIgnoreCase(method) && parts.length == 2 && parts[1].equals("test")) {
                JsonNode n = JSON.parse(body);
                String sourceId = text(n, "sourceId");
                WafPolicy p = n.has("policy") ? MAPPER.treeToValue(n.get("policy"), WafPolicy.class)
                        : MAPPER.readValue(MAPPER.writeValueAsString(waf.policy(sourceId)), WafPolicy.class);
                Map<String, String> headers = new LinkedHashMap<>();
                if (n.has("headers")) n.get("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
                WafEngine.Verdict v = waf.test(p, text(n, "uri"), headers, text(n, "body"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("blocked", v.blocked());
                out.put("score", v.score());
                List<Map<String, Object>> matches = new ArrayList<>();
                for (WafEngine.Match m : v.matches()) { // this Jackson version can't serialise records
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("ruleId", m.ruleId());
                    mm.put("ruleName", m.ruleName());
                    mm.put("category", m.category());
                    mm.put("location", m.location());
                    mm.put("snippet", m.snippet());
                    mm.put("action", m.action());
                    matches.add(mm);
                }
                out.put("matches", matches);
                return ok(out);
            }
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
        return error(404, "Unknown WAF endpoint.");
    }

    private static RequestRoutingResponse importResponse(WafEngine waf, CrsImporter.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", r.rules().size());
        Map<Integer, Integer> byPl = new java.util.TreeMap<>();
        Map<String, Integer> byCat = new java.util.TreeMap<>();
        for (WafRule w : r.rules()) { byPl.merge(w.getParanoia(), 1, Integer::sum); byCat.merge(w.getCategory(), 1, Integer::sum); }
        out.put("byParanoia", byPl);
        out.put("byCategory", byCat);
        out.put("skipped", r.skipped());
        out.put("examples", r.examples());
        out.put("waf", view(waf));
        return ok(out);
    }

    private static Map<String, Object> view(WafEngine waf) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> stats = waf.stats();
        @SuppressWarnings("unchecked") Map<String, Long> hits = (Map<String, Long>) stats.get("hitsByRule");
        List<Map<String, Object>> rules = new ArrayList<>();
        for (WafRule r : waf.allRules()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("category", r.getCategory());
            m.put("description", r.getDescription());
            String pat = r.getPattern();
            m.put("pattern", r.getRuleSet() != null && pat.length() > 300 ? pat.substring(0, 300) + "…" : pat);
            m.put("targets", r.getTargets());
            m.put("defaultAction", r.getDefaultAction().name().toLowerCase());
            m.put("builtIn", r.isBuiltIn());
            m.put("severity", r.getSeverity().name().toLowerCase());
            m.put("paranoia", r.getParanoia());
            if (r.getRuleSet() != null) m.put("ruleSet", r.getRuleSet());
            m.put("hits", hits.getOrDefault(r.getId(), 0L));
            rules.add(m);
        }
        out.put("rules", rules);
        List<Map<String, Object>> sets = new ArrayList<>();
        for (WafEngine.RuleSet rs : waf.ruleSets().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", rs.name());
            m.put("importedAt", rs.importedAt());
            m.put("rules", rs.rules().size());
            Map<Integer, Integer> byPl = new java.util.TreeMap<>();
            for (WafRule w : rs.rules()) byPl.merge(w.getParanoia(), 1, Integer::sum);
            m.put("byParanoia", byPl);
            m.put("skipped", rs.skipped());
            sets.add(m);
        }
        out.put("ruleSets", sets);
        List<Map<String, Object>> routes = new ArrayList<>();
        for (SourceSystem ss : RouteTable.getInstance().getSourceSystsems()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ss.getId());
            m.put("name", ss.getName());
            m.put("host", ss.getHost());
            m.put("scheme", ss.getScheme());
            m.put("policy", waf.policy(ss.getId()));
            routes.add(m);
        }
        out.put("routes", routes);
        out.put("stats", stats);
        out.put("events", waf.recentEvents(100));
        return out;
    }

    private static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private static RequestRoutingResponse ok(Object o) {
        return RequestRoutingResponse.succes(JSON.toString(o));
    }

    private static RequestRoutingResponse error(int status, String message) {
        return RequestRoutingResponse.response(status, JSON.toString(Map.of("message", message)));
    }
}
