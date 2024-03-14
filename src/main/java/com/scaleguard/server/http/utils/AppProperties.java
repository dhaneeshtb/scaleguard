package com.scaleguard.server.http.utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class AppProperties {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppProperties.class);

    private AppProperties(){}
    private static final Properties properties =new Properties();
    static {
        try {
            properties.load(AppProperties.class.getClassLoader().getResourceAsStream("/application.properties"));
        } catch (Exception e) {
            try {
                properties.load(AppProperties.class.getResourceAsStream("/application.properties"));
            } catch (Exception ex) {
                LOGGER.error("Error while loading application.properties",e);
            }
        }
        properties.putAll(System.getenv());
        properties.putAll(System.getProperties());
    }

    public static String get(String key){
        return properties.getProperty(key,"");
    }


}
