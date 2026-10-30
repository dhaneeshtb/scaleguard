package com.scaleguard.server.security.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.application.Application;
import com.scaleguard.server.application.ClientInfo;
import com.scaleguard.server.http.auth.AuthUtils;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * OAuth 2.0 authorization server for machine-to-machine access:
 * <ul>
 *   <li>{@code POST {prefix}/token} — client credentials grant (RFC 6749 §4.4) and refresh token grant (§6). Clients
 *       authenticate with HTTP Basic (client_secret_basic) or form fields (client_secret_post). Access tokens are
 *       RS256-signed JWTs in the RFC 9068 profile; refresh tokens are opaque, single-use and rotated.</li>
 *   <li>{@code POST {prefix}/revoke} — RFC 7009 token revocation.</li>
 *   <li>{@code POST {prefix}/introspect} — RFC 7662 token introspection (for clients of the same app, or the admin).</li>
 *   <li>{@code GET {prefix}/jwks} — RFC 7517 public keys, so backends can verify tokens offline.</li>
 *   <li>{@code GET /.well-known/oauth-authorization-server} — RFC 8414 discovery metadata.</li>
 * </ul>
 * Served on the admin port and on every route that has a security app attached.
 */
public final class OAuthServer {
    private static final Logger LOG = LoggerFactory.getLogger(OAuthServer.class);
    public static final String METADATA_PATH = "/.well-known/oauth-authorization-server";

    private OAuthServer() {}

    /** Cheap check on a raw request URI (with query string) — called for every request. */
    public static boolean handlesUri(String uri) {
        String p = OAuthSettings.get().pathPrefix;
        if (!uri.startsWith(p) && !uri.startsWith(METADATA_PATH)) return false;
        int q = uri.indexOf('?');
        return handles(q < 0 ? uri : uri.substring(0, q));
    }

    public static boolean handles(String path) {
        String p = OAuthSettings.get().pathPrefix;
        return path.equals(METADATA_PATH) || (path.startsWith(p) && path.length() > p.length() && path.charAt(p.length()) == '/');
    }

    public static RequestRoutingResponse handle(String method, String uri, HttpHeaders headers, String body, boolean tls) {
        String path = new QueryStringDecoder(uri).path();
        String prefix = OAuthSettings.get().pathPrefix;
        String issuer = issuer(headers, tls);
        try {
            if (path.equals(METADATA_PATH)) return get(method, () -> metadata(issuer));
            String ep = path.substring(prefix.length() + 1);
            switch (ep) {
                case "jwks":
                    return get(method, () -> new RequestRoutingResponse(200, OAuthKeys.get().jwks())
                            .header("Cache-Control", "public, max-age=300"));
                case "token":
                    if (!method.equalsIgnoreCase("POST")) return notAllowed("POST");
                    return RequestRoutingResponse.later(() -> token(headers, body, issuer));
                case "revoke":
                    if (!method.equalsIgnoreCase("POST")) return notAllowed("POST");
                    return RequestRoutingResponse.later(() -> revoke(headers, body));
                case "introspect":
                    if (!method.equalsIgnoreCase("POST")) return notAllowed("POST");
                    return introspect(headers, body);
                default:
                    return error(404, "invalid_request", "unknown OAuth endpoint " + path);
            }
        } catch (Exception e) {
            LOG.warn("OAuth endpoint {} failed: {}", path, e.toString());
            return error(500, "server_error", "internal error");
        }
    }

    // ------------------------------------------------------------------ token

    private static RequestRoutingResponse token(HttpHeaders headers, String body, String issuer) throws Exception {
        Map<String, String> form = form(headers, body);
        Auth auth = authenticate(headers, form);
        if (auth.error != null) return auth.error;
        ClientInfo.WrappeClientInfoRecord client = auth.client;

        String grant = form.get("grant_type");
        if (grant == null) return error(400, "invalid_request", "grant_type is required");
        OAuthSettings.Values cfg = OAuthSettings.get();
        int accessTtl = client.getAccessTokenTtlSeconds() != null ? client.getAccessTokenTtlSeconds() : cfg.accessTokenTtlSeconds;
        int refreshTtl = client.getRefreshTokenTtlSeconds() != null ? client.getRefreshTokenTtlSeconds() : cfg.refreshTokenTtlSeconds;

        switch (grant) {
            case "client_credentials": {
                List<String> scopes = grantedScopes(form.get("scope"), client.getScopes());
                if (scopes == null) return error(400, "invalid_scope", "requested scope is not allowed for this client");
                String refresh = client.isRefreshTokens()
                        ? RefreshTokens.issue(client.getClientid(), client.getAppid(), String.join(" ", scopes), null, refreshTtl) : null;
                return tokenResponse(client, scopes, issuer, accessTtl, refresh, refreshTtl);
            }
            case "refresh_token": {
                if (!client.isRefreshTokens()) return error(400, "unauthorized_client", "refresh tokens are turned off for this client");
                String rt = form.get("refresh_token");
                if (rt == null) return error(400, "invalid_request", "refresh_token is required");
                RefreshTokens.Grant g;
                try {
                    g = RefreshTokens.redeem(rt, client.getClientid());
                } catch (RefreshTokens.Reused e) {
                    return error(400, "invalid_grant", "refresh token was already used; all tokens from it have been revoked");
                }
                if (g == null) return error(400, "invalid_grant", "refresh token is invalid, expired or revoked");
                // The grant can't outlive the client's current permissions or app.
                List<String> original = new ArrayList<>(splitScopes(g.scope()));
                original.retainAll(client.getScopes());
                List<String> scopes = grantedScopes(form.get("scope"), original);
                if (scopes == null) return error(400, "invalid_scope", "a refreshed token can't gain scopes");
                String refresh = RefreshTokens.issue(client.getClientid(), client.getAppid(), String.join(" ", scopes), g.family(), refreshTtl);
                return tokenResponse(client, scopes, issuer, accessTtl, refresh, refreshTtl);
            }
            default:
                return error(400, "unsupported_grant_type", "supported grant types: client_credentials, refresh_token");
        }
    }

    private static RequestRoutingResponse tokenResponse(ClientInfo.WrappeClientInfoRecord client, List<String> scopes, String issuer,
                                                        int accessTtl, String refresh, int refreshTtl) {
        long now = System.currentTimeMillis();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("sub", client.getClientid());
        claims.put("aud", client.getAppid());
        claims.put("client_id", client.getClientid());
        if (!scopes.isEmpty()) claims.put("scope", String.join(" ", scopes));
        claims.put("iat", now / 1000);
        claims.put("nbf", now / 1000);
        claims.put("exp", now / 1000 + accessTtl);
        claims.put("jti", Secrets.random(16));
        claims.put(OAuthGuard.ISSUED_MS, now);   // millisecond issue time, for "revoke all tokens issued before now"
        Application.WrappeApplicationRecord app = Application.getAppId(client.getAppid());
        if (app != null) claims.put("app", app.getName());

        ObjectNode out = Jwt.MAPPER.createObjectNode();
        out.put("access_token", Jwt.sign(claims, "at+jwt"));
        out.put("token_type", "Bearer");
        out.put("expires_in", accessTtl);
        if (!scopes.isEmpty()) out.put("scope", String.join(" ", scopes));
        if (refresh != null) {
            out.put("refresh_token", refresh);
            out.put("refresh_token_expires_in", refreshTtl);
        }
        return noStore(new RequestRoutingResponse(200, out.toString()));
    }

    /** Requested ⊆ allowed (null when not); no request = everything allowed. */
    private static List<String> grantedScopes(String requested, List<String> allowed) {
        if (requested == null || requested.isBlank()) return new ArrayList<>(allowed);
        List<String> want = splitScopes(requested);
        return new HashSet<>(allowed).containsAll(want) ? want : null;
    }

    static List<String> splitScopes(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.trim().split("\\s+")) if (!p.isEmpty() && !out.contains(p)) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ revoke / introspect

    private static RequestRoutingResponse revoke(HttpHeaders headers, String body) throws Exception {
        Map<String, String> form = form(headers, body);
        Auth auth = authenticate(headers, form);
        if (auth.error != null) return auth.error;
        String token = form.get("token");
        if (token == null) return error(400, "invalid_request", "token is required");
        String clientId = auth.client.getClientid();
        if (!RefreshTokens.revoke(token, clientId)) {
            Jwt.Verified v = Jwt.verify(token);
            if (v != null && clientId.equals(v.str("client_id"))) AccessTokenRevocations.revoke(v.str("jti"), v.num("exp"));
        }
        return noStore(new RequestRoutingResponse(200, "{}"));   // RFC 7009 §2.2: 200 even for unknown tokens
    }

    private static RequestRoutingResponse introspect(HttpHeaders headers, String body) {
        Map<String, String> form = form(headers, body);
        String appScope;   // null = any app (admin)
        String bearer = headers.get("Authorization");
        if (bearer != null && bearer.regionMatches(true, 0, "Bearer ", 0, 7) && AuthUtils.isTokenValid(bearer.substring(7).trim())) {
            appScope = null;
        } else {
            Auth auth = authenticate(headers, form);
            if (auth.error != null) return auth.error;
            appScope = auth.client.getAppid();
        }
        String token = form.get("token");
        if (token == null) return error(400, "invalid_request", "token is required");
        OAuthGuard.Check c = OAuthGuard.inspect(token, appScope);
        ObjectNode out = Jwt.MAPPER.createObjectNode();
        if (c == null) {
            out.put("active", false);
        } else {
            out.put("active", true);
            JsonNode claims = c.claims();
            out.put("token_type", "Bearer");
            for (String f : List.of("scope", "client_id", "sub", "aud", "iss", "jti", "app"))
                if (claims.hasNonNull(f)) out.set(f, claims.get(f));
            for (String f : List.of("exp", "iat", "nbf"))
                if (claims.hasNonNull(f)) out.put(f, claims.get(f).asLong());
        }
        return noStore(new RequestRoutingResponse(200, out.toString()));
    }

    // ------------------------------------------------------------------ discovery

    private static RequestRoutingResponse metadata(String issuer) {
        String base = issuer + OAuthSettings.get().pathPrefix;
        ObjectNode m = Jwt.MAPPER.createObjectNode();
        m.put("issuer", issuer);
        m.put("token_endpoint", base + "/token");
        m.put("revocation_endpoint", base + "/revoke");
        m.put("introspection_endpoint", base + "/introspect");
        m.put("jwks_uri", base + "/jwks");
        m.putArray("grant_types_supported").add("client_credentials").add("refresh_token");
        m.putArray("token_endpoint_auth_methods_supported").add("client_secret_basic").add("client_secret_post");
        m.putArray("revocation_endpoint_auth_methods_supported").add("client_secret_basic").add("client_secret_post");
        m.putArray("introspection_endpoint_auth_methods_supported").add("client_secret_basic").add("client_secret_post");
        m.putArray("token_endpoint_auth_signing_alg_values_supported");
        m.putArray("response_types_supported");   // no browser flows: machine-to-machine only
        m.putArray("access_token_signing_alg_values_supported").add("RS256");
        Set<String> scopes = new TreeSet<>();
        for (ClientInfo.WrappeClientInfoRecord c : ClientInfo.get().values()) scopes.addAll(c.getScopes());
        var arr = m.putArray("scopes_supported");
        scopes.forEach(arr::add);
        return new RequestRoutingResponse(200, m.toString()).header("Cache-Control", "public, max-age=300");
    }

    // ------------------------------------------------------------------ helpers

    private record Auth(ClientInfo.WrappeClientInfoRecord client, RequestRoutingResponse error) {}

    /** RFC 6749 §2.3.1: HTTP Basic (values form-urlencoded) or client_id/client_secret in the body — not both. */
    private static Auth authenticate(HttpHeaders headers, Map<String, String> form) {
        String id = null, secret = null;
        boolean basic = false;
        String h = headers.get("Authorization");
        if (h != null && h.regionMatches(true, 0, "Basic ", 0, 6)) {
            basic = true;
            try {
                String dec = new String(Base64.getDecoder().decode(h.substring(6).trim()), StandardCharsets.UTF_8);
                int c = dec.indexOf(':');
                if (c < 0) return new Auth(null, invalidClient(true));
                id = URLDecoder.decode(dec.substring(0, c), StandardCharsets.UTF_8);
                secret = URLDecoder.decode(dec.substring(c + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return new Auth(null, invalidClient(true));
            }
            if (form.containsKey("client_secret"))
                return new Auth(null, error(400, "invalid_request", "use one client authentication method, not both"));
        } else {
            id = form.get("client_id");
            secret = form.get("client_secret");
        }
        if (id == null || secret == null) return new Auth(null, invalidClient(basic));
        ClientInfo.WrappeClientInfoRecord c = ClientInfo.byClientId(id);
        if (c == null || !c.checkSecret(secret) || !c.isUsable() || Application.getAppId(c.getAppid()) == null) {
            LOG.debug("OAuth client authentication failed for {}", id);
            return new Auth(null, invalidClient(basic));
        }
        return new Auth(c, null);
    }

    private static RequestRoutingResponse invalidClient(boolean basic) {
        RequestRoutingResponse r = error(401, "invalid_client", "client authentication failed");
        r.header("WWW-Authenticate", "Basic realm=\"scaleguard\", charset=\"UTF-8\"");
        return r;
    }

    /** Form body (application/x-www-form-urlencoded, as RFC 6749 requires) or, for convenience, a JSON object. */
    private static Map<String, String> form(HttpHeaders headers, String body) {
        Map<String, String> out = new HashMap<>();
        if (body == null || body.isBlank()) return out;
        String ct = headers.get("Content-Type", "");
        if (ct.toLowerCase(Locale.ROOT).contains("json") || body.trim().startsWith("{")) {
            try {
                JsonNode n = Jwt.MAPPER.readTree(body);
                n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString()));
            } catch (Exception ignored) {
                // falls through as an empty form → invalid_request
            }
            return out;
        }
        new QueryStringDecoder(body, StandardCharsets.UTF_8, false).parameters()
                .forEach((k, v) -> { if (!v.isEmpty()) out.put(k, v.get(0)); });
        return out;
    }

    private static String issuer(HttpHeaders headers, boolean tls) {
        String fixed = OAuthSettings.get().issuer;
        if (fixed != null && !fixed.isBlank()) return fixed;
        String host = headers.get("Host", "localhost");
        String proto = headers.get("X-Forwarded-Proto");
        return (proto != null && (proto.equals("https") || proto.equals("http")) ? proto : tls ? "https" : "http") + "://" + host;
    }

    private interface Body { RequestRoutingResponse get() throws Exception; }

    private static RequestRoutingResponse get(String method, Body b) throws Exception {
        if (!method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("HEAD")) return notAllowed("GET");
        return b.get();
    }

    private static RequestRoutingResponse notAllowed(String allow) {
        return error(405, "invalid_request", "use " + allow).header("Allow", allow);
    }

    static RequestRoutingResponse error(int status, String code, String description) {
        ObjectNode e = Jwt.MAPPER.createObjectNode();
        e.put("error", code);
        e.put("error_description", description);
        return noStore(new RequestRoutingResponse(status, e.toString()));
    }

    private static RequestRoutingResponse noStore(RequestRoutingResponse r) {
        return r.header("Cache-Control", "no-store").header("Pragma", "no-cache");
    }
}
