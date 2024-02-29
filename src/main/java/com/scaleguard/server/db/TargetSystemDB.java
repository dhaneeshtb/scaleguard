package com.scaleguard.server.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class TargetSystemDB extends DBManager<DBModelSystem> {

    private static boolean isInited = false;

    private TargetSystemDB() {
        super("target_systems");
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TargetSystemDB _db = new TargetSystemDB();

    public static TargetSystemDB getInstance() {
        return _db;
    }


    public void init() throws Exception {
        if (isInited) {
            return;
        }
//        String url = "jdbc:sqlite:db/" + LicenceUtil.getMACId();
        String sql = ConnectionUtil.isPostgres() ? "CREATE TABLE IF NOT EXISTS target_systems (\n"
                + "	id text PRIMARY KEY,\n"
                + "	name text NOT NULL,\n"
                + "	groupId text,\n"
                + "	target text,\n"
                + "	payload text,\n"
                + "	uts bigint,\n"
                + "	mts bigint\n"
                + ");" :

                "CREATE TABLE IF NOT EXISTS target_systems (\n"
                        + "	id text PRIMARY KEY,\n"
                        + "	name text NOT NULL,\n"
                        + "	groupId text,\n"
                        + "	target text,\n"
                        + "	payload text,\n"
                        + "	value text,\n"
                        + "	uts integer,\n"
                        + "	mts integer\n"
                        + ");";
        String indexs = "CREATE INDEX IF NOT EXISTS system_group_id_idx ON target_systems (groupId);\n" +
                "CREATE INDEX IF NOT EXISTS system_idx ON target_systems (id);\n" +
                "CREATE INDEX IF NOT EXISTS system_target_idx ON target_systems (target);\n" +
                "CREATE INDEX IF NOT EXISTS system_name_idx ON target_systems (name);";


        try (Connection connection = ConnectionUtil.getConnection()) {
            if (connection != null) {
                Statement statement = connection.createStatement();
                statement.setQueryTimeout(30);  // set timeout to 30 sec.
                statement.executeUpdate(sql);
                statement.executeUpdate(indexs);
            }
            isInited = true;

        } catch (SQLException e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }


    public static void main(String[] args) throws Exception {
    }
}
