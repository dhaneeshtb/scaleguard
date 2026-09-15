package com.scaleguard.server.http.metrics;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for MetricsHandler Prometheus text output.
 */
public class MetricsHandlerTest {

    @Test
    public void testGenerateMetricsNotNull() {
        String metrics = MetricsHandler.generateMetrics();
        assertNotNull("Metrics output should not be null", metrics);
        assertFalse("Metrics output should not be empty", metrics.isEmpty());
    }

    @Test
    public void testContainsJvmMemoryMetric() {
        String metrics = MetricsHandler.generateMetrics();
        assertTrue("Should contain JVM memory metric",
                metrics.contains("scaleguard_jvm_memory_used_bytes"));
    }

    @Test
    public void testContainsUptimeMetric() {
        String metrics = MetricsHandler.generateMetrics();
        assertTrue("Should contain uptime metric",
                metrics.contains("scaleguard_uptime_seconds"));
    }

    @Test
    public void testContainsRouteCountMetric() {
        String metrics = MetricsHandler.generateMetrics();
        assertTrue("Should contain routes total metric",
                metrics.contains("scaleguard_routes_total"));
    }

    @Test
    public void testContainsHostReachableMetric() {
        String metrics = MetricsHandler.generateMetrics();
        assertTrue("Should contain host reachable metric header",
                metrics.contains("scaleguard_host_reachable"));
    }

    @Test
    public void testContainsRequestsTotalMetric() {
        String metrics = MetricsHandler.generateMetrics();
        assertTrue("Should contain requests total metric header",
                metrics.contains("scaleguard_requests_total"));
    }

    @Test
    public void testPrometheusFormatHasTypeComments() {
        String metrics = MetricsHandler.generateMetrics();
        assertTrue("Should contain TYPE comments", metrics.contains("# TYPE"));
        assertTrue("Should contain HELP comments", metrics.contains("# HELP"));
    }

    @Test
    public void testPrometheusFormatHasCorrectTypes() {
        String metrics = MetricsHandler.generateMetrics();
        assertTrue("Memory should be gauge",
                metrics.contains("# TYPE scaleguard_jvm_memory_used_bytes gauge"));
        assertTrue("Uptime should be gauge",
                metrics.contains("# TYPE scaleguard_uptime_seconds gauge"));
        assertTrue("Requests should be counter",
                metrics.contains("# TYPE scaleguard_requests_total counter"));
    }

    @Test
    public void testContainsThreadMetric() {
        String metrics = MetricsHandler.generateMetrics();
        assertTrue("Should contain thread count metric",
                metrics.contains("scaleguard_jvm_threads_current"));
    }

    @Test
    public void testContainsActiveConnectionsMetric() {
        String metrics = MetricsHandler.generateMetrics();
        assertTrue("Should contain active connections metric header",
                metrics.contains("scaleguard_host_active_connections"));
    }
}
