package com.scaleguard.server.http.utils;

import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HttpUtils {

    public static Map<String, List<String>> getParameters(String uri) {
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(uri);
        return queryStringDecoder.parameters();
    }

    public static Map<String, String> getParametersAsString(String uri) {
        Map<String, List<String>> parameters = getParameters(uri);
        return parameters.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> toString(entry.getValue())));
    }

    public static <T> T getParametersAs(String uri, Class<T> targetClass) {
        Map<String, String> parametersAsString = getParametersAsString(uri);
        return JsonUtils.convert(parametersAsString, targetClass);
    }

    private static String toString(List<String> parameterValue) {
        if (parameterValue == null) {
            return null;
        }
        return String.join(",", parameterValue);
    }

}
