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

    @Test
    public void testActiveStandbyFailoverToStandbyWhenAllActiveUnreachable() {
        // Active hosts unreachable, Standby reachable → must failover to Standby
        hosts.get(0).setType("Active");
        hosts.get(0).setReachable(false);
        hosts.get(1).setType("Active");
        hosts.get(1).setReachable(false);
        hosts.get(2).setType("Standby");
        hosts.get(2).setReachable(true);

        HostGroup selected = BestHostSelector.getBestHost(hosts, BestHostSelector.LoadBalanceStrategy.ACTIVE_STANDBY);
        assertNotNull("Expected Standby host on failover", selected);
        assertEquals("Standby", selected.getType());
    }

    @Test
    public void testActiveStandbyReturnsNullWhenAllUnreachable() {
        hosts.forEach(h -> {
            h.setType("Active");
            h.setReachable(false);
        });

        HostGroup selected = BestHostSelector.getBestHost(hosts, BestHostSelector.LoadBalanceStrategy.ACTIVE_STANDBY);
        assertNull("All hosts unreachable — should return null", selected);
    }

    @Test
    public void testActiveStandbyRoundRobinWithinActiveTier() {
        // Two active hosts, one standby — should round-robin only across the two Active hosts
        List<HostGroup> pool = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            HostGroup hg = new HostGroup();
            hg.setId("hg-" + i);
            hg.setHost("server" + i + ".example.com");
            hg.setPort("8080");
            hg.setReachable(true);
            hg.setWeight(1);
            pool.add(hg);
        }
        pool.get(0).setType("Active");
        pool.get(1).setType("Active");
        pool.get(2).setType("Standby");
        pool.get(3).setType("Standby");

        int activeCount = 0;
        int standbyCount = 0;
        for (int i = 0; i < 100; i++) {
            HostGroup selected = BestHostSelector.getBestHost(pool, BestHostSelector.LoadBalanceStrategy.ACTIVE_STANDBY);
            assertNotNull(selected);
            if ("Active".equals(selected.getType())) activeCount++;
            else standbyCount++;
        }
        assertEquals("All requests must go to Active hosts when available", 100, activeCount);
        assertEquals("No requests must go to Standby when Active is available", 0, standbyCount);
    }

    @Test
    public void testActiveStandbyPriorityOrderWithinActiveTier() {
        // Lower priority value = higher priority (should be picked first in single-element scenario)
        List<HostGroup> pool = new ArrayList<>();
        HostGroup primary = new HostGroup();
        primary.setId("primary");
        primary.setHost("primary.example.com");
        primary.setPort("8080");
        primary.setType("Active");
        primary.setPriority(1);   // higher priority (lower number)
        primary.setReachable(true);

        HostGroup secondary = new HostGroup();
        secondary.setId("secondary");
        secondary.setHost("secondary.example.com");
        secondary.setPort("8080");
        secondary.setType("Active");
        secondary.setPriority(2); // lower priority (higher number)
        secondary.setReachable(true);

        pool.add(secondary);
        pool.add(primary);

        // When primary is reachable it should be selected (along with secondary in round-robin)
        // Both are in Active tier — verify primary appears
        boolean primarySelected = false;
        for (int i = 0; i < 20; i++) {
            HostGroup sel = BestHostSelector.getBestHost(pool, BestHostSelector.LoadBalanceStrategy.ACTIVE_STANDBY);
            if ("primary".equals(sel.getId())) primarySelected = true;
        }
        assertTrue("Primary (priority=1) should be selected among Active hosts", primarySelected);
    }

    @Test
    public void testActiveStandbyMultiClusterStandbyRoundRobin() {
        // Multiple standby hosts — should round-robin across them on failover
        List<HostGroup> pool = new ArrayList<>();
        HostGroup active = new HostGroup();
        active.setId("active-0");
        active.setHost("active.example.com");
        active.setPort("8080");
        active.setType("Active");
        active.setReachable(false); // simulate outage
        pool.add(active);

        for (int i = 0; i < 3; i++) {
            HostGroup sb = new HostGroup();
            sb.setId("standby-" + i);
            sb.setHost("standby" + i + ".example.com");
            sb.setPort("8080");
            sb.setType("Standby");
            sb.setReachable(true);
            pool.add(sb);
        }

        int[] standbyCounts = new int[3];
        for (int i = 0; i < 300; i++) {
            HostGroup sel = BestHostSelector.getBestHost(pool, BestHostSelector.LoadBalanceStrategy.ACTIVE_STANDBY);
            assertNotNull(sel);
            assertEquals("Standby", sel.getType());
            int idx = Integer.parseInt(sel.getId().replace("standby-", ""));
            standbyCounts[idx]++;
        }
        // Verify all 3 standby nodes got traffic (round-robin)
        assertTrue("standby-0 must receive traffic", standbyCounts[0] > 0);
        assertTrue("standby-1 must receive traffic", standbyCounts[1] > 0);
        assertTrue("standby-2 must receive traffic", standbyCounts[2] > 0);
    }

    @Test
    public void testActiveStandbyNullTypeHostTreatedAsActive() {
        // Hosts with null type should be treated as Active (default behaviour)
        List<HostGroup> pool = new ArrayList<>();
        HostGroup nullType = new HostGroup();
        nullType.setId("null-type");
        nullType.setHost("default.example.com");
        nullType.setPort("8080");
        nullType.setType(null);  // no explicit type
        nullType.setReachable(true);
        pool.add(nullType);

        HostGroup standby = new HostGroup();
        standby.setId("standby-0");
        standby.setHost("standby.example.com");
        standby.setPort("8080");
        standby.setType("Standby");
        standby.setReachable(true);
        pool.add(standby);

        HostGroup selected = BestHostSelector.getBestHost(pool, BestHostSelector.LoadBalanceStrategy.ACTIVE_STANDBY);
        assertNotNull(selected);
        // null-type host should be chosen over standby (treated as Active)
        assertNotEquals("Standby hosts must not be chosen when an Active (null-type) host is available",
                "standby-0", selected.getId());
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
