package com.scaleguard.server.http.router;

import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import com.scaleguard.server.http.utils.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-client, per-route HTTP rate limiter (token bucket).
 *
 * <p>Each (client IP, source route, target route) pair gets a bucket holding up to
 * {@code limit/60 × burstSeconds} tokens, refilled continuously at {@code limit/60} per second.
 * A request spends one token; with none left it gets 429 plus a Retry-After saying when the next
 * token arrives. There's no cliff at the minute boundary, unlike a fixed per-minute window.
 *
 * <p>Settings are edited from the admin UI ({@code /ratelimit}), stored in the system_properties
 * table and applied immediately. First-run defaults come from {@code scaleguard.rateLimit} and
 * {@code scaleguard.rateLimitBurstSeconds} (system property, env var or application.properties).
 * A source system can override the limit with its own {@code rateLimit} (0 = unlimited).
 * The client IP comes from {@link ClientIpResolver}, so X-Forwarded-For is only trusted from known proxies.
 */
public class RateLimitManager {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitManager.class);

    static final String GROUP = "ratelimit";
    static final String P_ENABLED = "ratelimit.enabled";
    static final String P_PER_MINUTE = "ratelimit.perMinute";
    static final String P_BURST_SECONDS = "ratelimit.burstSeconds";
    static final String P_TRUSTED = "ratelimit.trustedProxies";
    static final String P_CLUSTER_MODE = "ratelimit.clusterMode";

    /**
     * Cluster modes: each node limits on its own (local); nodes share one budget, syncing twice a second (shared);
     * each node gets limit ÷ live nodes (divided); or one budget that is never exceeded (strict: every 100 ms each
     * node gets a slice of the client's remaining budget in proportion to where its requests arrive, and spends only that).
     */
    public static final String MODE_LOCAL = "local", MODE_SHARED = "shared", MODE_DIVIDED = "divided", MODE_STRICT = "strict";

    public static final String DEFAULT_TRUSTED = "127.0.0.0/8,::1/128";
    private static final long IDLE_EVICT_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int MIN_BURST = 10;

    private static final ScheduledExecutorService SWEEPER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sg-ratelimit-sweeper");
        t.setDaemon(true);
        return t;
    });

    private static volatile RateLimitManager instance;

    /** Immutable settings snapshot; swapped atomically on update. */
    public record Settings(boolean enabled, int perMinute, int burstSeconds, String trustedProxies, String clusterMode) {
        public Settings {
            perMinute = Math.max(1, perMinute);
            burstSeconds = Math.max(1, Math.min(3600, burstSeconds));
            trustedProxies = trustedProxies == null || trustedProxies.isBlank() ? DEFAULT_TRUSTED : trustedProxies.trim();
            clusterMode = clusterMode == null || clusterMode.isBlank() ? MODE_SHARED : clusterMode.trim().toLowerCase(java.util.Locale.ROOT);
            if (!clusterMode.equals(MODE_LOCAL) && !clusterMode.equals(MODE_SHARED) && !clusterMode.equals(MODE_DIVIDED)
                    && !clusterMode.equals(MODE_STRICT)) {
                throw new IllegalArgumentException("clusterMode must be local, shared, divided or strict");
            }
        }

        public Settings(boolean enabled, int perMinute, int burstSeconds, String trustedProxies) {
            this(enabled, perMinute, burstSeconds, trustedProxies, MODE_SHARED);
        }
    }

    /** Tokens one node spent on a key, as published to the other nodes (cumulative since the bucket was created). */
    public record Usage(String key, double consumed, int limitPerMinute, double demand) {}

    /** Outcome of a rate check. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOW = new Decision(true, 0);
    }

    private static final class Bucket {
        final String client;
        final String route;
        String key;
        double tokens;
        boolean admin;   // admin & internal endpoints (fixed burst)
        long lastRefill;
        long lastLogged;
        long rejectedSinceLog;
        long rejectedTotal;
        long lastRejected;
        int limitPerMinute;
        double unpublished;      // spent here since the last cluster publish
        double publishedTotal;   // spent here since this bucket was created, as last published
        boolean republish;       // the last publish failed
        // strict mode
        double attempts;         // requests seen here (allowed + refused): this node's demand, cumulative
        double publishedAttempts;
        double attemptsAtRebalance;
        double localRate = -1;   // EWMA of attempts per tick here; -1 = not measured yet
        java.util.Map<String, double[]> remote;   // node -> {EWMA attempts per tick, attempts since last rebalance}
        double share = -1;       // this node's fraction of the budget; -1 = not assigned yet
        double quota;            // tokens this node may still spend before the next rebalance
        long quotaRefill;

        Bucket(String client, String route) { this.client = client; this.route = route; }
    }

    private final IPBlockingManager ibm;
    private volatile Settings settings;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongAdder allowed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final long startedAt = System.currentTimeMillis();

    /** The shared limiter used by the HTTP frontend; settings are loaded from the database. */
    public static RateLimitManager getInstance() {
        RateLimitManager m = instance;
        if (m == null) {
            synchronized (RateLimitManager.class) {
                if (instance == null) instance = new RateLimitManager(loadSettings(), null);
                m = instance;
            }
        }
        return m;
    }

    /** Standalone limiter using the default configured rate limit (scaleguard.rateLimit, default 10 000 req/min). */
    public RateLimitManager() {
        this(configuredInt("scaleguard.rateLimit", 10_000));
    }

    /** Standalone limiter with its own settings (not persisted). */
    public RateLimitManager(int perMinute) {
        this(new Settings(perMinute > 0, Math.max(1, perMinute), 10, DEFAULT_TRUSTED), null);
    }

    public RateLimitManager(int perMinute, IPBlockingManager ibm) {
        this(new Settings(perMinute > 0, Math.max(1, perMinute), 10, DEFAULT_TRUSTED), ibm);
    }

    private RateLimitManager(Settings s, IPBlockingManager ibm) {
        this.ibm = ibm;
        apply(s);
        SWEEPER.scheduleAtFixedRate(this::sweep, 1, 1, TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------------ settings

    public Settings getSettings() {
        return settings;
    }

    /** Validates, persists and applies new settings. Existing buckets adopt the new limit on their next request. */
    public synchronized void update(Settings s) throws Exception {
        persist(s);
        apply(s);
    }

    private void apply(Settings s) {
        this.settings = s;
        ClientIpResolver.setTrustedProxies(s.trustedProxies());
        logger.info("HTTP rate limit {}: {}/min per client per route, burst {}s, trusted proxies {}{}",
                s.enabled() ? "on" : "off", s.perMinute(), s.burstSeconds(), s.trustedProxies(),
                com.scaleguard.server.cluster.ClusterManager.enabled() ? ", cluster mode " + s.clusterMode() : "");
    }

    private static Settings loadSettings() {
        boolean enabled = configuredInt("scaleguard.rateLimit", 10_000) > 0;
        int perMinute = Math.max(1, configuredInt("scaleguard.rateLimit", 10_000));
        int burstSeconds = configuredInt("scaleguard.rateLimitBurstSeconds", 10);
        String trusted = configured("scaleguard.trustedProxies", DEFAULT_TRUSTED);
        String clusterMode = configured("scaleguard.rateLimitClusterMode", MODE_SHARED);
        try {
            for (SystemProperty p : SystemPropertyDB.getInstance().readAll()) {
                String v = p.getValue();
                if (v == null) continue;
                switch (p.getName()) {
                    case P_ENABLED -> enabled = Boolean.parseBoolean(v);
                    case P_PER_MINUTE -> perMinute = Integer.parseInt(v);
                    case P_BURST_SECONDS -> burstSeconds = Integer.parseInt(v);
                    case P_TRUSTED -> trusted = v;
                    case P_CLUSTER_MODE -> clusterMode = v;
                    default -> { }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not read rate-limit settings from the database, using defaults: {}", e.toString());
        }
        try {
            return new Settings(enabled, perMinute, burstSeconds, trusted, clusterMode);
        } catch (IllegalArgumentException e) {
            return new Settings(enabled, perMinute, burstSeconds, trusted, MODE_SHARED);
        }
    }

    /** Re-reads the settings from the database (another cluster node changed them). */
    public void reloadSettings() {
        apply(loadSettings());
    }

    private static void persist(Settings s) throws Exception {
        long now = System.currentTimeMillis();
        List<SystemProperty> props = new ArrayList<>();
        props.add(prop(P_ENABLED, String.valueOf(s.enabled()), now));
        props.add(prop(P_PER_MINUTE, String.valueOf(s.perMinute()), now));
        props.add(prop(P_BURST_SECONDS, String.valueOf(s.burstSeconds()), now));
        props.add(prop(P_TRUSTED, s.trustedProxies(), now));
        props.add(prop(P_CLUSTER_MODE, s.clusterMode(), now));
        SystemPropertyDB.getInstance().save(props);
    }

    private static SystemProperty prop(String name, String value, long now) {
        SystemProperty p = new SystemProperty();
        p.setId(name);
        p.setName(name);
        p.setGroupname(GROUP);
        p.setValue(value);
        p.setMts(now);
        p.setUts(now);
        return p;
    }

    // ------------------------------------------------------------------ checks

    public IPBlockingManager getIbm() {
        return ibm;
    }

    public boolean isBlocked(String address) {
        return ibm != null && ibm.isBlocked(address);
    }

    public boolean checkRate(RouteTarget rt, String clientIp) {
        return check(rt, clientIp).allowed();
    }

    /** Kept for compatibility; {@code add=false} only peeks without spending a token. */
    public boolean isInRate(RouteTarget rt, String clientIp, boolean add) {
        if (add) return check(rt, clientIp).allowed();
        Bucket b = buckets.get(key(rt, ip(rt, clientIp)));
        if (b == null) return true;
        synchronized (b) {
            refill(b, System.nanoTime(), limitFor(rt));
            return b.tokens >= 1;
        }
    }

    public Decision check(RouteTarget rt, String clientIp) {
        int limit = limitFor(rt);
        if (limit <= 0) {
            allowed.increment();
            return Decision.ALLOW;
        }
        String ip = ip(rt, clientIp);
        if (AccessLists.get().isExempt(ip)) {   // exempt networks are never limited
            allowed.increment();
            return Decision.ALLOW;
        }
        if (isBlocked(ip)) {
            rejected.increment();
            return new Decision(false, 60);
        }
        String key = key(rt, ip);
        long now = System.nanoTime();
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket n = new Bucket(ip, routeLabel(rt));
            n.key = k;
            n.limitPerMinute = limit;
            n.admin = rt == null;
            n.tokens = burst(limit, n.admin);
            n.lastRefill = now;
            return n;
        });
        synchronized (b) {
            refill(b, now, limit);
            boolean strict = !b.admin && clusterStrict();
            if (strict) {
                b.attempts += 1;
                refillQuota(b, now, limit);
            }
            if (b.tokens >= 1 && (!strict || b.quota >= 1)) {
                b.tokens -= 1;
                if (strict) b.quota -= 1;
                if (!b.admin && clusterShared()) b.unpublished += 1;
                allowed.increment();
                return Decision.ALLOW;
            }
            rejected.increment();
            b.rejectedTotal++;
            b.rejectedSinceLog++;
            b.lastRejected = System.currentTimeMillis();
            if (now - b.lastLogged >= LOG_INTERVAL_NANOS) {
                logger.warn("Rate limit exceeded for {} on {} — {} request(s) rejected since last report", ip, b.route, b.rejectedSinceLog);
                b.lastLogged = now;
                b.rejectedSinceLog = 0;
            }
            if (ibm != null) ibm.block(ip);
            double perSecond = limit / 60.0;
            return new Decision(false, Math.max(1, (long) Math.ceil((1 - b.tokens) / perSecond)));
        }
    }

    /**
     * Admin UI, API and other internal endpoints have their own fixed limit, independent of the
     * route settings — so a strict limit set in the UI can never lock the admin out, while login
     * brute-forcing is still capped. Override with scaleguard.adminRateLimit (requests/minute).
     */
    private static final int ADMIN_LIMIT = Math.max(0, configuredInt("scaleguard.adminRateLimit", 3_000));

    /** Effective limit for this route in requests/minute on this node; 0 means unlimited. */
    private int limitFor(RouteTarget rt) {
        Settings s = settings;
        if (rt == null) return ADMIN_LIMIT;
        int limit = s.enabled() ? s.perMinute() : 0;
        if (rt.getSourceSystem() != null) {
            Integer override = rt.getSourceSystem().getRateLimit();
            if (override != null && override >= 0) limit = override;
        }
        if (limit > 0 && MODE_DIVIDED.equals(s.clusterMode()) && com.scaleguard.server.cluster.ClusterManager.enabled()) {
            int nodes = com.scaleguard.server.cluster.ClusterManager.liveNodes();
            if (nodes > 1) limit = Math.max(1, (int) Math.ceil(limit / (double) nodes));
        }
        return limit;
    }

    // ------------------------------------------------------------------ cluster-wide limits

    /** True when buckets are shared with the other cluster nodes (shared or strict mode). */
    public boolean clusterShared() {
        String m = settings.clusterMode();
        return (MODE_SHARED.equals(m) || MODE_STRICT.equals(m)) && com.scaleguard.server.cluster.ClusterManager.enabled();
    }

    /** True when each node may spend only its slice of a client's budget (never exceeded cluster-wide). */
    public boolean clusterStrict() {
        return MODE_STRICT.equals(settings.clusterMode()) && com.scaleguard.server.cluster.ClusterManager.enabled();
    }

    /** Strict: the slice refills with this node's share of the rate; unassigned buckets start at 1 / live nodes. */
    private void refillQuota(Bucket b, long now, int limitPerMinute) {
        if (b.share < 0) {
            b.share = 1.0 / Math.max(1, com.scaleguard.server.cluster.ClusterManager.liveNodes());
            b.quota = b.share * Math.max(0, b.tokens);
            b.quotaRefill = now;
            return;
        }
        double elapsed = (now - b.quotaRefill) / 1e9;
        if (elapsed > 0) {
            double cap = b.share * burst(limitPerMinute, false);
            b.quota = Math.min(Math.max(cap, 1), b.quota + elapsed * b.share * limitPerMinute / 60.0);
            b.quotaRefill = now;
        }
    }

    /** Strict: another node's demand (requests it saw, allowed or not) on a key since the last tick. */
    public void applyRemoteDemand(String key, String node, double attempts) {
        if (attempts <= 0 || key == null) return;
        Bucket b = buckets.get(key);
        if (b == null) return;           // created by applyRemote first when that node spent tokens
        synchronized (b) {
            if (b.remote == null) b.remote = new java.util.HashMap<>();
            b.remote.computeIfAbsent(node, k -> new double[]{-1, 0})[1] += attempts;
        }
    }

    private static final double EWMA = 0.5;     // weight of the newest tick in demand rates

    /**
     * Strict: after each sync tick, give every active key's budget out again — this node's slice is its share of the
     * recent demand for that client (allowed + refused requests, so a node that is refusing claims more), times the
     * tokens the client has left cluster-wide. The slices add up to the budget, so the limit is never exceeded.
     */
    public void rebalance() {
        long now = System.nanoTime();
        int live = Math.max(1, com.scaleguard.server.cluster.ClusterManager.liveNodes());
        for (Bucket b : buckets.values()) {
            if (b.admin) continue;
            synchronized (b) {
                double localDelta = b.attempts - b.attemptsAtRebalance;
                b.attemptsAtRebalance = b.attempts;
                b.localRate = b.localRate < 0 ? localDelta : EWMA * localDelta + (1 - EWMA) * b.localRate;
                double remoteRate = 0;
                int remoteActive = 0;
                if (b.remote != null) {
                    for (java.util.Iterator<double[]> it = b.remote.values().iterator(); it.hasNext(); ) {
                        double[] r = it.next();
                        r[0] = r[0] < 0 ? r[1] : EWMA * r[1] + (1 - EWMA) * r[0];
                        r[1] = 0;
                        if (r[0] < 0.01) it.remove();
                        else { remoteRate += r[0]; remoteActive++; }
                    }
                }
                if (b.localRate < 0.01 && remoteRate == 0) {
                    if (b.share >= 0) b.share = 1.0 / live;    // idle client: back to an even split
                    continue;
                }
                double eps = 0.05;
                b.share = (b.localRate + eps) / (b.localRate + remoteRate + eps * (1 + remoteActive));
                refill(b, now, b.limitPerMinute);
                b.quota = b.share * Math.max(0, b.tokens);
                b.quotaRefill = now;
            }
        }
    }

    /** Takes the per-key spend accumulated since the last call (at most {@code max} keys; the rest wait a tick). */
    public List<Usage> drainUsage(int max) {
        List<Usage> out = new ArrayList<>();
        for (Bucket b : buckets.values()) {
            if (b.admin || (b.unpublished <= 0 && !b.republish && b.attempts <= b.publishedAttempts)) continue;
            synchronized (b) {
                if (b.unpublished <= 0 && !b.republish && b.attempts <= b.publishedAttempts) continue;
                b.publishedTotal += b.unpublished;
                b.unpublished = 0;
                b.republish = false;
                b.publishedAttempts = b.attempts;
                out.add(new Usage(b.key, b.publishedTotal, b.limitPerMinute, b.attempts));
            }
            if (out.size() >= max) break;
        }
        return out;
    }

    /** Marks usage that failed to publish so the next tick sends it again (totals are cumulative, so nothing is lost). */
    public void restoreUsage(List<Usage> usage) {
        for (Usage u : usage) {
            Bucket b = buckets.get(u.key());
            if (b != null) {
                synchronized (b) {
                    b.republish = true;
                }
            }
        }
    }

    /** Deducts tokens another node spent on this key. Tokens may go negative (down to −burst), i.e. into debt. */
    public void applyRemote(String key, double spent, int limitPerMinute) {
        if (spent <= 0 || key == null || key.endsWith("|system")) return;
        long now = System.nanoTime();
        int limit = limitPerMinute > 0 ? limitPerMinute : settings.perMinute();
        Bucket b = buckets.computeIfAbsent(key, k -> {
            int bar = k.indexOf('|');
            Bucket n = new Bucket(bar > 0 ? k.substring(0, bar) : k, bar > 0 ? "route " + k.substring(bar + 1) : k);
            n.key = k;
            n.limitPerMinute = limit;
            n.tokens = burst(limit, false);
            n.lastRefill = now;
            return n;
        });
        synchronized (b) {
            refill(b, now, b.limitPerMinute > 0 ? b.limitPerMinute : limit);
            double cap = burst(b.limitPerMinute > 0 ? b.limitPerMinute : limit, false);
            b.tokens = Math.max(-cap, b.tokens - spent);
        }
    }

    /** Admin/internal traffic always gets a 10-second burst, so a tight UI burst setting can't throttle the admin UI. */
    private double burst(int limitPerMinute, boolean admin) {
        int seconds = admin ? ADMIN_BURST_SECONDS : settings.burstSeconds();
        return Math.max(MIN_BURST, Math.ceil(limitPerMinute / 60.0 * seconds));
    }

    private static final int ADMIN_BURST_SECONDS = 10;

    private void refill(Bucket b, long now, int limitPerMinute) {
        if (limitPerMinute <= 0) return;
        double cap = burst(limitPerMinute, b.admin);
        if (b.limitPerMinute != limitPerMinute) { // limit changed from the UI
            b.limitPerMinute = limitPerMinute;
            b.tokens = Math.min(b.tokens, cap);
        }
        double elapsed = (now - b.lastRefill) / 1e9;
        if (elapsed > 0) {
            b.tokens = Math.min(cap, b.tokens + elapsed * limitPerMinute / 60.0);
            b.lastRefill = now;
        }
    }

    private static String ip(RouteTarget rt, String clientIp) {
        return rt != null && rt.getClientIp() != null ? rt.getClientIp() : clientIp;
    }

    private static String key(RouteTarget rt, String ip) {
        if (rt == null || rt.getSourceSystem() == null || rt.getTargetSystem() == null) return ip + "|system";
        return ip + '|' + rt.getSourceSystem().getId() + '|' + rt.getTargetSystem().getId();
    }

    private static String routeLabel(RouteTarget rt) {
        if (rt == null || rt.getSourceSystem() == null) return "admin & internal";
        SourceSystem s = rt.getSourceSystem();
        String name = s.getName() != null && !s.getName().isBlank() ? s.getName() : s.getHost();
        return name + (s.getId() != null ? " (" + s.getId() + ")" : "");
    }

    void sweep() {
        long now = System.nanoTime();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastRefill > IDLE_EVICT_NANOS);
    }

    // ------------------------------------------------------------------ stats for the UI

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allowed", allowed.sum());
        m.put("rejected", rejected.sum());
        m.put("trackedClients", buckets.size());
        m.put("sinceMs", startedAt);
        m.put("clusterMode", settings.clusterMode());
        m.put("cluster", com.scaleguard.server.cluster.ClusterManager.enabled());
        m.put("clusterNodes", com.scaleguard.server.cluster.ClusterManager.liveNodes());
        long now = System.currentTimeMillis();
        List<Map<String, Object>> top = new ArrayList<>();
        buckets.values().stream()
                .filter(b -> b.rejectedTotal > 0 && now - b.lastRejected < TimeUnit.MINUTES.toMillis(10))
                .sorted(Comparator.comparingLong((Bucket b) -> b.rejectedTotal).reversed())
                .limit(20)
                .forEach(b -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("client", b.client);
                    e.put("route", b.route);
                    e.put("rejected", b.rejectedTotal);
                    e.put("limitPerMinute", b.limitPerMinute);
                    e.put("lastRejectedSecondsAgo", (now - b.lastRejected) / 1000);
                    top.add(e);
                });
        m.put("limitedClients", top);
        return m;
    }

    public int trackedKeys() {
        return buckets.size();
    }

    /**
     * Logs the current rate-limit state for a route target (or null for admin/internal routes).
     * Safe to call with a null argument; this is a read-only diagnostic — no tokens are spent.
     */
    public void log(RouteTarget rt) {
        String label = rt == null ? "admin & internal" : routeLabel(rt);
        Settings s = settings;
        int limit = limitFor(rt);
        logger.info("RateLimit state — route: {}, enabled: {}, limit: {}/min, trackedClients: {}",
                label, s.enabled(), limit, buckets.size());
    }

    // ------------------------------------------------------------------ config helpers

    private static String configured(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = AppProperties.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int configuredInt(String key, int def) {
        try {
            return Integer.parseInt(configured(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            logger.warn("Ignoring invalid {}", key);
            return def;
        }
    }
}
