package com.scaleguard.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class SystemPropertyDB extends DBManager<SystemProperty>{

    private static final Logger LOG = LoggerFactory.getLogger(SystemPropertyDB.class);
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
                + "\tid text PRIMARY KEY,\n"
                + "\tname text NOT NULL,\n"
                + "\tgroupname text,\n"
                + "\tvalue text,\n"
                + "\tuts bigint,\n"
                + "\tmts bigint\n"
                + ");":

                "CREATE TABLE IF NOT EXISTS system_properties (\n"
                + "\tid text PRIMARY KEY,\n"
                + "\tname text NOT NULL,\n"
                + "\tgroupname text,\n"
                + "\tvalue text,\n"
                + "\tuts integer,\n"
                + "\tmts integer\n"
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
            LOG.error("Failed to initialise system_properties table: {}", e.getMessage(), e);
        }
    }



    public static void main(String[] args) throws Exception {
    }

    /** One scope per setting family ("waf.policy.x" -> system_properties/waf), so a rate-limit change doesn't reload the WAF. */
    @Override
    protected java.util.Collection<String> clusterScopes(java.util.Collection<String> ids) {
        java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            int dot = id == null ? -1 : id.indexOf('.');
            scopes.add("system_properties/" + (dot > 0 ? id.substring(0, dot) : "misc"));
        }
        return scopes;
    }
}
