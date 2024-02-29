package com.scaleguard.server.http.cache;

import com.scaleguard.server.http.router.TargetSystem;

import java.io.Serializable;

public interface CacheManager {
     CachedResponse lookup(TargetSystem info, String key);
          void save(TargetSystem info,String key, Object msg);
     void saveFresh(TargetSystem info,String key, Object msg);

     class TimedCacheElement implements Serializable{

          private long cacheTime;

          //In seconds
          private int expiry;

          private Object message;

          public long getCacheTime() {
               return cacheTime;
          }

          public void setCacheTime(long cacheTime) {
               this.cacheTime = cacheTime;
          }

          public int getExpiry() {
               return expiry;
          }

          public void setExpiry(int expiry) {
               this.expiry = expiry;
          }

          public Object getMessage() {
               return message;
          }

          public void setMessage(Object message) {
               this.message = message;
          }
     }
}
