package com.scaleguard.server.http.router;

public class RouteTarget {

    private SourceSystem sourceSystem;

    private TargetSystem targetSystem;

    private String targetHost;

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
