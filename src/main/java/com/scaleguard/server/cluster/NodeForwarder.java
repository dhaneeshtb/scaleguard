package com.scaleguard.server.cluster;

import com.scaleguard.server.http.auth.AuthUtils;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.utils.JSON;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Forwards an admin request to another cluster member, e.g. so the Analytics page can read that node's DuckDB.
 * Every node signs admin tokens with the same key when they share admin credentials, so a short-lived token minted
 * here is accepted there.
 */
public final class NodeForwarder {

    private NodeForwarder() {}

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Runs off the I/O thread; answers 502 if the node is gone or unreachable. */
    public static RequestRoutingResponse forward(String node, String method, String uriWithQuery, String body) {
        return RequestRoutingResponse.later(() -> call(node, method, uriWithQuery, body));
    }

    /** Blocking: sends the admin request to another member and returns its answer (502 if unreachable). */
    public static RequestRoutingResponse call(String node, String method, String uriWithQuery, String body) {
        {
            String base = ClusterManager.get().addressOf(node);
            if (base == null) {
                return RequestRoutingResponse.response(502, JSON.toString(Map.of("message",
                        "Node '" + node + "' is not a live member of this cluster.")));
            }
            String path = uriWithQuery.startsWith("/") ? uriWithQuery : "/" + uriWithQuery;
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", AuthUtils.createJWT(60_000))
                    .header("X-ScaleGuard-Forwarded-By", ClusterManager.get().node());
            if ("post".equalsIgnoreCase(method)) {
                b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            } else if ("delete".equalsIgnoreCase(method)) {
                b.DELETE();
            } else if ("put".equalsIgnoreCase(method) || "patch".equalsIgnoreCase(method)) {
                b.header("Content-Type", "application/json").method(method.toUpperCase(), HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            } else {
                b.GET();
            }
            try {
                HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
                String ct = r.headers().firstValue("Content-Type").orElse("application/json");
                return new RequestRoutingResponse(r.statusCode(), r.body(), ct);
            } catch (Exception e) {
                return RequestRoutingResponse.response(502, JSON.toString(Map.of("message",
                        "Couldn't reach node '" + node + "' at " + base + ": " + e.getMessage())));
            }
        }
    }
}
