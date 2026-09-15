package com.scaleguard.server.http.router;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for IPBlockingManager.
 */
public class IPBlockingManagerTest {

    private IPBlockingManager ibm;

    @Before
    public void setUp() {
        ibm = new IPBlockingManager();
    }

    @Test
    public void testUnblockedIPIsNotBlocked() {
        assertFalse("Fresh IP should not be blocked", ibm.isBlocked("192.168.1.1"));
    }

    @Test
    public void testBlockedIPIsBlocked() {
        ibm.block("192.168.1.1");
        assertTrue("Blocked IP should be detected as blocked", ibm.isBlocked("192.168.1.1"));
    }

    @Test
    public void testBlockingOneIPDoesNotAffectOthers() {
        ibm.block("192.168.1.1");
        assertFalse("Other IPs should not be affected", ibm.isBlocked("192.168.1.2"));
    }

    @Test
    public void testMultipleBlocksOnSameIP() {
        ibm.block("10.0.0.1");
        ibm.block("10.0.0.1");
        ibm.block("10.0.0.1");
        assertTrue("IP should remain blocked after multiple blocks", ibm.isBlocked("10.0.0.1"));
    }

    @Test
    public void testBlockMultipleIPs() {
        ibm.block("10.0.0.1");
        ibm.block("10.0.0.2");
        ibm.block("10.0.0.3");
        assertTrue(ibm.isBlocked("10.0.0.1"));
        assertTrue(ibm.isBlocked("10.0.0.2"));
        assertTrue(ibm.isBlocked("10.0.0.3"));
        assertFalse(ibm.isBlocked("10.0.0.4"));
    }

    @Test
    public void testEmptyStringIPCanBeBlocked() {
        ibm.block("");
        assertTrue("Empty string IP should be blockable", ibm.isBlocked(""));
    }
}
