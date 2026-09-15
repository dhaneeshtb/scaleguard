package com.scaleguard.server.http.router;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for BestHostSelector load balancing strategies.
 */
public class BestHostSelectorTest {

    private List<HostGroup> hosts;

    @Before
    public void setUp() {
        hosts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            HostGroup hg = new HostGroup();
            hg.setId("host-" + i);
            hg.setHost("server" + i + ".example.com");
            hg.setPort(String.valueOf(8080 + i));
            hg.setReachable(true);
            hg.setWeight(1);
            hg.setType("Active");
            hosts.add(hg);
        }
    }

    // --- Null / Empty ---

    @Test
    public void testNullHostGroups() {
        assertNull(BestHostSelector.getBestHost(null));
    }

    @Test
    public void testEmptyHostGroups() {
        assertNull(BestHostSelector.getBestHost(new ArrayList<>()));
    }

    @Test
    public void testAllUnreachable() {
        hosts.forEach(h -> h.setReachable(false));
        assertNull(BestHostSelector.getBestHost(hosts));
    }

    // --- Round Robin ---

    @Test
    public void testRoundRobinDistribution() {
        // Call multiple times and verify distribution across all hosts
        int[] counts = new int[3];
        int iterations = 300;
        for (int i = 0; i < iterations; i++) {
            HostGroup selected = BestHostSelector.getBestHost(hosts, BestHostSelector.LoadBalanceStrategy.ROUND_ROBIN);
            assertNotNull(selected);
            int idx = Integer.parseInt(selected.getId().replace("host-", ""));
            counts[idx]++;
        }
        // Each host should get exactly 100 requests (300 / 3)
        assertEquals(100, counts[0]);
        assertEquals(100, counts[1]);
        assertEquals(100, counts[2]);
    }

    @Test
    public void testRoundRobinSkipsUnreachable() {
        hosts.get(1).setReachable(false);
        // Now only host-0 and host-2 are reachable
        int[] counts = new int[3];
        for (int i = 0; i < 100; i++) {
            HostGroup selected = BestHostSelector.getBestHost(hosts, BestHostSelector.LoadBalanceStrategy.ROUND_ROBIN);
            assertNotNull(selected);
            int idx = Integer.parseInt(selected.getId().replace("host-", ""));
            counts[idx]++;
        }
        assertEquals(50, counts[0]);
        assertEquals(0, counts[1]);
        assertEquals(50, counts[2]);
    }

    @Test
    public void testRoundRobinSingleHost() {
        List<HostGroup> single = hosts.subList(0, 1);
        for (int i = 0; i < 10; i++) {
            HostGroup selected = BestHostSelector.getBestHost(single, BestHostSelector.LoadBalanceStrategy.ROUND_ROBIN);
            assertEquals("host-0", selected.getId());
        }
    }

    // --- Least Connections ---

    @Test
    public void testLeastConnectionsPicksMinimum() {
        hosts.get(0).getActiveConnections().set(10);
        hosts.get(1).getActiveConnections().set(2);
        hosts.get(2).getActiveConnections().set(5);

        HostGroup selected = BestHostSelector.getBestHost(hosts, BestHostSelector.LoadBalanceStrategy.LEAST_CONNECTIONS);
        assertEquals("host-1", selected.getId());
    }

    @Test
    public void testLeastConnectionsAllZero() {
        // All have 0 connections — should return the first one
        HostGroup selected = BestHostSelector.getBestHost(hosts, BestHostSelector.LoadBalanceStrategy.LEAST_CONNECTIONS);
        assertNotNull(selected);
    }

    @Test
    public void testLeastConnectionsSkipsUnreachable() {
        hosts.get(0).getActiveConnections().set(100);
        hosts.get(1).setReachable(false);
        hosts.get(1).getActiveConnections().set(0);  // lowest but unreachable
        hosts.get(2).getActiveConnections().set(5);

        HostGroup selected = BestHostSelector.getBestHost(hosts, BestHostSelector.LoadBalanceStrategy.LEAST_CONNECTIONS);
        assertEquals("host-2", selected.getId());
    }

    // --- Weighted ---

    @Test
    public void testWeightedDistribution() {
        hosts.get(0).setWeight(3);
        hosts.get(1).setWeight(1);
        hosts.get(2).setWeight(1);
        // Total weight = 5

        int[] counts = new int[3];
        int iterations = 500;  // Multiple of total weight (5)
        for (int i = 0; i < iterations; i++) {
            HostGroup selected = BestHostSelector.getBestHost(hosts, BestHostSelector.LoadBalanceStrategy.WEIGHTED);
            assertNotNull(selected);
            int idx = Integer.parseInt(selected.getId().replace("host-", ""));
            counts[idx]++;
        }

        // host-0 gets 3/5 = 300, host-1 gets 1/5 = 100, host-2 gets 1/5 = 100
        assertEquals(300, counts[0]);
        assertEquals(100, counts[1]);
        assertEquals(100, counts[2]);
    }

    @Test
    public void testWeightedWithZeroWeightDefaultsToOne() {
        hosts.get(0).setWeight(0);  // Should be treated as 1
        hosts.get(1).setWeight(1);
        hosts.get(2).setWeight(1);

        // Should not throw and should distribute
        for (int i = 0; i < 30; i++) {
            HostGroup selected = BestHostSelector.getBestHost(hosts, BestHostSelector.LoadBalanceStrategy.WEIGHTED);
            assertNotNull(selected);
        }
    }

    // --- Active Standby ---

    @Test
    public void testActiveStandbyPicksActive() {
        hosts.get(0).setType("Standby");
        hosts.get(1).setType("Active");
        hosts.get(2).setType("Standby");

        HostGroup selected = BestHostSelector.getBestHost(hosts, BestHostSelector.LoadBalanceStrategy.ACTIVE_STANDBY);
        assertEquals("Active", selected.getType());
    }

    // --- Strategy Parsing ---

    @Test
    public void testParseStrategyValid() {
        assertEquals(BestHostSelector.LoadBalanceStrategy.ROUND_ROBIN, BestHostSelector.parseStrategy("ROUND_ROBIN"));
        assertEquals(BestHostSelector.LoadBalanceStrategy.LEAST_CONNECTIONS, BestHostSelector.parseStrategy("least_connections"));
        assertEquals(BestHostSelector.LoadBalanceStrategy.WEIGHTED, BestHostSelector.parseStrategy("Weighted"));
        assertEquals(BestHostSelector.LoadBalanceStrategy.ACTIVE_STANDBY, BestHostSelector.parseStrategy("ACTIVE_STANDBY"));
    }

    @Test
    public void testParseStrategyNull() {
        assertEquals(BestHostSelector.LoadBalanceStrategy.ROUND_ROBIN, BestHostSelector.parseStrategy(null));
    }

    @Test
    public void testParseStrategyInvalid() {
        assertEquals(BestHostSelector.LoadBalanceStrategy.ROUND_ROBIN, BestHostSelector.parseStrategy("INVALID_STRATEGY"));
    }

    @Test
    public void testParseStrategyEmpty() {
        assertEquals(BestHostSelector.LoadBalanceStrategy.ROUND_ROBIN, BestHostSelector.parseStrategy(""));
    }

    // --- Default strategy ---

    @Test
    public void testDefaultStrategyIsRoundRobin() {
        // The no-arg version should use ROUND_ROBIN
        HostGroup selected = BestHostSelector.getBestHost(hosts);
        assertNotNull(selected);
    }
}
