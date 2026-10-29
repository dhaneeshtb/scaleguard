package com.scaleguard.server.http.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.concurrent.VirtualThreads;
import com.scaleguard.server.db.QueueEvent;
import com.scaleguard.server.db.QueueEventDB;
import com.scaleguard.server.http.cache.ProxyRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Startup recovery scanner for the embedded async queue.
 *
 * <p>On server start, any events left in {@code pending}, {@code dispatching}, or
 * {@code failed} status are candidates for replay — they were either never dispatched
 * (server crashed before the subscriber ran) or failed on the previous attempt and
 * still have attempts remaining.</p>
 *
 * <p>This scanner is run once from {@link AsyncFlowDrivers} immediately after all
 * engines are loaded. It submits each recoverable event back through its engine's
 * {@link EmbeddedAsyncFlowDriver} so the normal dispatch + DB-update path applies.</p>
 *
 * <h3>Dead-letter events are NOT replayed</h3>
 * <p>Events with status {@code dead} have exhausted their retry budget and require
 * explicit manual action (e.g., via a future admin API or direct DB update).</p>
 */
public class EmbeddedQueueRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedQueueRecovery.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmbeddedQueueRecovery() {}

    /**
     * Scan the DB for recoverable events and re-submit them through their engine.
     * Runs on a virtual thread to avoid blocking server startup.
     *
     * @param drivers map of engineId → driver (only {@link EmbeddedAsyncFlowDriver} entries
     *                are considered)
     */
    public static void recoverPendingEvents(java.util.Map<String, AsyncFlowDrivers.AsyncFlowDriver> drivers) {
        VirtualThreads.submit("queue-recovery", () -> {
            LOG.info("Starting queue event recovery scan...");
            try {
                List<QueueEvent> all = QueueEventDB.getInstance().readAll();
                int recovered = 0;
                int skipped = 0;

                for (QueueEvent event : all) {
                    String status = event.getStatus();
                    boolean recoverable = "pending".equals(status)
                            || "dispatching".equals(status)
                            || "failed".equals(status);

                    if (!recoverable) {
                        skipped++;
                        continue;
                    }

                    AsyncFlowDrivers.AsyncFlowDriver driver = drivers.get(event.getEngineId());
                    if (!(driver instanceof EmbeddedAsyncFlowDriver)) {
                        LOG.debug("No EmbeddedAsyncFlowDriver for engine [{}], skipping event {}",
                                event.getEngineId(), event.getId());
                        skipped++;
                        continue;
                    }

                    try {
                        ProxyRequest pr = MAPPER.readValue(event.getPayload(), ProxyRequest.class);
                        pr.setId(event.getId()); // preserve original ID so DB row is updated
                        LOG.info("Recovering queue event {} (status={}, attempt={})",
                                event.getId(), status, event.getAttempt());
                        driver.publish(pr);
                        recovered++;
                    } catch (Exception e) {
                        LOG.warn("Could not recover queue event {}: {}", event.getId(), e.getMessage());
                        skipped++;
                    }
                }

                LOG.info("Queue recovery complete: {} recovered, {} skipped (success/dead/unknown engine)",
                        recovered, skipped);
            } catch (Exception e) {
                LOG.error("Queue recovery scan failed: {}", e.getMessage(), e);
            }
        });
    }
}
