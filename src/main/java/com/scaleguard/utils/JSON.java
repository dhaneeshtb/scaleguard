package com.scaleguard.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.exceptions.GenericServerProcessingException;

public class JSON {

    private JSON(){}

    public static <T> T toObject(Class<T> type,String payload){
        try {
            return Constants.OBJECT_MAPPER.readValue(payload,type);
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
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
}
