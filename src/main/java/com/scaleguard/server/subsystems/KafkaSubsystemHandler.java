package com.scaleguard.server.subsystems;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.router.RouteTarget;
import com.scaleguard.server.kafka.producer.ProducerCreator;
import com.scaleguard.utils.JSON;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.UUID;

public class KafkaSubsystemHandler implements SubsystemHandler{

    private final RouteTarget rt;
    Producer<String, JsonNode> publisher;

    public KafkaSubsystemHandler(RouteTarget rt){
        this.rt=rt;
        String brokerURL = rt.getTargetSystem().getHostGroup().getHost()+":"+rt.getTargetSystem().getHostGroup().getPort();
        publisher = ProducerCreator.createAsyncEventProducer(brokerURL,rt.getTargetSystem().getGroupId());
    }
    @Override
    public String publish(RouteTarget rt, JsonNode message) {
        //String topicName = rt.getTargetSystem().getBasePath();
        String topicName = String.join("-",rt.getUri().substring(1,rt.getUri().length()).split("/"));
        if(topicName.length()<3||topicName.length()>20){
            throw new RuntimeException("invalid topic name evaluated "+topicName);
        }
        String id = message.has("id")?message.get("id").asText(): UUID.randomUUID().toString();
        publisher.send(new ProducerRecord<>(topicName,0,
                id,
                message));
        publisher.flush();
        return topicName;
    }
}
