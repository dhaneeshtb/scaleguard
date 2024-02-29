package com.scaleguard.server.http.utils;
import java.util.Properties;

public class AppProperties {
    private static Properties appProperties=new Properties();
    static {
        try {
            appProperties.load(AppProperties.class.getClassLoader().getResourceAsStream("/application.properties"));
        } catch (Exception e) {
            try {
                appProperties.load(AppProperties.class.getResourceAsStream("/application.properties"));
            } catch (Exception ex) {
                e.printStackTrace();
            }
        }
        System.getenv().forEach((k,v)->appProperties.put(k,v));
        System.getProperties().forEach((k,v)->appProperties.put(k,v));
    }

    public static String get(String key){
        return appProperties.getProperty(key,"");
    }


}
