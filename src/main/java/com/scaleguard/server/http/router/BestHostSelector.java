package com.scaleguard.server.http.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Selects the best backend host from a group of candidates for multi-cluster,
 * disaster-recovery load balancing.
 *
 * <h3>Disaster-recovery semantics</h3>
 * <ul>
 *   <li><b>ACTIVE_STANDBY</b> — traffic always goes to the lowest-priority Active host.
 *       Standby hosts receive traffic only when ALL Active hosts are unreachable.
 *       Within each tier hosts are selected round-robin to spread load evenly.</li>
 *   <li><b>ROUND_ROBIN</b> — equal distribution across all reachable hosts.</li>
 *   <li><b>LEAST_CONNECTIONS</b> — always pick the host with the fewest in-flight connections.</li>
 *   <li><b>WEIGHTED</b> — distribute proportionally to each host's {@code weight} field.</li>
 * </ul>
 *
 * <p>All strategies silently skip unreachable hosts (active checks by {@link HostGroupWatcher}, passive
 * ejection by {@link PassiveHealthManager}). If every host is out only through passive ejection, the
 * actively-healthy hosts are used anyway (panic mode).
 * If <em>all</em> hosts are unreachable, {@code null} is returned and the caller must
 * surface a 502 to the client.</p>
 */
public class BestHostSelector {

    private static final Logger LOG = LoggerFactory.getLogger(BestHostSelector.class);

    public enum LoadBalanceStrategy {
        ROUND_ROBIN,
        LEAST_CONNECTIONS,
        WEIGHTED,
        ACTIVE_STANDBY
    }

    // Counters are per-strategy so they do not interfere with each other
    private static final AtomicLong roundRobinCounter  = new AtomicLong(0);
    private static final AtomicLong weightedCounter    = new AtomicLong(0);
    // Separate counters for ACTIVE_STANDBY tiers — dedicated so they never interfere
    // with the plain ROUND_ROBIN counter on routes that mix both strategies.
    private static final AtomicLong activeCounter      = new AtomicLong(0);
    private static final AtomicLong standbyCounter     = new AtomicLong(0);
    private static volatile long lastAllDownLog = System.nanoTime() - 60_000_000_000L;

    /** Select the best host using the default ROUND_ROBIN strategy. */
    public static HostGroup getBestHost(List<HostGroup> hostGroups) {
        return getBestHost(hostGroups, LoadBalanceStrategy.ROUND_ROBIN);
    }

    /**
     * Select the best host using the specified load balancing strategy.
     *
     * @param hostGroups list of candidate hosts (may contain unreachable hosts)
     * @param strategy   the load balancing strategy to apply
     * @return the selected HostGroup, or {@code null} if no reachable hosts exist
     */
    public static HostGroup getBestHost(List<HostGroup> hostGroups, LoadBalanceStrategy strategy) {
        if (hostGroups == null || hostGroups.isEmpty()) {
            return null;
        }

        // Servers marked down (active=false) never get traffic; servers at max_conns are skipped.
        List<HostGroup> usable = hostGroups.stream().filter(HostGroup::isActive).collect(Collectors.toList());
        List<HostGroup> reachable = usable.stream()
                .filter(h -> h.isReachable() && !h.isAtCapacity())
                .collect(Collectors.toList());

        if (reachable.isEmpty()) {
            // Panic mode: if hosts are out only because passive health checking ejected them, keep using the
            // actively-healthy ones rather than failing every request (outlier detection must never black out a service).
            reachable = usable.stream().filter(h -> h.isActivelyHealthy() && !h.isAtCapacity()).collect(Collectors.toList());
        }
        if (strategy != LoadBalanceStrategy.ACTIVE_STANDBY && reachable.size() > 1) {
            // Backup servers (type Standby) only take traffic when no primary can (nginx "backup")
            List<HostGroup> primaries = reachable.stream().filter(h -> !h.isBackup()).collect(Collectors.toList());
            if (!primaries.isEmpty()) reachable = primaries;
        }
        if (reachable.isEmpty()) {
            // All hosts are down — log (at most every 30 s, this runs per request) so operators can act
            long now = System.nanoTime();
            if (now - lastAllDownLog < 30_000_000_000L) return null;
            lastAllDownLog = now;
            LOG.error("ALL hosts in group are unreachable — cannot route request. Hosts: {}",
                    hostGroups.stream()
                            .map(h -> h.getHost() + ":" + h.getPort())
                            .collect(Collectors.joining(", ")));
            return null;
        }

        switch (strategy) {
            case LEAST_CONNECTIONS: return selectLeastConnections(reachable);
            case WEIGHTED:          return selectWeighted(reachable);
            case ACTIVE_STANDBY:    return selectActiveStandby(reachable);
            default:                return selectRoundRobin(reachable);
        }
    }

    /** Parse a strategy name, returning ROUND_ROBIN for null / unrecognised values. */
    public static LoadBalanceStrategy parseStrategy(String strategy) {
        if (strategy == null || strategy.isEmpty()) {
            return LoadBalanceStrategy.ROUND_ROBIN;
        }
        try {
            return LoadBalanceStrategy.valueOf(strategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("Unknown LB strategy '{}', falling back to ROUND_ROBIN", strategy);
            return LoadBalanceStrategy.ROUND_ROBIN;
        }
    }

    // -------------------------------------------------------------------------
    // Strategy implementations
    // -------------------------------------------------------------------------

    /**
     * Round-robin across all reachable hosts.
     * Uses an {@link AtomicLong} to stay safe under concurrent Netty I/O threads.
     */
    static HostGroup selectRoundRobin(List<HostGroup> hosts) {
        int idx = (int) (Math.abs(roundRobinCounter.getAndIncrement()) % hosts.size());
        return hosts.get(idx);
    }

    /**
     * Least-connections: always pick the host with the fewest active in-flight connections.
     * Connection tracking is maintained by {@link HostGroup#incrementConnections()} /
     * {@link HostGroup#decrementConnections()} in the proxy handlers.
     */
    static HostGroup selectLeastConnections(List<HostGroup> hosts) {
        int min = Integer.MAX_VALUE;
        for (HostGroup h : hosts) min = Math.min(min, h.getActiveConnections().get());
        // Rotate among the hosts tied for the fewest connections; otherwise the first host in the
        // list wins every tie and takes all traffic whenever connections are short-lived.
        List<HostGroup> least = new java.util.ArrayList<>(hosts.size());
        for (HostGroup h : hosts) if (h.getActiveConnections().get() == min) least.add(h);
        return least.get((int) (Math.abs(leastConnCounter.getAndIncrement()) % least.size()));
    }

    private static final AtomicLong leastConnCounter = new AtomicLong(0);

    /**
     * Weighted round-robin: distribute proportionally to each host's {@code weight} field.
     * Hosts with weight ≤ 0 are treated as weight 1.
     */
    static HostGroup selectWeighted(List<HostGroup> hosts) {
        int totalWeight = hosts.stream().mapToInt(h -> Math.max(h.getWeight(), 1)).sum();
        int idx = (int) (Math.abs(weightedCounter.getAndIncrement()) % totalWeight);
        int cumulative = 0;
        for (HostGroup host : hosts) {
            cumulative += Math.max(host.getWeight(), 1);
            if (idx < cumulative) {
                return host;
            }
        }
        return hosts.get(0);
    }

    /**
     * Active-Standby with multi-cluster disaster recovery.
     *
     * <h4>Selection algorithm</h4>
     * <ol>
     *   <li>Split reachable hosts into <em>Active</em> (type={@code "Active"} or priority ≤ 0)
     *       and <em>Standby</em> (all others).</li>
     *   <li>Among Active hosts, sort by {@code priority} ascending then by
     *       {@code weight} descending so the highest-capacity primary is preferred.</li>
     *   <li>If at least one Active host is reachable, select from Active hosts
     *       using round-robin to spread load within the primary cluster.</li>
     *   <li>If <em>all</em> Active hosts are unreachable (disaster scenario),
     *       fall over to Standby hosts — also sorted by priority and selected
     *       round-robin within the standby cluster.</li>
     *   <li>If no Standby either, return {@code null} (total blackout).</li>
     * </ol>
     *
     * <h4>Configuration</h4>
     * Set {@link HostGroup#setType(String)} to {@code "Active"} or {@code "Standby"}.
     * Set {@link HostGroup#setPriority(int)} to control ordering within each tier
     * (lower value = higher priority). Set {@link HostGroup#setWeight(int)} for
     * weighted selection within the same priority tier.
     */
    static HostGroup selectActiveStandby(List<HostGroup> hosts) {
        // Partition by type
        List<HostGroup> activeHosts = hosts.stream()
                .filter(h -> "Active".equalsIgnoreCase(h.getType()) || h.getType() == null)
                .sorted(Comparator.comparingInt(HostGroup::getPriority)
                        .thenComparingInt(h -> -Math.max(h.getWeight(), 1)))
                .collect(Collectors.toList());

        List<HostGroup> standbyHosts = hosts.stream()
                .filter(h -> "Standby".equalsIgnoreCase(h.getType()))
                .sorted(Comparator.comparingInt(HostGroup::getPriority)
                        .thenComparingInt(h -> -Math.max(h.getWeight(), 1)))
                .collect(Collectors.toList());

        if (!activeHosts.isEmpty()) {
            // Primary cluster available — round-robin within active tier
            int idx = (int) (Math.abs(activeCounter.getAndIncrement()) % activeHosts.size());
            return activeHosts.get(idx);
        }

        if (!standbyHosts.isEmpty()) {
            // DISASTER FAILOVER — all active hosts are down, switching to standby cluster
            LOG.warn("DISASTER FAILOVER: all Active hosts are unreachable, failing over to {} Standby host(s): {}",
                    standbyHosts.size(),
                    standbyHosts.stream()
                            .map(h -> h.getHost() + ":" + h.getPort())
                            .collect(Collectors.joining(", ")));
            int idx = (int) (Math.abs(standbyCounter.getAndIncrement()) % standbyHosts.size());
            return standbyHosts.get(idx);
        }

        // Total blackout — caller must handle null
        return null;
    }
}
