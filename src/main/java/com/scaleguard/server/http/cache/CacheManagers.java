package com.scaleguard.server.http.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for {@link CacheManager} implementations.
 *
 * <p>Selects the backend based on the JVM system property {@code cache.backend}:
 * <ul>
 *   <li>{@code duckdb} — persistent DuckDB-backed cache (recommended for large datasets)</li>
 *   <li>{@code memory} (default) — in-process {@link InMemoryCacheLooker}</li>
 * </ul>
 *
 * <p>The DuckDB backend is automatically chosen when the system property
 * {@code duckdb.cache.path} is set (e.g. passed via {@code run.sh}).
 *
 * <h3>Usage</h3>
 * <pre>
 *   CacheManager cm = CacheManagers.get();
 *   CachedResponse cr = cm.lookup(targetSystem, key);
 * </pre>
 */
public class CacheManagers {

    private static final Logger LOG = LoggerFactory.getLogger(CacheManagers.class);

    /** JVM system property controlling backend selection. */
    public static final String BACKEND_PROPERTY = "cache.backend";

    private static volatile CacheManager instance;

    private CacheManagers() {}

    /**
     * Return the singleton {@link CacheManager}, initialising it on first call.
     *
     * <p>Thread-safe via double-checked locking.
     */
    public static CacheManager get() {
        if (instance == null) {
            synchronized (CacheManagers.class) {
                if (instance == null) {
                    instance = createInstance();
                }
            }
        }
        return instance;
    }

    private static CacheManager createInstance() {
        String backend = System.getProperty(BACKEND_PROPERTY, "").trim().toLowerCase();

        // Also treat a non-empty duckdb.cache.path as an implicit duckdb selector
        String duckDbPath = System.getProperty(DuckDBCacheManager.DB_PATH_PROPERTY, "").trim();

        if ("duckdb".equals(backend) || (!backend.isEmpty() && !"memory".equals(backend))
                || !duckDbPath.isEmpty()) {
            try {
                DuckDBCacheManager duckDBCacheManager = DuckDBCacheManager.getInstance();
                // DuckDBCacheEvictor is started inside DuckDBCacheManager constructor (idempotent).
                LOG.info("Cache backend: DuckDB (path={})", duckDbPath.isEmpty() ? "default" : duckDbPath);
                return duckDBCacheManager;
            } catch (Exception e) {
                LOG.error("DuckDB cache failed to initialise — falling back to in-memory: {}", e.getMessage(), e);
            }
        }

        LOG.info("Cache backend: in-memory");
        return InMemoryCacheLooker.getInstance();
    }

    /**
     * Override the singleton instance (for testing only).
     */
    static void setInstance(CacheManager manager) {
        instance = manager;
    }
}

