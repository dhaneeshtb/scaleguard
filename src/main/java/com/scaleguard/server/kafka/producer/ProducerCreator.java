package com.scaleguard.server.kafka.producer;

import com.scaleguard.server.kafka.constants.IKafkaConstants;
import com.scaleguard.server.kafka.models.StreamingRawData;
import com.scaleguard.server.kafka.serializer.CustomIntegrationSerializer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

public class ProducerCreator {

    private ProducerCreator(){}

    private static Properties getCommonProperties() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, IKafkaConstants.KAFKA_BROKERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, IKafkaConstants.KAFKA_DEFAULT_PARTITIONER);
        return props;
    }

    public static Producer<String, StreamingRawData> createProducerIntegrations(String brokers) {
        Properties props = getCommonProperties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, IKafkaConstants.INTEGRATION_CLIENT_ID);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, CustomIntegrationSerializer.class.getName());
        return new KafkaProducer<>(props);
    }


}