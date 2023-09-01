package com.scaleguard.server.http.cache;

import com.scaleguard.server.http.router.RouteTable;
import com.scaleguard.server.http.router.TargetSystem;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryCacheLooker implements CacheManager {

  private ChecksumKey checksumKey = new ChecksumKey();

  private Map<String, List<Object>> dataMap = new ConcurrentHashMap<>();

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
    List<Object> dataList = dataMap.get(key);
    if(dataList!=null) {
      dataList= dataList.stream().map(x ->{
        if(x instanceof ByteBuf){
          return ((ByteBuf) x).duplicate().retain();
        }else{
          return ((FullHttpResponse) x).duplicate().retain();
        }
      }).collect(Collectors.toList());
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
    List<Object> list =  dataMap.getOrDefault(key,new ArrayList<Object>());
    list.add(msg);
    dataMap.put(key,list);
  }
  @Override
  public void saveFresh(TargetSystem info,String key, Object msg) {
    List<Object> list =new ArrayList<Object>();
    list.add(msg);
    dataMap.put(key,list);
  }


}
