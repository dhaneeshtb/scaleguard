package com.scaleguard.server.security.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.application.ClientInfo;
import com.scaleguard.server.http.router.RouteTarget;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks requests to routes that have a security app attached. A request must carry
 * {@code Authorization: Bearer <access token>} issued by this gateway for that app (RFC 6750); optionally the route
 * requires scopes. When the old scheme is allowed (OAuth settings → legacyHeaders), {@code client_id} /
 * {@code client_secret} headers are still accepted. On success the backend receives {@code X-ScaleGuard-Client-Id} and
 * {@code X-ScaleGuard-Scope}; client secrets are never forwarded.
 */
public final class OAuthGuard {
    /** Private claim: issue time in milliseconds, so "revoke all tokens" can't miss one issued in the same second. */
    static final String ISSUED_MS = "sg_iat_ms";
    public static final String CLIENT_HEADER = "X-ScaleGuard-Client-Id";
    public static final String SCOPE_HEADER = "X-ScaleGuard-Scope";

    private OAuthGuard() {}

    public enum Outcome { OK, NO_CREDENTIALS, INVALID_TOKEN, INSUFFICIENT_SCOPE }

    public record Result(Outcome outcome, String clientId, String scope, String description, String requiredScope) {
        public boolean ok() { return outcome == Outcome.OK; }

        /** RFC 6750 §3 challenge for 401/403 responses. */
        public String challenge() {
            StringBuilder b = new StringBuilder("Bearer realm=\"scaleguard\"");
            if (outcome == Outcome.INVALID_TOKEN) b.append(", error=\"invalid_token\", error_description=\"").append(description).append('"');
            if (outcome == Outcome.INSUFFICIENT_SCOPE) b.append(", error=\"insufficient_scope\", scope=\"").append(requiredScope).append('"');
            return b.toString();
        }

        public int httpStatus() { return outcome == Outcome.INSUFFICIENT_SCOPE ? 403 : 401; }

        public String body() {
            String code = switch (outcome) {
                case NO_CREDENTIALS -> "invalid_request";
                case INVALID_TOKEN -> "invalid_token";
                case INSUFFICIENT_SCOPE -> "insufficient_scope";
                default -> "ok";
            };
            String d = description == null ? "" : description.replace("\\", "\\\\").replace("\"", "\\\"");
            return "{\"error\":\"" + code + "\",\"error_description\":\"" + d + "\"}";
        }
    }

    private static final Result PASS = new Result(Outcome.OK, null, null, null, null);

    /** Signature-checked tokens, so RSA verification runs once per token rather than once per request. */
    private record Parsed(JsonNode claims, String clientId, String aud, Set<String> scopes, long expMs, long nbfMs, long issuedMs, String jti) {}
    private static final int CACHE_MAX = Integer.getInteger("scaleguard.oauth.tokenCache", 50_000);
    private static final Map<String, Parsed> CACHE = new ConcurrentHashMap<>();

    /** Result for a request to {@code rt}; PASS when the route isn't protected. */
    public static Result check(RouteTarget rt) {
        String appId = rt.getSourceSystem().getSecappid();
        if (appId == null || appId.isEmpty()) return PASS;
        HttpHeaders h = rt.getHeaders();
        if (h == null) return new Result(Outcome.NO_CREDENTIALS, null, null, "missing access token", null);
        h.remove(CLIENT_HEADER);   // only the gateway sets these
        h.remove(SCOPE_HEADER);

        String auth = h.get("Authorization");
        Result r;
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            r = checkToken(auth.substring(7).trim(), appId, rt.getSourceSystem().getOauthScopes());
        } else if (OAuthSettings.get().legacyHeaders && h.contains("client_id")) {
            r = checkLegacy(h.get("client_id"), h.get("client_secret"), appId);
        } else {
            r = new Result(Outcome.NO_CREDENTIALS, null, null, "missing access token", null);
        }
        h.remove("client_secret");
        if (r.ok()) {
            h.set(CLIENT_HEADER, r.clientId());
            if (r.scope() != null && !r.scope().isEmpty()) h.set(SCOPE_HEADER, r.scope());
        }
        return r;
    }

    private static Result checkToken(String token, String appId, String requiredScopes) {
        Parsed p = parse(token);
        if (p == null) return invalid("token is malformed or its signature is not valid");
        long now = System.currentTimeMillis();
        if (p.expMs <= now) return invalid("token has expired");
        if (p.nbfMs > now + 60_000) return invalid("token is not valid yet");
        if (!appId.equals(p.aud)) return invalid("token was issued for a different app");
        ClientInfo.WrappeClientInfoRecord c = ClientInfo.byClientId(p.clientId);
        if (c == null || !c.isUsable() || !appId.equals(c.getAppid())) return invalid("client is unknown or disabled");
        if (p.issuedMs < c.getNotBefore()) return invalid("token has been revoked");
        if (AccessTokenRevocations.isRevoked(p.jti)) return invalid("token has been revoked");
        String scope = String.join(" ", p.scopes);
        if (requiredScopes != null && !requiredScopes.isBlank()) {
            for (String need : OAuthServer.splitScopes(requiredScopes.replace(',', ' '))) {
                if (!p.scopes.contains(need))
                    return new Result(Outcome.INSUFFICIENT_SCOPE, p.clientId, scope, "token lacks a required scope",
                            requiredScopes.replace(',', ' ').trim());
            }
        }
        return new Result(Outcome.OK, p.clientId, scope, null, null);
    }

    private static Result checkLegacy(String clientId, String secret, String appId) {
        ClientInfo.WrappeClientInfoRecord c = ClientInfo.byClientId(clientId);
        if (c == null) c = ClientInfo.get().get(clientId);   // very old clients used the record id
        if (c == null || secret == null || !c.isUsable() || !appId.equals(c.getAppid()) || !c.checkSecret(secret))
            return invalid("client credentials are not valid");
        return new Result(Outcome.OK, c.getClientid(), String.join(" ", c.getScopes()), null, null);
    }

    /** For introspection: the token's claims when it is active (and, if {@code appId} is set, for that app). */
    public record Check(JsonNode claims) {}

    public static Check inspect(String token, String appId) {
        Parsed p = parse(token);
        if (p == null) return null;
        long now = System.currentTimeMillis();
        if (p.expMs <= now || (appId != null && !appId.equals(p.aud))) return null;
        ClientInfo.WrappeClientInfoRecord c = ClientInfo.byClientId(p.clientId);
        if (c == null || !c.isUsable() || p.issuedMs < c.getNotBefore() || AccessTokenRevocations.isRevoked(p.jti)) return null;
        return new Check(p.claims);
    }

    private static Parsed parse(String token) {
        Parsed p = CACHE.get(token);
        if (p != null) return p;
        Jwt.Verified v = Jwt.verify(token);
        if (v == null || !"at+jwt".equalsIgnoreCase(v.header().path("typ").asText())) return null;
        String iss = OAuthSettings.get().issuer;
        if (iss != null && !iss.isBlank() && !iss.equals(v.str("iss"))) return null;
        long iat = v.num("iat");
        long issued = v.claims().has(ISSUED_MS) ? v.num(ISSUED_MS) : iat * 1000;
        Set<String> scopes = new LinkedHashSet<>(OAuthServer.splitScopes(v.str("scope")));
        p = new Parsed(v.claims(), v.str("client_id"), v.str("aud"), Collections.unmodifiableSet(scopes),
                v.num("exp") * 1000, v.num("nbf") * 1000, issued, v.str("jti"));
        if (p.clientId == null || p.expMs == 0) return null;
        if (CACHE.size() >= CACHE_MAX) {
            CACHE.clear();
            AccessTokenRevocations.prune();
        }
        CACHE.put(token, p);
        return p;
    }

    private static Result invalid(String why) {
        return new Result(Outcome.INVALID_TOKEN, null, null, why, null);
    }

    /** Called when keys or settings change: cached verifications may no longer hold. */
    public static void clearCache() {
        CACHE.clear();
    }
}
