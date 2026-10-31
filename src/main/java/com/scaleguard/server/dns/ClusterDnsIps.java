package com.scaleguard.server.dns;

import com.scaleguard.server.cluster.ClusterManager;
import com.scaleguard.server.system.SystemManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The IPs a "base" record answers with when dnsAdvertiseIps is not pinned: this node's public IPs plus those of every
 * live peer whose DNS server is running (each node publishes {@code dnsIps} / {@code dnsRunning} in its heartbeat).
 * A peer that stops, or whose DNS server stops, drops out within a few seconds.
 */
final class ClusterDnsIps {

    private static final long REFRESH_MS = 5_000;
    private static volatile List<String> peers = List.of();
    private static volatile long refreshedAt;
    private static final AtomicBoolean refreshing = new AtomicBoolean();

    private ClusterDnsIps() {}

    /** This node's own public IPs, as published to peers. */
    static List<String> local() {
        return SystemManager.readNetworksCached();
    }

    static List<String> all() {
        List<String> local = local();
        if (!ClusterManager.enabled()) return local;
        // reading the member list can hit the database, so refresh off the query path and serve the last result
        if (System.currentTimeMillis() - refreshedAt > REFRESH_MS && refreshing.compareAndSet(false, true)) {
            Thread.ofVirtual().name("sg-dns-peer-ips").start(() -> {
                try {
                    peers = fromSnapshot(ClusterManager.get().snapshot());
                } catch (RuntimeException ignored) {
                    // keep the last known peers
                } finally {
                    refreshedAt = System.currentTimeMillis();
                    refreshing.set(false);
                }
            });
        }
        Set<String> out = new LinkedHashSet<>(local);
        out.addAll(peers);
        return new ArrayList<>(out);
    }

    /** IPs of the other nodes that are alive with their DNS server running. */
    @SuppressWarnings("unchecked")
    static List<String> fromSnapshot(Map<String, Object> snapshot) {
        Set<String> out = new LinkedHashSet<>();
        Object nodes = snapshot.get("nodes");
        if (!(nodes instanceof List<?> list)) return List.of();
        for (Object o : list) {
            Map<String, Object> n = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(n.get("self")) || !Boolean.TRUE.equals(n.get("alive"))) continue;
            Object info = n.get("info");
            if (!(info instanceof Map<?, ?> m) || !Boolean.TRUE.equals(m.get("dnsRunning"))) continue;
            if (m.get("dnsIps") instanceof List<?> ips) ips.forEach(ip -> out.add(String.valueOf(ip)));
        }
        return new ArrayList<>(out);
    }
}
