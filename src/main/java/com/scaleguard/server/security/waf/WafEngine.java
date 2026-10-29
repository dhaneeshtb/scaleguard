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
    private static final int MAX_FIELD = 8_192;
    private static final int MAX_EVENTS = 500;

    private static volatile WafEngine instance;

    private final List<WafRule> builtIn = BuiltInRules.all();
    private volatile List<WafRule> customRules = List.of();
    private final Map<String, WafPolicy> policies = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> hitsByRule = new ConcurrentHashMap<>();
    private final LongAdder inspected = new LongAdder();
    private final LongAdder blocked = new LongAdder();
    private final LongAdder logged = new LongAdder();
    private final Deque<Map<String, Object>> events = new ArrayDeque<>();

    /** Result of an inspection. {@code blocked=false} with matches means log-only. */
    public record Verdict(boolean blocked, String incidentId, List<Match> matches) {
        static final Verdict PASS = new Verdict(false, null, List.of());
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
        return all;
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
        p.mode = "detect".equalsIgnoreCase(p.mode) ? "detect" : "block";
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

    private void load() {
        try {
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
                        customRules = List.copyOf(loaded);
                    } else if (name.startsWith(P_POLICY_PREFIX)) {
                        policies.put(name.substring(P_POLICY_PREFIX.length()), MAPPER.readValue(p.getValue(), WafPolicy.class));
                    }
                } catch (Exception e) {
                    LOG.error("Ignoring unreadable WAF setting {}: {}", name, e.toString());
                }
            }
            LOG.info("WAF loaded: {} built-in rules, {} custom rules, {} route policies", builtIn.size(), customRules.size(), policies.size());
        } catch (Exception e) {
            LOG.warn("Could not load WAF settings: {}", e.toString());
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
        Map<Target, List<String[]>> fields = extract(uri, headers, body); // [location, normalised value]
        List<Match> matches = new ArrayList<>(2);
        boolean block = false;
        for (WafRule rule : allRules()) {
            WafPolicy.RuleSetting rs = p.setting(rule.getId());
            if (rs != null && !rs.enabled) continue;
            Action action = rs != null && rs.action != null ? ("log".equalsIgnoreCase(rs.action) ? Action.LOG : Action.BLOCK) : rule.getDefaultAction();
            Match m = firstMatch(rule, fields, action);
            if (m == null) continue;
            matches.add(m);
            if (live) hitsByRule.computeIfAbsent(rule.getId(), k -> new LongAdder()).increment();
            if (action == Action.BLOCK && !p.detectOnly()) {
                block = true;
                break; // no need to keep scanning a request we're rejecting
            }
        }
        if (matches.isEmpty()) return Verdict.PASS;
        if (live) { if (block) blocked.increment(); else logged.increment(); }
        return new Verdict(block, UUID.randomUUID().toString().substring(0, 12), matches);
    }

    private static Match firstMatch(WafRule rule, Map<Target, List<String[]>> fields, Action action) {
        for (Target t : rule.getTargets()) {
            List<String[]> values = fields.get(t);
            if (values == null) continue;
            for (String[] f : values) {
                Matcher m = rule.compiled().matcher(f[1]);
                if (m.find()) {
                    int s = Math.max(0, m.start() - 20), e = Math.min(f[1].length(), m.end() + 20);
                    return new Match(rule.getId(), rule.getName(), rule.getCategory(), f[0], f[1].substring(s, e),
                            action == Action.BLOCK ? "block" : "log");
                }
            }
        }
        return null;
    }

    private static Map<Target, List<String[]>> extract(String uri, HttpHeaders headers, String body) {
        Map<Target, List<String[]>> out = new LinkedHashMap<>();
        String rawPath = uri;
        String rawQuery = null;
        int q = uri.indexOf('?');
        if (q >= 0) { rawPath = uri.substring(0, q); rawQuery = uri.substring(q + 1); }
        out.put(Target.PATH, List.<String[]>of(new String[]{"path", normalize(rawPath)}));

        List<String[]> query = new ArrayList<>();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            query.add(new String[]{"query", normalize(rawQuery)});
            try {
                for (Entry<String, List<String>> e : new QueryStringDecoder(uri).parameters().entrySet()) {
                    query.add(new String[]{"query param name", normalize(e.getKey())});
                    for (String v : e.getValue()) query.add(new String[]{"query param " + e.getKey(), normalize(v)});
                }
            } catch (Exception ignored) {
                // malformed encoding — the raw normalised query above is still inspected
            }
        }
        out.put(Target.QUERY, query);

        List<String[]> hdrs = new ArrayList<>();
        String ua = "";
        for (Entry<String, String> h : headers) {
            String name = h.getKey().toLowerCase();
            if (name.equals("authorization") || name.equals("content-length") || name.equals("host")) continue;
            if (name.equals("user-agent")) ua = h.getValue();
            hdrs.add(new String[]{"header " + h.getKey(), normalize(h.getValue())});
        }
        out.put(Target.HEADERS, hdrs);
        out.put(Target.USER_AGENT, List.<String[]>of(new String[]{"user-agent", normalize(ua)}));

        if (body != null && !body.isEmpty()) out.put(Target.BODY, List.<String[]>of(new String[]{"body", normalize(body)}));
        return out;
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
        return m;
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
