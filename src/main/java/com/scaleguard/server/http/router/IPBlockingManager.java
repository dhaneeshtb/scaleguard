package com.scaleguard.server.http.router;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IPBlockingManager {

    private Map<String, Integer> rateLimitMap = new ConcurrentHashMap<>();

    public void block(String ip){
       int c= rateLimitMap.computeIfAbsent(ip,i->0);
       rateLimitMap.put(ip,c+1);
    }

    public boolean isBlocked(String ip){
       return rateLimitMap.containsKey(ip);
    }

}
