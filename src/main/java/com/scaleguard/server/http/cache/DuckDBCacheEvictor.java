package com.scaleguard.server.http.cache;

import com.scaleguard.server.concurrent.VirtualThreads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background task that periodically evicts expired entries from the
 * {@link DuckDBCacheManager}.
 *
 * <p>Runs every {@value #INTERVAL_MS} ms on a virtual thread — no OS thread is
 * blocked while sleeping.  Started lazily by {@link DuckDBCacheManager} when the
 * first instance is created.
 */
public class DuckDBCacheEvictor {

    private static final Logger LOG = LoggerFactory.getLogger(DuckDBCacheEvictor.class);

    /** Eviction check interval — every 5 minutes. */
    private static final long INTERVAL_MS = 5 * 60 * 1000L;

    private static volatile boolean started = false;

    private DuckDBCacheEvictor() {}

    /**
     * Start the eviction loop (idempotent — safe to call multiple times).
     *
     * @param manager the DuckDB cache manager to evict from
     */
    public static synchronized void start(DuckDBCacheManager manager) {
        if (started) return;
        started = true;
        VirtualThreads.submit("duckdb-cache-evictor", () -> {
            while (true) {
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.info("DuckDB cache evictor interrupted — stopping");
                    break;
                }
                try {
                    int deleted = manager.evictExpired();
                    if (deleted > 0) {
                        LOG.info("DuckDB cache evictor removed {} expired entries", deleted);
                    } else {
                        LOG.debug("DuckDB cache evictor: no expired entries");
                    }
                } catch (Exception e) {
                    LOG.error("DuckDB cache evictor error: {}", e.getMessage(), e);
                }
            }
        });
        LOG.info("DuckDB cache evictor started (interval={}ms)", INTERVAL_MS);
    }
}

