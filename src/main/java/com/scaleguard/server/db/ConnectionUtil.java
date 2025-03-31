package com.scaleguard.server.db;


import com.scaleguard.server.licencing.licensing.LicenceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.mc.SQLiteMCConfig;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

public class ConnectionUtil {

    private static final Logger LOGGER
            = LoggerFactory.getLogger(ConnectionUtil.class);


    public static boolean isPostgres() {
        return postgres;
    }
    private static boolean postgres =false;
    private static boolean init =false;

    static {
        checkDBProperties();
    }
    public static synchronized void checkDBProperties(){
        if(!init) {
            boolean isLocalFileExists = new File("postgres.properties").exists();
            if (isLocalFileExists) {
                copyFile("postgres.properties", LicenceUtil.dataDir() + "/postgres.properties");
            }
            postgres = new File(LicenceUtil.dataDir() + "/postgres.properties").exists();
            init=true;
        }
    }

    private static void copyFile(String sourcePath,String targetPath){
        Path source = Paths.get(sourcePath);  // Source file
        Path destination = Paths.get(targetPath); // Destination folder
        try {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("File copied successfully!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Connection getConnection() throws Exception {
        String devId = "scaleguard";
        Class.forName("org.sqlite.JDBC");

        if(isPostgres()){
            try {
                Properties properties = new Properties();
                properties.load(new FileInputStream(LicenceUtil.dataDir() + "/postgres.properties"));

                Class.forName(properties.getProperty("driver"));

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
            if(LOGGER.isDebugEnabled()) {
                LOGGER.debug("time taken to get connection->{}", (System.currentTimeMillis() - start));
            }
        }
    }
}
