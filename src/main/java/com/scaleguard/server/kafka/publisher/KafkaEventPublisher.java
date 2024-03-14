package com.scaleguard.server.kafka.publisher;

import com.scaleguard.server.kafka.models.StreamingRawData;
import com.scaleguard.server.kafka.producer.ProducerCreator;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class KafkaEventPublisher implements Publisher<StreamingRawData> {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private final String brokers;
    Producer<String, StreamingRawData> producer;

    public KafkaEventPublisher(String brokers){
        this.brokers = brokers;
        producer = ProducerCreator.createProducerIntegrations(this.brokers);
    }

    @Override
    public void publish(String topic, StreamingRawData sd) {
        final ProducerRecord<String, StreamingRawData> streamingRecord = new ProducerRecord<>(topic,
                sd.getRequestId(),
                sd);
        try {
            producer.send(streamingRecord);
        } catch (Exception e) {
            log.error("stacktrace", e);
        }

    }

    @Override
    public void publish(String topic, StreamingRawData sd, int partitionCount) {

        //Handle
    }
}
