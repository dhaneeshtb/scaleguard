package com.scaleguard.server.http.router;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.scaleguard.server.http.cache.CachedResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TargetSystem {
  public List<CachedResource> getCachedResources() {
    return cachedResources;
  }

  public void setCachedResources(List<CachedResource> cachedResources) {
    this.cachedResources = cachedResources;
  }

  private List<CachedResource> cachedResources;
  private List<HostGroup> hostGroups;
  private String host;
  private String port;

  public String getHostGroupId() {
    return hostGroupId;
  }

  public void setHostGroupId(String hostGroupId) {
    this.hostGroupId = hostGroupId;
  }

  private String hostGroupId;


  public List<HostGroup> getHostGroups() {
    return hostGroups;
  }


  @JsonIgnore
  public HostGroup  getHostGroup() {
    return BestHostSelector.getBestHost(hostGroups);
  }


  public void setHostGroups(List<HostGroup> hostGroups) {
    this.hostGroups = hostGroups;
  }

  private String scheme;
  private String basePath;
  private String id;
  private String name;
  private String groupId;
  private Map<String, String> includeHeaders = new HashMap<>();
  private Map<String, String> excludeHeaders = new HashMap<>();

  public boolean isEnableCache() {
    return enableCache;
  }

  public void setEnableCache(boolean enableCache) {
    this.enableCache = enableCache;
  }

  private boolean enableCache;

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

  public Map<String, String> getIncludeHeaders() {
    return includeHeaders;
  }

  public void setIncludeHeaders(Map<String, String> includeHeaders) {
    this.includeHeaders = includeHeaders;
  }

  public Map<String, String> getExcludeHeaders() {
    return excludeHeaders;
  }

  public void setExcludeHeaders(Map<String, String> excludeHeaders) {
    this.excludeHeaders = excludeHeaders;
  }
}
