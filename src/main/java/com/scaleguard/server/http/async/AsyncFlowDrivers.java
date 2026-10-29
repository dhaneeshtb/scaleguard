package com.scaleguard.server.http.async;

import com.scaleguard.server.application.AsyncEngines;
import com.scaleguard.server.http.cache.ProxyRequest;
import com.scaleguard.server.http.cache.ProxyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncFlowDrivers {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncFlowDrivers.class);

    private static final Map<String, AsyncFlowDriver> flowDriverMap = new ConcurrentHashMap<>();
    private static final AtomicBoolean recoveryRun = new AtomicBoolean(false);

    public interface AsyncFlowDriver {
        ProxyResponse publish(ProxyRequest pr);
    }

    public static AsyncFlowDriver get(String name) {
        AsyncEngines.WrappedAsyncEngineRecord war = AsyncEngines.get().get(name);
        if (war != null) {
            String key = war.getName() + ":" + war.getPayload().get("topic").asText();
            AsyncFlowDriver driver = flowDriverMap.computeIfAbsent(key, k -> {
                if ("kafka".equals(war.getType())) {
                    return new KafkaAsyncFlowDriver(war);
                } else {
                    return new EmbeddedAsyncFlowDriver(war);
                }
            });

            // Run startup recovery exactly once after the first driver is resolved
            if (recoveryRun.compareAndSet(false, true)) {
                // Build an engineId → driver view for recovery
                Map<String, AsyncFlowDriver> engineMap = new ConcurrentHashMap<>();
                flowDriverMap.forEach((k, d) -> {
                    AsyncEngines.get().values().stream()
                            .filter(e -> (e.getName() + ":" + e.getPayload().get("topic").asText()).equals(k))
                            .findFirst()
                            .ifPresent(e -> engineMap.put(e.getId(), d));
                });
                // Also register the just-resolved driver by its engine id
                engineMap.put(war.getId(), driver);
                EmbeddedQueueRecovery.recoverPendingEvents(engineMap);
            }

            return driver;
        }
        return null;
    }
}

