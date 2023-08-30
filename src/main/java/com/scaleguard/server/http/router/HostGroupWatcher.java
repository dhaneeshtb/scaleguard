package com.scaleguard.server.http.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class HostGroupWatcher {

    private static final Logger logger
            = LoggerFactory.getLogger(HostGroupWatcher.class);

    private final List<HostGroup> allHosts;

    public HostGroupWatcher(List<HostGroup> allHosts){
        this.allHosts=allHosts;
    }
    public void start(){
        Thread t=  new Thread(()->{
            while(true) {
                try {
                    allHosts.forEach(hg -> {
                        try {
                            int resp = getResponse(hg.getHealth());
                            if (resp != 200) {
                                hg.setReachable(false);
                            } else {
                                hg.setReachable(true);
                            }
                        } catch (Exception e) {
                            hg.setReachable(false);
                            logger.error("Error while reaching {}",hg.getHealth());
                        }
                    });
                } catch (Exception e) {
                    logger.error("Error ",e);
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    logger.error("Error ",e);
                }
            }

        });
        t.setDaemon(true);
        t.start();

    }

    public static int getResponse(String urlToRead) throws Exception {
        StringBuilder result = new StringBuilder();
        URL url = new URL(urlToRead);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            for (String line; (line = reader.readLine()) != null; ) {
                result.append(line);
            }
        }
        return conn.getResponseCode();
    }
}
