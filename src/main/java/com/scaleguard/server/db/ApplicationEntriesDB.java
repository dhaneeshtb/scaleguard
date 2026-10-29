package com.scaleguard.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class ApplicationEntriesDB extends DBManager<ApplicationEntry> {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationEntriesDB.class);
    private boolean isInited = false;

    private ApplicationEntriesDB() {
        super("applications");
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ApplicationEntriesDB _db = new ApplicationEntriesDB();

    public static ApplicationEntriesDB getInstance() {
        return _db;
    }


    public void init() throws Exception {
        if (isInited) {
            return;
        }
        String sql = ConnectionUtil.isPostgres() ? "CREATE TABLE IF NOT EXISTS applications (\n"
                + "\tid text PRIMARY KEY,\n"
                + "\tname text NOT NULL,\n"
                + "\tdescription text,\n"
                + "\tgroupId text,\n"
                + "\tpayload text,\n"
                + "\tuts bigint,\n"
                + "\tmts bigint\n"
                + ");" :

                "CREATE TABLE IF NOT EXISTS applications (\n"
                        + "\tid text PRIMARY KEY,\n"
                        + "\tname text NOT NULL,\n"
                        + "\tdescription text NOT NULL,\n"
                        + "\tgroupId text,\n"
                        + "\tpayload text,\n"
                        + "\tuts integer,\n"
                        + "\tmts integer\n"
                        + ");";
        String indexs = "CREATE INDEX IF NOT EXISTS application_name_idx ON applications (name);\n";


        try (Connection connection = ConnectionUtil.getConnection(); Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(30);  // set timeout to 30 sec.
                statement.executeUpdate(sql);
                statement.executeUpdate(indexs);

            isInited = true;

        } catch (SQLException e) {
            LOG.error("Failed to initialise applications table: {}", e.getMessage(), e);
        }
    }


    public static void main(String[] args) throws Exception {
    }
}
