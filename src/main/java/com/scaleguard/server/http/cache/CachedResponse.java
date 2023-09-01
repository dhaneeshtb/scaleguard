package com.scaleguard.server.http.cache;


public class CachedResponse {

  public Object response;

  public CachedResource resource;

  public ProxyRequest getProxyRequest() {
    return proxyRequest;
  }

  public void setProxyRequest(ProxyRequest proxyRequest) {
    this.proxyRequest = proxyRequest;
  }

  public ProxyRequest proxyRequest;

  private String key;

  public CachedResource getResource() {
    return resource;
  }

  public void setResource(CachedResource resource) {
    this.resource = resource;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public Object getResponse() {
    return response;
  }

  public void setResponse(Object response) {
    this.response = response;
  }
}
