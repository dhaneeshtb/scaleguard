package com.scaleguard.server.dns;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DnsServiceTest {

    @Test
    public void startStopFollowsReplicatedSetting() throws Exception {
        System.setProperty("dnsPort", "15353");
        DnsService.setEnabled(true);
        assertTrue(DnsService.desired());
        assertTrue(DnsService.running());

        DnsService.setEnabled(false);
        assertFalse(DnsService.desired());
        assertFalse(DnsService.running());

        DnsService.setEnabled(true); // restart on the same port proves stop() released the sockets
        assertTrue(DnsService.running());
        DnsService.setEnabled(false);
    }
}
