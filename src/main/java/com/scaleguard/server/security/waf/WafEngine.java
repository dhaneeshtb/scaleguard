package com.scaleguard.server.security.waf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import com.scaleguard.server.security.waf.WafRule.Action;
import com.scaleguard.server.security.waf.WafRule.Target;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;

/**
 * Web application firewall: inspects requests on routes whose policy has the WAF enabled.
 *
 * <p>Inputs are normalised before matching — URL-decoded up to three times (defeats double encoding),
 * HTML entities decoded, null bytes and SQL inline comments removed, whitespace collapsed — and capped
 * in length, so encoded payloads can't slip past and huge inputs can't slow the proxy down.
 *
 * <p>Custom rules and per-route policies are stored in the system_properties table (group "waf") and
 * changes take effect immediately.
 */
public final class WafEngine {

    private static final Logger LOG = LoggerFactory.getLogger(WafEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GROUP = "waf";
    private static final String P_CUSTOM_RULES = "waf.customRules";
    private static final String P_POLICY_PREFIX = "waf.policy.";
    private static final String P_RULESET_PREFIX = "waf.ruleset.";
    private static final int MAX_FIELD = 8_192;
    private static final int MAX_EVENTS = 500;

    private static volatile WafEngine instance;

    private final List<WafRule> builtIn = BuiltInRules.all();
    private volatile List<WafRule> customRules = List.of();
    /** Imported rule sets (e.g. OWASP CRS), by name. */
    private volatile Map<String, RuleSet> ruleSets = Map.of();

    public record RuleSet(String name, long importedAt, List<WafRule> rules, Map<String, Integer> skipped) {}
    private final Map<String, WafPolicy> policies = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> hitsByRule = new ConcurrentHashMap<>();
    private final LongAdder inspected = new LongAdder();
    private final LongAdder blocked = new LongAdder();
    private final LongAdder logged = new LongAdder();
    private final Deque<Map<String, Object>> events = new ArrayDeque<>();

    /** Result of an inspection. {@code blocked=false} with matches means log-only. */
    public record Verdict(boolean blocked, String incidentId, List<Match> matches, int score) {
        static final Verdict PASS = new Verdict(false, null, List.of(), 0);
    }

    public record Match(String ruleId, String ruleName, String category, String location, String snippet, String action) {}

    public static WafEngine getInstance() {
        WafEngine e = instance;
        if (e == null) {
            synchronized (WafEngine.class) {
                if (instance == null) {
                    instance = new WafEngine();
                    instance.load();
                }
                e = instance;
            }
        }
        return e;
    }

    // ------------------------------------------------------------------ configuration

    public List<WafRule> allRules() {
        List<WafRule> all = new ArrayList<>(builtIn);
        all.addAll(customRules);
        for (RuleSet rs : ruleSets.values()) all.addAll(rs.rules());
        return all;
    }

    public Map<String, RuleSet> ruleSets() {
        return ruleSets;
    }

    /** Rules that can run on a route: built-in, custom, and the imported sets the route has switched on. */
    private List<WafRule> candidates(WafPolicy p) {
        if (p.ruleSets == null || p.ruleSets.isEmpty() || ruleSets.isEmpty()) {
            List<WafRule> base = new ArrayList<>(builtIn);
            base.addAll(customRules);
            return base;
        }
        List<WafRule> all = new ArrayList<>(builtIn);
        all.addAll(customRules);
        for (String name : p.ruleSets) {
            RuleSet rs = ruleSets.get(name);
            if (rs != null) all.addAll(rs.rules());
        }
        return all;
    }

    /** Imports (or replaces) a rule set from ModSecurity/CRS files. */
    public synchronized CrsImporter.Result importRuleSet(String name, Map<String, String> files) throws Exception {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the rule set a name.");
        if (name.length() > 80) throw new IllegalArgumentException("Rule set name is too long (max 80 characters).");
        CrsImporter.Result r = CrsImporter.parse(name.trim(), files);
        if (r.rules().isEmpty()) throw new IllegalArgumentException("No usable rules found. Upload the CRS REQUEST-*.conf files (and the .data files they use).");
        RuleSet set = new RuleSet(name.trim(), System.currentTimeMillis(), r.rules(), r.skipped());
        persistRuleSet(set);
        Map<String, RuleSet> next = new LinkedHashMap<>(ruleSets);
        next.put(set.name(), set);
        ruleSets = Map.copyOf(next);
        LOG.info("WAF: imported rule set '{}' — {} rules, {} skipped", set.name(), r.rules().size(),
                r.skipped().values().stream().mapToInt(Integer::intValue).sum());
        return r;
    }

    public synchronized boolean deleteRuleSet(String name) throws Exception {
        if (!ruleSets.containsKey(name)) return false;
        SystemPropertyDB.getInstance().delete(P_RULESET_PREFIX + name);
        Map<String, RuleSet> next = new LinkedHashMap<>(ruleSets);
        next.remove(name);
        ruleSets = Map.copyOf(next);
        for (Map.Entry<String, WafPolicy> e : policies.entrySet()) {   // routes stop referencing it
            if (e.getValue().ruleSets != null && e.getValue().ruleSets.remove(name)) savePolicy(e.getKey(), e.getValue());
        }
        return true;
    }

    private void persistRuleSet(RuleSet set) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", set.name());
        m.put("importedAt", set.importedAt());
        m.put("skipped", set.skipped());
        List<Map<String, Object>> rs = new ArrayList<>();
        for (WafRule r : set.rules()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", r.getId());
            x.put("name", r.getName());
            x.put("category", r.getCategory());
            x.put("description", r.getDescription());
            x.put("pattern", r.getPattern());
            x.put("targets", r.getTargets());
            x.put("severity", r.getSeverity().name());
            x.put("paranoia", r.getParanoia());
            if (r.getHeaderNames() != null) x.put("headers", r.getHeaderNames());
            rs.add(x);
        }
        m.put("rules", rs);
        persist(P_RULESET_PREFIX + set.name(), MAPPER.writeValueAsString(m));
    }

    @SuppressWarnings("unchecked")
    private static RuleSet readRuleSet(String json) throws Exception {
        Map<String, Object> m = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        String name = String.valueOf(m.get("name"));
        List<WafRule> rules = new ArrayList<>();
        for (Map<String, Object> x : (List<Map<String, Object>>) m.get("rules")) {
            List<Target> targets = new ArrayList<>();
            for (Object o : (List<Object>) x.get("targets")) targets.add(Target.valueOf(String.valueOf(o)));
            try {
                rules.add(new WafRule(String.valueOf(x.get("id")), String.valueOf(x.get("name")), String.valueOf(x.get("category")),
                        String.valueOf(x.get("description")), String.valueOf(x.get("pattern")),
                        targets.isEmpty() ? null : java.util.EnumSet.copyOf(targets), Action.BLOCK, false,
                        WafRule.Severity.valueOf(String.valueOf(x.get("severity"))), ((Number) x.get("paranoia")).intValue(), name, true,
                        x.get("headers") instanceof List<?> hl ? new java.util.TreeSet<>(hl.stream().map(String::valueOf).toList()) : null));
            } catch (Exception e) {
                LOG.warn("Skipping unreadable imported rule {}: {}", x.get("id"), e.toString());
            }
        }
        Map<String, Integer> skipped = new LinkedHashMap<>();
        Object sk = m.get("skipped");
        if (sk instanceof Map<?, ?> sm) sm.forEach((k, v) -> skipped.put(String.valueOf(k), ((Number) v).intValue()));
        return new RuleSet(name, ((Number) m.getOrDefault("importedAt", 0L)).longValue(), List.copyOf(rules), skipped);
    }

    public WafRule rule(String id) {
        for (WafRule r : allRules()) if (r.getId().equals(id)) return r;
        return null;
    }

    public WafPolicy policy(String sourceId) {
        WafPolicy p = sourceId == null ? null : policies.get(sourceId);
        return p != null ? p : new WafPolicy();
    }

    public Map<String, WafPolicy> policies() {
        return policies;
    }

    public synchronized void savePolicy(String sourceId, WafPolicy p) throws Exception {
        if (p.rules != null) p.rules.keySet().removeIf(id -> rule(id) == null);
        p.maxBodyBytes = Math.max(1024, Math.min(1_048_576, p.maxBodyBytes));
        p.mode = "detect".equalsIgnoreCase(p.mode) ? "detect" : "anomaly".equalsIgnoreCase(p.mode) ? "anomaly" : "block";
        p.anomalyThreshold = Math.max(1, Math.min(1000, p.anomalyThreshold));
        p.paranoiaLevel = Math.max(1, Math.min(4, p.paranoiaLevel));
        if (p.ruleSets == null) p.ruleSets = new ArrayList<>();
        p.ruleSets.removeIf(n -> !ruleSets.containsKey(n));
        persist(P_POLICY_PREFIX + sourceId, MAPPER.writeValueAsString(p));
        policies.put(sourceId, p);
        LOG.info("WAF policy for route {}: {} ({} mode, {} rule override(s))", sourceId, p.enabled ? "enabled" : "disabled", p.mode,
                p.rules == null ? 0 : p.rules.size());
    }

    public synchronized void deletePolicy(String sourceId) throws Exception {
        policies.remove(sourceId);
        SystemPropertyDB.getInstance().delete(P_POLICY_PREFIX + sourceId);
    }

    /** Adds or replaces a custom rule. Throws IllegalArgumentException for an invalid pattern. */
    public synchronized WafRule saveCustomRule(String id, String name, String description, String pattern,
                                               Collection<Target> targets, Action action) throws Exception {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the rule a name.");
        if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("Enter a pattern to match.");
        if (pattern.length() > 1000) throw new IllegalArgumentException("Pattern is too long (max 1000 characters).");
        String ruleId = id == null || id.isBlank() ? "custom-" + UUID.randomUUID().toString().substring(0, 8) : id;
        if (builtIn.stream().anyMatch(r -> r.getId().equals(ruleId))) throw new IllegalArgumentException("Built-in rules can't be edited; disable it and add a custom rule instead.");
        WafRule r;
        try {
            r = new WafRule(ruleId, name.trim(), "Custom", description == null ? "" : description.trim(), pattern,
                    targets == null || targets.isEmpty() ? null : java.util.EnumSet.copyOf(targets), action, false);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression: " + e.getDescription());
        }
        List<WafRule> next = new ArrayList<>(customRules);
        next.removeIf(x -> x.getId().equals(ruleId));
        next.add(r);
        persistCustom(next);
        customRules = List.copyOf(next);
        return r;
    }

    public synchronized boolean deleteCustomRule(String id) throws Exception {
        List<WafRule> next = new ArrayList<>(customRules);
        if (!next.removeIf(x -> x.getId().equals(id))) return false;
        persistCustom(next);
        customRules = List.copyOf(next);
        return true;
    }

    private void persistCustom(List<WafRule> rules) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WafRule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("description", r.getDescription());
            m.put("pattern", r.getPattern());
            m.put("targets", r.getTargets());
            m.put("action", r.getDefaultAction());
            out.add(m);
        }
        persist(P_CUSTOM_RULES, MAPPER.writeValueAsString(out));
    }

    private static void persist(String name, String value) throws Exception {
        long now = System.currentTimeMillis();
        SystemProperty p = new SystemProperty();
        p.setId(name);
        p.setName(name);
        p.setGroupname(GROUP);
        p.setValue(value);
        p.setMts(now);
        p.setUts(now);
        SystemPropertyDB.getInstance().save(List.of(p));
    }

    /** Re-reads every WAF setting from the database (another cluster node changed one). */
    public synchronized void reload() {
        load();
    }

    /**
     * Loads custom rules, imported rule sets and route policies, replacing what's in memory. An imported set whose
     * import time hasn't changed keeps its already-compiled rules.
     */
    private synchronized void load() {
        try {
            List<WafRule> custom = List.of();
            Map<String, RuleSet> sets = new LinkedHashMap<>();
            Map<String, WafPolicy> pols = new LinkedHashMap<>();
            Map<String, RuleSet> before = ruleSets;
            for (SystemProperty p : SystemPropertyDB.getInstance().readAll()) {
                String name = p.getName();
                if (name == null || p.getValue() == null) continue;
                try {
                    if (name.equals(P_CUSTOM_RULES)) {
                        List<Map<String, Object>> raw = MAPPER.readValue(p.getValue(), new TypeReference<>() {});
                        List<WafRule> loaded = new ArrayList<>();
                        for (Map<String, Object> m : raw) {
                            List<Target> targets = new ArrayList<>();
                            Object t = m.get("targets");
                            if (t instanceof List<?> l) for (Object o : l) targets.add(Target.valueOf(String.valueOf(o)));
                            loaded.add(new WafRule(String.valueOf(m.get("id")), String.valueOf(m.get("name")), "Custom",
                                    String.valueOf(m.getOrDefault("description", "")), String.valueOf(m.get("pattern")),
                                    targets.isEmpty() ? null : java.util.EnumSet.copyOf(targets),
                                    Action.valueOf(String.valueOf(m.getOrDefault("action", "BLOCK"))), false));
                        }
                        custom = List.copyOf(loaded);
                    } else if (name.startsWith(P_RULESET_PREFIX)) {
                        String setName = name.substring(P_RULESET_PREFIX.length());
                        RuleSet old = before.get(setName);
                        Long importedAt = importedAt(p.getValue());
                        RuleSet rs = old != null && importedAt != null && old.importedAt() == importedAt ? old : readRuleSet(p.getValue());
                        sets.put(rs.name(), rs);
                    } else if (name.startsWith(P_POLICY_PREFIX)) {
                        pols.put(name.substring(P_POLICY_PREFIX.length()), MAPPER.readValue(p.getValue(), WafPolicy.class));
                    }
                } catch (Exception e) {
                    LOG.error("Ignoring unreadable WAF setting {}: {}", name, e.toString());
                }
            }
            customRules = custom;
            ruleSets = Map.copyOf(sets);
            policies.putAll(pols);
            policies.keySet().retainAll(pols.keySet());
            LOG.info("WAF loaded: {} built-in rules, {} custom rules, {} imported rule set(s), {} route policies", builtIn.size(),
                    customRules.size(), ruleSets.size(), policies.size());
        } catch (Exception e) {
            LOG.warn("Could not load WAF settings: {}", e.toString());
        }
    }

    /** Cheap peek at a persisted rule set's import time, without compiling its rules. */
    private static Long importedAt(String json) {
        int i = json.indexOf("\"importedAt\"");
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        int e = c + 1;
        while (e < json.length() && (Character.isWhitespace(json.charAt(e)) || Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) e++;
        try {
            return Long.parseLong(json.substring(c + 1, e).trim());
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ inspection

    /** Inspects a live request for the given route. Returns PASS quickly when the route's WAF is off. */
    public Verdict inspect(String sourceId, String routeName, String clientIp, FullHttpRequest req) {
        WafPolicy p = sourceId == null ? null : policies.get(sourceId);
        if (p == null || !p.enabled) return Verdict.PASS;
        String body = null;
        if (p.inspectBody && req.content() != null && req.content().isReadable() && inspectableBody(req.headers())) {
            int n = Math.min(req.content().readableBytes(), p.maxBodyBytes);
            body = req.content().toString(req.content().readerIndex(), n, StandardCharsets.UTF_8);
        }
        Verdict v = evaluate(p, req.uri(), req.headers(), body, true);
        if (!v.matches().isEmpty()) record(sourceId, routeName, clientIp, req.method().name(), req.uri(), v);
        return v;
    }

    /** Runs a policy against a sample request without recording anything (used by the "Test" panel). */
    public Verdict test(WafPolicy p, String uri, Map<String, String> headers, String body) {
        HttpHeaders h = new io.netty.handler.codec.http.DefaultHttpHeaders();
        if (headers != null) headers.forEach(h::set);
        WafPolicy enabled = p == null ? new WafPolicy() : p;
        enabled.enabled = true;
        return evaluate(enabled, uri == null ? "/" : uri, h, body, false);
    }

    private Verdict evaluate(WafPolicy p, String uri, HttpHeaders headers, String body, boolean live) {
        if (live) inspected.increment();
        // per-argument fields are only needed (and parsed) when imported CRS rule sets run on this route
        boolean imported = p.ruleSets != null && !p.ruleSets.isEmpty();
        Map<Target, List<String[]>> fields = extract(uri, headers, body, imported);
        List<Match> matches = new ArrayList<>(2);
        boolean block = false;
        boolean anomaly = p.anomaly();
        int score = 0;
        for (WafRule rule : candidates(p)) {
            if (rule.getParanoia() > p.paranoiaLevel) continue;
            WafPolicy.RuleSetting rs = p.setting(rule.getId());
            if (rs != null && !rs.enabled) continue;
            Action action = rs != null && rs.action != null ? ("log".equalsIgnoreCase(rs.action) ? Action.LOG : Action.BLOCK) : rule.getDefaultAction();
            Match m = firstMatch(rule, fields, action);
            if (m == null) continue;
            matches.add(m);
            if (live) hitsByRule.computeIfAbsent(rule.getId(), k -> new LongAdder()).increment();
            if (anomaly) {
                if (action == Action.BLOCK) score += rule.getSeverity().score;   // rules set to "log" only record
                if (score >= p.anomalyThreshold) {
                    block = true;
                    break;   // threshold reached: the request is rejected, no need to score further
                }
            } else if (action == Action.BLOCK && !p.detectOnly()) {
                block = true;
                break; // no need to keep scanning a request we're rejecting
            }
        }
        if (matches.isEmpty()) return Verdict.PASS;
        if (live) { if (block) blocked.increment(); else logged.increment(); }
        return new Verdict(block, UUID.randomUUID().toString().substring(0, 12), matches, score);
    }

    private static Match firstMatch(WafRule rule, Map<Target, List<String[]>> fields, Action action) {
        java.util.Set<String> only = rule.getHeaderNames();
        for (Target t : rule.getTargets()) {
            List<String[]> values = fields.get(t);
            if (values == null) continue;
            boolean importedRule = rule.getRuleSet() != null;
            boolean hasArgs = false;
            if (importedRule) for (String[] f : values) if (!RAW.equals(f[3])) { hasArgs = true; break; }
            for (String[] f : values) {
                // header-specific rules (e.g. CRS REQUEST_HEADERS:Content-Type) only see those headers; f[2] = lower-case name
                if (only != null && t == Target.HEADERS && (f[2] == null || !only.contains(f[2]))) continue;
                if (importedRule) {
                    if (RAW.equals(f[3]) && hasArgs) continue;      // CRS inspects arguments one by one, not the whole string
                } else if (DERIVED.equals(f[3])) {
                    continue;                                        // built-in rules keep their tuned whole-string view
                }
                Matcher m = importedRule ? (rule.matches(f[1]) ? rule.find(f[1]) : null) : rule.find(f[1]);
                if (m != null) {
                    int s = Math.max(0, m.start() - 20), e = Math.min(f[1].length(), m.end() + 20);
                    return new Match(rule.getId(), rule.getName(), rule.getCategory(), f[0], f[1].substring(s, e),
                            action == Action.BLOCK ? "block" : "log");
                }
            }
        }
        return null;
    }

    // Field = {location, normalised value, header name (headers only), kind}. Kinds:
    //  RAW     whole query string / whole body / whole Cookie header — what the built-in rules were tuned on
    //  PART    a query parameter or a single header — seen by every rule
    //  DERIVED a parsed body argument (form, JSON, XML) or one cookie — seen by imported (CRS) rules only,
    //          which, like ModSecurity, inspect arguments one at a time instead of the whole string
    static final String RAW = "raw", PART = "part", DERIVED = "derived";

    private static String[] field(String location, String value, String header, String kind) {
        return new String[]{location, value, header, kind};
    }

    private static Map<Target, List<String[]>> extract(String uri, HttpHeaders headers, String body, boolean deriveArgs) {
        Map<Target, List<String[]>> out = new LinkedHashMap<>();
        String rawPath = uri;
        String rawQuery = null;
        int q = uri.indexOf('?');
        if (q >= 0) { rawPath = uri.substring(0, q); rawQuery = uri.substring(q + 1); }
        out.put(Target.PATH, List.<String[]>of(field("path", normalize(rawPath), null, PART)));

        List<String[]> query = new ArrayList<>();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            query.add(field("query", normalize(rawQuery), null, RAW));
            try {
                for (Entry<String, List<String>> e : new QueryStringDecoder(uri).parameters().entrySet()) {
                    query.add(field("query param name", normalize(e.getKey()), null, PART));
                    for (String v : e.getValue()) query.add(field("query param " + e.getKey(), normalize(v), null, PART));
                }
            } catch (Exception ignored) {
                // malformed encoding — the raw normalised query above is still inspected
            }
        }
        out.put(Target.QUERY, query);

        List<String[]> hdrs = new ArrayList<>();
        String ua = null;
        for (Entry<String, String> h : headers) {
            String name = h.getKey().toLowerCase();
            if (name.equals("authorization") || name.equals("content-length") || name.equals("host")) continue;
            if (name.equals("user-agent")) ua = h.getValue();
            if (name.equals("cookie")) {
                hdrs.add(field("header Cookie", normalize(h.getValue()), name, RAW));
                if (deriveArgs) {
                    for (String c : h.getValue().split(";")) {
                        int eq = c.indexOf('=');
                        String cn = (eq < 0 ? c : c.substring(0, eq)).trim();
                        if (cn.isEmpty()) continue;
                        hdrs.add(field("cookie name", normalize(cn), name, DERIVED));
                        if (eq >= 0) hdrs.add(field("cookie " + cn, normalize(c.substring(eq + 1).trim()), name, DERIVED));
                    }
                }
                continue;
            }
            hdrs.add(field("header " + h.getKey(), normalize(h.getValue()), name, PART));
        }
        out.put(Target.HEADERS, hdrs);
        // Only when the client sent one: "empty User-Agent" rules must not fire for requests without the header.
        out.put(Target.USER_AGENT, ua == null ? List.of() : List.<String[]>of(field("user-agent", normalize(ua), "user-agent", PART)));

        if (body != null && !body.isEmpty()) {
            List<String[]> b = new ArrayList<>();
            b.add(field("body", normalize(body), null, RAW));
            if (deriveArgs) bodyArgs(body, headers.get(HttpHeaderNames.CONTENT_TYPE, "").toLowerCase(), b);
            out.put(Target.BODY, b);
        }
        return out;
    }

    /** Body → individual arguments (names and values), like ModSecurity's URLENCODED/JSON/XML body processors. */
    private static void bodyArgs(String body, String ct, List<String[]> out) {
        int before = out.size();
        try {
            if (ct.contains("json") || (ct.isEmpty() && (body.startsWith("{") || body.startsWith("[")))) {
                jsonArgs("", MAPPER.readTree(body), out, 0);
            } else if (ct.contains("form-urlencoded")) {
                for (Entry<String, List<String>> e : new QueryStringDecoder(body, false).parameters().entrySet()) {
                    out.add(field("body param name", normalize(e.getKey()), null, DERIVED));
                    for (String v : e.getValue()) out.add(field("body param " + e.getKey(), normalize(v), null, DERIVED));
                }
            } else if (ct.contains("xml")) {
                Matcher m = XML_VALUES.matcher(body);
                while (m.find() && out.size() - before < 500) {
                    String v = m.group(1) != null ? m.group(1) : m.group(2);
                    if (v != null && !v.isBlank()) out.add(field("xml value", normalize(v.trim()), null, DERIVED));
                }
            }
        } catch (Exception ignored) {
            // unparseable body: rules fall back to the raw body
        }
    }

    private static final java.util.regex.Pattern XML_VALUES = java.util.regex.Pattern.compile(">([^<]{1,4096})<|=\\s*\"([^\"]{0,4096})\"");

    private static void jsonArgs(String path, com.fasterxml.jackson.databind.JsonNode n, List<String[]> out, int depth) {
        if (depth > 32 || out.size() > 1000) return;
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> {
                out.add(field("json key", normalize(e.getKey()), null, DERIVED));
                jsonArgs(path + "." + e.getKey(), e.getValue(), out, depth + 1);
            });
        } else if (n.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode c : n) jsonArgs(path + "[]", c, out, depth + 1);
        } else if (!n.isNull()) {
            out.add(field("json " + (path.isEmpty() ? "value" : path.substring(1)), normalize(n.asText()), null, DERIVED));
        }
    }

    private static boolean inspectableBody(HttpHeaders h) {
        String ct = h.get(HttpHeaderNames.CONTENT_TYPE, "").toLowerCase();
        if (ct.isEmpty()) return true;
        return ct.contains("form-urlencoded") || ct.contains("json") || ct.contains("xml") || ct.startsWith("text/")
                || ct.contains("graphql") || ct.contains("multipart/form-data");
    }

    /** Decodes and canonicalises input so encoded or obfuscated attacks look like plain ones. */
    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.length() > MAX_FIELD ? raw.substring(0, MAX_FIELD) : raw;
        for (int i = 0; i < 3 && (s.indexOf('%') >= 0 || s.indexOf('+') >= 0); i++) {
            String d = urlDecode(s);
            if (d.equals(s)) break;
            s = d;
        }
        if (s.indexOf('&') >= 0) s = htmlDecode(s);
        if (s.indexOf('\\') >= 0) s = s.replace("\\u003c", "<").replace("\\u003e", ">").replace("\\x3c", "<").replace("\\x3e", ">");
        s = s.replace("\0", "");
        if (s.indexOf("/*") >= 0) s = s.replaceAll("/\\*[^*]{0,500}\\*/", " "); // SQL inline comments: UNION/**/SELECT
        return s.replaceAll("[\\t\\r\\n\\x0B\\f]+", " ");
    }

    private static String urlDecode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        byte[] buf = new byte[s.length()];
        int bl = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                buf[bl++] = (byte) (hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 2;
                continue;
            }
            if (bl > 0) { sb.append(new String(buf, 0, bl, StandardCharsets.UTF_8)); bl = 0; }
            sb.append(c == '+' ? ' ' : c);
        }
        if (bl > 0) sb.append(new String(buf, 0, bl, StandardCharsets.UTF_8));
        return sb.toString();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String htmlDecode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = s.substring(i + 1, semi).toLowerCase();
                    String rep = switch (ent) {
                        case "lt" -> "<"; case "gt" -> ">"; case "quot" -> "\""; case "apos" -> "'"; case "amp" -> "&";
                        case "colon" -> ":"; case "lpar" -> "("; case "rpar" -> ")"; case "sol" -> "/"; case "tab", "newline" -> " ";
                        default -> {
                            try {
                                if (ent.startsWith("#x")) yield String.valueOf((char) Integer.parseInt(ent.substring(2), 16));
                                if (ent.startsWith("#")) yield String.valueOf((char) Integer.parseInt(ent.substring(1)));
                            } catch (NumberFormatException ignored) { }
                            yield null;
                        }
                    };
                    if (rep != null) { sb.append(rep); i = semi; continue; }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ events & stats

    private void record(String sourceId, String routeName, String clientIp, String method, String uri, Verdict v) {
        Match first = v.matches().get(0);
        if (v.blocked()) {
            LOG.warn("WAF blocked {} {} from {} on {} — rule {} in {} [incident {}]", method, truncate(uri, 200), clientIp, routeName,
                    first.ruleId(), first.location(), v.incidentId());
        } else if (LOG.isInfoEnabled()) {
            LOG.info("WAF match (logged) {} {} from {} on {} — rule {}", method, truncate(uri, 200), clientIp, routeName, first.ruleId());
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", System.currentTimeMillis());
        e.put("incidentId", v.incidentId());
        e.put("action", v.blocked() ? "blocked" : "logged");
        if (v.score() > 0) e.put("score", v.score());
        e.put("routeId", sourceId);
        e.put("route", routeName);
        e.put("clientIp", clientIp);
        e.put("method", method);
        e.put("uri", truncate(uri, 300));
        List<Map<String, Object>> ms = new ArrayList<>();
        for (Match m : v.matches()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("ruleId", m.ruleId());
            mm.put("ruleName", m.ruleName());
            mm.put("category", m.category());
            mm.put("location", m.location());
            mm.put("snippet", truncate(m.snippet(), 160));
            mm.put("action", m.action());
            ms.add(mm);
        }
        e.put("matches", ms);
        synchronized (events) {
            events.addFirst(e);
            while (events.size() > MAX_EVENTS) events.removeLast();
        }
    }

    public List<Map<String, Object>> recentEvents(int limit) {
        synchronized (events) {
            List<Map<String, Object>> out = new ArrayList<>(Math.min(limit, events.size()));
            for (Map<String, Object> e : events) {
                if (out.size() >= limit) break;
                out.add(e);
            }
            return out;
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inspected", inspected.sum());
        m.put("blocked", blocked.sum());
        m.put("logged", logged.sum());
        Map<String, Long> hits = new LinkedHashMap<>();
        hitsByRule.forEach((k, v) -> hits.put(k, v.sum()));
        m.put("hitsByRule", hits);
        m.put("regexTimeouts", WafRule.TIMEOUTS.sum());
        m.put("cacheHits", WafRule.CACHE_HITS.sum());
        return m;
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
