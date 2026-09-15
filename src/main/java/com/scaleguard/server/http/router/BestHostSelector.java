package com.scaleguard.server.http.router;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class BestHostSelector {

    public enum LoadBalanceStrategy {
        ROUND_ROBIN,
        LEAST_CONNECTIONS,
        WEIGHTED,
        ACTIVE_STANDBY
    }

    private static final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private static final AtomicInteger weightedCounter = new AtomicInteger(0);

    /**
     * Select the best host using the default ROUND_ROBIN strategy.
     */
    public static HostGroup getBestHost(List<HostGroup> hostGroups) {
        return getBestHost(hostGroups, LoadBalanceStrategy.ROUND_ROBIN);
    }

    /**
     * Select the best host using the specified load balancing strategy.
     *
     * @param hostGroups list of candidate hosts
     * @param strategy   the load balancing strategy to apply
     * @return the selected HostGroup, or null if none are reachable
     */
    public static HostGroup getBestHost(List<HostGroup> hostGroups, LoadBalanceStrategy strategy) {
        if (hostGroups == null || hostGroups.isEmpty()) {
            return null;
        }

        List<HostGroup> reachableHosts = hostGroups.stream()
                .filter(HostGroup::isReachable)
                .collect(Collectors.toList());

        if (reachableHosts.isEmpty()) {
            return null;
        }

        switch (strategy) {
            case ROUND_ROBIN:
                return selectRoundRobin(reachableHosts);
            case LEAST_CONNECTIONS:
                return selectLeastConnections(reachableHosts);
            case WEIGHTED:
                return selectWeighted(reachableHosts);
            case ACTIVE_STANDBY:
                return selectActiveStandby(reachableHosts);
            default:
                return selectRoundRobin(reachableHosts);
        }
    }

    /**
     * Parses a strategy string, returning ROUND_ROBIN if null or unrecognized.
     */
    public static LoadBalanceStrategy parseStrategy(String strategy) {
        if (strategy == null || strategy.isEmpty()) {
            return LoadBalanceStrategy.ROUND_ROBIN;
        }
        try {
            return LoadBalanceStrategy.valueOf(strategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            return LoadBalanceStrategy.ROUND_ROBIN;
        }
    }

    /**
     * Round-robin: cycle through reachable hosts sequentially.
     */
    static HostGroup selectRoundRobin(List<HostGroup> hosts) {
        int idx = Math.abs(roundRobinCounter.getAndIncrement() % hosts.size());
        return hosts.get(idx);
    }

    /**
     * Least-connections: pick the host with the fewest active connections.
     */
    static HostGroup selectLeastConnections(List<HostGroup> hosts) {
        return hosts.stream()
                .min(Comparator.comparingInt(h -> h.getActiveConnections().get()))
                .orElse(hosts.get(0));
    }

    /**
     * Weighted round-robin: distribute based on host weight.
     * Builds a virtual list proportional to weights and cycles through it.
     */
    static HostGroup selectWeighted(List<HostGroup> hosts) {
        int totalWeight = hosts.stream().mapToInt(h -> Math.max(h.getWeight(), 1)).sum();
        int idx = Math.abs(weightedCounter.getAndIncrement() % totalWeight);
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
     * Active-standby: sort by type (Active first) and pick the first reachable host.
     * This preserves the original behavior.
     */
    static HostGroup selectActiveStandby(List<HostGroup> hosts) {
        hosts.sort(Comparator.comparing(h -> h.getType() != null ? h.getType() : ""));
        return hosts.get(0);
    }
}
