package com.scaleguard.server.http.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class RouteLogger {
    private static final Logger logger
            = LoggerFactory.getLogger(RouteLogger.class);

    static public class RouteStats{

        private String key;

        RouteStats(String key){
            this.key=key;
        }

        private AtomicLong total=new AtomicLong(0);

        public AtomicLong getTotal() {
            return total;
        }

        public void setTotal(AtomicLong total) {
            this.total = total;
        }

        private AtomicLong count=new AtomicLong(0);

        private double averageRT;

        private long minTime=Long.MAX_VALUE;

        private long maxTime;

        public AtomicLong getCount() {
            return count;
        }

        public void setCount(AtomicLong count) {
            this.count = count;
        }

        public double getAverageRT() {
            return averageRT;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public void setAverageRT(double averageRT) {
            this.averageRT = averageRT;
        }

        public long getMinTime() {
            return minTime;
        }

        public void setMinTime(long minTime) {
            this.minTime = minTime;
        }

        public long getMaxTime() {
            return maxTime;
        }

        public void setMaxTime(long maxTime) {
            this.maxTime = maxTime;
        }
        public synchronized void addRT(long repTime){
            long totalRequests =getCount().incrementAndGet();
            long ct = getTotal().addAndGet(repTime);
            setAverageRT(ct/totalRequests);
            if(minTime>repTime){
                minTime=repTime;
            }
            if(maxTime<repTime){
                maxTime=repTime;
            }
        }
    }

    private static Map<String,RouteStats> routeStatsMap = new ConcurrentHashMap<>();

    public static void log(RouteTarget rt){
        log(rt,false);
    }
    public static void log(RouteTarget rt,boolean isCached){
        String key =isCached?rt.getSourceSystem().getId()+":"+rt.getTargetSystem().getId()+":cached": rt.getSourceSystem().getId()+":"+rt.getTargetSystem().getId()+":"+rt.getTargetHost();
        long repTime =System.currentTimeMillis()-rt.getStartTime();
        RouteStats rs = routeStatsMap.computeIfAbsent(key,k->new RouteStats(k));
        rs.addRT(repTime);
        logger.debug(key+" : "+(repTime)+" Average:"+rs.getAverageRT()+": Min=>"+rs.getMinTime()+",Max=>"+rs.getMaxTime());
    }

    public static JsonNode toStatsJson(){
        ArrayNode an =LocalSystemLoader.mapper.createArrayNode();
        routeStatsMap.values().stream().forEach(r->{
            ObjectNode on =LocalSystemLoader.mapper.createObjectNode();
            on.put("key",r.getKey());
            on.put("total",r.getCount().get());
            on.put("max",r.getMaxTime());
            on.put("min",r.getMinTime());
            on.put("avg",r.getAverageRT());
            an.add(on);
        });
        return an;
    }
}
