package com.scaleguard.server.dns;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DnsZoneTest {

    @Test
    public void declaredDomainAnswersWithTheConnectedServersUnlessOverridden() {
        System.setProperty("dnsAdvertiseIps", "192.0.2.10,192.0.2.11");
        DnsConfig.reset();
        try {
            String zone = "app" + System.nanoTime() + ".example.test.";
            assertNull(DNSAddressBook.resolve(zone, DnsWire.TYPE_SOA)); // unknown: caller forwards or refuses

            DNSAddressBook.add(zone, "zone", "zone", 600);

            assertEquals(1, DNSAddressBook.resolve(zone, DnsWire.TYPE_SOA).answers().size());
            // the domain and every name below it answer with all connected servers
            assertEquals(2, DNSAddressBook.resolve(zone, DnsWire.TYPE_A).answers().size());
            assertEquals(2, DNSAddressBook.resolve("www." + zone, DnsWire.TYPE_A).answers().size());
            // the marker never leaks out as another record type
            DNSAddressBook.AuthAnswer txt = DNSAddressBook.resolve(zone, DnsWire.TYPE_TXT);
            assertEquals(DnsWire.RCODE_NOERROR, txt.rcode());
            assertTrue(txt.answers().isEmpty());

            // an address record of its own replaces the default
            DNSAddressBook.add(zone, "198.51.100.7", "a", 60);
            assertEquals(1, DNSAddressBook.resolve(zone, DnsWire.TYPE_A).answers().size());
        } finally {
            System.clearProperty("dnsAdvertiseIps");
            DnsConfig.reset();
        }
    }

    @Test
    public void routeStoresADomainWithoutAValue() throws Exception {
        System.setProperty("dnsPort", "15357");
        DnsService.setEnabled(true);
        try {
            DnsSettings.save(java.util.Map.of("dnsNameServers", "ns1.zoneroute.test"));
            DNSRoute route = new DNSRoute();
            assertEquals(200, route.handle("POST", "/dns", "{\"name\":\"app.zoneroute.test\",\"type\":\"zone\"}").getStatus());
            assertTrue(DNSAddressBook.get().get("app.zoneroute.test.").stream().anyMatch(r -> "zone".equals(r.getType()) && "zone".equals(r.getIp())));
        } finally {
            DnsSettings.save(java.util.Map.of("dnsNameServers", ""));
            DnsService.setEnabled(false);
        }
    }
}
