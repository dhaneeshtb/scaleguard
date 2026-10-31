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
    public void invalidValuesAreRejected() {
        assertNotNull(DnsSettings.validate("dnsPort", "70000"));
        assertNotNull(DnsSettings.validate("dnsPort", "abc"));
        assertNull(DnsSettings.validate("dnsPort", "5353"));
        assertNotNull(DnsSettings.validate("dnsProxy", "1.1.1.1,not a host"));
        assertNull(DnsSettings.validate("dnsProxy", "1.1.1.1, 8.8.8.8:53, dns.example.com, [2001:db8::1]:53"));
        assertNotNull(DnsSettings.validate("dnsRecursionAllow", "10.0.0.0/40"));
        assertNull(DnsSettings.validate("dnsRecursionAllow", "10.0.0.0/8,::1/128"));
        assertNotNull(DnsSettings.validate("dnsAdvertiseIps", "example.com"));
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

            assertNull(DnsSettings.save(Map.of("dnsRateLimit", "")));
            DnsService.apply();
            assertEquals(100, DnsConfig.get().rateLimit); // blank = default again
        } finally {
            DnsService.setEnabled(false);
        }
        assertFalse(DnsService.running());
    }
}
