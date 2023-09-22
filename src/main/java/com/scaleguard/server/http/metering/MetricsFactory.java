package com.scaleguard.server.http.metering;

import com.scaleguard.server.http.metering.inmemory.InMemoryMetricsProcessor;
import com.scaleguard.server.http.metering.inmemory.InMemoryMetricsQueue;
import com.scaleguard.server.http.metering.inmemory.InMemoryMetricsService;

public class MetricsFactory {

    private final MetricsService metricsService = new InMemoryMetricsService();

    private final MetricsProcessor metricsProcessor = new InMemoryMetricsProcessor((InMemoryMetricsService) metricsService);

    private final MetricsQueue metricsQueue = new InMemoryMetricsQueue(10000, metricsProcessor);


    public MetricsQueue getMetricsQueue() {
        return metricsQueue;
    }

    public MetricsProcessor getProcessor() {
        return metricsProcessor;
    }

    public MetricsService getMetricsService() {
        return metricsService;
    }
}
