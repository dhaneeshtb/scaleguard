package com.scaleguard.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DNSEntriesDB extends DBManager<DNSEntry> {

    private static final Logger LOG = LoggerFactory.getLogger(DNSEntriesDB.class);
    private boolean isInited = false;

    private DNSEntriesDB() {
        super("dns_entries");
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static DNSEntriesDB _db = new DNSEntriesDB();

    public static DNSEntriesDB getInstance() {
        return _db;
    }


    public void init() throws Exception {
        if (isInited) {
            return;
        }
        String sql = ConnectionUtil.isPostgres() ? "CREATE TABLE IF NOT EXISTS dns_entries (\n"
                + "\tid text PRIMARY KEY,\n"
                + "\tname text NOT NULL,\n"
                + "\ttype text NOT NULL,\n"
                + "\tttl bigint,\n"
                + "\tgroupId text,\n"
                + "\ttarget text,\n"
                + "\tpayload text,\n"
                + "\tvalue text,\n"
                + "\tuts bigint,\n"
                + "\tmts bigint\n"
                + ");" :

                "CREATE TABLE IF NOT EXISTS dns_entries (\n"
                        + "\tid text PRIMARY KEY,\n"
                        + "\tname text NOT NULL,\n"
                        + "\ttype text NOT NULL,\n"
                        + "\tttl integer,\n"
                        + "\tgroupId text,\n"
                        + "\ttarget text,\n"
                        + "\tpayload text,\n"
                        + "\tvalue text,\n"
                        + "\tuts integer,\n"
                        + "\tmts integer\n"
                        + ");";
        String indexs = "CREATE INDEX IF NOT EXISTS system_group_id_idx ON dns_entries (groupId);\n" +
                "CREATE INDEX IF NOT EXISTS system_idx ON dns_entries (id);\n" +
                "CREATE INDEX IF NOT EXISTS system_target_idx ON dns_entries (target);\n" +
                "CREATE INDEX IF NOT EXISTS system_name_idx ON dns_entries (name);";


        try (Connection connection = ConnectionUtil.getConnection(); Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(30);  // set timeout to 30 sec.
                statement.executeUpdate(sql);
                statement.executeUpdate(indexs);

            isInited = true;

        } catch (SQLException e) {
            LOG.error("Failed to initialise dns_entries table: {}", e.getMessage(), e);
        }
    }


    public static void main(String[] args) throws Exception {
    }
}
