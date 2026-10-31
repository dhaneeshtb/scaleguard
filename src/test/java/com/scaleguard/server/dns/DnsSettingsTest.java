package com.scaleguard.server.dns;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DnsSettingsTest {

    @Test
    public void viewReportsTheIpsABlankPublicIpsSettingResolvesTo() {
        assertNotNull(DnsSettings.view().get("detectedIps"));
    }

    @Test
    public void invalidValuesAreRejected() throws Exception {
        assertNotNull(DnsSettings.validate("dnsPort", "70000"));
        assertNotNull(DnsSettings.validate("dnsPort", "abc"));
        assertNull(DnsSettings.validate("dnsPort", "5353"));
        assertNotNull(DnsSettings.validate("dnsProxy", "1.1.1.1,not a host"));
        assertNull(DnsSettings.validate("dnsProxy", "1.1.1.1, 8.8.8.8:53, dns.example.com, [2001:db8::1]:53"));
        assertNotNull(DnsSettings.validate("dnsRecursionAllow", "10.0.0.0/40"));
        assertNull(DnsSettings.validate("dnsRecursionAllow", "10.0.0.0/8,::1/128"));
        assertNotNull(DnsSettings.save(Map.of("dnsAdvertiseIps", "203.0.113.10"))); // detected, not editable
        assertNotNull(DnsSettings.validate("dnsRecursion", "maybe"));
        assertNull(DnsSettings.validate("dnsNameServers", "ns1.example.com,ns2.example.com."));
        assertNull(DnsSettings.validate("dnsBlockedDomains", "")); // blank = default
    }

    @Test
    public void savedSettingsReachConfigAndRestartTheServer() throws Exception {
        System.setProperty("dnsPort", "15355");
        DnsService.setEnabled(true);
        try {
            assertNull(DnsSettings.save(Map.of("dnsRateLimit", "7", "dnsNameServers", "ns1.saved.test", "dnsProxy", "9.9.9.9")));
            DnsService.apply();
            assertTrue(DnsService.running());
            assertEquals(7, DnsConfig.get().rateLimit);
            assertEquals("ns1.saved.test.", DnsConfig.get().nameServers.get(0));
            assertEquals(1, DnsConfig.get().upstreams.size());

            assertNotNull(DnsSettings.save(Map.of("dnsPort", "0")));
            assertNotNull(DnsSettings.save(Map.of("bogus", "1")));
            assertEquals(7, DnsConfig.get().rateLimit); // rejected saves change nothing

            assertTrue(DNSAddressBook.get().containsKey("ns1.saved.test.")); // base record so the name resolves to us
            assertNull(DnsSettings.save(Map.of("dnsNameServers", "")));
            assertFalse(DNSAddressBook.get().containsKey("ns1.saved.test."));

            assertNull(DnsSettings.save(Map.of("dnsRateLimit", "")));
            DnsService.apply();
            assertEquals(100, DnsConfig.get().rateLimit); // blank = default again
        } finally {
            DnsService.setEnabled(false);
        }
        assertFalse(DnsService.running());
    }

    @Test
    public void recordsNeedARunningServerWithANameServer() throws Exception {
        System.setProperty("dnsPort", "15356");
        DNSRoute route = new DNSRoute();
        String rec = "{\"name\":\"www.gate.test\",\"type\":\"a\",\"ip\":\"192.0.2.1\"}";
        try {
            DnsService.setEnabled(false);
            assertEquals(409, route.handle("POST", "/dns", rec).getStatus());

            DnsService.setEnabled(true);
            assertNull(DnsSettings.save(Map.of("dnsNameServers", "")));
            DNSAddressBook.get().values().forEach(l -> l.stream().filter(DNSAddressBook.WrappedDNSRecord::isBase)
                    .forEach(b -> DNSAddressBook.remove(b.getName(), "base")));
            assertEquals(409, route.handle("POST", "/dns", rec).getStatus()); // started but no name server

            assertNull(DnsSettings.save(Map.of("dnsNameServers", "ns1.gate.test")));
            assertEquals(200, route.handle("POST", "/dns", rec).getStatus());
            assertEquals(200, route.handle("POST", "/dns", "{\"name\":\"ns9.gate.test\",\"type\":\"base\"}").getStatus());
        } finally {
            DnsSettings.save(Map.of("dnsNameServers", ""));
            DnsService.setEnabled(false);
        }
    }
}
