package com.scaleguard.server.dns;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class TunnelDomainTest {
    @Test
    public void requestWinsThenSettingThenBaseRecordThenServerHost() {
        assertEquals("dhaneesh.in", TunnelRoute.chooseBase("DhaneeSH.in.", "x.example.com", "ns1.dhaneesh.in."));
        assertEquals("example.com", TunnelRoute.chooseBase(null, "example.com", "ns1.dhaneesh.in."));
        assertEquals("example.com", TunnelRoute.chooseBase("", " example.com ", "ns1.dhaneesh.in."));
        assertEquals("ns1.dhaneesh.in", TunnelRoute.chooseBase(null, null, "ns1.dhaneesh.in."));
        assertNull(TunnelRoute.chooseBase(null, null, null));
    }

    @Test
    public void anythingThatIsNotAHostnameIsRefused() {
        for (String bad : new String[]{"a b.com", "evil.com/x", "http://x.com", "x", "-a.com", "a..com", "a.com:80"}) {
            try {
                TunnelRoute.chooseBase(bad, null, null);
                fail(bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }
}
