package com.scaleguard.server.db;


import com.scaleguard.server.licencing.licensing.LicenceUtil;
import org.sqlite.mc.SQLiteMCConfig;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

public class ConnectionUtil {

    public static boolean isPostgres() {
        return postgres;
    }
    private static boolean postgres = new File(LicenceUtil.dataDir()+"/postgres.properties").exists();

    public static Connection getConnection() throws Exception {
        String devId = "scaleguard";
        Class.forName("org.sqlite.JDBC");

        if(isPostgres()){
            try {
                Properties properties = new Properties();
                properties.load(new FileInputStream(LicenceUtil.dataDir() + "/postgres.properties"));
                return DriverManager.getConnection(properties.getProperty("url"), properties.getProperty("username"),properties.getProperty("password"));
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        String connectionURL = "jdbc:sqlite:"+LicenceUtil.dataDir()+"/" + devId+".db";
        long start=System.currentTimeMillis();
        try {

            return DriverManager.getConnection(connectionURL, new SQLiteMCConfig.Builder().withKey(devId).build().toProperties());
        }finally {
            System.out.println("time taken to get connection->"+(System.currentTimeMillis()-start));
        }
    }
}
