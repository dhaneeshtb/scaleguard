package com.scaleguard.server.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class ApplicationEntriesDB extends DBManager<ApplicationEntry> {

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
                + "	id text PRIMARY KEY,\n"
                + "	name text NOT NULL,\n"
                + "	description text,\n"
                + "	groupId text,\n"
                + "	payload text,\n"
                + "	uts bigint,\n"
                + "	mts bigint\n"
                + ");" :

                "CREATE TABLE IF NOT EXISTS applications (\n"
                        + "	id text PRIMARY KEY,\n"
                        + "	name text NOT NULL,\n"
                        + "	description text NOT NULL,\n"
                        + "	groupId text,\n"
                        + "	payload text,\n"
                        + "	uts integer,\n"
                        + "	mts integer\n"
                        + ");";
        String indexs = "CREATE INDEX IF NOT EXISTS application_name_idx ON applications (name);\n";


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
