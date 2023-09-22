package com.scaleguard.server.http.metering.inmemory;

import com.scaleguard.server.http.metering.ApiData;
import com.scaleguard.server.http.metering.MetricRequest;
import com.scaleguard.server.http.metering.MetricResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MinuteMetrics {

    private static final Logger log = LoggerFactory.getLogger(MinuteMetrics.class);

    private final ConcurrentMap<String, MetricsEntry> metricsMap = new ConcurrentHashMap<>();

    public void addRequest(ApiData apiData) {
        LocalDateTime minute = apiData.getStarted().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        String minuteKey = minute.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        metricsMap.compute(minuteKey, (key, metricsEntry) -> {
            if (metricsEntry == null) {
                metricsEntry = new MetricsEntry();
            }
            metricsEntry.add(apiData);
            return metricsEntry;
        });
    }

    public MetricsEntry getMetricsForMinute(LocalDateTime minute) {
        String minuteKey = minute.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return metricsMap.get(minuteKey);
    }

    public Map<LocalDateTime, MetricsEntry> getAllMetrics() {
        TreeMap<LocalDateTime, MetricsEntry> sortedMetrics = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<String, MetricsEntry> entry : metricsMap.entrySet()) {
            LocalDateTime minute = LocalDateTime.parse(entry.getKey(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            sortedMetrics.put(minute, entry.getValue());
        }
        return sortedMetrics;
    }

    public long getTotalRequestsInHour(int year, int month, int day, int hour) {
        LocalDateTime startHour = LocalDateTime.of(year, month, day, hour, 0, 0);
        LocalDateTime endHour = startHour.plusHours(1);

        long totalRequests = 0;

        for (LocalDateTime minute = startHour; minute.isBefore(endHour); minute = minute.plusMinutes(1)) {
            MetricsEntry metricsEntry = getMetricsForMinute(minute);
            if (metricsEntry != null) {
                totalRequests += metricsEntry.getRequestCount();
            }
        }

        return totalRequests;
    }

    public void deleteMetrics(int timeToKeep, TemporalUnit temporalUnit) {
        log.info("Going to delete older metrics");
        LocalDateTime keepFrom = LocalDateTime.now().minus(timeToKeep, temporalUnit);
        metricsMap.entrySet().removeIf(entry -> {
            LocalDateTime minute = LocalDateTime.parse(entry.getKey(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return minute.isBefore(keepFrom);
        });
        log.info("Older metrics deleted successfully");
    }

    private String buildRegexPattern(String path, String status, String method, String target) {
        StringBuilder regexPattern = new StringBuilder("^");

        if (path != null && !path.isEmpty()) {
            regexPattern.append(Pattern.quote(path));
        } else {
            regexPattern.append(".*"); // Match anything for path
        }

        regexPattern.append("\\|");

        if (target != null && !target.isEmpty()) {
            regexPattern.append(Pattern.quote(target));
        } else {
            regexPattern.append(".*"); // Match anything for target
        }

        regexPattern.append("\\|");

        if (method != null && !method.isEmpty()) {
            regexPattern.append(Pattern.quote(method));
        } else {
            regexPattern.append(".*"); // Match anything for method
        }

        regexPattern.append("\\|");

        if (status != null && !status.isEmpty()) {
            regexPattern.append(Pattern.quote(status));
        } else {
            regexPattern.append(".*"); // Match anything for status
        }

        regexPattern.append("$");

        return regexPattern.toString();
    }


    public MetricResponse getMetrics(MetricRequest metricRequest) {
        String path = metricRequest.getPath();
        String status = metricRequest.getStatus();
        String method = metricRequest.getMethod();
        String target = metricRequest.getTarget();
        int interval = metricRequest.getInterval();
        String timeUnit = metricRequest.getTimeUnit();
        String regexPattern = buildRegexPattern(path, status, method, target);
        LocalDateTime currentMinute = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime startMinute = currentMinute.minusMinutes(getMinutes(timeUnit, interval));
        long totalRequests = 0;
        long totalTime = 0;
        int matchingCount = 0;
        Pattern pattern = Pattern.compile(regexPattern);
        for (String key : metricsMap.keySet()) {
            LocalDateTime minute = LocalDateTime.parse(key, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            if (minute.isAfter(startMinute)) {
                MetricsEntry metricsEntry = getMetricsForMinute(minute);
                Set<String> groupKeys = metricsEntry.getGroupMetricsMap().keySet();
                for (String groupKey : groupKeys) {
                    Matcher matcher = pattern.matcher(groupKey);
                    if (matcher.matches()) {
                        GroupMetrics groupMetrics = metricsEntry.getGroupMetrics(groupKey);
                        if (groupMetrics != null) {
                            totalRequests += groupMetrics.getRequestCount();
                            totalTime += groupMetrics.getTotalTime();
                            matchingCount++;
                        }
                    }
                }
            }
        }
        long averageTime = (matchingCount > 0) ? totalTime / totalRequests : 0;
        return new MetricResponse()
                .setRequest(metricRequest)
                .setTotalRequests(totalRequests)
                .setAverageTime(averageTime);
    }

    private long getMinutes(String timeUnit, int interval) {
        if ("minutes".equalsIgnoreCase(timeUnit)) {
            return interval;
        } else if ("hours".equalsIgnoreCase(timeUnit)) {
            return interval * 60L;
        } else if ("days".equalsIgnoreCase(timeUnit)) {
            return (long) interval * 60 * 24;
        }
        return 0;
    }
}