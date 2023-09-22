package com.scaleguard.server.http.metering.inmemory;

import com.scaleguard.server.http.metering.ApiData;
import com.scaleguard.server.http.metering.MetricsProcessor;
import com.scaleguard.server.http.metering.MetricsQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class InMemoryMetricsQueue implements MetricsQueue {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMetricsQueue.class);

    private final BlockingQueue<ApiData> queue;

    private final Thread consumerThread;

    private final MetricsProcessor metricsProcessor;


    public InMemoryMetricsQueue(int capacity, MetricsProcessor metricsProcessor) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.metricsProcessor = metricsProcessor;
        this.consumerThread = new Thread(this::consume);
        this.consumerThread.start();
    }

    @Override
    public void push(ApiData apiData) {
        boolean added = queue.offer(apiData);
        if (!added) {
            log.error("Queue is full. Ignoring apiData: {}", apiData);
        }
    }

    private void consume() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ApiData apiData = queue.take();
                metricsProcessor.process(apiData);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Thread interrupted", e);
            } catch (Exception e) {
                log.error("Got an unhandled exception from processor", e);
            }
        }
    }

    public void stopConsumer() {
        consumerThread.interrupt();
        try {
            consumerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
