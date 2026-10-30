package com.scaleguard.server.security.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Client secrets and refresh tokens. Both are 256-bit random values, so a salted SHA-256 is the right storage hash:
 * nothing can be guessed from a dictionary, and verification stays cheap enough to run on every request. Only the
 * hash is stored; the secret is shown once when it is created.
 */
public final class Secrets {
    private static final SecureRandom RNG = new SecureRandom();
    static final String SECRET_PREFIX = "sgs_";
    static final String REFRESH_PREFIX = "sgr_";
    private static final String HASH_PREFIX = "sha256$";

    private Secrets() {}

    public static String newSecret() {
        return SECRET_PREFIX + random(32);
    }

    public static String newRefreshToken() {
        return REFRESH_PREFIX + random(32);
    }

    public static String newClientId() {
        return "sgc_" + random(15);
    }

    public static String random(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static boolean isHashed(String stored) {
        return stored != null && stored.startsWith(HASH_PREFIX);
    }

    /** {@code sha256$<salt>$<hex>}. */
    public static String hash(String secret) {
        String salt = random(16);
        return HASH_PREFIX + salt + "$" + sha256Hex(salt + ":" + secret);
    }

    /** Constant-time check of a presented secret against the stored hash. */
    public static boolean matches(String presented, String stored) {
        if (presented == null || stored == null || !isHashed(stored)) return false;
        int d = stored.indexOf('$', HASH_PREFIX.length());
        if (d < 0) return false;
        String salt = stored.substring(HASH_PREFIX.length(), d);
        byte[] want = stored.substring(d + 1).getBytes(StandardCharsets.US_ASCII);
        byte[] got = sha256Hex(salt + ":" + presented).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(want, got);
    }

    /** Unsalted digest used as the lookup key for refresh tokens (they are random, so no salt is needed). */
    public static String lookupKey(String token) {
        return sha256Hex(token);
    }

    /** Last characters of a secret, safe to show so people can tell secrets apart. */
    public static String hint(String secret) {
        return secret == null || secret.length() < 8 ? "" : "…" + secret.substring(secret.length() - 4);
    }

    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
