package com.scaleguard.server.cluster;

import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.utils.JSON;

import java.util.Map;
import java.util.Set;

/**
 * Ledger mode: configuration changes are made on the elected master. An admin write (POST/PUT/PATCH/DELETE) that
 * reaches another node is forwarded to the master, which applies it and appends it to its ledger; this node then pulls
 * that entry before answering, so the UI reads its own write. While no master is elected (fewer than a majority of
 * nodes reachable) writes are refused with 503 — traffic keeps flowing on the configuration every node already has.
 */
public final class MasterRouter {

    private MasterRouter() {}

    /** Admin areas that are per-node or read-only even when POSTed to. */
    private static final Set<String> LOCAL = Set.of("cluster", "signin", "login", "oauth2", ".well-known");

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(2)).build();

    /**
     * Runs an operation that must have a single authority (refresh-token redemption) on the master. Returns the master's
     * JSON answer, or null when this node should do it itself: it is the master, it isn't clustered, or no master is
     * reachable (then the ledger's conflict check is the safety net).
     */
    public static com.fasterxml.jackson.databind.JsonNode callMaster(String op, Object body) {
        if (!ClusterManager.ledgerMode() || !ClusterManager.enabled()) return null;
        PeerMesh mesh = PeerMesh.get();
        if (mesh.isLeader()) return null;
        String master = mesh.leaderNode();
        String base = master == null ? null : mesh.addressOf(master);
        if (base == null) return null;
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/cluster/" + op + "?scaleguard=true"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("Authorization", com.scaleguard.server.http.auth.AuthUtils.createJWT(60_000))
                    .header("Content-Type", "application/json")
                    .header("X-ScaleGuard-Forwarded-By", java.net.URLEncoder.encode(ClusterManager.get().node(), java.nio.charset.StandardCharsets.UTF_8))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(JSON.toString(body))).build();
            var r = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? JSON.parse(r.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the master's answer when this request must run there, or null to handle it here. */
    public static RequestRoutingResponse route(String method, String path, String uriWithQuery, String body, String forwardedBy) {
        if (!ClusterManager.ledgerMode() || !ClusterManager.enabled()) return null;
        if (method == null || "get".equalsIgnoreCase(method) || "head".equalsIgnoreCase(method) || "options".equalsIgnoreCase(method)) return null;
        if (forwardedBy != null && !forwardedBy.isBlank()) return null;      // already sent here by another node
        String[] parts = path.replaceAll("^/+", "").split("/");
        String area = parts.length > 0 ? parts[0] : "";
        if (LOCAL.contains(area)) return null;
        if ("analytics".equals(area) && !(parts.length > 1 && "settings".equals(parts[1]))) return null;   // queries read the local copy
        PeerMesh mesh = PeerMesh.get();
        if (mesh.isLeader()) return null;
        String master = mesh.leaderNode();
        if (master == null && mesh.isDr()) {
            return new RequestRoutingResponse(503, JSON.toString(Map.of("message",
                    "This is a DR standby and the primary site's master is unreachable, so configuration can't change here. "
                            + "If the primary site is lost, promote this site (POST /cluster/dr/promote).")), "application/json");
        }
        if (master == null) {
            return new RequestRoutingResponse(503, JSON.toString(Map.of("message",
                    "No master is elected right now, so configuration can't change (a majority of the cluster's nodes must be reachable). "
                            + "Traffic is still served. Try again in a few seconds.")), "application/json");
        }
        return RequestRoutingResponse.later(() -> {
            RequestRoutingResponse r = NodeForwarder.call(master, method, uriWithQuery, body);
            if (r.getStatus() < 300) mesh.catchUpWith(master, 3000);   // read-your-writes on this node
            r.header("X-ScaleGuard-Master", master);
            return r;
        });
    }
}
