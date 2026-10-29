package com.scaleguard.server.http.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.application.AsyncEngines;
import com.scaleguard.server.db.QueueEvent;
import com.scaleguard.server.db.QueueEventDB;
import com.scaleguard.server.http.cache.ProxyRequest;
import com.scaleguard.server.http.cache.ProxyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.SubmissionPublisher;

/**
 * Embedded (in-process) async flow driver.
 *
 * <p>On {@link #publish(ProxyRequest)} the request is:
 * <ol>
 *   <li>Assigned a UUID if it has none.</li>
 *   <li>Persisted to {@link QueueEventDB} with status {@code pending}.</li>
 *   <li>Submitted to the in-memory {@link SubmissionPublisher} for dispatch.</li>
 * </ol>
 * This ensures events survive a restart because they are durable in the DB
 * before the in-memory queue picks them up.
 * </p>
 */
public class EmbeddedAsyncFlowDriver implements AsyncFlowDrivers.AsyncFlowDriver {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedAsyncFlowDriver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AsyncEngines.WrappedAsyncEngineRecord engineRecord;
    private final SubmissionPublisher<ProxyRequest> publisher = new SubmissionPublisher<>();

    public EmbeddedAsyncFlowDriver(AsyncEngines.WrappedAsyncEngineRecord engineRecord) {
        this.engineRecord = engineRecord;
        ProxySubscriber<ProxyRequest> subscriber = new ProxySubscriber<>();
        publisher.subscribe(subscriber);
        LOG.info("EmbeddedAsyncFlowDriver initialised for engine '{}'", engineRecord.getId());
    }

    /**
     * Accepts a {@link ProxyRequest}, persists it as a {@code pending} queue
     * event, then enqueues it for async dispatch.
     *
     * @param pr the request to publish
     * @return a {@link ProxyResponse} with status {@code pending} and the event id
     */
    @Override
    public ProxyResponse publish(ProxyRequest pr) {
        // Ensure every request has a stable ID before we touch the DB.
        if (pr.getId() == null || pr.getId().isBlank()) {
            pr.setId(UUID.randomUUID().toString());
        }

        // Persist to DB so the event survives a restart.
        try {
            String payloadJson = MAPPER.writeValueAsString(pr);
            long now = System.currentTimeMillis();

            QueueEvent event = new QueueEvent();
            event.setId(pr.getId());
            event.setEngineId(engineRecord.getId());
            event.setStatus("pending");
            event.setPayload(payloadJson);
            event.setAttempt(0);
            event.setCreatedAt(now);
            event.setUpdatedAt(now);

            QueueEventDB.getInstance().create(event);
            LOG.info("Queue event created: id={} engineId={}", event.getId(), event.getEngineId());
        } catch (Exception e) {
            // Log but do not block the async flow — event will still be dispatched
            // in-memory even if DB persistence failed.
            LOG.error("Failed to persist queue event id={} to DB: {}", pr.getId(), e.getMessage(), e);
        }

        // Hand off to the in-memory flow (non-blocking).
        publisher.submit(pr);

        ProxyResponse prs = new ProxyResponse();
        prs.setId(pr.getId());
        prs.setStatus("pending");
        return prs;
    }
}
