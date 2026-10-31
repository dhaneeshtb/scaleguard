package com.scaleguard.server.functions;

import com.scaleguard.server.db.ConnectionUtil;
import com.scaleguard.server.db.DBManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Statement;

public class CloudFunctionDB extends DBManager<CloudFunction> {

    private static final Logger LOG = LoggerFactory.getLogger(CloudFunctionDB.class);
    private volatile boolean inited;

    private CloudFunctionDB() {
        super("cloud_functions");
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final CloudFunctionDB INSTANCE = new CloudFunctionDB();

    public static CloudFunctionDB getInstance() {
        return INSTANCE;
    }

    @Override
    public void init() throws Exception {
        if (inited) return;
        String num = ConnectionUtil.isPostgres() ? "bigint" : "integer";
        try (Connection c = ConnectionUtil.getConnection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(30);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS cloud_functions (id text PRIMARY KEY, name text NOT NULL, payload text, uts "
                    + num + ", mts " + num + ")");
            inited = true;
        } catch (Exception e) {
            LOG.error("Failed to initialise cloud_functions table: {}", e.getMessage(), e);
        }
    }
}
