package com.scaleguard.server.http.cache;

import com.scaleguard.server.http.router.TargetSystem;

public interface CacheManager {
     CachedResponse lookup(TargetSystem info, String key);
          void save(TargetSystem info,String key, Object msg);
     void saveFresh(TargetSystem info,String key, Object msg);

}
