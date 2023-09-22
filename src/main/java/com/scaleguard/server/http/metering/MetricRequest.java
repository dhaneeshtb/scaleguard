package com.scaleguard.server.http.metering;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetricRequest {

    private String path;

    private String target;

    private String method;

    private String status;

    private int interval;

    private String timeUnit;

    public String getPath() {
        return path;
    }

    public MetricRequest setPath(String path) {
        this.path = path;
        return this;
    }

    public String getTarget() {
        return target;
    }

    public MetricRequest setTarget(String target) {
        this.target = target;
        return this;
    }

    public String getMethod() {
        return method;
    }

    public MetricRequest setMethod(String method) {
        this.method = method;
        return this;
    }

    public String getStatus() {
        return status;
    }

    public MetricRequest setStatus(String status) {
        this.status = status;
        return this;
    }

    public int getInterval() {
        return interval;
    }

    public MetricRequest setInterval(int interval) {
        this.interval = interval;
        return this;
    }

    public String getTimeUnit() {
        return timeUnit;
    }

    public MetricRequest setTimeUnit(String timeUnit) {
        this.timeUnit = timeUnit;
        return this;
    }
}
