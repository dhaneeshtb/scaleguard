package com.scaleguard.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class ClientInfoEntriesDB extends DBManager<ClientInfoEntry> {

    private static final Logger LOG = LoggerFactory.getLogger(ClientInfoEntriesDB.class);
    private static ClientInfoEntriesDB _db = new ClientInfoEntriesDB();
    private boolean isInited = false;

    private ClientInfoEntriesDB() {
        super("clientinfos");
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static ClientInfoEntriesDB getInstance() {
        return _db;
    }

    public static void main(String[] args) throws Exception {
    }

    public void init() throws Exception {
        if (isInited) {
            return;
        }
        String sql = ConnectionUtil.isPostgres() ? "CREATE TABLE IF NOT EXISTS clientinfos (\n"
                + "\tid text PRIMARY KEY,\n"
                + "\tname text NOT NULL,\n"
                + "\tdescription text,\n"
                + "\tappid text NOT NULL,\n"
                + "\tclientid text NOT NULL,\n"
                + "\tclientsecret text NOT NULL,\n"
                + "\tpayload text,\n"
                + "\texpiry bigint,\n"
                + "\tuts bigint,\n"
                + "\tmts bigint\n"
                + ");" :

                "CREATE TABLE IF NOT EXISTS clientinfos (\n"
                        + "\tid text PRIMARY KEY,\n"
                        + "\tname text NOT NULL,\n"
                        + "\tdescription text,\n"
                        + "\tappid text NOT NULL,\n"
                        + "\tclientid text NOT NULL,\n"
                        + "\tclientsecret text NOT NULL,\n"
                        + "\tpayload text,\n"
                        + "\texpiry integer,\n"
                        + "\tuts integer,\n"
                        + "\tmts integer\n"
                        + ");";

        String indexs = "CREATE INDEX IF NOT EXISTS client_name_idx ON clientinfos (name);\n" +
                "CREATE INDEX IF NOT EXISTS app_id_name_idx ON clientinfos (appid);\n" +
                "CREATE INDEX IF NOT EXISTS client_id_name_idx ON clientinfos (clientid);\n";


        try (Connection connection = ConnectionUtil.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);  // set timeout to 30 sec.
            statement.executeUpdate(sql);
            statement.executeUpdate(indexs);

            isInited = true;

        } catch (SQLException e) {
            LOG.error("Failed to initialise clientinfos table: {}", e.getMessage(), e);
        }
    }
}
