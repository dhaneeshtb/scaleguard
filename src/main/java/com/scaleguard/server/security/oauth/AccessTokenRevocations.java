package com.scaleguard.server.security.oauth;

import com.scaleguard.server.db.ConnectionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Individually revoked access tokens (RFC 7009 on an access token). Access tokens are self-contained JWTs, so the
 * gateway keeps their ids ({@code jti}) until they would have expired anyway. Held in memory for the per-request check,
 * stored in the shared database and reloaded on the {@code oauth_revocations} cluster scope.
 */
public final class AccessTokenRevocations {
    private static final Logger LOG = LoggerFactory.getLogger(AccessTokenRevocations.class);
    public static final String SCOPE = "oauth_revocations";
    private static volatile Map<String, Long> revoked = new ConcurrentHashMap<>();
    private static volatile boolean ready;

    private AccessTokenRevocations() {}

    static {
        reload();
    }

    public static boolean isRevoked(String jti) {
        return jti != null && !revoked.isEmpty() && revoked.containsKey(jti);
    }

    static void revoke(String jti, long expSeconds) throws Exception {
        if (jti == null) return;
        long exp = expSeconds * 1000;
        try (Connection c = conn();
             PreparedStatement del = c.prepareStatement("DELETE FROM oauth_revoked_tokens WHERE jti = ? OR expires < ?");
             PreparedStatement ins = c.prepareStatement("INSERT INTO oauth_revoked_tokens (jti, expires) VALUES (?, ?)")) {
            del.setString(1, jti);
            del.setLong(2, System.currentTimeMillis());
            del.executeUpdate();
            ins.setString(1, jti);
            ins.setLong(2, exp);
            ins.executeUpdate();
        }
        revoked.put(jti, exp);
        try {
            com.scaleguard.server.cluster.ClusterManager.changed(java.util.List.of(SCOPE));
        } catch (Throwable ignored) {
            // single node
        }
    }

    public static void reload() {
        Map<String, Long> fresh = new ConcurrentHashMap<>();
        long now = System.currentTimeMillis();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT jti, expires FROM oauth_revoked_tokens WHERE expires > ?")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) fresh.put(rs.getString(1), rs.getLong(2));
            }
        } catch (Exception e) {
            LOG.debug("Could not load revoked access tokens: {}", e.toString());
        }
        revoked = fresh;
    }

    /** Forget ids whose tokens have expired by now. */
    static void prune() {
        long now = System.currentTimeMillis();
        revoked.values().removeIf(exp -> exp < now);
    }

    private static Connection conn() throws Exception {
        Connection c = ConnectionUtil.getConnection();
        if (!ready) {
            synchronized (AccessTokenRevocations.class) {
                if (!ready) {
                    try (Statement st = c.createStatement()) {
                        st.executeUpdate("CREATE TABLE IF NOT EXISTS oauth_revoked_tokens (jti TEXT PRIMARY KEY, expires BIGINT NOT NULL)");
                    }
                    ready = true;
                }
            }
        }
        return c;
    }
}
