package com.scaleguard.server.subsystems;

import com.scaleguard.server.application.AsyncEngines;
import com.scaleguard.server.http.async.EmbeddedAsyncFlowDriver;
import com.scaleguard.server.http.async.KafkaAsyncFlowDriver;
import com.scaleguard.server.http.cache.ProxyRequest;
import com.scaleguard.server.http.cache.ProxyResponse;
import com.scaleguard.server.http.router.RouteTarget;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SubsytemHandlers {

    private static Map<String,SubsystemHandler> subsystemHandlerMap=new ConcurrentHashMap<>();
    public static SubsystemHandler get(RouteTarget rt){
          String key = rt.getTargetSystem().getGroupId()+":"+rt.getTargetSystem().getHostGroup().getHost();
          return subsystemHandlerMap.computeIfAbsent(key,(k)-> new KafkaSubsystemHandler(rt));
    }
}
