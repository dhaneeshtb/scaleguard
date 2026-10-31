package com.scaleguard.server.http.reverse;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CachePatternTest {
    @Test
    public void starAndSlashStarMatchEveryPath() {
        for (String p : new String[]{"*", "/*", "/"}) {
            assertTrue(p, InboundMessageHandler.matchesPattern("/", p));
            assertTrue(p, InboundMessageHandler.matchesPattern("/a/b/c", p));
        }
    }

    @Test
    public void prefixesStillScopeTheRule() {
        assertTrue(InboundMessageHandler.matchesPattern("/api/items/1", "/api/*"));
        assertTrue(InboundMessageHandler.matchesPattern("/api/items/1", "/api"));
        assertFalse(InboundMessageHandler.matchesPattern("/other", "/api/*"));
        assertFalse(InboundMessageHandler.matchesPattern("/other", null));
    }
}
