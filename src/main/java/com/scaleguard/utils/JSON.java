package com.scaleguard.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.http.cache.ProxyRequest;
import org.apache.kafka.clients.producer.ProducerRecord;

public class JSON {

    private JSON(){}

    public static <T> T toObject(Class<T> type,String payload){
        try {
            return Constants.OBJECT_MAPPER.readValue(payload,type);
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
    }

    public static ObjectNode object(){
        return Constants.OBJECT_MAPPER.createObjectNode();
    }
    public static ArrayNode array(){
        return Constants.OBJECT_MAPPER.createArrayNode();
    }

    public static byte[] toBytes(Object payload){
        try {
            return Constants.OBJECT_MAPPER.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new GenericServerProcessingException(e);
        }
    }
    public static String toString(Object payload){
        try {
            return Constants.OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new GenericServerProcessingException(e);
        }
    }
    public static JsonNode parse(String payload){
        try {
            return Constants.OBJECT_MAPPER.readTree(payload);
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
    }

    public static JsonNode toJson(Object payload) {
        return Constants.OBJECT_MAPPER.valueToTree(payload);
    }
}
