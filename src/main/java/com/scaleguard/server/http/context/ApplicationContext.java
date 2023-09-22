package com.scaleguard.server.http.context;

import com.scaleguard.server.http.metering.ApiDataProcessor;
import com.scaleguard.server.http.metering.MetricsFactory;

import java.util.HashMap;
import java.util.Map;

public class ApplicationContext {

    private static final Map<Class<?>, Object> CONTEXT_HOLDER = new HashMap<>();


    public static void init() {
        CONTEXT_HOLDER.put(ApiDataProcessor.class, new ApiDataProcessor());
        CONTEXT_HOLDER.put(MetricsFactory.class, new MetricsFactory());
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> serviceClass) {
        Object service = CONTEXT_HOLDER.get(serviceClass);
        if (service == null) {
            throw new IllegalArgumentException("Could not find service for class:" + serviceClass);
        }
        return (T) service;
    }

}
