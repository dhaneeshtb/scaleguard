package com.scaleguard.server.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DNSEntriesDB extends DBManager<DNSEntry> {

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
                + "	id text PRIMARY KEY,\n"
                + "	name text NOT NULL,\n"
                + "	type text NOT NULL,\n"
                + "	ttl bigint,\n"
                + "	groupId text,\n"
                + "	target text,\n"
                + "	payload text,\n"
                + "	value text,\n"
                + "	uts bigint,\n"
                + "	mts bigint\n"
                + ");" :

                "CREATE TABLE IF NOT EXISTS dns_entries (\n"
                        + "	id text PRIMARY KEY,\n"
                        + "	name text NOT NULL,\n"
                        + "	type text NOT NULL,\n"
                        + "	ttl integer,\n"
                        + "	groupId text,\n"
                        + "	target text,\n"
                        + "	payload text,\n"
                        + "	value text,\n"
                        + "	uts integer,\n"
                        + "	mts integer\n"
                        + ");";
        String indexs = "CREATE INDEX IF NOT EXISTS system_group_id_idx ON dns_entries (groupId);\n" +
                "CREATE INDEX IF NOT EXISTS system_idx ON dns_entries (id);\n" +
                "CREATE INDEX IF NOT EXISTS system_target_idx ON dns_entries (target);\n" +
                "CREATE INDEX IF NOT EXISTS system_name_idx ON dns_entries (name);";


        try (Connection connection = ConnectionUtil.getConnection();Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(30);  // set timeout to 30 sec.
                statement.executeUpdate(sql);
                statement.executeUpdate(indexs);

            isInited = true;

        } catch (SQLException e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }


    public static void main(String[] args) throws Exception {
    }
}
