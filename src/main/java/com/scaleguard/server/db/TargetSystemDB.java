package com.scaleguard.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class TargetSystemDB extends DBManager<DBModelSystem> {

    private static final Logger LOG = LoggerFactory.getLogger(TargetSystemDB.class);
    private boolean isInited = false;

    private TargetSystemDB() {
        super("target_systems");
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TargetSystemDB targetSystemDB = new TargetSystemDB();

    public static TargetSystemDB getInstance() {
        return targetSystemDB;
    }


    public void init() throws Exception {
        if (isInited) {
            return;
        }
        String sql = ConnectionUtil.isPostgres() ? "CREATE TABLE IF NOT EXISTS target_systems (\n"
                + "\tid text PRIMARY KEY,\n"
                + "\tname text NOT NULL,\n"
                + "\tgroupId text,\n"
                + "\ttarget text,\n"
                + "\tpayload text,\n"
                + "\tuts bigint,\n"
                + "\tmts bigint\n"
                + ");" :

                "CREATE TABLE IF NOT EXISTS target_systems (\n"
                        + "\tid text PRIMARY KEY,\n"
                        + "\tname text NOT NULL,\n"
                        + "\tgroupId text,\n"
                        + "\ttarget text,\n"
                        + "\tpayload text,\n"
                        + "\tvalue text,\n"
                        + "\tuts integer,\n"
                        + "\tmts integer\n"
                        + ");";
        String indexs = "CREATE INDEX IF NOT EXISTS system_group_id_idx ON target_systems (groupId);\n" +
                "CREATE INDEX IF NOT EXISTS system_idx ON target_systems (id);\n" +
                "CREATE INDEX IF NOT EXISTS system_target_idx ON target_systems (target);\n" +
                "CREATE INDEX IF NOT EXISTS system_name_idx ON target_systems (name);";


        try (Connection connection = ConnectionUtil.getConnection(); Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(30);  // set timeout to 30 sec.
                statement.executeUpdate(sql);
                statement.executeUpdate(indexs);
                isInited = true;
        } catch (SQLException e) {
            LOG.error("Failed to initialise target_systems table: {}", e.getMessage(), e);
        }
    }
}
