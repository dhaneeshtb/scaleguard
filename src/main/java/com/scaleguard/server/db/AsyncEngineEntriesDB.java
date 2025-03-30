package com.scaleguard.server.db;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.utils.JSON;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton class to manage database operations for AsyncEngineEntries.
 * Ensures table creation, indexing, and default entries.
 */
public class AsyncEngineEntriesDB extends DBManager<AsyncEngineEntry> {

    private static final AsyncEngineEntriesDB INSTANCE = new AsyncEngineEntriesDB();
    private boolean isInitialized = false;

    private AsyncEngineEntriesDB() {
        super("asyncengines");
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Provides a singleton instance of the database manager.
     *
     * @return The singleton instance of AsyncEngineEntriesDB.
     */
    public static AsyncEngineEntriesDB getInstance() {
        return INSTANCE;
    }

    /**
     * Initializes the database table and indexes if they do not exist.
     * Ensures only one-time initialization.
     *
     * @throws Exception If any SQL operation fails.
     */
    private void initialize() throws Exception {
        if (isInitialized) {
            return;
        }

        String createTableSQL = ConnectionUtil.isPostgres() ?
                "CREATE TABLE IF NOT EXISTS asyncengines (" +
                        " id TEXT PRIMARY KEY," +
                        " name TEXT NOT NULL," +
                        " description TEXT," +
                        " type TEXT NOT NULL," +
                        " payload TEXT," +
                        " uts BIGINT," +
                        " mts BIGINT" +
                        ");" :
                "CREATE TABLE IF NOT EXISTS asyncengines (" +
                        " id TEXT PRIMARY KEY," +
                        " name TEXT NOT NULL," +
                        " description TEXT," +
                        " type TEXT NOT NULL," +
                        " payload TEXT," +
                        " uts INTEGER," +
                        " mts INTEGER" +
                        ");";

        String createIndexesSQL =
                "CREATE INDEX IF NOT EXISTS engine_name_idx ON asyncengines (name);" +
                        "CREATE INDEX IF NOT EXISTS engine_type_idx ON asyncengines (type);";

        try (Connection connection = ConnectionUtil.getConnection();
             Statement statement = connection.createStatement()) {

            statement.setQueryTimeout(30);
            statement.executeUpdate(createTableSQL);

            try {
                statement.executeUpdate(createIndexesSQL);
            } catch (SQLException e) {
                System.err.println("Index creation failed: " + e.getMessage());
            }

            try {
                statement.executeUpdate(createDefaultEntry());
            } catch (SQLException e) {
                System.err.println("Default entry insertion failed: " + e.getMessage());
            }

            isInitialized = true;
        } catch (SQLException e) {
            throw new SQLException("Database initialization failed", e);
        }
    }

    /**
     * Generates the SQL statement for inserting a default async engine entry.
     *
     * @return SQL INSERT statement as a String.
     */
    private static String createDefaultEntry() {
        long currentTime = System.currentTimeMillis();
        ObjectNode on = JSON.object();
        on.put("topic","event-stream");
        return "INSERT INTO asyncengines (id, name, description, type, payload, uts, mts) " +
                "VALUES (" +
                "'default-embedded'," +
                "'default-embedded'," +
                "NULL," +
                "'embedded'," +
                "'"+on.toString()+"'," +
                currentTime + "," +
                currentTime +
                ") ON CONFLICT (id) DO NOTHING;";
    }

    @Override
    public void init() throws Exception {
        try {
            initialize();
        } catch (Exception e) {
            throw new RuntimeException("Error initializing AsyncEngineEntriesDB", e);
        }
    }
}
