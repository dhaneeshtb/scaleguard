package com.scaleguard.server.http.router;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SourceSystem {

  private String host;
  private String port;
  private String scheme;
  private String basePath;
  private String id;
  private String name;
  private String groupId;
  private String target;//instance id or groupid
  private boolean async;
  private String callbackId;
  private String jwtKeylookup;

  public Map<String, List<String>> getKeyLookupMap() {
    return keyLookupMap;
  }

  public void setKeyLookupMap(
      Map<String, List<String>> keyLookupMap) {
    this.keyLookupMap = keyLookupMap;
  }

  private Map<String, List<String>> keyLookupMap;

  public String getJwtKeylookup() {
    return jwtKeylookup;
  }

  public void setJwtKeylookup(String jwtKeylookup) {
    this.jwtKeylookup = jwtKeylookup;
    if(jwtKeylookup!=null) {
      keyLookupMap=new HashMap<>();
      Arrays.stream(jwtKeylookup.split(";")).forEach(s-> {
        String[] tups =s.split(":");
        keyLookupMap.put(tups[0], Arrays.asList(tups[1].split(",")));
      });
    }
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public String getPort() {
    return port;
  }

  public void setPort(String port) {
    this.port = port;
  }

  public String getScheme() {
    return scheme;
  }

  public void setScheme(String scheme) {
    this.scheme = scheme;
  }

  public String getBasePath() {
    return basePath;
  }

  public void setBasePath(String basePath) {
    this.basePath = basePath;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getGroupId() {
    return groupId;
  }

  public void setGroupId(String groupId) {
    this.groupId = groupId;
  }

  public boolean isAsync() {
    return async;
  }

  public void setAsync(boolean async) {
    this.async = async;
  }

  public String getCallbackId() {
    return callbackId;
  }

  public void setCallbackId(String callbackId) {
    this.callbackId = callbackId;
  }

  public String getTarget() {
    return target;
  }

  public void setTarget(String target) {
    this.target = target;
  }
}
