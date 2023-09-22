package com.scaleguard.server.http.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


    public static <T, R> R convert(T input, Class<R> targetClass) {
        return OBJECT_MAPPER.convertValue(input, targetClass);
    }

    public static String stringify(Object anyObject) {
        try {
            return OBJECT_MAPPER.writeValueAsString(anyObject);
        } catch (JsonProcessingException e) {
            throw new JsonException(e);
        }
    }

    private static class JsonException extends RuntimeException {

        public JsonException() {
            super();
        }

        public JsonException(String message) {
            super(message);
        }

        public JsonException(String message, Throwable cause) {
            super(message, cause);
        }

        public JsonException(Throwable cause) {
            super(cause);
        }

        protected JsonException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
            super(message, cause, enableSuppression, writableStackTrace);
        }
    }


}
