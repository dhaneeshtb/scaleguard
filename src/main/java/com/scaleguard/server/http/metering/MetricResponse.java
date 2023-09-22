package com.scaleguard.server.http.metering;

public class MetricResponse {

    private MetricRequest request;

    private long totalRequests;

    private long averageTime;

    public MetricRequest getRequest() {
        return request;
    }

    public MetricResponse setRequest(MetricRequest request) {
        this.request = request;
        return this;
    }

    public long getTotalRequests() {
        return totalRequests;
    }

    public MetricResponse setTotalRequests(long totalRequests) {
        this.totalRequests = totalRequests;
        return this;
    }

    public long getAverageTime() {
        return averageTime;
    }

    public MetricResponse setAverageTime(long averageTime) {
        this.averageTime = averageTime;
        return this;
    }
}
