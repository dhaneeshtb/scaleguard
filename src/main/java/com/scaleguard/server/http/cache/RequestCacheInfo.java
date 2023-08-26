package com.scaleguard.server.http.cache;

public class RequestCacheInfo {
  private String pattern;
  private String method;
  private String keyLookupType="hash";//hash/parameters/custom
  private String[] keyLookupParameters;
  private String keyLookupClass;

  public String getPattern() {
    return pattern;
  }

  public void setPattern(String pattern) {
    this.pattern = pattern;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public String getKeyLookupType() {
    return keyLookupType;
  }

  public void setKeyLookupType(String keyLookupType) {
    this.keyLookupType = keyLookupType;
  }

  public String[] getKeyLookupParameters() {
    return keyLookupParameters;
  }

  public void setKeyLookupParameters(String[] keyLookupParameters) {
    this.keyLookupParameters = keyLookupParameters;
  }

  public String getKeyLookupClass() {
    return keyLookupClass;
  }

  public void setKeyLookupClass(String keyLookupClass) {
    this.keyLookupClass = keyLookupClass;
  }
}
