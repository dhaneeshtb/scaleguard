package com.scaleguard.server.dns;

import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Desired on/off state of the DNS server, stored as the replicated setting {@code dns.enabled} (scope
 * {@code system_properties/dns}). Every node runs {@link #apply()} on startup and when that scope changes, so
 * start/stop issued on any node reaches the whole cluster; saved settings ({@link DnsSettings}) ride the same scope. Until an operator sets it, the {@code dnsServer}
 * property decides.
 */
public final class DnsService {

    private static final Logger LOG = LoggerFactory.getLogger(DnsService.class);
    static final String P_ENABLED = "dns.enabled";

    /** Saved-settings JSON the running config was built from; a different value means a restart. */
    private static String applied;

    private DnsService() {}

    /** The desired state: the replicated setting if set, else the {@code dnsServer} property. */
    public static boolean desired() {
        try {
            for (SystemProperty p : SystemPropertyDB.getInstance().readItems("id", P_ENABLED)) {
                if (p.getValue() != null) return Boolean.parseBoolean(p.getValue());
            }
        } catch (Exception e) {
            LOG.warn("Could not read {}, using dnsServer property: {}", P_ENABLED, e.toString());
        }
        return DnsConfig.enabled();
    }

    public static boolean running() { return DnsServer.current() != null; }

    /** Persists the desired state (replicated to every node) and applies it here. */
    public static void setEnabled(boolean enabled) throws Exception {
        long now = System.currentTimeMillis();
        SystemProperty p = new SystemProperty();
        p.setId(P_ENABLED);
        p.setName(P_ENABLED);
        p.setGroupname("dns");
        p.setValue(String.valueOf(enabled));
        p.setMts(now);
        p.setUts(now);
        SystemPropertyDB.getInstance().save(List.of(p));
        apply();
    }

    /** Starts or stops this node's server to match {@link #desired()}. Start failures (e.g. port in use) are thrown. */
    public static synchronized void apply() {
        boolean want = desired();
        String saved = DnsSettings.rawJson();
        boolean changed = !saved.equals(applied);
        if (changed) {
            DnsConfig.reset();
            applied = saved;
        }
        DnsServer s = DnsServer.current();
        if (!want && s != null) {
            s.stop();
        } else if (want && (s == null || changed)) {
            if (s != null) s.stop(); // new settings (port, forwarders, limits) need a fresh server
            try {
                new DnsServer().startBlocking();
            } catch (Exception e) {
                throw new IllegalStateException("DNS server failed to start: " + e, e);
            }
        }
    }

    /** Cluster reload hook: a failed start is logged and shows as running=false in /dns/status. */
    public static void reload() {
        try {
            apply();
        } catch (RuntimeException e) {
            LOG.error(e.getMessage(), e);
        }
    }
}
