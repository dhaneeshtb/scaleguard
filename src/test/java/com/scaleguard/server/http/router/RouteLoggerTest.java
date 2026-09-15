package com.scaleguard.server.http.router;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for RouteLogger — stats accumulation and JSON output.
 */
public class RouteLoggerTest {

    private RouteTarget routeTarget;

    @Before
    public void setUp() {
        SourceSystem ss = new SourceSystem();
        ss.setId("src-test");
        TargetSystem ts = new TargetSystem();
        ts.setId("tgt-test");
        routeTarget = new RouteTarget(ss, ts);
        routeTarget.setTargetHost("localhost:8080");
    }

    @Test
    public void testLogDoesNotThrow() {
        RouteLogger.log(routeTarget);
        RouteLogger.log(routeTarget, true);
        RouteLogger.log(routeTarget, false);
    }

    @Test
    public void testStatsJsonReturnsArray() {
        RouteLogger.log(routeTarget);
        JsonNode stats = RouteLogger.toStatsJson();
        assertNotNull("Stats JSON should not be null", stats);
        assertTrue("Stats JSON should be an array", stats.isArray());
    }

    @Test
    public void testStatsJsonContainsExpectedFields() {
        // Log a few requests
        for (int i = 0; i < 5; i++) {
            RouteTarget rt = createRouteTarget("src-fields", "tgt-fields");
            rt.setTargetHost("host-fields:9090");
            RouteLogger.log(rt);
        }

        JsonNode stats = RouteLogger.toStatsJson();
        // Find our entry
        boolean found = false;
        for (int i = 0; i < stats.size(); i++) {
            JsonNode stat = stats.get(i);
            if (stat.has("key") && stat.get("key").asText().contains("src-fields")) {
                found = true;
                assertTrue("Should have 'total' field", stat.has("total"));
                assertTrue("Should have 'max' field", stat.has("max"));
                assertTrue("Should have 'min' field", stat.has("min"));
                assertTrue("Should have 'avg' field", stat.has("avg"));
                assertTrue("Total should be >= 5", stat.get("total").asLong() >= 5);
                break;
            }
        }
        assertTrue("Should find stats for our route", found);
    }

    @Test
    public void testRouteStatsAccumulation() {
        RouteLogger.RouteStats rs = new RouteLogger.RouteStats("test-key");

        rs.addRT(100);
        assertEquals(1, rs.getCount().get());
        assertEquals(100, rs.getMinTime());
        assertEquals(100, rs.getMaxTime());
        assertEquals(100.0, rs.getAverageRT(), 0.01);

        rs.addRT(200);
        assertEquals(2, rs.getCount().get());
        assertEquals(100, rs.getMinTime());
        assertEquals(200, rs.getMaxTime());
        assertEquals(150.0, rs.getAverageRT(), 0.01);

        rs.addRT(50);
        assertEquals(3, rs.getCount().get());
        assertEquals(50, rs.getMinTime());
        assertEquals(200, rs.getMaxTime());
    }

    @Test
    public void testCachedRouteKeyDiffers() {
        RouteTarget rt1 = createRouteTarget("src-cache", "tgt-cache");
        rt1.setTargetHost("host-cache:8080");
        RouteLogger.log(rt1, false);
        RouteLogger.log(rt1, true);

        JsonNode stats = RouteLogger.toStatsJson();
        int cachedEntries = 0;
        int nonCachedEntries = 0;
        for (int i = 0; i < stats.size(); i++) {
            String key = stats.get(i).get("key").asText();
            if (key.contains("src-cache") && key.contains("cached")) {
                cachedEntries++;
            } else if (key.contains("src-cache")) {
                nonCachedEntries++;
            }
        }
        assertTrue("Should have cached entry", cachedEntries > 0);
        assertTrue("Should have non-cached entry", nonCachedEntries > 0);
    }

    private RouteTarget createRouteTarget(String srcId, String tgtId) {
        SourceSystem ss = new SourceSystem();
        ss.setId(srcId);
        TargetSystem ts = new TargetSystem();
        ts.setId(tgtId);
        return new RouteTarget(ss, ts);
    }
}
