package com.scaleguard.server.http.metrics;

import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.RouteLogger;
import com.scaleguard.server.http.router.RouteTable;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Generates Prometheus-compatible metrics in text exposition format.
 * No external dependencies required — hand-crafted output.
 *
 * @see <a href="https://prometheus.io/docs/instrumenting/exposition_formats/">Prometheus Exposition Format</a>
 */
public class MetricsHandler {

    private static final long START_TIME = System.currentTimeMillis();

    /**
     * Generate all metrics in Prometheus text exposition format.
     */
    public static String generateMetrics() {
        StringBuilder sb = new StringBuilder();

        appendJvmMetrics(sb);
        appendUptimeMetrics(sb);
        appendRouteCountMetrics(sb);
        appendRouteStatsMetrics(sb);
        appendHostHealthMetrics(sb);

        return sb.toString();
    }

    private static void appendJvmMetrics(StringBuilder sb) {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();

        sb.append("# HELP scaleguard_jvm_memory_used_bytes JVM memory currently in use\n");
        sb.append("# TYPE scaleguard_jvm_memory_used_bytes gauge\n");
        sb.append("scaleguard_jvm_memory_used_bytes ").append(usedMemory).append("\n\n");

        sb.append("# HELP scaleguard_jvm_memory_max_bytes JVM maximum memory\n");
        sb.append("# TYPE scaleguard_jvm_memory_max_bytes gauge\n");
        sb.append("scaleguard_jvm_memory_max_bytes ").append(maxMemory).append("\n\n");

        sb.append("# HELP scaleguard_jvm_memory_total_bytes JVM total allocated memory\n");
        sb.append("# TYPE scaleguard_jvm_memory_total_bytes gauge\n");
        sb.append("scaleguard_jvm_memory_total_bytes ").append(totalMemory).append("\n\n");

        sb.append("# HELP scaleguard_jvm_threads_current Current number of JVM threads\n");
        sb.append("# TYPE scaleguard_jvm_threads_current gauge\n");
        sb.append("scaleguard_jvm_threads_current ").append(ManagementFactory.getThreadMXBean().getThreadCount()).append("\n\n");
    }

    private static void appendUptimeMetrics(StringBuilder sb) {
        long uptimeSeconds = (System.currentTimeMillis() - START_TIME) / 1000;
        sb.append("# HELP scaleguard_uptime_seconds Server uptime in seconds\n");
        sb.append("# TYPE scaleguard_uptime_seconds gauge\n");
        sb.append("scaleguard_uptime_seconds ").append(uptimeSeconds).append("\n\n");
    }

    private static void appendRouteCountMetrics(StringBuilder sb) {
        int sourceCount = RouteTable.getInstance().getSourceSystsems().size();
        int targetCount = RouteTable.getInstance().getTargetSystems().size();
        int hostGroupCount = RouteTable.getInstance().getHostGroups().size();

        sb.append("# HELP scaleguard_routes_total Total number of configured routes\n");
        sb.append("# TYPE scaleguard_routes_total gauge\n");
        sb.append("scaleguard_routes_total{type=\"source\"} ").append(sourceCount).append("\n");
        sb.append("scaleguard_routes_total{type=\"target\"} ").append(targetCount).append("\n");
        sb.append("scaleguard_routes_total{type=\"hostgroup\"} ").append(hostGroupCount).append("\n\n");
    }

    private static void appendRouteStatsMetrics(StringBuilder sb) {
        com.fasterxml.jackson.databind.JsonNode stats = RouteLogger.toStatsJson();

        sb.append("# HELP scaleguard_requests_total Total number of requests per route\n");
        sb.append("# TYPE scaleguard_requests_total counter\n");
        for (int i = 0; i < stats.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode stat = stats.get(i);
            String key = sanitizeLabel(stat.get("key").asText());
            sb.append("scaleguard_requests_total{route=\"").append(key).append("\"} ")
                    .append(stat.get("total").asLong()).append("\n");
        }
        sb.append("\n");

        sb.append("# HELP scaleguard_request_duration_avg_ms Average request duration in milliseconds\n");
        sb.append("# TYPE scaleguard_request_duration_avg_ms gauge\n");
        for (int i = 0; i < stats.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode stat = stats.get(i);
            String key = sanitizeLabel(stat.get("key").asText());
            sb.append("scaleguard_request_duration_avg_ms{route=\"").append(key).append("\"} ")
                    .append(stat.get("avg").asDouble()).append("\n");
        }
        sb.append("\n");

        sb.append("# HELP scaleguard_request_duration_min_ms Minimum request duration in milliseconds\n");
        sb.append("# TYPE scaleguard_request_duration_min_ms gauge\n");
        for (int i = 0; i < stats.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode stat = stats.get(i);
            String key = sanitizeLabel(stat.get("key").asText());
            sb.append("scaleguard_request_duration_min_ms{route=\"").append(key).append("\"} ")
                    .append(stat.get("min").asLong()).append("\n");
        }
        sb.append("\n");

        sb.append("# HELP scaleguard_request_duration_max_ms Maximum request duration in milliseconds\n");
        sb.append("# TYPE scaleguard_request_duration_max_ms gauge\n");
        for (int i = 0; i < stats.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode stat = stats.get(i);
            String key = sanitizeLabel(stat.get("key").asText());
            sb.append("scaleguard_request_duration_max_ms{route=\"").append(key).append("\"} ")
                    .append(stat.get("max").asLong()).append("\n");
        }
        sb.append("\n");
    }

    private static void appendHostHealthMetrics(StringBuilder sb) {
        List<HostGroup> hostGroups = RouteTable.getInstance().getHostGroups();

        sb.append("# HELP scaleguard_host_reachable Whether a backend host is reachable (1=yes, 0=no)\n");
        sb.append("# TYPE scaleguard_host_reachable gauge\n");
        for (HostGroup hg : hostGroups) {
            String host = sanitizeLabel(hg.getHost() != null ? hg.getHost() : "unknown");
            String port = hg.getPort() != null ? hg.getPort() : "0";
            String groupId = sanitizeLabel(hg.getGroupId() != null ? hg.getGroupId() : "unknown");
            sb.append("scaleguard_host_reachable{host=\"").append(host)
                    .append("\",port=\"").append(port)
                    .append("\",group=\"").append(groupId)
                    .append("\"} ").append(hg.isReachable() ? 1 : 0).append("\n");
        }
        sb.append("\n");

        sb.append("# HELP scaleguard_host_active_connections Current active connections per host\n");
        sb.append("# TYPE scaleguard_host_active_connections gauge\n");
        for (HostGroup hg : hostGroups) {
            String host = sanitizeLabel(hg.getHost() != null ? hg.getHost() : "unknown");
            String port = hg.getPort() != null ? hg.getPort() : "0";
            sb.append("scaleguard_host_active_connections{host=\"").append(host)
                    .append("\",port=\"").append(port)
                    .append("\"} ").append(hg.getActiveConnections().get()).append("\n");
        }
        sb.append("\n");
    }

    /**
     * Sanitize label values for Prometheus format (escape backslashes, quotes, newlines).
     */
    private static String sanitizeLabel(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
