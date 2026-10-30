package com.scaleguard.server.http.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class RouteLogger {
    private static final Logger logger
            = LoggerFactory.getLogger(RouteLogger.class);

    static public class RouteStats {

        private final String key;

        RouteStats(String key) {
            this.key = key;
        }

        /**
         * LongAdder gives lower contention than AtomicLong under high concurrency:
         * each thread increments its own cell; the sum is computed lazily on read.
         */
        private final LongAdder total = new LongAdder();
        private final LongAdder count = new LongAdder();

        // Min / max are updated via a CAS loop — no lock needed.
        private final AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxTime = new AtomicLong(0L);

        public long getTotal() {
            return total.sum();
        }

        public long getCount() {
            return count.sum();
        }

        public double getAverageRT() {
            long c = count.sum();
            return c == 0 ? 0.0 : (double) total.sum() / c;
        }

        public String getKey() {
            return key;
        }

        public long getMinTime() {
            return minTime.get();
        }

        public long getMaxTime() {
            return maxTime.get();
        }

        /**
         * Lock-free response-time recording.
         * Safe to call from multiple Netty I/O threads simultaneously.
         */
        public void addRT(long repTime) {
            count.increment();
            total.add(repTime);
            // CAS-loop for min
            long prev;
            do {
                prev = minTime.get();
            } while (repTime < prev && !minTime.compareAndSet(prev, repTime));
            // CAS-loop for max
            do {
                prev = maxTime.get();
            } while (repTime > prev && !maxTime.compareAndSet(prev, repTime));
        }
    }

    private static Map<String,RouteStats> routeStatsMap = new ConcurrentHashMap<>();

    /** The stats bucket for this route + backend, for callers that log many responses for the same pair. */
    public static RouteStats stats(RouteTarget rt) {
        String key = rt.getSourceSystem().getId()+":"+rt.getTargetSystem().getId()+":"+rt.getTargetHost();
        return routeStatsMap.computeIfAbsent(key, RouteStats::new);
    }

    public static void log(RouteTarget rt){
        log(rt,false);
    }
    public static void log(RouteTarget rt,boolean isCached){
        String key =isCached?rt.getSourceSystem().getId()+":"+rt.getTargetSystem().getId()+":cached": rt.getSourceSystem().getId()+":"+rt.getTargetSystem().getId()+":"+rt.getTargetHost();
        long repTime =System.currentTimeMillis()-rt.getStartTime();
        RouteStats rs = routeStatsMap.computeIfAbsent(key,k->new RouteStats(k));
        rs.addRT(repTime);
        if (logger.isDebugEnabled()) {
            logger.debug(key+" : "+(repTime)+" Average:"+rs.getAverageRT()+": Min=>"+rs.getMinTime()+",Max=>"+rs.getMaxTime());
        }
    }

    public static JsonNode toStatsJson(){
        ArrayNode an =LocalSystemLoader.mapper.createArrayNode();
        routeStatsMap.values().stream().forEach(r->{
            ObjectNode on =LocalSystemLoader.mapper.createObjectNode();
            on.put("key",r.getKey());
            on.put("total",r.getCount());
            on.put("max",r.getMaxTime());
            on.put("min",r.getMinTime());
            on.put("avg",r.getAverageRT());
            an.add(on);
        });
        return an;
    }
}
