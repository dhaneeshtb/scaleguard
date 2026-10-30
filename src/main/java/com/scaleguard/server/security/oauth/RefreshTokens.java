package com.scaleguard.server.security.oauth;

import com.scaleguard.server.db.ConnectionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Refresh tokens (RFC 6749 §6) with rotation and reuse detection (OAuth 2.0 Security BCP, RFC 9700 §4.14.2):
 * every refresh returns a new refresh token and invalidates the old one; presenting an already-used token revokes the
 * whole chain ("family"), because it means the token was copied. Only a SHA-256 of each token is stored, in the shared
 * database, so any cluster node can redeem it.
 */
public final class RefreshTokens {
    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokens.class);
    private static volatile boolean ready;
    private static volatile long lastPurge;

    private RefreshTokens() {}

    /** A redeemed token's grant, to be re-issued. */
    public record Grant(String clientId, String appId, String scope, String family) {}

    public static final class Reused extends Exception {
        Reused() { super("refresh token was already used"); }
    }

    private static Connection conn() throws Exception {
        Connection c = ConnectionUtil.getConnection();
        if (!ready) {
            synchronized (RefreshTokens.class) {
                if (!ready) {
                    try (Statement st = c.createStatement()) {
                        st.executeUpdate("CREATE TABLE IF NOT EXISTS oauth_refresh_tokens ("
                                + " id TEXT PRIMARY KEY, family TEXT NOT NULL, clientid TEXT NOT NULL, appid TEXT,"
                                + " scope TEXT, issued BIGINT NOT NULL, expires BIGINT NOT NULL,"
                                + " used INTEGER NOT NULL DEFAULT 0, revoked INTEGER NOT NULL DEFAULT 0)");
                        st.executeUpdate("CREATE INDEX IF NOT EXISTS oauth_rt_family_idx ON oauth_refresh_tokens (family)");
                        st.executeUpdate("CREATE INDEX IF NOT EXISTS oauth_rt_client_idx ON oauth_refresh_tokens (clientid)");
                    }
                    ready = true;
                }
            }
        }
        return c;
    }

    /** Stores a new refresh token and returns it (the only time its value exists). */
    public static String issue(String clientId, String appId, String scope, String family, int ttlSeconds) throws Exception {
        String token = Secrets.newRefreshToken();
        long now = System.currentTimeMillis();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("INSERT INTO oauth_refresh_tokens"
                     + " (id, family, clientid, appid, scope, issued, expires, used, revoked) VALUES (?,?,?,?,?,?,?,0,0)")) {
            ps.setString(1, Secrets.lookupKey(token));
            ps.setString(2, family != null ? family : Secrets.random(12));
            ps.setString(3, clientId);
            ps.setString(4, appId);
            ps.setString(5, scope);
            ps.setLong(6, now);
            ps.setLong(7, now + ttlSeconds * 1000L);
            ps.executeUpdate();
        }
        purgeOccasionally();
        return token;
    }

    /**
     * Marks the token used and returns its grant. Returns null when it is unknown, expired, revoked or belongs to another
     * client; throws {@link Reused} (after revoking the family) when it was already used.
     */
    public static Grant redeem(String token, String clientId) throws Exception {
        if (token == null || !token.startsWith(Secrets.REFRESH_PREFIX)) return null;
        String id = Secrets.lookupKey(token);
        try (Connection c = conn()) {
            Grant g;
            boolean used, revoked;
            long expires;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT family, clientid, appid, scope, expires, used, revoked FROM oauth_refresh_tokens WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    g = new Grant(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(1));
                    expires = rs.getLong(5);
                    used = rs.getInt(6) != 0;
                    revoked = rs.getInt(7) != 0;
                }
            }
            if (!g.clientId().equals(clientId)) return null;
            if (revoked) return null;
            if (used) {
                revokeFamily(c, g.family());
                LOG.warn("Refresh token reuse for client {} — revoked its token chain", clientId);
                throw new Reused();
            }
            if (expires < System.currentTimeMillis()) return null;
            // Claim it atomically: if two requests race with the same token, only one wins.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE oauth_refresh_tokens SET used = 1 WHERE id = ? AND used = 0 AND revoked = 0")) {
                ps.setString(1, id);
                if (ps.executeUpdate() != 1) {
                    revokeFamily(c, g.family());
                    throw new Reused();
                }
            }
            return g;
        }
    }

    /** RFC 7009: revokes the token's whole chain. Returns false when the token is unknown or another client's. */
    public static boolean revoke(String token, String clientId) throws Exception {
        if (token == null || !token.startsWith(Secrets.REFRESH_PREFIX)) return false;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT family, clientid FROM oauth_refresh_tokens WHERE id = ?")) {
            ps.setString(1, Secrets.lookupKey(token));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !rs.getString(2).equals(clientId)) return false;
                revokeFamily(c, rs.getString(1));
                return true;
            }
        }
    }

    /** Revokes every refresh token of a client (secret rotated, client disabled or deleted, "revoke all"). */
    public static int revokeClient(String clientId) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("UPDATE oauth_refresh_tokens SET revoked = 1 WHERE clientid = ? AND revoked = 0")) {
            ps.setString(1, clientId);
            return ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("Could not revoke refresh tokens of {}: {}", clientId, e.toString());
            return 0;
        }
    }

    /** Live (unused, unrevoked, unexpired) refresh tokens per client, for the admin view. */
    public static java.util.Map<String, Integer> activeCounts() {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT clientid, COUNT(*) FROM oauth_refresh_tokens"
                     + " WHERE used = 0 AND revoked = 0 AND expires > ? GROUP BY clientid")) {
            ps.setLong(1, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
            }
        } catch (Exception e) {
            LOG.debug("Could not count refresh tokens: {}", e.toString());
        }
        return out;
    }

    private static void revokeFamily(Connection c, String family) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE oauth_refresh_tokens SET revoked = 1 WHERE family = ?")) {
            ps.setString(1, family);
            ps.executeUpdate();
        }
    }

    /** Drops rows that expired more than a day ago (used tokens are kept that long to detect reuse). */
    private static void purgeOccasionally() {
        long now = System.currentTimeMillis();
        if (now - lastPurge < 3_600_000L) return;
        lastPurge = now;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("DELETE FROM oauth_refresh_tokens WHERE expires < ?")) {
            ps.setLong(1, now - 86_400_000L);
            int n = ps.executeUpdate();
            if (n > 0) LOG.info("Removed {} expired refresh tokens", n);
        } catch (Exception e) {
            LOG.debug("Refresh token purge failed: {}", e.toString());
        }
    }
}
