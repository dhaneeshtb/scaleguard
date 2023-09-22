package com.scaleguard.server.http.metering;

public interface MetricsQueue {

    void push(ApiData apiData);
}
