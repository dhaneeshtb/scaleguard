package com.scaleguard.server.http.router;

import java.util.Calendar;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimitManager {

    private  IPBlockingManager ibm;
    private  Map<String, Queue<RateLimit>> rateLimitMap = new ConcurrentHashMap<>();

    private int allowedRate=1000;

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
        Calendar cal = Calendar.getInstance();
        String minKey = cal.get(Calendar.HOUR) + "" + cal.get(Calendar.MINUTE);
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
        Calendar cal = Calendar.getInstance();
        String minKey = cal.get(Calendar.HOUR) + "" + cal.get(Calendar.MINUTE);
        if (rl!=null && rl.getMinuteKey().equalsIgnoreCase(minKey)) {
            if ( (add?rl.getCount().getAndIncrement(): rl.getCount().get()) < allowedRate) {
                return true;
            } else {
                System.out.println("Rate Exceeded " + rl.getMinuteKey() + " " + rl.getCount().get());
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
                    System.out.println("Rate Exceeded..");
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }

    public static class SizeLimitedQueue<E>
            extends LinkedList<E> {

        // Variable which store the
        // SizeLimitOfQueue of the queue
        private int SizeLimitOfQueue;

        // Constructor method for initializing
        // the SizeLimitOfQueue variable
        public SizeLimitedQueue(int SizeLimitOfQueue) {

            this.SizeLimitOfQueue = SizeLimitOfQueue;
        }

        // Override the method add() available
        // in LinkedList class so that it allow
        // addition  of element in queue till
        // queue size is less than
        // SizeLimitOfQueue otherwise it remove
        // the front element of queue and add
        // new element
        @Override
        public boolean add(E o) {

            // If queue size become greater
            // than SizeLimitOfQueue then
            // front of queue will be removed
            while (this.size() == SizeLimitOfQueue) {

                super.remove();
            }
            super.add(o);
            return true;
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
