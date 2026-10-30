package com.scaleguard.server.security.oauth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * The gateway's token-signing keys (RSA 2048, RS256). Kept in {@code system_properties} as {@code oauth.keys} so every
 * cluster node signs and verifies with the same set. Rotation adds a new active key and keeps the previous ones for
 * verification until tokens signed with them have expired, so rotating never logs clients out. The public halves are
 * published as a JWK Set (RFC 7517) at {@code /oauth2/jwks} for backends that want to verify tokens themselves.
 */
public final class OAuthKeys {
    private static final Logger LOG = LoggerFactory.getLogger(OAuthKeys.class);
    static final String P_KEYS = "oauth.keys";
    /** Retired keys are kept this long after rotation (must exceed the longest access-token lifetime). */
    static final long RETIRED_KEEP_MS = 7L * 24 * 3600 * 1000;

    /** Stored form. */
    public static final class Stored {
        public String kid;
        public long created;
        public Long retired;      // null = active
        public String privateKey; // PKCS#8, base64
        public String publicKey;  // X.509 SubjectPublicKeyInfo, base64
    }

    public static final class Key {
        private final Stored s;
        private final PrivateKey priv;
        private final PublicKey pub;

        Key(Stored s) throws GeneralSecurityException {
            this.s = s;
            KeyFactory kf = KeyFactory.getInstance("RSA");
            this.priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(s.privateKey)));
            this.pub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(s.publicKey)));
        }

        public String kid() { return s.kid; }
        public long created() { return s.created; }
        public Long retired() { return s.retired; }

        byte[] sign(byte[] data) throws GeneralSecurityException {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(priv);
            sig.update(data);
            return sig.sign();
        }

        boolean verify(byte[] data, byte[] signature) {
            try {
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initVerify(pub);
                sig.update(data);
                return sig.verify(signature);
            } catch (GeneralSecurityException e) {
                return false;
            }
        }

        ObjectNode jwk() {
            RSAPublicKey rsa = (RSAPublicKey) pub;
            ObjectNode n = Jwt.MAPPER.createObjectNode();
            n.put("kty", "RSA");
            n.put("use", "sig");
            n.put("alg", "RS256");
            n.put("kid", s.kid);
            n.put("n", Jwt.b64(unsigned(rsa.getModulus())));
            n.put("e", Jwt.b64(unsigned(rsa.getPublicExponent())));
            return n;
        }

        private static byte[] unsigned(BigInteger v) {
            byte[] b = v.toByteArray();
            return b.length > 1 && b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
        }
    }

    private static final OAuthKeys INSTANCE = new OAuthKeys();
    private volatile List<Key> keys = List.of();

    private OAuthKeys() {
        reload();
    }

    public static OAuthKeys get() {
        return INSTANCE;
    }

    public Key active() {
        List<Key> k = keys;
        for (Key key : k) if (key.retired() == null) return key;
        synchronized (this) {           // first token ever: create the key
            for (Key key : keys) if (key.retired() == null) return key;
            rotate();
            return keys.get(0);
        }
    }

    public Key byKid(String kid) {
        if (kid == null) return null;
        for (Key key : keys) if (key.kid().equals(kid)) return key;
        return null;
    }

    public List<Key> all() {
        return keys;
    }

    /** RFC 7517 JWK Set with the active and still-valid retired keys. */
    public String jwks() {
        if (keys.isEmpty()) active();
        ObjectNode root = Jwt.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("keys");
        for (Key k : keys) arr.add(k.jwk());
        return root.toString();
    }

    /** Creates a new active key; the current one is retired but still verifies tokens it signed. */
    public synchronized void rotate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            Stored s = new Stored();
            byte[] rnd = new byte[9];
            new SecureRandom().nextBytes(rnd);
            s.kid = "sg-" + Jwt.b64(rnd);
            s.created = System.currentTimeMillis();
            s.privateKey = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
            s.publicKey = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

            long now = System.currentTimeMillis();
            List<Stored> stored = new ArrayList<>();
            stored.add(s);
            for (Stored old : readStored()) {
                if (old.retired == null) old.retired = now;
                if (now - old.retired < RETIRED_KEEP_MS) stored.add(old);
            }
            write(stored);
            reload();
            LOG.info("OAuth signing key {} is now active ({} key(s) published)", s.kid, keys.size());
        } catch (Exception e) {
            throw new IllegalStateException("could not create a signing key: " + e.getMessage(), e);
        }
    }

    /** Re-reads the key set (start-up, or another cluster node rotated it). */
    public synchronized void reload() {
        List<Key> fresh = new ArrayList<>();
        for (Stored s : readStored()) {
            try {
                fresh.add(new Key(s));
            } catch (Exception e) {
                LOG.warn("Ignoring unreadable OAuth signing key {}: {}", s.kid, e.toString());
            }
        }
        fresh.sort(Comparator.comparing((Key k) -> k.retired() == null ? 0 : 1).thenComparing(k -> -k.created()));
        keys = List.copyOf(fresh);
    }

    private static List<Stored> readStored() {
        try {
            for (SystemProperty p : SystemPropertyDB.getInstance().readItems("id", P_KEYS)) {
                if (p.getValue() != null) return Jwt.MAPPER.readValue(p.getValue(), new TypeReference<List<Stored>>() {});
            }
        } catch (Exception e) {
            LOG.warn("Could not read OAuth signing keys: {}", e.toString());
        }
        return new ArrayList<>();
    }

    private static void write(List<Stored> stored) throws Exception {
        long now = System.currentTimeMillis();
        SystemProperty p = new SystemProperty();
        p.setId(P_KEYS);
        p.setName(P_KEYS);
        p.setGroupname("oauth");
        p.setValue(Jwt.MAPPER.writeValueAsString(stored));
        p.setMts(now);
        p.setUts(now);
        SystemPropertyDB.getInstance().save(List.of(p));
    }
}
