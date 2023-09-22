package com.scaleguard.server.http.metering.inmemory;

import com.scaleguard.server.http.metering.ApiData;
import com.scaleguard.server.http.metering.MetricsProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class InMemoryMetricsProcessor implements MetricsProcessor {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMetricsProcessor.class);

    private final InMemoryMetricsService inMemoryMetricsService;

    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

    public InMemoryMetricsProcessor(InMemoryMetricsService inMemoryMetricsService) {
        this.inMemoryMetricsService = inMemoryMetricsService;
        addCleanUpHook();
    }

    @Override
    public void process(ApiData apiData) {
        try {

            inMemoryMetricsService.process(apiData);
        } catch (Exception e) {
            log.error("Could not add to metrics:{}", apiData, e);
        }
    }

    private void addCleanUpHook() {
        scheduledExecutorService.schedule(() -> inMemoryMetricsService.deleteMetrics(1, ChronoUnit.DAYS), 1, TimeUnit.HOURS);
    }


}
