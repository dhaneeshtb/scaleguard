package com.scaleguard.server.http.router;

import com.scaleguard.server.http.utils.AppProperties;
import com.scaleguard.server.http.utils.CidrSet;

/**
 * Works out the real client IP for a request.
 *
 * <p>X-Forwarded-For is only believed when the TCP peer is a trusted proxy (for example a load
 * balancer or CDN in front of ScaleGuard). Otherwise any client could put a fake address in the
 * header to dodge rate limits or to get somebody else limited.
 *
 * <p>With a trusted peer, the header is read right to left, skipping trusted hops; the first
 * untrusted address is the client. This is the standard algorithm (as in nginx real_ip_recursive).
 *
 * <p>Configure with {@code scaleguard.trustedProxies} (system property, env var or
 * application.properties), a comma-separated CIDR list. Default: loopback only.
 * Behind Cloudflare, list Cloudflare's published ranges here.
 */
public final class ClientIpResolver {

    private static volatile CidrSet trusted = load();

    private ClientIpResolver() {}

    private static CidrSet load() {
        String v = System.getProperty("scaleguard.trustedProxies");
        if (v == null || v.isBlank()) v = AppProperties.get("scaleguard.trustedProxies");
        if (v == null || v.isBlank()) v = "127.0.0.0/8,::1/128";
        return CidrSet.parse(v);
    }

    /** For tests and runtime reconfiguration. */
    public static void setTrustedProxies(String csv) {
        trusted = CidrSet.parse(csv);
    }

    /**
     * @param peerIp        address of the TCP peer
     * @param xForwardedFor value of the X-Forwarded-For header, or null
     */
    public static String resolve(String peerIp, String xForwardedFor) {
        CidrSet t = trusted;
        if (xForwardedFor == null || xForwardedFor.isBlank() || !t.contains(peerIp)) {
            return peerIp;
        }
        String[] hops = xForwardedFor.split(",");
        String leftmostValid = null;
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = clean(hops[i]);
            if (hop == null) continue;
            leftmostValid = hop;
            if (!t.contains(hop)) return hop;
        }
        return leftmostValid != null ? leftmostValid : peerIp;
    }

    /** Value to send upstream: the incoming chain (only if the peer is trusted) plus the peer itself. */
    public static String forwardedFor(String peerIp, String incomingXff) {
        if (incomingXff == null || incomingXff.isBlank() || !trusted.contains(peerIp)) {
            return peerIp;
        }
        return incomingXff.trim() + ", " + peerIp;
    }

    private static String clean(String raw) {
        String s = raw.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("unknown")) return null;
        if (s.startsWith("[")) { // [v6]:port
            int close = s.indexOf(']');
            return close > 0 ? s.substring(1, close) : null;
        }
        int colon = s.indexOf(':');
        if (colon > 0 && colon == s.lastIndexOf(':') && s.indexOf('.') > 0) { // v4:port
            s = s.substring(0, colon);
        }
        return s.length() > 64 ? null : s;
    }
}
