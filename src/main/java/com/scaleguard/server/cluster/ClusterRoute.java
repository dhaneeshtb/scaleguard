package com.scaleguard.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.utils.JSON;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin API for the cluster.
 * <ul>
 *   <li>{@code GET /cluster} — membership, master, per-node metrics, ledger and sync status.</li>
 *   <li>{@code POST /cluster/resync} — reload every subsystem on this node from its store now (and pull the ledger).</li>
 *   <li>{@code GET /cluster/ledger/verify} — re-hash every ledger chain on this node.</li>
 *   <li>{@code GET /cluster/ledger?origin=&after=&limit=} — node-to-node: ledger entries of one chain.</li>
 *   <li>{@code POST /cluster/gossip}, {@code /cluster/vote}, {@code /cluster/rates} — node-to-node: membership and
 *       chain heads, master election, rate-limit totals.</li>
 * </ul>
 */
public class ClusterRoute implements RequestRoute {

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public boolean receivesQueryString() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        String[] pq = uri.split("\\?", 2);
        Map<String, String> q = query(pq.length > 1 ? pq[1] : "");
        String[] parts = pq[0].replaceAll("^/+|/+$", "").split("/");
        boolean get = "get".equalsIgnoreCase(method), post = "post".equalsIgnoreCase(method);
        String a = parts.length > 1 ? parts[1] : "";
        String b = parts.length > 2 ? parts[2] : "";

        if (get && parts.length == 1) {
            return RequestRoutingResponse.later(() -> RequestRoutingResponse.succes(JSON.toString(ClusterManager.get().snapshot())));
        }
        if (post && "resync".equals(a)) {
            if (!ClusterManager.enabled()) return error(400, "This node isn't part of a cluster.");
            ClusterManager.get().resyncAll();
            return RequestRoutingResponse.succes(JSON.toString(Map.of("message", "Reloading configuration on this node.")));
        }
        if (!ClusterManager.ledgerMode()) return error(404, "Not found");

        if (post && "gossip".equals(a)) {
            JsonNode msg = JSON.parse(body);
            return RequestRoutingResponse.later(() -> RequestRoutingResponse.succes(JSON.toString(PeerMesh.get().onGossip(msg))));
        }
        if (post && "vote".equals(a)) {
            JsonNode req = JSON.parse(body);
            if (req == null) return error(400, "Body must be JSON.");
            return RequestRoutingResponse.later(() -> RequestRoutingResponse.succes(JSON.toString(PeerMesh.get().onVote(req))));
        }
        if (post && "rates".equals(a)) {
            ClusterManager.get().receiveRates(JSON.parse(body));
            return RequestRoutingResponse.succes("{}");
        }
        if (post && "oauth".equals(a)) {           // node-to-node: refresh tokens are issued and redeemed on the master
            JsonNode n = JSON.parse(body);
            if (n == null) return error(400, "Body must be JSON.");
            return RequestRoutingResponse.later(() -> {
                Map<String, Object> out = new LinkedHashMap<>();
                switch (b) {
                    case "issue" -> out.put("token", com.scaleguard.server.security.oauth.RefreshTokens.issueLocal(n.path("clientId").asText(),
                            n.path("appId").asText(null), n.path("scope").asText(null), n.path("family").isNull() ? null : n.path("family").asText(null),
                            n.path("ttl").asInt()));
                    case "redeem" -> {
                        try {
                            var g = com.scaleguard.server.security.oauth.RefreshTokens.redeemLocal(n.path("token").asText(), n.path("clientId").asText());
                            if (g != null) {
                                out.put("clientId", g.clientId());
                                out.put("appId", g.appId());
                                out.put("scope", g.scope());
                                out.put("family", g.family());
                            }
                        } catch (com.scaleguard.server.security.oauth.RefreshTokens.Reused e) {
                            out.put("reused", true);
                        }
                    }
                    case "revoke" -> out.put("revoked", com.scaleguard.server.security.oauth.RefreshTokens.revokeLocal(n.path("token").asText(), n.path("clientId").asText()));
                    default -> {
                        return error(404, "Not found");
                    }
                }
                return RequestRoutingResponse.succes(JSON.toString(out));
            });
        }
        if (post && "dr".equals(a) && "promote".equals(b)) {
            JsonNode n = JSON.parse(body);
            boolean force = n != null && n.path("force").asBoolean(false);
            return RequestRoutingResponse.later(() -> {
                var r = PeerMesh.get().promote(force);
                return RequestRoutingResponse.response(r.path("ok").asBoolean() ? 200 : 409, JSON.toString(r));
            });
        }
        if (post && "dr".equals(a) && "join".equals(b)) {
            JsonNode n = JSON.parse(body);
            if (n == null) return error(400, "Body must be JSON.");
            return RequestRoutingResponse.succes(JSON.toString(PeerMesh.get().joinPromotion(n)));
        }
        if (get && "ledger".equals(a) && "verify".equals(b)) {
            return RequestRoutingResponse.later(() -> RequestRoutingResponse.succes(JSON.toString(Ledger.get().verify())));
        }
        if (get && "ledger".equals(a) && b.isEmpty()) {
            String origin = q.get("origin");
            if (origin == null) return RequestRoutingResponse.later(() -> RequestRoutingResponse.succes(JSON.toString(Ledger.get().status())));
            long after = parseLong(q.get("after"), 0);
            int limit = (int) parseLong(q.get("limit"), 1000);
            return RequestRoutingResponse.later(() -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("origin", origin);
                out.put("entries", Ledger.get().read(origin, after, limit));
                return RequestRoutingResponse.succes(JSON.toString(out));
            });
        }
        if (post && "ledger".equals(a) && "tamper".equals(b)) {   // test hook, off unless -Dscaleguard.ledger.testHooks=true
            JsonNode n = JSON.parse(body);
            if (n == null) return error(400, "Body must be JSON.");
            return RequestRoutingResponse.later(() -> Ledger.get().tamper(n.path("origin").asText(), n.path("seq").asLong(), n.path("row").asText())
                    ? RequestRoutingResponse.succes("{\"tampered\":true}") : error(404, "Not found"));
        }
        return error(404, "Not found");
    }

    private static RequestRoutingResponse error(int status, String msg) {
        return RequestRoutingResponse.response(status, JSON.toString(Map.of("message", msg)));
    }

    private static long parseLong(String s, long dflt) {
        try {
            return s == null ? dflt : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Map<String, String> query(String qs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String kv : qs.split("&")) {
            if (kv.isEmpty()) continue;
            String[] p = kv.split("=", 2);
            m.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }
}
