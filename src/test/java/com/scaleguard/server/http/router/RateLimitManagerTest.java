package com.scaleguard.server.http.router;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for RateLimitManager — rate limiting and IP blocking integration.
 * 
 * The rate limit key is based on rt.getClientIp() + sourceId + targetId,
 * so we need to set clientIp on the RouteTarget for per-client isolation.
 */
public class RateLimitManagerTest {

    private RateLimitManager rateLimitManager;

    @Before
    public void setUp() {
        rateLimitManager = new RateLimitManager(10); // 10 requests per minute
    }

    private RouteTarget createRouteTarget(String clientIp) {
        SourceSystem ss = new SourceSystem();
        ss.setId("source-1");
        TargetSystem ts = new TargetSystem();
        ts.setId("target-1");
        RouteTarget rt = new RouteTarget(ss, ts);
        rt.setClientIp(clientIp);
        return rt;
    }

    @Test
    public void testRequestsWithinRateLimitPass() {
        RouteTarget rt = createRouteTarget("192.168.1.1");
        for (int i = 0; i < 10; i++) {
            assertTrue("Request " + i + " should pass", rateLimitManager.checkRate(rt, "192.168.1.1"));
        }
    }

    @Test
    public void testRequestsExceedingRateLimitBlocked() {
        RouteTarget rt = createRouteTarget("192.168.1.1");
        // First 10 should pass
        for (int i = 0; i < 10; i++) {
            rateLimitManager.checkRate(rt, "192.168.1.1");
        }
        // 11th should be blocked
        assertFalse("Request exceeding limit should be blocked",
                rateLimitManager.checkRate(rt, "192.168.1.1"));
    }

    @Test
    public void testDifferentClientsHaveSeparateLimits() {
        RouteTarget rt1 = createRouteTarget("192.168.1.1");
        RouteTarget rt2 = createRouteTarget("192.168.1.2");

        // Fill up client 1's rate
        for (int i = 0; i < 10; i++) {
            rateLimitManager.checkRate(rt1, "192.168.1.1");
        }
        // Client 2 should still be allowed (different clientIp in RouteTarget)
        assertTrue("Different client should have separate limit",
                rateLimitManager.checkRate(rt2, "192.168.1.2"));
    }

    @Test
    public void testRateLimitWithIPBlockingIntegration() {
        IPBlockingManager ibm = new IPBlockingManager();
        RateLimitManager rlmWithBlocking = new RateLimitManager(5, ibm);

        RouteTarget rt = createRouteTarget("10.0.0.1");

        // Exceed rate limit — should trigger IP blocking
        for (int i = 0; i < 6; i++) {
            rlmWithBlocking.checkRate(rt, "10.0.0.1");
        }

        // IP should now be blocked
        assertTrue("IP should be blocked after rate exceeded", ibm.isBlocked("10.0.0.1"));
    }

    @Test
    public void testIsBlockedDelegation() {
        IPBlockingManager ibm = new IPBlockingManager();
        RateLimitManager rlmWithBlocking = new RateLimitManager(10, ibm);

        assertFalse("IP should not be blocked initially", rlmWithBlocking.isBlocked("10.0.0.1"));
        ibm.block("10.0.0.1");
        assertTrue("IP should be blocked after explicit block", rlmWithBlocking.isBlocked("10.0.0.1"));
    }

    @Test
    public void testLogDoesNotThrow() {
        RouteTarget rt = createRouteTarget("10.0.0.1");
        // logging should not throw for null or valid route targets
        rateLimitManager.log(rt);
        rateLimitManager.log(null);
    }

    @Test
    public void testNullRouteTargetDoesNotThrow() {
        assertTrue(rateLimitManager.checkRate(null, "10.0.0.3"));
    }

    @Test
    public void testNoIPBlockingManagerIsNullSafe() {
        RateLimitManager rlmNoIbm = new RateLimitManager(5);
        assertFalse("isBlocked should return false without IBM", rlmNoIbm.isBlocked("10.0.0.1"));

        RouteTarget rt = createRouteTarget("10.0.0.1");
        // Exceeding rate should not throw when IBM is null
        for (int i = 0; i < 10; i++) {
            rlmNoIbm.checkRate(rt, "10.0.0.1");
        }
    }

    @Test
    public void testDefaultConstructorHighLimit() {
        // Default constructor uses 1000 rate limit
        RateLimitManager defaultRlm = new RateLimitManager();
        RouteTarget rt = createRouteTarget("10.0.0.5");
        // Should handle many requests
        for (int i = 0; i < 100; i++) {
            assertTrue(defaultRlm.checkRate(rt, "10.0.0.5"));
        }
    }
}
