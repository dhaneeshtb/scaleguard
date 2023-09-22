package com.scaleguard.server.http.metering.inmemory;

import com.scaleguard.server.http.metering.ApiData;

import java.util.concurrent.atomic.AtomicLong;

public class GroupMetrics {

    private final AtomicLong groupRequestCount = new AtomicLong();

    private final AtomicLong groupTotalTime = new AtomicLong();

    public void add(ApiData apiData) {
        groupRequestCount.incrementAndGet();
        groupTotalTime.addAndGet(apiData.getDuration());
    }

    public long getRequestCount() {
        return groupRequestCount.get();
    }

    public long getTotalTime() {
        return groupTotalTime.get();
    }

    public double getAverageTime() {
        long count = groupRequestCount.get();
        long total = groupTotalTime.get();
        return (count > 0) ? (double) total / count : 0.0;
    }
}