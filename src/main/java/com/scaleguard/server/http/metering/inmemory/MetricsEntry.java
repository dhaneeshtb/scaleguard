package com.scaleguard.server.http.metering.inmemory;

import com.scaleguard.server.http.metering.ApiData;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

class MetricsEntry {
    private final Map<String, GroupMetrics> groupMetricsMap = new ConcurrentHashMap<>();
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong totalTime = new AtomicLong();

    public void add(ApiData apiData) {
        String groupKey = getGroupKey(apiData);
        groupMetricsMap.compute(groupKey, (key, groupMetrics) -> {
            if (groupMetrics == null) {
                groupMetrics = new GroupMetrics();
            }
            groupMetrics.add(apiData);
            return groupMetrics;
        });
        requestCount.incrementAndGet();
        totalTime.addAndGet(apiData.getDuration());
    }

    public long getRequestCount() {
        return requestCount.get();
    }

    public double getAverageTime() {
        long count = requestCount.get();
        long total = totalTime.get();
        return (count > 0) ? (double) total / count : 0.0;
    }

    public Map<String, GroupMetrics> getGroupMetricsMap() {
        return groupMetricsMap;
    }


    public GroupMetrics getGroupMetrics(String groupKey) {
        return groupMetricsMap.get(groupKey);
    }

    public Collection<GroupMetrics> getAllGroupMetrics() {
        return groupMetricsMap.values();
    }

    private String getGroupKey(ApiData apiData) {
        return apiData.getPath() + "|" + apiData.getTarget() + "|" + apiData.getMethod() + "|" + apiData.getStatus();
    }
}