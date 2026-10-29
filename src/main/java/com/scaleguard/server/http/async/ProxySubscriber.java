package com.scaleguard.server.http.async;

import com.scaleguard.server.db.QueueEvent;
import com.scaleguard.server.db.QueueEventDB;
import com.scaleguard.server.http.cache.ProxyRequest;
import com.scaleguard.server.http.cache.ProxyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Flow;

/**
 * Flow subscriber that dispatches {@link ProxyRequest} items to the backend
 * and maintains the DB lifecycle for each queue event.
 *
 * <p>Status transitions managed here:
 * <pre>
 *   pending → dispatching → success
 *                        ↘ failed  (attempt < MAX_ATTEMPTS → re-queued externally)
 *                        ↘ dead    (attempt >= MAX_ATTEMPTS)
 * </pre>
 * </p>
 */
class ProxySubscriber<T> implements Flow.Subscriber<T> {

    private static final Logger LOG = LoggerFactory.getLogger(ProxySubscriber.class);

    /** Maximum number of dispatch attempts before a queue event is marked {@code dead}. */
    private static final int MAX_ATTEMPTS = 3;

    /** Maximum stored length of a response body (chars). */
    private static final int MAX_RESPONSE_BODY_LEN = 500;

    private Flow.Subscription subscription;

    // -------------------------------------------------------------------------
    // Flow.Subscriber
    // -------------------------------------------------------------------------

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        LOG.info("ProxySubscriber subscribed — requesting first item");
        subscription.request(1);
    }

    @Override
    public void onNext(T item) {
        if (item instanceof ProxyRequest) {
            dispatch((ProxyRequest) item);
        } else {
            LOG.warn("ProxySubscriber received unexpected item type: {}", item == null ? "null" : item.getClass().getName());
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable t) {
        LOG.error("ProxySubscriber encountered an error in the subscription flow", t);
    }

    @Override
    public void onComplete() {
        LOG.info("ProxySubscriber flow completed");
    }

    // -------------------------------------------------------------------------
    // Internal dispatch logic
    // -------------------------------------------------------------------------

    /**
     * Looks up the persisted {@link QueueEvent} for this request, marks it
     * {@code dispatching}, calls the backend, then updates the final status.
     */
    private void dispatch(ProxyRequest pr) {
        String eventId = pr.getId();
        LOG.info("Dispatching queue event id={}", eventId);

        // Fetch the current DB record (if any).
        QueueEvent event = findEvent(eventId);

        // Update status to 'dispatching' before the HTTP call.
        if (event != null) {
            long now = System.currentTimeMillis();
            event.setStatus("dispatching");
            event.setDispatchedAt(now);
            event.setAttempt(event.getAttempt() + 1);
            event.setUpdatedAt(now);
            updateEvent(event);
        }

        // Execute the outbound HTTP call.
        try {
            ProxyResponse response = OutboundDispatchUtil.sendRequest(pr);

            String body = response.getResponseBody();
            String truncatedBody = (body != null && body.length() > MAX_RESPONSE_BODY_LEN)
                    ? body.substring(0, MAX_RESPONSE_BODY_LEN)
                    : body;

            LOG.info("Queue event id={} dispatched successfully, responseCode={}", eventId, response.getResponseCode());

            if (event != null) {
                event.setStatus("success");
                event.setResponseCode(response.getResponseCode());
                event.setResponseBody(truncatedBody);
                event.setErrorMessage(null);
                event.setUpdatedAt(System.currentTimeMillis());
                updateEvent(event);
            }

        } catch (Exception e) {
            int attempts = event != null ? event.getAttempt() : MAX_ATTEMPTS;
            LOG.error("Queue event id={} dispatch failed on attempt {}: {}", eventId, attempts, e.getMessage(), e);

            if (event != null) {
                String finalStatus = attempts >= MAX_ATTEMPTS ? "dead" : "failed";
                event.setStatus(finalStatus);
                event.setErrorMessage(truncate(e.getMessage(), MAX_RESPONSE_BODY_LEN));
                event.setUpdatedAt(System.currentTimeMillis());
                updateEvent(event);

                if ("dead".equals(finalStatus)) {
                    LOG.warn("Queue event id={} moved to dead-letter after {} attempts", eventId, attempts);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // DB helpers
    // -------------------------------------------------------------------------

    private QueueEvent findEvent(String id) {
        try {
            List<QueueEvent> items = QueueEventDB.getInstance().readItems("id", id);
            if (items != null && !items.isEmpty()) {
                return items.get(0);
            }
        } catch (Exception e) {
            LOG.error("Failed to read QueueEvent id={} from DB: {}", id, e.getMessage(), e);
        }
        return null;
    }

    private void updateEvent(QueueEvent event) {
        try {
            QueueEventDB.getInstance().edit(event);
        } catch (Exception e) {
            LOG.error("Failed to update QueueEvent id={} in DB: {}", event.getId(), e.getMessage(), e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
