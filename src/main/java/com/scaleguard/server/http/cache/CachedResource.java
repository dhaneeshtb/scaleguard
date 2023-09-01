package com.scaleguard.server.http.cache;
//{"pattern":"/","method":"get",keyLookupType:"hash","keyLookupParameters":[],"keyLookupClass":""
public class CachedResource {
    private String pattern;
    private String method;

    public boolean isCached() {
        return cached;
    }

    public void setCached(boolean cached) {
        this.cached = cached;
    }

    private boolean cached;

    private boolean async;

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    private String keyLookupType;
    private String[] keyLookupHeaders;

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

    public String[] getKeyLookupHeaders() {
        return keyLookupHeaders;
    }

    public void setKeyLookupHeaders(String[] keyLookupHeaders) {
        this.keyLookupHeaders = keyLookupHeaders;
    }

    public String getKeyLookupClass() {
        return keyLookupClass;
    }

    public void setKeyLookupClass(String keyLookupClass) {
        this.keyLookupClass = keyLookupClass;
    }

    private String keyLookupClass;
}
