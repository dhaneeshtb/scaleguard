package com.scaleguard.server.http.metering;

import com.scaleguard.server.http.router.SourceSystem;
import io.netty.channel.ChannelHandlerContext;

public interface MetricsService {


    void handleMetricsRequest(ChannelHandlerContext ctx, SourceSystem sourceSystem);

    MetricResponse getMetrics(MetricRequest metricRequest);
}
