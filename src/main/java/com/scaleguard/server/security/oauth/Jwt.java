package com.scaleguard.server.security.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Minimal JSON Web Token (RFC 7519) support for RS256 (RFC 7518 §3.3) — the only algorithm the gateway issues or
 * accepts, so "alg: none" and HMAC/RSA confusion attacks are impossible by construction.
 */
public final class Jwt {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getUrlDecoder();

    private Jwt() {}

    /** A token whose signature has been checked; claims are not yet validated (exp, aud, …). */
    public record Verified(String kid, JsonNode header, JsonNode claims) {
        public String str(String name) {
            JsonNode n = claims.get(name);
            return n == null || n.isNull() ? null : n.asText();
        }
        public long num(String name) {
            JsonNode n = claims.get(name);
            return n == null || !n.canConvertToLong() ? 0 : n.asLong();
        }
    }

    /** Signs {@code claims} with the active key; header carries {@code typ} (e.g. "at+jwt", RFC 9068) and {@code kid}. */
    public static String sign(Map<String, Object> claims, String typ) {
        OAuthKeys.Key key = OAuthKeys.get().active();
        ObjectNode header = MAPPER.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", typ);
        header.put("kid", key.kid());
        try {
            String signingInput = B64.encodeToString(MAPPER.writeValueAsBytes(header)) + "."
                    + B64.encodeToString(MAPPER.writeValueAsBytes(claims));
            byte[] sig = key.sign(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64.encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("could not sign token", e);
        }
    }

    /** Parses and checks the signature against the published key set. Returns null for anything malformed or forged. */
    public static Verified verify(String token) {
        if (token == null) return null;
        int d1 = token.indexOf('.');
        int d2 = d1 < 0 ? -1 : token.indexOf('.', d1 + 1);
        if (d1 <= 0 || d2 <= d1 + 1 || d2 == token.length() - 1 || token.indexOf('.', d2 + 1) >= 0) return null;
        try {
            JsonNode header = MAPPER.readTree(D64.decode(token.substring(0, d1)));
            if (!"RS256".equals(header.path("alg").asText())) return null;
            String kid = header.path("kid").asText(null);
            OAuthKeys.Key key = OAuthKeys.get().byKid(kid);
            if (key == null) return null;
            byte[] sig = D64.decode(token.substring(d2 + 1));
            if (!key.verify(token.substring(0, d2).getBytes(StandardCharsets.US_ASCII), sig)) return null;
            JsonNode claims = MAPPER.readTree(D64.decode(token.substring(d1 + 1, d2)));
            if (!claims.isObject()) return null;
            return new Verified(kid, header, claims);
        } catch (Exception e) {
            return null;
        }
    }

    static String b64(byte[] b) {
        return B64.encodeToString(b);
    }
}
