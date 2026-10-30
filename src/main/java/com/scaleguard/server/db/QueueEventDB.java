package com.scaleguard.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton DB manager for the {@code queue_events} table.
 *
 * <p>Stores every event processed by the embedded async engine so that:
 * <ul>
 *   <li>Events survive a server restart (durability)</li>
 *   <li>Failed / dead-letter events are visible for manual reprocessing</li>
 *   <li>A full audit trail (attempt count, response codes, error messages) is available</li>
 * </ul>
 * </p>
 *
 * <p>Supports both SQLite (default) and PostgreSQL — the correct DDL variant is
 * chosen via {@link ConnectionUtil#isPostgres()}.</p>
 */
public class QueueEventDB extends DBManager<QueueEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(QueueEventDB.class);
    private static final QueueEventDB INSTANCE = new QueueEventDB();
    private boolean initialized = false;

    private QueueEventDB() {
        super("queue_events");
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise QueueEventDB", e);
        }
    }

    public static QueueEventDB getInstance() {
        return INSTANCE;
    }

    @Override
    public void init() throws Exception {
        if (initialized) return;
        try {
            createSchema();
            initialized = true;
        } catch (Exception e) {
            throw new RuntimeException("QueueEventDB schema initialisation failed", e);
        }
    }

    /**
     * The {@code status} column is meaningful for queue events — allow it in
     * the generic DBManager UPDATE/INSERT path.
     */
    @Override
    public boolean isStatusSupported() {
        return true;
    }

    // -------------------------------------------------------------------------

    private void createSchema() throws Exception {
        boolean pg = ConnectionUtil.isPostgres();

        String ddl = pg
                ? "CREATE TABLE IF NOT EXISTS queue_events (" +
                  "  id           TEXT    PRIMARY KEY," +
                  "  engineId     TEXT    NOT NULL," +
                  "  status       TEXT    NOT NULL," +
                  "  payload      TEXT    NOT NULL," +
                  "  attempt      INTEGER DEFAULT 0," +
                  "  responseCode INTEGER," +
                  "  responseBody TEXT," +
                  "  errorMessage TEXT," +
                  "  createdAt    BIGINT," +
                  "  updatedAt    BIGINT," +
                  "  dispatchedAt BIGINT" +
                  ");"
                : "CREATE TABLE IF NOT EXISTS queue_events (" +
                  "  id           TEXT    PRIMARY KEY," +
                  "  engineId     TEXT    NOT NULL," +
                  "  status       TEXT    NOT NULL," +
                  "  payload      TEXT    NOT NULL," +
                  "  attempt      INTEGER DEFAULT 0," +
                  "  responseCode INTEGER," +
                  "  responseBody TEXT," +
                  "  errorMessage TEXT," +
                  "  createdAt    INTEGER," +
                  "  updatedAt    INTEGER," +
                  "  dispatchedAt INTEGER" +
                  ");";

        String indexes =
                "CREATE INDEX IF NOT EXISTS qe_engine_idx  ON queue_events (engineId);" +
                "CREATE INDEX IF NOT EXISTS qe_status_idx  ON queue_events (status);" +
                "CREATE INDEX IF NOT EXISTS qe_created_idx ON queue_events (createdAt);";

        try (Connection conn = ConnectionUtil.getConnection();
             Statement st = conn.createStatement()) {
            st.setQueryTimeout(30);
            st.executeUpdate(ddl);
            try {
                st.executeUpdate(indexes);
            } catch (SQLException e) {
                // Indexes are advisory — log and continue
                LOG.warn("Could not create queue_events indexes: {}", e.getMessage());
            }
        }
        LOG.info("queue_events table ready (postgres={})", pg);
    }

    /** Queue events are data, not configuration: never broadcast to the cluster. */
    @Override
    protected java.util.Collection<String> clusterScopes(java.util.Collection<String> ids) {
        return java.util.List.of();
    }
}
