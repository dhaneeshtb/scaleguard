package com.scaleguard.server.security.oauth;

import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Gateway-wide OAuth settings ({@code oauth.settings} in system_properties). Per-client values override the token
 * lifetimes.
 */
public final class OAuthSettings {
    private static final Logger LOG = LoggerFactory.getLogger(OAuthSettings.class);
    static final String P_SETTINGS = "oauth.settings";

    /** Stored / API form. */
    public static final class Values {
        /** "iss" claim; blank = derived from the host the token was requested on (scheme://host). */
        public String issuer = "";
        public int accessTokenTtlSeconds = 3600;
        public int refreshTokenTtlSeconds = 30 * 24 * 3600;
        /** Accept the old per-request client_id / client_secret headers on protected routes. */
        public boolean legacyHeaders = true;
        /** Path the token endpoints are served under, on protected routes and the admin port. */
        public String pathPrefix = "/oauth2";

        Values copy() {
            Values v = new Values();
            v.issuer = issuer;
            v.accessTokenTtlSeconds = accessTokenTtlSeconds;
            v.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
            v.legacyHeaders = legacyHeaders;
            v.pathPrefix = pathPrefix;
            return v;
        }
    }

    private static volatile Values current = load();

    private OAuthSettings() {}

    public static Values get() {
        return current;
    }

    public static void reload() {
        current = load();
    }

    public static Values validate(Values v) {
        if (v.accessTokenTtlSeconds < 60 || v.accessTokenTtlSeconds > 86_400)
            throw new IllegalArgumentException("accessTokenTtlSeconds must be between 60 and 86400 (1 minute to 1 day)");
        if (v.refreshTokenTtlSeconds < 300 || v.refreshTokenTtlSeconds > 365 * 86_400)
            throw new IllegalArgumentException("refreshTokenTtlSeconds must be between 300 and 31536000 (5 minutes to 1 year)");
        if (v.issuer == null) v.issuer = "";
        v.issuer = v.issuer.trim();
        if (!v.issuer.isEmpty() && !v.issuer.matches("https?://[^\\s?#]+"))
            throw new IllegalArgumentException("issuer must be an http(s) URL without query or fragment, or blank");
        if (v.issuer.endsWith("/")) v.issuer = v.issuer.substring(0, v.issuer.length() - 1);
        if (v.pathPrefix == null || !v.pathPrefix.matches("/[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*"))
            throw new IllegalArgumentException("pathPrefix must look like /oauth2");
        String first = v.pathPrefix.substring(1).split("/")[0];
        if (com.scaleguard.server.http.reverse.RequestRoutingContexts.routesMap.containsKey(first)
                || java.util.Set.of("adminui", "health", "healthz", "metrics", "info", "stats", "config", "signin").contains(first))
            throw new IllegalArgumentException("pathPrefix /" + first + " is used by ScaleGuard itself; pick another");
        return v;
    }

    public static void save(Values v) throws Exception {
        validate(v);
        long now = System.currentTimeMillis();
        SystemProperty p = new SystemProperty();
        p.setId(P_SETTINGS);
        p.setName(P_SETTINGS);
        p.setGroupname("oauth");
        p.setValue(Jwt.MAPPER.writeValueAsString(v));
        p.setMts(now);
        p.setUts(now);
        SystemPropertyDB.getInstance().save(List.of(p));
        current = v.copy();
    }

    private static Values load() {
        try {
            for (SystemProperty p : SystemPropertyDB.getInstance().readItems("id", P_SETTINGS)) {
                if (p.getValue() != null) return Jwt.MAPPER.readValue(p.getValue(), Values.class);
            }
        } catch (Exception e) {
            LOG.warn("Could not read OAuth settings, using defaults: {}", e.toString());
        }
        return new Values();
    }
}
