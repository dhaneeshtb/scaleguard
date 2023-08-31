package com.scaleguard.server.http.cache;

public interface CacheManager {
     CachedResponse lookup(RequestCacheInfo info,String key);
          void save(RequestCacheInfo info,String key, Object msg);
     void saveFresh(RequestCacheInfo info,String key, Object msg);

}
