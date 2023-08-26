package com.scaleguard.server.http.router;

import java.util.HashMap;
import java.util.Map;

public class TargetSystem {

  private String host;
  private String port;
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
