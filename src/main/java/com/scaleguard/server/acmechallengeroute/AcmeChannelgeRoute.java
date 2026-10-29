package com.scaleguard.server.acmechallengeroute;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import org.shredzone.acme4j.AcmeUtils;
import org.shredzone.acme4j.exception.AcmeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AcmeChannelgeRoute implements RequestRoute {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcmeChannelgeRoute.class);

    // FIX #6: In-memory time-bounded cache of token → key-authorisation content.
    // Building this map requires one filesystem read per certificate on every request;
    // with a short TTL we pay that cost at most once per 30 s regardless of how
    // many ACME challenge polls arrive in that window.
    private static volatile Map<String, String> tokenCache = null;
    private static volatile long tokenCacheTimestamp = 0;
    private static final long TOKEN_CACHE_TTL_MS = 30_000;

    /**
     * Return the key-authorisation string for {@code token}, or {@code null} if not found.
     *
     * <p>FIX #6: Results are cached for {@value TOKEN_CACHE_TTL_MS} ms to avoid an N+1
     * filesystem-read on every ACME HTTP-01 poll.</p>
     *
     * <p>FIX #11: Token lookup uses {@link String#equals} (not {@code equalsIgnoreCase})
     * because ACME tokens are case-sensitive per RFC 8555 §8.3.</p>
     */
    private static String lookupToken(String token) {
        long now = System.currentTimeMillis();
        if (tokenCache == null || (now - tokenCacheTimestamp) > TOKEN_CACHE_TTL_MS) {
            Map<String, String> cache = new HashMap<>();
            AcmeUtils.listCertificateIds().forEach(s -> {
                try {
                    JsonNode jn = AcmeUtils.readCachedCertificate(s);
                    if (jn.has("httpChallenge")) {
                        JsonNode jnn = jn.get("httpChallenge");
                        // FIX #11: equals — ACME tokens are case-sensitive (RFC 8555 §8.3)
                        cache.put(jnn.get("token").asText(), jnn.get("content").asText());
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to read certificate {} for token cache: {}", s, e.getMessage());
                }
            });
            tokenCache = cache;
            tokenCacheTimestamp = now;
        }
        return tokenCache.get(token); // exact (case-sensitive) match
    }

    /**
     * Invalidate the token cache — call this whenever a new order is placed so the
     * next challenge poll picks up the freshly written token immediately.
     */
    public static void invalidateTokenCache() {
        tokenCache = null;
        tokenCacheTimestamp = 0;
    }

    @Override
    public boolean isAuthNeeded() {
        return false;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws AcmeException, IOException {

        // FIX #12: Demote to DEBUG — this fires on every ACME poll (up to several per
        // second during certificate issuance) and would flood INFO logs.
        LOGGER.debug("Acme challenge request for uri: {}", uri);

        if (method.equalsIgnoreCase("get")) {
            String cleanUri = uri;
            if (cleanUri.contains("?")) {
                cleanUri = cleanUri.substring(0, cleanUri.indexOf("?"));
            }
            while (cleanUri.endsWith("/") && cleanUri.length() > 1) {
                cleanUri = cleanUri.substring(0, cleanUri.length() - 1);
            }
            String[] tuples = cleanUri.split("/");
            String token = tuples[tuples.length - 1];

            // FIX #6: single cache lookup instead of N filesystem reads
            String content = lookupToken(token);
            if (content != null) {
                return new RequestRoutingResponse(200, content, "text/plain");
            } else {
                return new RequestRoutingResponse(404, "not found " + token, "text/plain");
            }
        }
        return new RequestRoutingResponse(404, "not found", "text/plain");
    }
}
