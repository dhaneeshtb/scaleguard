package com.scaleguard.server.http.router;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/***
 * in case of kafka
 * scheme : kafka
 * path: topicname
 * groupid: appid
 * 
 */
public class SourceSystem {

  private String host;

  private boolean tunnel;

  public boolean isTunnel() {
    return tunnel;
  }

  public void setTunnel(boolean tunnel) {
    this.tunnel = tunnel;
  }

  private String port;
  private String scheme;
  private String basePath;
  private String id;
  private String name;
  private String groupId;
  private String target;//instance id or groupid
  private boolean async;

  private String asyncEngine;

  public String getAsyncEngine() {
    return asyncEngine;
  }

  public void setAsyncEngine(String asyncEngine) {
    this.asyncEngine = asyncEngine;
  }

  private String callbackId;
  private String jwtKeylookup;

  private String certificateId;

  public String getSecappid() {
    return secappid;
  }

  public void setSecappid(String secappid) {
    this.secappid = secappid;
  }

  private String secappid;

  /** Scopes an access token must carry on this route (space or comma separated); blank = any token for the app. */
  private String oauthScopes;

  public String getOauthScopes() { return oauthScopes; }
  public void setOauthScopes(String oauthScopes) { this.oauthScopes = oauthScopes; }

  public boolean isAutoProcure() {
    return autoProcure;
  }

  public void setAutoProcure(boolean autoProcure) {
    this.autoProcure = autoProcure;
  }

  private boolean autoProcure=false;

  public String getCertificateId() {
    return certificateId;
  }

  public void setCertificateId(String certificateId) {
    this.certificateId = certificateId;
  }

  public TargetSystem getTargetSystem() {
    return targetSystem;
  }

  public void setTargetSystem(TargetSystem targetSystem) {
    this.targetSystem = targetSystem;
  }

  private TargetSystem targetSystem;

  public String getHeaderLookup() {
    return headerLookup;
  }

  public void setHeaderLookup(String headerLookup) {
    setJwtKeylookup(headerLookup);
  }

  private String headerLookup;

  public Map<String, List<String>> getKeyLookupMap() {
    return keyLookupMap;
  }

  public void setKeyLookupMap(
      Map<String, List<String>> keyLookupMap) {
    this.keyLookupMap = keyLookupMap;
  }

  private Map<String, List<String>> keyLookupMap=new HashMap<>();

  public String getJwtKeylookup() {
    return jwtKeylookup;
  }

  public void setJwtKeylookup(String jwtKeylookup) {
    this.jwtKeylookup = jwtKeylookup;
    if(jwtKeylookup!=null) {
      Arrays.stream(jwtKeylookup.split(";")).forEach(s-> {
        String[] tups =s.split(":");
        if(tups.length==2) {
          keyLookupMap.put(tups[0], Arrays.asList(tups[1].split(",")));
        }
      });
    }
  }

  /**
   * Per-route rate limit in requests per minute per client.
   * null or negative = use the global setting; 0 = unlimited for this route.
   */
  private Integer rateLimit;

  /*
   * Client-facing protocols for this route, like nginx's per-server "http2 on", "http3 on" and "grpc_pass".
   * null = on (the default); false = off. HTTP/1.1 is always available.
   */
  private Boolean http2;
  private Boolean http3;
  private Boolean grpc;

  public Boolean getHttp2() { return http2; }
  public void setHttp2(Boolean http2) { this.http2 = http2; }
  public Boolean getHttp3() { return http3; }
  public void setHttp3(Boolean http3) { this.http3 = http3; }
  public Boolean getGrpc() { return grpc; }
  public void setGrpc(Boolean grpc) { this.grpc = grpc; }

  /** HTTP/2 (ALPN h2 over TLS, h2c with prior knowledge on plain HTTP) is accepted for this route. */
  public boolean http2Enabled() { return !Boolean.FALSE.equals(http2); }
  /** HTTP/3 over QUIC is offered (Alt-Svc) and accepted for this route; HTTPS routes only. */
  public boolean http3Enabled() { return !Boolean.FALSE.equals(http3) && "https".equalsIgnoreCase(scheme); }
  /** gRPC calls are proxied end to end over HTTP/2; needs HTTP/2. */
  public boolean grpcEnabled() { return !Boolean.FALSE.equals(grpc) && http2Enabled(); }

  /*
   * Per-route IP access, like nginx "allow …; deny …;" in a server block. Comma-separated IPs / CIDRs.
   * ipAllow non-empty = only these clients may use the route; ipDeny = refused. Checked after the gateway blocklist.
   */
  private String ipAllow;
  private String ipDeny;
  private transient volatile String compiledAllowSrc, compiledDenySrc;
  private transient volatile com.scaleguard.server.http.utils.CidrSet compiledAllow, compiledDeny;

  public String getIpAllow() { return ipAllow; }
  public void setIpAllow(String v) { this.ipAllow = v; }
  public String getIpDeny() { return ipDeny; }
  public void setIpDeny(String v) { this.ipDeny = v; }

  /** True when this route's allow/deny lists refuse the client (or the connecting peer, for deny). */
  public boolean refusesClient(String peerIp, String clientIp) {
    String d = ipDeny;
    if (d != null && !d.isBlank()) {
      if (!d.equals(compiledDenySrc)) { compiledDeny = com.scaleguard.server.http.utils.CidrSet.parse(d); compiledDenySrc = d; }
      if (compiledDeny.contains(clientIp) || (peerIp != null && compiledDeny.contains(peerIp))) return true;
    }
    String a = ipAllow;
    if (a != null && !a.isBlank()) {
      if (!a.equals(compiledAllowSrc)) { compiledAllow = com.scaleguard.server.http.utils.CidrSet.parse(a); compiledAllowSrc = a; }
      if (!compiledAllow.isEmpty() && !compiledAllow.contains(clientIp)) return true;
    }
    return false;
  }

  public Integer getRateLimit() {
    return rateLimit;
  }

  public void setRateLimit(Integer rateLimit) {
    this.rateLimit = rateLimit;
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
