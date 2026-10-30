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
    if (hostGroups == null || hostGroups.isEmpty()) {
      return null;
    }
    return BestHostSelector.getBestHost(hostGroups, BestHostSelector.parseStrategy(lbStrategy));
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

  private String lbStrategy;

  /*
   * Upstream behaviour, after nginx's proxy_* directives. null or <= 0 = the server default.
   *   connectTimeoutMs   proxy_connect_timeout       (default scaleguard.connectTimeoutMs, 5000)
   *   readTimeoutSeconds proxy_read_timeout          (default scaleguard.readTimeoutSeconds, 60) → 504 when exceeded
   *   retries            proxy_next_upstream_tries−1 (connect failures retried on another backend; default 1)
   *   tlsVerify          proxy_ssl_verify            (default false, as in nginx)
   *   tlsServerName      proxy_ssl_name              (SNI / verified name; default the backend's host)
   */
  private Integer connectTimeoutMs;
  private Integer readTimeoutSeconds;
  private Integer retries;
  private Boolean tlsVerify;
  private String tlsServerName;

  public Integer getConnectTimeoutMs() { return connectTimeoutMs; }
  public void setConnectTimeoutMs(Integer v) { this.connectTimeoutMs = v; }
  public Integer getReadTimeoutSeconds() { return readTimeoutSeconds; }
  public void setReadTimeoutSeconds(Integer v) { this.readTimeoutSeconds = v; }
  public Integer getRetries() { return retries; }
  public void setRetries(Integer v) { this.retries = v; }
  public Boolean getTlsVerify() { return tlsVerify; }
  public void setTlsVerify(Boolean v) { this.tlsVerify = v; }
  public String getTlsServerName() { return tlsServerName; }
  public void setTlsServerName(String v) { this.tlsServerName = v; }

  private static final int DEFAULT_CONNECT_MS = Integer.getInteger("scaleguard.connectTimeoutMs", 5_000);
  private static final int DEFAULT_READ_S = Integer.getInteger("scaleguard.readTimeoutSeconds", 60);
  private static final int DEFAULT_RETRIES = Integer.getInteger("scaleguard.connectRetries", 1);

  public int connectTimeoutMillis() { return connectTimeoutMs != null && connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_MS; }
  public long readTimeoutMillis() { return (readTimeoutSeconds != null && readTimeoutSeconds > 0 ? readTimeoutSeconds : DEFAULT_READ_S) * 1000L; }
  /** Connect retries on another backend; a negative value means the default, 0 turns retries off. */
  public int connectRetries() { return retries != null && retries >= 0 ? retries : DEFAULT_RETRIES; }

  public String getLbStrategy() {
    return lbStrategy;
  }

  public void setLbStrategy(String lbStrategy) {
    this.lbStrategy = lbStrategy;
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
