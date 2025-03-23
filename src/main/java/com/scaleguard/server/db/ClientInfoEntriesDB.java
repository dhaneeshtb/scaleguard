package com.scaleguard.server.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class ClientInfoEntriesDB extends DBManager<ClientInfoEntry> {

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
                + "	id text PRIMARY KEY,\n"
                + "	name text NOT NULL,\n"
                + "	description text,\n"
                + "	appid text NOT NULL,\n"
                + "	clientid text NOT NULL,\n"
                + "	clientsecret text NOT NULL,\n"
                + "	payload text,\n"
                + "	expiry bigint,\n"
                + "	uts bigint,\n"
                + "	mts bigint\n"
                + ");" :

                "CREATE TABLE IF NOT EXISTS clientinfos (\n"
                        + "	id text PRIMARY KEY,\n"
                        + "	name text NOT NULL,\n"
                        + "	description text,\n"
                        + "	appid text NOT NULL,\n"
                        + "	clientid text NOT NULL,\n"
                        + "	clientsecret text NOT NULL,\n"
                        + "	payload text,\n"
                        + "	expiry integer,\n"
                        + "	uts integer,\n"
                        + "	mts integer\n"
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
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }
}
