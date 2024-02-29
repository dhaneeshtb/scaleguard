package com.scaleguard.server.db;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.licencing.licensing.LicenceManager;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class SystemPropertyDB extends DBManager<SystemProperty>{

    private static boolean isInited = false;

    private SystemPropertyDB(){
        super("system_properties");
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SystemPropertyDB _db = new SystemPropertyDB();

    public static SystemPropertyDB getInstance(){
        return _db;
    }


    public void init() throws Exception {
        if (isInited) {
            return;
        }
//        String url = "jdbc:sqlite:db/" + LicenceUtil.getMACId();
        String sql =ConnectionUtil.isPostgres()? "CREATE TABLE IF NOT EXISTS system_properties (\n"
                + "	id text PRIMARY KEY,\n"
                + "	name text NOT NULL,\n"
                + "	groupname text,\n"
                + "	value text,\n"
                + "	uts bigint,\n"
                + "	mts bigint\n"
                + ");":

                "CREATE TABLE IF NOT EXISTS system_properties (\n"
                + "	id text PRIMARY KEY,\n"
                + "	name text NOT NULL,\n"
                + "	groupname text,\n"
                + "	value text,\n"
                + "	uts integer,\n"
                + "	mts integer\n"
                + ");";
        String indexs ="CREATE INDEX IF NOT EXISTS system_group_name_idx ON system_properties (groupname);\n" +
                "CREATE INDEX IF NOT EXISTS system_idx ON system_properties (id);\n" +
                "CREATE INDEX IF NOT EXISTS system_name_idx ON system_properties (name);";


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
