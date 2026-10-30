package com.scaleguard.server.cluster;

import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.utils.JSON;

import java.util.Map;

/**
 * Admin API for the cluster.
 * <ul>
 *   <li>{@code GET /cluster} — membership, leader, per-node metrics, configuration versions and sync status.</li>
 *   <li>{@code POST /cluster/resync} — reload every subsystem on this node from the shared database now.</li>
 * </ul>
 */
public class ClusterRoute implements RequestRoute {

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        String[] parts = uri.split("\\?")[0].replaceAll("^/+|/+$", "").split("/");
        if ("get".equalsIgnoreCase(method) && parts.length == 1) {
            return RequestRoutingResponse.later(() -> RequestRoutingResponse.succes(JSON.toString(ClusterManager.get().snapshot())));
        }
        if ("post".equalsIgnoreCase(method) && parts.length == 2 && "resync".equals(parts[1])) {
            if (!ClusterManager.enabled()) {
                return RequestRoutingResponse.response(400, JSON.toString(Map.of("message", "This node isn't part of a cluster.")));
            }
            ClusterManager.get().resyncAll();
            return RequestRoutingResponse.succes(JSON.toString(Map.of("message", "Reloading configuration from the shared database.")));
        }
        return RequestRoutingResponse.response(404, JSON.toString(Map.of("message", "Not found")));
    }
}
