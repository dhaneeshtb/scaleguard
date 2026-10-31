package com.scaleguard.server.dns;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DnsZoneTest {

    @Test
    public void declaredDomainAnswersForItselfAndRefusesNothingBelowIt() {
        String zone = "app" + System.nanoTime() + ".example.test.";
        assertNull(DNSAddressBook.resolve(zone, DnsWire.TYPE_SOA)); // unknown: caller forwards or refuses

        DNSAddressBook.add(zone, "zone", "zone", 600);

        DNSAddressBook.AuthAnswer soa = DNSAddressBook.resolve(zone, DnsWire.TYPE_SOA);
        assertNotNull(soa);
        assertEquals(1, soa.answers().size());

        // no records at all yet: NODATA at the apex, and the marker never leaks out as a TXT record
        DNSAddressBook.AuthAnswer txt = DNSAddressBook.resolve(zone, DnsWire.TYPE_TXT);
        assertEquals(DnsWire.RCODE_NOERROR, txt.rcode());
        assertTrue(txt.answers().isEmpty());

        // a name inside the domain that has no record: authoritative NXDOMAIN with the SOA for caching
        DNSAddressBook.AuthAnswer below = DNSAddressBook.resolve("www." + zone, DnsWire.TYPE_A);
        assertEquals(DnsWire.RCODE_NXDOMAIN, below.rcode());
        assertEquals(1, below.authority().size());

        // real records inside it still answer
        DNSAddressBook.add("www." + zone, "192.0.2.9", "a", 60);
        assertEquals(1, DNSAddressBook.resolve("www." + zone, DnsWire.TYPE_A).answers().size());
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
