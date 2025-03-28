package com.scaleguard.server.http.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
                            if(hg.getHealth()!=null && !hg.getHealth().isEmpty()) {
                                int resp = getResponse(hg.getHealth());
                                if (resp != 200) {
                                    hg.setReachable(false);
                                    logger.error("Lost connection {}", hg.getHealth());
                                } else {
                                    hg.setReachable(true);
                                }
                            }else{
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
                    Thread.sleep(20000);
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
        try {
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    result.append(line);
                }
            }
            return conn.getResponseCode();
        }finally {
            conn.disconnect();
        }
    }

    public static String getResponseData(String urlToRead)  {
        StringBuilder result = new StringBuilder();

        try {
           URL url = new URL(urlToRead);
           HttpURLConnection conn = (HttpURLConnection) url.openConnection();
           try {
               conn.setReadTimeout(15000);
               conn.setRequestMethod("GET");
               try (BufferedReader reader = new BufferedReader(
                       new InputStreamReader(conn.getInputStream()))) {
                   for (String line; (line = reader.readLine()) != null; ) {
                       result.append(line);
                   }
               }
           } finally {
               conn.disconnect();
           }
           return result.toString();
       }catch (Exception e){
            e.printStackTrace();
            return  result.toString();
        }
    }

    public static void main(String[] args) {
        AtomicInteger ai = new AtomicInteger(0);
        AtomicInteger aiSuccess = new AtomicInteger(0);
       ExecutorService es =  Executors.newFixedThreadPool(200);
       long start = System.currentTimeMillis();
       for(int i=0;i<1000;i++){
           es.submit(()->{
               for(int j=0;j<100;j++){
                   try {
                       int rs = getResponse("http://localhost:8080/hckeck");
                       if (rs != 200) {
                           ai.incrementAndGet();
                           System.out.println(rs);

                       }else {
                           aiSuccess.incrementAndGet();
                       }
                   } catch (Exception e) {
                       ai.incrementAndGet();
                       e.printStackTrace();

                   }
               }
           });
       }
       es.shutdown();
        try {
            es.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Failure count -> "+ai.get());
        System.out.println("Success count -> "+aiSuccess.get());
        System.out.println("Time taken  -> "+(System.currentTimeMillis()-start));
    }
}
