package com.scaleguard.server.http.metering;

import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ApiDataProcessor {

    public static final AttributeKey<ApiData> API_DATA_ATTRIBUTE_KEY = AttributeKey.valueOf("apiData");

    public ApiData parse(Object request) {
        if (request instanceof FullHttpRequest) {
            return parse((FullHttpRequest) request);
        }
        return new ApiData();
    }


    public ApiData updateResponseInformation(ApiData apiData, FullHttpResponse fullHttpResponse) {
        HttpResponseStatus status = fullHttpResponse.status();
        apiData.setStatus(status.code());
        apiData.setEnded(LocalDateTime.now(ZoneOffset.UTC));
        return apiData;
    }


    public ApiData parse(FullHttpRequest request) {
        ApiData apiData = new ApiData();
        apiData.setPath(request.uri().split("\\?")[0]);
        HttpMethod method = request.method();
        apiData.setMethod(method == null ? null : method.name());
        apiData.setHeaders(parseHeaders(request));
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
        Map<String, List<String>> parameters = queryStringDecoder.parameters();
        Map<String, String> requestParams = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (!values.isEmpty()) {
                requestParams.put(key, values.get(0));
            }
        }
        apiData.setRequestParams(requestParams);

        String requestId = generateRequestId();
        apiData.setId(requestId);

        LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC);
        apiData.setStarted(startTime);

        return apiData;
    }

    private Map<String, String> parseHeaders(FullHttpRequest httpRequest) {
        HttpHeaders headers = httpRequest.headers();
        Map<String, String> headerMap = new HashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers) {
                headerMap.put(entry.getKey(), entry.getValue());
            }
        }
        return headerMap;
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }
}
