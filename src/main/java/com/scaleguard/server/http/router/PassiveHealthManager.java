package com.scaleguard.server.http.router;

import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Passive health checking (outlier detection): watches real traffic and takes a backend out of rotation as soon as
 * it fails {@code consecutiveFailures} requests in a row, instead of waiting for the next active health poll.
 *
 * <p>A failure is a connection error, a connection reset before the response, a response status in
 * {@code failureStatuses} (default 502, 503, 504), or gRPC UNAVAILABLE. Any other response counts as success
 * and resets the streak.
 *
 * <p>An ejected host gets no new requests for {@code baseEjectionSeconds × 2^(n-1)} (n = consecutive ejections,
 * capped at {@code maxEjectionSeconds}). When the time is up it is tried again: one failure ejects it again with a
 * longer back-off; a success clears the back-off ("half-open" probing with real requests).
 *
 * <p>Safety: if every host of a target is unavailable only because of passive ejection, {@link BestHostSelector}
 * keeps routing to the actively-healthy ones (panic mode), so outlier detection can never take a whole service down.
 *
 * <p>Settings are edited from the admin UI ({@code /backends}) and stored in system_properties ({@code health.passive.*}).
 */
public final class PassiveHealthManager {

    private static final Logger LOG = LoggerFactory.getLogger(PassiveHealthManager.class);
    private static final String GROUP = "health";
    static final String P_ENABLED = "health.passive.enabled";
    static final String P_FAILURES = "health.passive.consecutiveFailures";
    static final String P_BASE = "health.passive.baseEjectionSeconds";
    static final String P_MAX = "health.passive.maxEjectionSeconds";
    static final String P_STATUSES = "health.passive.failureStatuses";

    public record Settings(boolean enabled, int consecutiveFailures, int baseEjectionSeconds, int maxEjectionSeconds,
                           Set<Integer> failureStatuses) {
        public Settings {
            consecutiveFailures = Math.max(1, Math.min(1000, consecutiveFailures));
            baseEjectionSeconds = Math.max(1, Math.min(3600, baseEjectionSeconds));
            maxEjectionSeconds = Math.max(baseEjectionSeconds, Math.min(86_400, maxEjectionSeconds));
            failureStatuses = failureStatuses == null ? Set.of(502, 503, 504) : Set.copyOf(failureStatuses);
        }

        public static Set<Integer> parseStatuses(String csv) {
            Set<Integer> out = new TreeSet<>();
            if (csv == null) return out;
            for (String p : csv.split("[,\\s]+")) {
                if (p.isBlank()) continue;
                int code = Integer.parseInt(p.trim());
                if (code < 100 || code > 599) throw new IllegalArgumentException("Not an HTTP status: " + p);
                out.add(code);
            }
            return out;
        }

        public String statusesCsv() {
            return String.join(",", new TreeSet<>(failureStatuses).stream().map(String::valueOf).toList());
        }
    }

    private static final PassiveHealthManager INSTANCE = new PassiveHealthManager();
    private volatile Settings settings;

    private PassiveHealthManager() {
        settings = load();
    }

    public static PassiveHealthManager getInstance() {
        return INSTANCE;
    }

    public Settings getSettings() {
        return settings;
    }

    public synchronized void update(Settings s) throws Exception {
        long now = System.currentTimeMillis();
        List<SystemProperty> props = new ArrayList<>();
        props.add(prop(P_ENABLED, String.valueOf(s.enabled()), now));
        props.add(prop(P_FAILURES, String.valueOf(s.consecutiveFailures()), now));
        props.add(prop(P_BASE, String.valueOf(s.baseEjectionSeconds()), now));
        props.add(prop(P_MAX, String.valueOf(s.maxEjectionSeconds()), now));
        props.add(prop(P_STATUSES, s.statusesCsv(), now));
        SystemPropertyDB.getInstance().save(props);
        settings = s;
        if (!s.enabled()) RouteTable.getInstance().getHostGroups().forEach(this::reset);
        LOG.info("Passive health checks {}: eject after {} consecutive failures for {}–{}s; failure statuses {}",
                s.enabled() ? "on" : "off", s.consecutiveFailures(), s.baseEjectionSeconds(), s.maxEjectionSeconds(), s.statusesCsv());
    }

    private static Settings load() {
        boolean enabled = !"false".equalsIgnoreCase(System.getProperty("scaleguard.passiveHealth", "true"));
        int failures = 5, base = 10, max = 300;
        Set<Integer> statuses = Set.of(502, 503, 504);
        try {
            for (SystemProperty p : SystemPropertyDB.getInstance().readAll()) {
                String v = p.getValue();
                if (v == null || p.getName() == null) continue;
                switch (p.getName()) {
                    case P_ENABLED -> enabled = Boolean.parseBoolean(v);
                    case P_FAILURES -> failures = Integer.parseInt(v);
                    case P_BASE -> base = Integer.parseInt(v);
                    case P_MAX -> max = Integer.parseInt(v);
                    case P_STATUSES -> statuses = Settings.parseStatuses(v);
                    default -> { }
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not read passive health settings, using defaults: {}", e.toString());
        }
        return new Settings(enabled, failures, base, max, statuses);
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

    // ------------------------------------------------------------------ signals from the proxy

    /** Records the outcome of one proxied request from its HTTP status. */
    public void recordStatus(HostGroup hg, int status) {
        if (hg == null) return;
        if (settings.failureStatuses().contains(status)) recordFailure(hg, "HTTP " + status);
        else recordSuccess(hg);
    }

    public void recordSuccess(HostGroup hg) {
        if (hg == null) return;
        HostGroup.PassiveState p = hg.getPassive();
        p.requests.increment();
        if (p.consecutiveFailures.get() != 0) p.consecutiveFailures.set(0);
        // A success after the ejection window has passed: the host has recovered, reset the back-off.
        if (p.ejections != 0 && !hg.isEjected()) {
            p.ejections = 0;
            p.ejectedUntilNanos = 0;
            LOG.info("Backend {}:{} recovered after passive ejection", hg.getHost(), hg.getPort());
        }
    }

    public void recordFailure(HostGroup hg, String reason) {
        if (hg == null) return;
        HostGroup.PassiveState p = hg.getPassive();
        p.requests.increment();
        p.failures.increment();
        p.lastFailure = reason;
        p.lastFailureMillis = System.currentTimeMillis();
        Settings s = settings;
        if (!s.enabled() || hg.isEjected()) return;
        // Half-open: a host that was ejected before and fails again is re-ejected immediately.
        boolean probing = p.ejections > 0;
        int streak = p.consecutiveFailures.incrementAndGet();
        if (probing || streak >= s.consecutiveFailures()) eject(hg, s, reason, streak);
    }

    private void eject(HostGroup hg, Settings s, String reason, int streak) {
        HostGroup.PassiveState p = hg.getPassive();
        synchronized (p) {
            if (hg.isEjected()) return;
            int n = Math.min(p.ejections + 1, 20);
            long seconds = Math.min((long) s.baseEjectionSeconds() << (n - 1), s.maxEjectionSeconds());
            p.ejections = n;
            p.ejectedAtMillis = System.currentTimeMillis();
            p.ejectedUntilNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            p.consecutiveFailures.set(0);
            p.totalEjections.increment();
            LOG.warn("Backend {}:{} ejected for {}s after {} failure(s) (last: {}); ejection #{}",
                    hg.getHost(), hg.getPort(), seconds, streak, reason, n);
        }
    }

    /** Brings a host back immediately (admin action, or when passive checking is switched off). */
    public void reset(HostGroup hg) {
        HostGroup.PassiveState p = hg.getPassive();
        p.ejectedUntilNanos = 0;
        p.ejections = 0;
        p.consecutiveFailures.set(0);
    }

    /** Seconds until an ejected host is tried again (0 when it isn't ejected). */
    public static long secondsLeft(HostGroup hg) {
        if (!hg.isEjected()) return 0;
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(hg.getPassive().ejectedUntilNanos - System.nanoTime()));
    }
}
