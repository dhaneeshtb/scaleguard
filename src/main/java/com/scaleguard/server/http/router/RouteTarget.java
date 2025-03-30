package com.scaleguard.server.http.router;

import io.netty.handler.codec.http.HttpHeaders;

public class RouteTarget {

    private SourceSystem sourceSystem;

    private TargetSystem targetSystem;

    private String targetHost;

    private String uri;

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    private String clientIp;

    public HttpHeaders getHeaders() {
        return headers;
    }

    public void setHeaders(HttpHeaders headers) {
        this.headers = headers;
    }

    private HttpHeaders headers;

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    private long startTime;

    public RouteTarget(SourceSystem sourceSystem,TargetSystem targetSystem){
        this.sourceSystem=sourceSystem;
        this.targetSystem=targetSystem;
        this.startTime = System.currentTimeMillis();
    }

    public long getStartTime() {
        return startTime;
    }


    public SourceSystem getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(SourceSystem sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public TargetSystem getTargetSystem() {
        return targetSystem;
    }

    public void setTargetSystem(TargetSystem targetSystem) {
        this.targetSystem = targetSystem;
    }
}
