package com.scaleguard.server.http.metering.inmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.http.metering.ApiData;
import com.scaleguard.server.http.metering.MetricRequest;
import com.scaleguard.server.http.metering.MetricResponse;
import com.scaleguard.server.http.metering.MetricsService;
import com.scaleguard.server.http.router.SourceSystem;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.time.LocalDateTime;
import java.time.temporal.TemporalUnit;
import java.util.Map;

public class InMemoryMetricsService implements MetricsService {

    private final MinuteMetrics metrics = new MinuteMetrics();

    @Override
    public void handleMetricsRequest(ChannelHandlerContext ctx, SourceSystem sourceSystem) {
        ByteBuf content = Unpooled.copiedBuffer(findRequestMetrics(10), CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                //It is successful here, and does not represent the success of the customer, and brush out the data success default representative has completed
                if (future.isSuccess()) {
                    ctx.channel().read();
                } else {
                    future.channel().close();
                }
            }
        });
    }

    @Override
    public MetricResponse getMetrics(MetricRequest metricRequest) {
        return metrics.getMetrics(metricRequest);
    }

    public String findRequestMetrics(int lastMinutes) {
        try {
            Map<LocalDateTime, MetricsEntry> allMetrics = metrics.getAllMetrics();
            return new ObjectMapper().writeValueAsString(allMetrics);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    public void process(ApiData apiData) {
        metrics.addRequest(apiData);
    }

    public void deleteMetrics(int timeToKeepData, TemporalUnit temporalUnit) {
        metrics.deleteMetrics(timeToKeepData, temporalUnit);
    }


}
