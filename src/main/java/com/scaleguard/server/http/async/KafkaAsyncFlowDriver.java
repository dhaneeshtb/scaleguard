package com.scaleguard.server.http.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scaleguard.server.application.AsyncEngines;
import com.scaleguard.server.http.cache.ProxyRequest;
import com.scaleguard.server.http.cache.ProxyResponse;
import com.scaleguard.server.http.client.HttpClient;
import com.scaleguard.server.kafka.ConsumerCreator;
import com.scaleguard.server.kafka.KafkaEventsConsumer;
import com.scaleguard.server.kafka.models.StreamingRawData;
import com.scaleguard.server.kafka.producer.ProducerCreator;
import com.scaleguard.server.system.SystemManager;
import com.scaleguard.utils.JSON;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.SubmissionPublisher;

public class KafkaAsyncFlowDriver implements AsyncFlowDrivers.AsyncFlowDriver {
    private AsyncEngines.WrappedAsyncEngineRecord engineRecord;
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaEventsConsumer.class);

    public KafkaAsyncFlowDriver(AsyncEngines.WrappedAsyncEngineRecord engineRecord){
        this.engineRecord=engineRecord;
        publisher = ProducerCreator.createAsyncEventProducer(engineRecord.getPayload().get("brokers").asText(),engineRecord.getName());
        runConsumer();
    }

    public void runConsumer() {
        new Thread(() -> {
            Consumer<String, String> consumer = ConsumerCreator.createRawDataConsumer(engineRecord.getPayload().get("brokers").asText(),engineRecord.getName(),5);
            consumer.subscribe(List.of(engineRecord.getPayload().get("topic").asText()));

            while (true) {
                final ConsumerRecords<String, String> consumerRecords = consumer.poll(Duration.ofMillis(1000));
                    consumerRecords.forEach(event -> {
                        try {

                            String value = event.value();
                            try {
                                ProxyResponse proxyResponse= OutboundDispatchUtil.sendRequest(SystemManager.getMapper().readValue(value,ProxyRequest.class));
                                System.out.println(proxyResponse.getResponseBody());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            LOGGER.info("Record value {}", value);
                            LOGGER.info("Record offset {}", event.offset());
                        } catch (Exception e) {
                            LOGGER.error("Discarded due to unsupported message " + event.offset(), e);
                        }
                    });
                consumer.commitAsync();
            }
        }).start();
        LOGGER.info("Kafka Event Consumptions Started");
    }

    Producer<String, JsonNode> publisher;


    public ProxyResponse publish(ProxyRequest pr){
        publisher.send(new ProducerRecord<>(engineRecord.getPayload().get("topic").asText(),0,
                pr.getId(),
                JSON.toJson(pr)));
        publisher.flush();
        ProxyResponse prs = new ProxyResponse();
        prs.setId(pr.getId());
        prs.setStatus("pending");
        return prs;
    }

}
