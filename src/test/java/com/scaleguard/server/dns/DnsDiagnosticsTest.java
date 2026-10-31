package com.scaleguard.server.dns;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DnsDiagnosticsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> check(Map<String, Object> result, String name) {
        return ((List<Map<String, Object>>) result.get("checks")).stream()
                .filter(c -> name.equals(c.get("name"))).findFirst().orElseThrow();
    }

    @Test
    public void localServerAnswersOnlyForConfiguredZone() throws Exception {
        System.setProperty("dnsPort", "15354");
        DnsService.setEnabled(true);
        try {
            String zone = "diag" + System.nanoTime() + ".test";
            String local = "Local server answers for " + zone;

            assertFalse((Boolean) check(DnsDiagnostics.run(zone), local).get("ok"));

            DNSAddressBook.add("www." + zone + ".", "192.0.2.7", "a", 60);
            Map<String, Object> r = DnsDiagnostics.run("www." + zone);
            assertTrue((Boolean) check(r, "Local server answers for www." + zone).get("ok"));
            assertFalse((Boolean) r.get("ok")); // no public delegation in a test
            assertEquals(5, ((List<?>) r.get("instructions")).size());
        } finally {
            DnsService.setEnabled(false);
        }
    }
}
