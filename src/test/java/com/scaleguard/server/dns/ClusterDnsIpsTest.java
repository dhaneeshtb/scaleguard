package com.scaleguard.server.dns;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ClusterDnsIpsTest {

    private static Map<String, Object> node(boolean self, boolean alive, Boolean dnsRunning, List<String> ips) {
        Map<String, Object> info = dnsRunning == null ? Map.of() : Map.of("dnsRunning", dnsRunning, "dnsIps", ips);
        return Map.of("self", self, "alive", alive, "info", info);
    }

    @Test
    public void onlyLivePeersWithDnsRunningContribute() {
        Map<String, Object> snap = Map.of("nodes", List.of(
                node(true, true, true, List.of("198.51.100.1")),       // this node: added separately
                node(false, true, true, List.of("198.51.100.2")),      // counts
                node(false, true, true, List.of("198.51.100.2", "2001:db8::3")), // dedup + IPv6
                node(false, true, false, List.of("198.51.100.4")),     // DNS stopped there
                node(false, false, true, List.of("198.51.100.5")),     // node down
                node(false, true, null, List.of())));                  // older node without the field
        assertEquals(List.of("198.51.100.2", "2001:db8::3"), ClusterDnsIps.fromSnapshot(snap));
        assertEquals(List.of(), ClusterDnsIps.fromSnapshot(Map.of()));
    }
}
