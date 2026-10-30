package com.scaleguard.server.asyncengines;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.db.QueueEvent;
import com.scaleguard.server.db.QueueEventDB;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST endpoint for auditing the embedded async queue.
 *
 * <h3>Endpoints</h3>
 * <pre>
 * GET /queueevents                   — all events (most recent first, max 200)
 * GET /queueevents?status=pending    — filter by status (pending|dispatching|success|failed|dead)
 * GET /queueevents?engineId=&lt;id&gt;    — filter by engine
 * GET /queueevents/&lt;eventId&gt;        — single event detail
 * </pre>
 *
 * <p>Requires a valid {@code Authorization} token (same as other admin routes).</p>
 */
public class QueueEventRoute implements RequestRoute {

    private static final Logger LOG = LoggerFactory.getLogger(QueueEventRoute.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 200;

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public boolean receivesQueryString() {
        return true;   // ?status= and ?engineId= filters (were silently ignored before)
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        if (!"get".equalsIgnoreCase(method)) {
            return RequestRoutingResponse.response(405, "{\"message\":\"Method not allowed\"}");
        }

        try {
            // Parse query string manually (no Netty context here)
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf("?")) : uri;
            String query = uri.contains("?") ? uri.substring(uri.indexOf("?") + 1) : "";

            String statusFilter = queryParam(query, "status");
            String engineFilter = queryParam(query, "engineId");

            // Check if a specific event ID was requested: /queueevents/<id>
            String[] segments = path.split("/");
            String eventId = segments.length > 2 ? segments[segments.length - 1] : null;
            if (eventId != null && eventId.equalsIgnoreCase("queueevents")) {
                eventId = null; // path ended at the route root
            }

            if (eventId != null) {
                return handleSingle(eventId);
            }

            return handleList(statusFilter, engineFilter);

        } catch (Exception e) {
            LOG.error("Error handling /queueevents request: {}", e.getMessage(), e);
            return RequestRoutingResponse.response(500, "{\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    // -------------------------------------------------------------------------

    private RequestRoutingResponse handleSingle(String eventId) throws Exception {
        List<QueueEvent> events = QueueEventDB.getInstance().readItems("id", eventId);
        if (events.isEmpty()) {
            return RequestRoutingResponse.response(404, "{\"message\":\"Event not found: " + eventId + "\"}");
        }
        return new RequestRoutingResponse(200, MAPPER.writeValueAsString(events.get(0)), "application/json");
    }

    private RequestRoutingResponse handleList(String statusFilter, String engineFilter) throws Exception {
        List<QueueEvent> all = QueueEventDB.getInstance().readAll();

        List<QueueEvent> filtered = all.stream()
                .filter(e -> statusFilter == null || statusFilter.equalsIgnoreCase(e.getStatus()))
                .filter(e -> engineFilter == null || engineFilter.equalsIgnoreCase(e.getEngineId()))
                // Most recent first
                .sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()))
                .limit(MAX_RESULTS)
                .collect(Collectors.toList());

        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("total", filtered.size());
        if (statusFilter != null) envelope.put("statusFilter", statusFilter);
        if (engineFilter != null) envelope.put("engineFilter", engineFilter);
        envelope.set("events", MAPPER.valueToTree(filtered));

        return new RequestRoutingResponse(200, MAPPER.writeValueAsString(envelope), "application/json");
    }

    /** Extract a named query-string parameter from a raw query string (e.g. {@code "status=pending&foo=bar"}). */
    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq).trim();
                if (key.equals(name)) {
                    return pair.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }
}
