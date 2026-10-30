package com.scaleguard.server.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.server.security.oauth.OAuthSettings;
import com.scaleguard.server.security.oauth.RefreshTokens;
import com.scaleguard.utils.Constants;
import com.scaleguard.utils.JSON;

import java.util.*;

/**
 * Admin API for OAuth clients:
 * <pre>
 *   GET    /clients                    list (secrets are never returned; secretHint shows the last characters)
 *   POST   /clients                    create → client + client_secret (shown once)
 *   POST   /clients/{id}               update name, description, expiry, scopes, token lifetimes, enabled
 *   POST   /clients/{id}/secret        rotate the secret → new client_secret (old one and its tokens stop working)
 *   POST   /clients/{id}/revoke        revoke every token issued so far
 *   DELETE /clients/{id}
 * </pre>
 */
public class ClientRoute implements RequestRoute {
    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        String[] parts = Arrays.stream(uri.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
        String id = parts.length > 1 ? parts[1] : null;
        String action = parts.length > 2 ? parts[2] : null;
        try {
            if (method.equalsIgnoreCase("get")) {
                return RequestRoutingResponse.later(this::list);
            }
            if (method.equalsIgnoreCase("delete")) {
                ClientInfo.remove(id);
                return RequestRoutingResponse.succes("{}");
            }
            if (!method.equalsIgnoreCase("post")) return RequestRoutingResponse.response(405, "{\"message\":\"method not allowed\"}");

            JsonNode node = body == null || body.isBlank() ? JSON.object() : JSON.parse(body);
            if (id != null && "secret".equals(action)) {
                return RequestRoutingResponse.later(() -> {
                    String secret = ClientInfo.rotateSecret(id);
                    return withSecret(201, ClientInfo.get().getOrDefault(id, ClientInfo.byClientId(id)), secret);
                });
            }
            if (id != null && "revoke".equals(action)) {
                return RequestRoutingResponse.later(() -> {
                    ClientInfo.revokeTokens(id);
                    return RequestRoutingResponse.succes("{\"revoked\":true}");
                });
            }
            // Older UIs posted the full record (with id) for both create and update.
            String bodyId = text(node, "id");
            String target = id != null ? id : (bodyId != null && (ClientInfo.get().containsKey(bodyId)) ? bodyId : null);
            if (target != null) {
                ClientInfo.WrappeClientInfoRecord r = ClientInfo.update(target, text(node, "name"), text(node, "description"),
                        node.has("expiry") ? node.get("expiry").asLong() : null, meta(node, ClientInfo.get().get(target)));
                return RequestRoutingResponse.succes(JSON.toString(r));
            }

            String appid = text(node, "appid");
            if (appid == null || Application.getAppId(appid) == null)
                return RequestRoutingResponse.response(404, "{\"message\":\"app not found\"}");
            String name = text(node, "name");
            if (name == null || name.isBlank()) return RequestRoutingResponse.response(400, "{\"message\":\"name is required\"}");
            Map.Entry<ClientInfo.WrappeClientInfoRecord, String> made = ClientInfo.create(name, text(node, "description"), appid,
                    text(node, "clientid"), node.has("expiry") ? node.get("expiry").asLong() : 0, meta(node, null),
                    text(node, "clientsecret"));
            return withSecret(201, made.getKey(), made.getValue());
        } catch (IllegalArgumentException e) {
            return RequestRoutingResponse.response(400, JSON.toString(Map.of("message", e.getMessage())));
        } catch (NoSuchElementException e) {
            return RequestRoutingResponse.response(404, JSON.toString(Map.of("message", e.getMessage())));
        }
    }

    private RequestRoutingResponse list() {
        Map<String, Integer> live = RefreshTokens.activeCounts();
        ArrayNode arr = JSON.array();
        for (ClientInfo.WrappeClientInfoRecord r : ClientInfo.get().values()) {
            ObjectNode o = Constants.OBJECT_MAPPER.valueToTree(r);
            o.put("activeRefreshTokens", live.getOrDefault(r.getClientid(), 0));
            arr.add(o);
        }
        return RequestRoutingResponse.succes(arr.toString());
    }

    /** The one response that carries the plain secret, plus a ready-to-save credentials block. */
    private static RequestRoutingResponse withSecret(int status, ClientInfo.WrappeClientInfoRecord r, String secret) {
        ObjectNode out = JSON.object();
        out.set("client", Constants.OBJECT_MAPPER.valueToTree(r));
        out.put("client_secret", secret);
        ObjectNode cred = out.putObject("credentials");
        cred.put("client_id", r.getClientid());
        cred.put("client_secret", secret);
        cred.put("token_endpoint_path", OAuthSettings.get().pathPrefix + "/token");
        cred.put("grant_type", "client_credentials");
        if (!r.getScopes().isEmpty()) cred.put("scope", String.join(" ", r.getScopes()));
        Application.WrappeApplicationRecord app = Application.getAppId(r.getAppid());
        if (app != null) cred.put("app", app.getName());
        out.put("note", "Store this secret now — ScaleGuard keeps only a hash and cannot show it again.");
        return RequestRoutingResponse.response(status, out.toString());
    }

    private static ClientInfo.Meta meta(JsonNode n, ClientInfo.WrappeClientInfoRecord existing) {
        ClientInfo.Meta m = new ClientInfo.Meta();
        if (existing != null) {
            m.scopes = existing.getScopes();
            m.accessTokenTtlSeconds = existing.getAccessTokenTtlSeconds();
            m.refreshTokenTtlSeconds = existing.getRefreshTokenTtlSeconds();
            m.refreshTokens = existing.isRefreshTokens();
            m.enabled = existing.isEnabled();
        }
        if (n.has("scopes")) {
            List<String> scopes = new ArrayList<>();
            JsonNode s = n.get("scopes");
            if (s.isArray()) s.forEach(x -> scopes.addAll(splitScopes(x.asText())));
            else scopes.addAll(splitScopes(s.asText()));
            for (String sc : scopes)
                if (!sc.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")) throw new IllegalArgumentException("invalid scope: " + sc);
            m.scopes = new ArrayList<>(new LinkedHashSet<>(scopes));
        }
        if (n.has("accessTokenTtlSeconds")) m.accessTokenTtlSeconds = ttl(n.get("accessTokenTtlSeconds"), 60, 86_400, "accessTokenTtlSeconds");
        if (n.has("refreshTokenTtlSeconds")) m.refreshTokenTtlSeconds = ttl(n.get("refreshTokenTtlSeconds"), 300, 365 * 86_400, "refreshTokenTtlSeconds");
        if (n.has("refreshTokens")) m.refreshTokens = n.get("refreshTokens").asBoolean(true);
        if (n.has("enabled")) m.enabled = n.get("enabled").asBoolean(true);
        return m;
    }

    private static Integer ttl(JsonNode v, int min, int max, String name) {
        if (v == null || v.isNull() || (v.isNumber() && v.asInt() < 0) || (v.isTextual() && v.asText().isBlank())) return null;   // default
        int s = v.asInt();
        if (s < min || s > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        return s;
    }

    static List<String> splitScopes(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("[\\s,]+")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    private static String text(JsonNode n, String f) {
        return n.has(f) && !n.get(f).isNull() ? n.get(f).asText() : null;
    }
}
