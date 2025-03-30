package com.scaleguard.server.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scaleguard.server.http.client.HttpClient;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.server.kafka.models.StreamingRawData;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

public class KafkaEventsConsumer {
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaEventsConsumer.class);
    private final String broker;
    private final String appId;
    private final List<String> topicNames;
    private SourceSystem sourceSystem;

    private int pollCount;

    public KafkaEventsConsumer(String broker,String appId,List<String> topicNames){
        this.broker= broker;
        this.appId=appId;
        this.pollCount = 100;
        this.topicNames=topicNames;
    }

    public KafkaEventsConsumer(SourceSystem ss){
        this.broker= ss.getHost()+":"+ss.getPort();
        this.appId=ss.getGroupId();
        this.pollCount = 100;
        this.topicNames=List.of(ss.getBasePath().split(","));
        this.sourceSystem=ss;
    }

    public KafkaEventsConsumer(String broker,String appId,String topicName){
        this.broker= broker;
        this.appId=appId;
        this.pollCount = 100;
        this.topicNames=List.of(topicName);
    }


    public void onApplicationReady() {
            runConsumer();
    }

    public void runConsumer() {
        new Thread(() -> {
            Consumer<String, String> consumer = ConsumerCreator.createRawDataConsumer(broker,appId,pollCount);
            consumer.subscribe(topicNames);

            while (true) {
                final ConsumerRecords<String, String> consumerRecords = consumer.poll(Duration.ofMillis(1000));
                consumerRecords.forEach(event -> {
                    try {

                       String value =  event.value();

                       StreamingRawData srd = new StreamingRawData();
                       srd.setAppId(appId);
                       srd.setOffset(event.offset());

                       JsonNode node = mapper.readTree(value);
                       ArrayNode arr = null;
                       if(node.isArray()){
                           arr = (ArrayNode) node;
                       }else{
                            arr =mapper.createArrayNode();
                            arr.add(node);
                       }
                       srd.setFeatures(arr);
                       HttpClient.getClient(sourceSystem).send(mapper.writeValueAsString(srd));
                       LOGGER.debug("Record offset {}", event.offset());
                    } catch (Exception e) {
                        LOGGER.error("Discarded due to unsupported message " + event.offset(), e);
                    }
                });

                consumer.commitAsync();
            }
        }).start();
        LOGGER.info("Kafka Event Consumptions Started");
    }


}
