package com.scaleguard.server.security.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.application.ClientInfo;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;

import java.util.Map;

/**
 * Admin API for the OAuth service:
 * <pre>
 *   GET  /oauth                 settings, signing keys (public info only), endpoint paths
 *   POST /oauth/settings        issuer, default token lifetimes, legacy client_id/client_secret headers, path prefix
 *   POST /oauth/keys/rotate     new signing key (old ones keep verifying until their tokens expire)
 * </pre>
 */
public class OAuthRoute implements RequestRoute {
    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        String path = uri.replaceAll("/+$", "");
        try {
            if (method.equalsIgnoreCase("GET") && path.equals("/oauth")) return RequestRoutingResponse.later(OAuthRoute::view);
            if (method.equalsIgnoreCase("POST") && path.equals("/oauth/settings")) {
                OAuthSettings.Values v = OAuthSettings.get().copy();
                JsonNode n = Jwt.MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
                if (n.has("issuer")) v.issuer = n.get("issuer").isNull() ? "" : n.get("issuer").asText();
                if (n.has("accessTokenTtlSeconds")) v.accessTokenTtlSeconds = n.get("accessTokenTtlSeconds").asInt();
                if (n.has("refreshTokenTtlSeconds")) v.refreshTokenTtlSeconds = n.get("refreshTokenTtlSeconds").asInt();
                if (n.has("legacyHeaders")) v.legacyHeaders = n.get("legacyHeaders").asBoolean();
                if (n.has("pathPrefix")) v.pathPrefix = n.get("pathPrefix").asText();
                OAuthSettings.save(v);
                OAuthGuard.clearCache();
                return RequestRoutingResponse.later(OAuthRoute::view);
            }
            if (method.equalsIgnoreCase("POST") && path.equals("/oauth/keys/rotate")) {
                return RequestRoutingResponse.later(() -> {
                    OAuthKeys.get().rotate();
                    OAuthGuard.clearCache();
                    return view();
                });
            }
            return RequestRoutingResponse.response(404, "{\"message\":\"not found\"}");
        } catch (IllegalArgumentException e) {
            return RequestRoutingResponse.response(400, Jwt.MAPPER.writeValueAsString(Map.of("message", e.getMessage())));
        }
    }

    private static RequestRoutingResponse view() {
        OAuthKeys.get().active();   // make sure a key exists so the JWKS isn't empty
        ObjectNode out = Jwt.MAPPER.createObjectNode();
        out.set("settings", Jwt.MAPPER.valueToTree(OAuthSettings.get()));
        ArrayNode keys = out.putArray("keys");
        for (OAuthKeys.Key k : OAuthKeys.get().all()) {
            ObjectNode o = keys.addObject();
            o.put("kid", k.kid());
            o.put("created", k.created());
            if (k.retired() != null) o.put("retired", k.retired());
            o.put("active", k.retired() == null);
            o.put("algorithm", "RS256");
        }
        String p = OAuthSettings.get().pathPrefix;
        ObjectNode ep = out.putObject("endpoints");
        ep.put("token", p + "/token");
        ep.put("revoke", p + "/revoke");
        ep.put("introspect", p + "/introspect");
        ep.put("jwks", p + "/jwks");
        ep.put("metadata", OAuthServer.METADATA_PATH);
        out.put("clients", ClientInfo.get().size());
        return RequestRoutingResponse.succes(out.toString());
    }
}
