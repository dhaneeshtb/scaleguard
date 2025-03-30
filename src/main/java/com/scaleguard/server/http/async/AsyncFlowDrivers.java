package com.scaleguard.server.http.async;

import com.scaleguard.server.application.AsyncEngines;
import com.scaleguard.server.http.cache.ProxyRequest;
import com.scaleguard.server.http.cache.ProxyResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AsyncFlowDrivers {

    private static Map<String,AsyncFlowDriver> flowDriverMap=new ConcurrentHashMap<>();
    public static interface AsyncFlowDriver{
         ProxyResponse publish(ProxyRequest pr);
    }

    public static AsyncFlowDriver get(String name){
      AsyncEngines.WrappedAsyncEngineRecord war= AsyncEngines.get().get(name);
      if(war!=null){
          String key = war.getName()+":"+war.getPayload().get("topic").asText();
          return flowDriverMap.computeIfAbsent(key,(k)->{
              AsyncFlowDriver af;
              if (war.getType().equals("kafka")) {
                  af = new KafkaAsyncFlowDriver(war);
              } else {
                  af = new EmbeddedAsyncFlowDriver(war);
              }
              return af;
          });
      }
      return null;
    }


}
