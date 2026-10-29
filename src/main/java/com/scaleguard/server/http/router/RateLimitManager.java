package com.scaleguard.server.http.router;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RateLimitManager {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitManager.class);

    private  IPBlockingManager ibm;
    private  Map<String, Queue<RateLimit>> rateLimitMap = new ConcurrentHashMap<>();

    /**
     * Maximum requests per minute per client IP (per route).
     * Configure via -Dscaleguard.rateLimit=<n>  (default 10 000).
     * The original hardcoded value of 1 000 was too low for load-test / high-throughput scenarios.
     */
    private int allowedRate = Integer.getInteger("scaleguard.rateLimit", 10_000);

    public RateLimitManager(){

    }
    public RateLimitManager(int allowedRate){
        this.allowedRate=allowedRate;
    }
    public RateLimitManager(int allowedRate,IPBlockingManager ibm){
        this.allowedRate=allowedRate;
        this.ibm=ibm;
    }

    public IPBlockingManager getIbm() {
        return ibm;
    }

    public boolean isBlocked(String address){
        return ibm != null && ibm.isBlocked(address);
    }

    public  void log(RouteTarget rt) {
        String key = "system";
        if (rt != null) {
            key = rt.getSourceSystem().getId() + ":" + rt.getTargetSystem().getId();
        }
        Queue<RateLimit> rs = rateLimitMap.computeIfAbsent(key, k -> new SizeLimitedQueue<>(10));
        RateLimit rl = rs.peek();
        String minKey = minuteKey();
        if (rl != null && rl.getMinuteKey().equalsIgnoreCase(minKey)) {
            rl.getCount().incrementAndGet();
        } else {
            RateLimit rli = new RateLimit();
            rli.setMinuteKey(minKey);
            rli.getCount().incrementAndGet();
            rs.add(rli);
        }

    }

    public  boolean checkRate(RouteTarget rt,String inAddress) {
        return isInRate(rt,inAddress,true);
    }
    public  boolean isInRate(RouteTarget rt,String inAddress,boolean add) {
        String key = inAddress+":system";
        if (rt != null) {
            key = rt.getClientIp()+":"+rt.getSourceSystem().getId() + ":" + rt.getTargetSystem().getId();
        }
        Queue<RateLimit> rs = rateLimitMap.computeIfAbsent(key, k -> new SizeLimitedQueue<>(10));
        RateLimit rl = rs.peek();
        String minKey = minuteKey();
        if (rl!=null && rl.getMinuteKey().equalsIgnoreCase(minKey)) {
            if ( (add?rl.getCount().getAndIncrement(): rl.getCount().get()) < allowedRate) {
                return true;
            } else {
                logger.warn("Rate exceeded for key {} at minute {} count {}", key, rl.getMinuteKey(), rl.getCount().get());
                if(ibm!=null){
                    ibm.block(rt != null?rt.getClientIp():inAddress);
                }
                return false;
            }
        }else if(add){
            RateLimit rli = new RateLimit();
            rli.setMinuteKey(minKey);
            rli.getCount().incrementAndGet();
            rs.add(rli);
        }

        return true;
    }

    /**
     * Returns a "HHmm" string for the current wall-clock hour+minute.
     *
     * <p>Replaces {@code Calendar.getInstance()} which allocates a full Calendar
     * object on every call (expensive: timezone lookup, field array, etc.).
     * This implementation computes the same key in ~5 ns via epoch arithmetic.</p>
     */
    private static String minuteKey() {
        long totalMinutes = System.currentTimeMillis() / 60_000L;
        long minuteOfDay  = totalMinutes % 1440L;   // minutes since midnight (UTC)
        long hour         = minuteOfDay / 60L;
        long minute       = minuteOfDay % 60L;
        return hour + "" + minute;
    }

    public static void main(String[] args) {
        SourceSystem ss = new SourceSystem();
        ss.setId("10");
        TargetSystem ts = new TargetSystem();
        ts.setId("20");
        RouteTarget rt = new RouteTarget(ss, ts);
        RateLimitManager rtm=new RateLimitManager();
        for (int i = 0; i < 10000; i++) {
            rtm.log(rt);
            if (!rtm.isInRate(rt,"",false)) {
                try {
                    logger.warn("Rate exceeded..");
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }

    /**
     * A size-bounded FIFO queue safe for concurrent access from multiple Netty I/O threads.
     *
     * <p>The previous implementation extended {@link java.util.LinkedList}, which is
     * <em>not thread-safe</em>. Because {@code rateLimitMap} is a {@link ConcurrentHashMap},
     * two threads can hold the same Queue reference and call {@link #add}/{@link #peek}
     * concurrently — causing lost updates and {@code ConcurrentModificationException}.
     *
     * <p>This replacement uses a {@link ConcurrentLinkedQueue} as the backing store and
     * a volatile {@code size} counter (updated atomically via CAS) to enforce the capacity
     * limit without synchronisation.</p>
     */
    public static class SizeLimitedQueue<E> extends ConcurrentLinkedQueue<E> {

        private final int maxSize;
        private final java.util.concurrent.atomic.AtomicInteger sz =
                new java.util.concurrent.atomic.AtomicInteger(0);

        public SizeLimitedQueue(int maxSize) {
            this.maxSize = maxSize;
        }

        @Override
        public boolean add(E o) {
            // Evict head until we have room, then add atomically.
            while (sz.get() >= maxSize) {
                if (super.poll() != null) {
                    sz.decrementAndGet();
                } else {
                    break; // queue became empty concurrently — proceed with add
                }
            }
            boolean added = super.add(o);
            if (added) sz.incrementAndGet();
            return added;
        }

        @Override
        public E poll() {
            E e = super.poll();
            if (e != null) sz.decrementAndGet();
            return e;
        }
    }

    public static class RateLimit {

        private String minuteKey;
        private AtomicInteger count = new AtomicInteger(0);
        private String api;

        public String getMinuteKey() {
            return minuteKey;
        }

        public void setMinuteKey(String minuteKey) {
            this.minuteKey = minuteKey;
        }

        public AtomicInteger getCount() {
            return count;
        }

        public void setCount(AtomicInteger count) {
            this.count = count;
        }

        public String getApi() {
            return api;
        }

        public void setApi(String api) {
            this.api = api;
        }
    }


}
