package com.scaleguard.server.http.async;

import com.scaleguard.server.http.cache.ProxyRequest;
import com.scaleguard.server.http.cache.ProxyResponse;
import okhttp3.*;
import java.io.IOException;
import java.util.Map;

public class OutboundDispatchUtil {
    private static final OkHttpClient client = new OkHttpClient();
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100;

    public static ProxyResponse sendRequest(ProxyRequest request) throws IOException {
        if (request.getHost() == null || request.getPort() == null || request.getUri() == null || request.getMethod() == null) {
            throw new IllegalArgumentException("Host, Port, URI, and Method must not be null");
        }

        String scheme = request.getScheme() != null ? request.getScheme() : "http";
        String url = scheme + "://" + request.getHost() + ":" + request.getPort() + request.getUri();

        RequestBody requestBody = (request.getBody() != null && !request.getMethod().equalsIgnoreCase("GET"))
                ? RequestBody.create(request.getBody(), MediaType.parse("application/json"))
                : null;

        Request.Builder requestBuilder = new Request.Builder().url(url);

        switch (request.getMethod().toUpperCase()) {
            case "POST":
                requestBuilder.post(requestBody);
                break;
            case "PUT":
                requestBuilder.put(requestBody);
                break;
            case "DELETE":
                requestBuilder.delete(requestBody);
                break;
            case "GET":
                requestBuilder.get();
                break;
            default:
                throw new IllegalArgumentException("Unsupported HTTP method: " + request.getMethod());
        }

        // Add headers if available
        if (request.getHeaders() != null) {
            for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }

        Request httpRequest = requestBuilder.build();
        ProxyResponse proxyResponse = new ProxyResponse();
        proxyResponse.setId(request.getId());
        proxyResponse.setGroupId(request.getGroupId());

        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try (Response response = client.newCall(httpRequest).execute()) {
                proxyResponse.setResponseCode(response.code());
                proxyResponse.setStatus(response.isSuccessful() ? "success" : "failed");
                proxyResponse.setResponseBody(response.body() != null ? response.body().string() : "");
                return proxyResponse;
            } catch (IOException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    proxyResponse.setStatus("error");
                    proxyResponse.setResponseBody(e.getMessage());
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Request interrupted during retry", ie);
                }
            }
        }
        return proxyResponse;
    }
}