package com.scaleguard.server.http.cache;

import com.scaleguard.server.http.router.TargetSystem;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryCacheLooker implements CacheManager {

  private Map<String, TimedCacheElement> dataMap = new ConcurrentHashMap<>();

  private static InMemoryCacheLooker routeTable;

  public static synchronized InMemoryCacheLooker getInstance(){
    if(routeTable==null){
      routeTable = new InMemoryCacheLooker();
    }
    return routeTable;
  }

  private InMemoryCacheLooker(){
  }


  @Override
  public CachedResponse lookup(TargetSystem info, String key) {
    TimedCacheElement tce = dataMap.get(key);
    List<Object> dataList;
    if(tce!=null) {
      if((System.currentTimeMillis()-tce.getCacheTime())> tce.getExpiry()*1000){
        dataMap.remove(key);
        dataList=null;
      }else {
        List<Object> dataListCached = (List<Object>) tce.getMessage();
        dataList = dataListCached.stream().map(x -> {
          if (x instanceof ByteBuf) {
            return ((ByteBuf) x).duplicate().retain();
          } else {
            return ((FullHttpResponse) x).duplicate().retain();
          }
        }).collect(Collectors.toList());
      }
    }else{
      dataList=null;
    }
    CachedResponse cr = new CachedResponse();
    cr.setKey(key);
    if(dataList!=null) {
      cr.setResponse(dataList);
    }
    return cr;
  }


  @Override
  public void save(TargetSystem info,String key, Object msg) {
    throw new RuntimeException("");
  }
  @Override
  public void saveFresh(TargetSystem info,String key, Object msg) {
    saveFresh(info, key, msg, 300);
  }

  /** Honours the rule's TTL (seconds); the old fixed 300 s only applies when none is given. */
  @Override
  public void saveFresh(TargetSystem info,String key, Object msg, int ttlSeconds) {
    List<Object> list =new ArrayList<>();
    list.add(msg);

    CacheManager.TimedCacheElement cacheElement=new CacheManager.TimedCacheElement();
    cacheElement.setCacheTime(System.currentTimeMillis());
    cacheElement.setExpiry(ttlSeconds > 0 ? ttlSeconds : 300);
    cacheElement.setMessage(list);
    dataMap.put(key,cacheElement);
  }


}
