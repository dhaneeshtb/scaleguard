package com.scaleguard.server.http.router;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import com.scaleguard.server.http.utils.CidrSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Gateway-wide IP access lists, managed from the Rate limiting page.
 *
 * <ul>
 *   <li><b>Blocked</b> — IPs and CIDR ranges refused with 403 on every route (nginx {@code deny}). Matched against the
 *       real client address (X-Forwarded-For, believed only from trusted proxies) <i>and</i> the connecting peer, so a
 *       misbehaving proxy or CDN edge can be blocked too. Entries can expire (temporary bans).</li>
 *   <li><b>Exempt</b> — never rate limited (nginx {@code geo} + empty {@code limit_req} key): monitoring, office
 *       networks, partners. Matched against the real client address only, so it can't be claimed with a forged header.</li>
 * </ul>
 * The admin API isn't subject to the blocklist, so an operator can't lock themselves out; it keeps its own login and
 * rate limit. Per-route allow/deny lists live on the source system ({@code ipAllow}, {@code ipDeny}).
 * Stored in system_properties ({@code ratelimit.blocked}, {@code ratelimit.exempt}), so a cluster shares them.
 */
public final class AccessLists {

    private static final Logger LOG = LoggerFactory.getLogger(AccessLists.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String P_BLOCKED = "ratelimit.blocked";
    static final String P_EXEMPT = "ratelimit.exempt";
    public static final String BLOCKED = "blocked", EXEMPT = "exempt";
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_RECENT = 500;

    /** One list entry. A plain class (not a record) so Jackson can store it. */
    public static final class Entry {
        public String value;
        public String note;
        public long addedAt;
        public long expiresAt;   // epoch ms; 0 = never

        public Entry() {}

        Entry(String value, String note, long addedAt, long expiresAt) {
            this.value = value;
            this.note = note;
            this.addedAt = addedAt;
            this.expiresAt = expiresAt;
        }

        boolean expired(long now) {
            return expiresAt > 0 && now >= expiresAt;
        }
    }

    private record Compiled(List<Entry> entries, CidrSet set, long nextExpiry) {
        static final Compiled EMPTY = new Compiled(List.of(), CidrSet.parse(""), Long.MAX_VALUE);
    }

    private static final AccessLists INSTANCE = new AccessLists();

    public static AccessLists get() {
        return INSTANCE;
    }

    private volatile Compiled blocked = Compiled.EMPTY;
    private volatile Compiled exempt = Compiled.EMPTY;
    private final LongAdder blockedRequests = new LongAdder();
    private final Map<String, long[]> recent = new ConcurrentHashMap<>();   // ip -> {count, lastMillis}

    private AccessLists() {
        reload();
    }

    // ------------------------------------------------------------------ checks (hot path)

    /** True when the client or the connecting peer is on the blocklist. Records the hit for the UI. */
    public boolean isBlocked(String peerIp, String clientIp) {
        Compiled c = current(BLOCKED);
        if (c.entries.isEmpty()) return false;
        String hit = c.set.contains(clientIp) ? clientIp : peerIp != null && !peerIp.equals(clientIp) && c.set.contains(peerIp) ? peerIp : null;
        if (hit == null) return false;
        blockedRequests.increment();
        long now = System.currentTimeMillis();
        long[] r = recent.computeIfAbsent(hit, k -> new long[2]);
        synchronized (r) {
            r[0]++;
            r[1] = now;
        }
        if (recent.size() > MAX_RECENT) trimRecent();
        return true;
    }

    /** True when the (real) client address is exempt from rate limiting. */
    public boolean isExempt(String clientIp) {
        Compiled c = current(EXEMPT);
        return !c.entries.isEmpty() && c.set.contains(clientIp);
    }

    private Compiled current(String list) {
        Compiled c = BLOCKED.equals(list) ? blocked : exempt;
        if (System.currentTimeMillis() >= c.nextExpiry) {   // a temporary entry ran out: rebuild without it
            synchronized (this) {
                c = BLOCKED.equals(list) ? blocked : exempt;
                if (System.currentTimeMillis() >= c.nextExpiry) {
                    c = compile(c.entries);
                    if (BLOCKED.equals(list)) blocked = c; else exempt = c;
                }
            }
        }
        return c;
    }

    private void trimRecent() {
        List<Map.Entry<String, long[]>> all = new ArrayList<>(recent.entrySet());
        all.sort(Comparator.comparingLong(e -> e.getValue()[1]));
        for (int i = 0; i < all.size() - MAX_RECENT / 2; i++) recent.remove(all.get(i).getKey());
    }

    // ------------------------------------------------------------------ management

    /** Normalises one IP or CIDR ("10.0.0.0/8", "2001:db8::/32", "203.0.113.7"); null when it isn't one. */
    public static String normalise(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        CidrSet s = CidrSet.parse(v);
        if (s.isEmpty()) return null;
        String n = s.toString();                   // e.g. "203.0.113.7/32"
        if (n.endsWith("/32") && !v.contains("/")) return n.substring(0, n.length() - 3);
        if (n.endsWith("/128") && !v.contains("/")) return n.substring(0, n.length() - 4);
        return n;
    }

    /**
     * Adds entries (comma, space or newline separated). Returns the values added; throws IllegalArgumentException
     * listing anything that isn't an IP or CIDR.
     */
    public synchronized List<String> add(String list, String values, String note, long ttlSeconds) throws Exception {
        Compiled c = compiledOf(list);
        List<String> bad = new ArrayList<>();
        List<String> ok = new ArrayList<>();
        for (String raw : values == null ? new String[0] : values.split("[,\\s]+")) {
            if (raw.isBlank()) continue;
            String n = normalise(raw);
            if (n == null) bad.add(raw.trim()); else if (!ok.contains(n)) ok.add(n);
        }
        if (!bad.isEmpty()) throw new IllegalArgumentException("Not an IP address or CIDR range: " + String.join(", ", bad));
        if (ok.isEmpty()) throw new IllegalArgumentException("Enter an IP address or CIDR range.");
        long now = System.currentTimeMillis();
        long expires = ttlSeconds > 0 ? now + TimeUnit.SECONDS.toMillis(ttlSeconds) : 0;
        Map<String, Entry> next = new LinkedHashMap<>();
        for (Entry e : c.entries) if (!e.expired(now)) next.put(e.value, e);
        for (String v : ok) {
            next.put(v, new Entry(v, note == null || note.isBlank() ? null : note.trim(), now, expires));
        }
        if (next.size() > MAX_ENTRIES) throw new IllegalArgumentException("A list can hold at most " + MAX_ENTRIES + " entries.");
        save(list, new ArrayList<>(next.values()));
        if (BLOCKED.equals(list)) for (String v : ok) recent.remove(v);
        return ok;
    }

    public synchronized boolean remove(String list, String value) throws Exception {
        Compiled c = compiledOf(list);
        String n = normalise(value);
        List<Entry> next = new ArrayList<>();
        boolean found = false;
        for (Entry e : c.entries) {
            if (e.value.equals(n) || e.value.equals(value)) found = true; else next.add(e);
        }
        if (found) save(list, next);
        return found;
    }

    private Compiled compiledOf(String list) {
        if (BLOCKED.equals(list)) return blocked;
        if (EXEMPT.equals(list)) return exempt;
        throw new IllegalArgumentException("list must be \"blocked\" or \"exempt\"");
    }

    private void save(String list, List<Entry> entries) throws Exception {
        long now = System.currentTimeMillis();
        SystemProperty p = new SystemProperty();
        String name = BLOCKED.equals(list) ? P_BLOCKED : P_EXEMPT;
        p.setId(name);
        p.setName(name);
        p.setGroupname("ratelimit");
        p.setValue(MAPPER.writeValueAsString(entries));
        p.setMts(now);
        p.setUts(now);
        SystemPropertyDB.getInstance().save(List.of(p));
        Compiled c = compile(entries);
        if (BLOCKED.equals(list)) blocked = c; else exempt = c;
    }

    /** Re-reads both lists from the database (start-up, or another cluster node changed them). */
    public synchronized void reload() {
        List<Entry> b = List.of(), x = List.of();
        try {
            for (SystemProperty p : SystemPropertyDB.getInstance().readAll()) {
                if (p.getName() == null || p.getValue() == null) continue;
                if (p.getName().equals(P_BLOCKED)) b = read(p.getValue());
                else if (p.getName().equals(P_EXEMPT)) x = read(p.getValue());
            }
        } catch (Exception e) {
            LOG.warn("Could not read IP access lists: {}", e.toString());
        }
        blocked = compile(b);
        exempt = compile(x);
        if (!b.isEmpty() || !x.isEmpty()) LOG.info("IP access lists: {} blocked, {} exempt from rate limits", b.size(), x.size());
    }

    private static List<Entry> read(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<Entry>>() {});
        } catch (Exception e) {
            LOG.warn("Ignoring unreadable IP access list: {}", e.toString());
            return List.of();
        }
    }

    private static Compiled compile(List<Entry> entries) {
        long now = System.currentTimeMillis();
        List<Entry> live = new ArrayList<>();
        long next = Long.MAX_VALUE;
        for (Entry e : entries) {
            if (e == null || e.value == null || e.expired(now)) continue;
            live.add(e);
            if (e.expiresAt > 0) next = Math.min(next, e.expiresAt);
        }
        if (live.isEmpty()) return new Compiled(List.of(), CidrSet.parse(""), Long.MAX_VALUE);
        return new Compiled(List.copyOf(live), CidrSet.parse(String.join(",", live.stream().map(e -> e.value).toList())), next);
    }

    // ------------------------------------------------------------------ view

    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(BLOCKED, entries(current(BLOCKED)));
        m.put(EXEMPT, entries(current(EXEMPT)));
        m.put("blockedRequests", blockedRequests.sum());
        long now = System.currentTimeMillis();
        List<Map<String, Object>> top = new ArrayList<>();
        recent.entrySet().stream()
                .filter(e -> now - e.getValue()[1] < TimeUnit.HOURS.toMillis(1))
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(20)
                .forEach(e -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("client", e.getKey());
                    r.put("requests", e.getValue()[0]);
                    r.put("lastSecondsAgo", (now - e.getValue()[1]) / 1000);
                    top.add(r);
                });
        m.put("recentlyBlocked", top);
        return m;
    }

    private static List<Map<String, Object>> entries(Compiled c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : c.entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", e.value);
            m.put("note", e.note);
            m.put("addedAt", e.addedAt);
            m.put("expiresAt", e.expiresAt == 0 ? null : e.expiresAt);
            out.add(m);
        }
        return out;
    }
}
