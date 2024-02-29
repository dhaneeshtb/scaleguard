package com.scaleguard.server.kafka.partitioner;

import com.scaleguard.server.kafka.constants.IKafkaConstants;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;

import java.util.Map;

public class CustomPartitioner implements Partitioner {

    @Override
    public void configure(Map<String, ?> configs) {

    }

    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
        return Math.abs(key.hashCode()) % IKafkaConstants.PARTITION_COUNT;
    }

    @Override
    public void close() {
    }
}
