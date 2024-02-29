package com.scaleguard.server.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class HostGroupsDB extends DBManager<DBModelSystem> {

    private static boolean isInited = false;

    private HostGroupsDB() {
        super("host_groups");
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static HostGroupsDB _db = new HostGroupsDB();

    public static HostGroupsDB getInstance() {
        return _db;
    }


    public void init() throws Exception {
        if (isInited) {
            return;
        }
//        String url = "jdbc:sqlite:db/" + LicenceUtil.getMACId();
        String sql = ConnectionUtil.isPostgres() ? "CREATE TABLE IF NOT EXISTS host_groups (\n"
                + "	id text PRIMARY KEY,\n"
                + "	name text NOT NULL,\n"
                + "	groupId text,\n"
                + "	target text,\n"
                + "	payload text,\n"
                + "	uts bigint,\n"
                + "	mts bigint\n"
                + ");" :

                "CREATE TABLE IF NOT EXISTS host_groups (\n"
                        + "	id text PRIMARY KEY,\n"
                        + "	name text NOT NULL,\n"
                        + "	groupId text,\n"
                        + "	target text,\n"
                        + "	payload text,\n"
                        + "	value text,\n"
                        + "	uts integer,\n"
                        + "	mts integer\n"
                        + ");";
        String indexs = "CREATE INDEX IF NOT EXISTS system_group_id_idx ON host_groups (groupId);\n" +
                "CREATE INDEX IF NOT EXISTS system_idx ON host_groups (id);\n" +
                "CREATE INDEX IF NOT EXISTS system_target_idx ON host_groups (target);\n" +
                "CREATE INDEX IF NOT EXISTS system_name_idx ON host_groups (name);";


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
