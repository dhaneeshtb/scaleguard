package com.scaleguard.server.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.ClientInfoEntriesDB;
import com.scaleguard.server.db.ClientInfoEntry;
import com.scaleguard.server.security.oauth.Secrets;
import com.scaleguard.utils.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth clients of security apps. A client has a public {@code clientid} and a secret that is stored only as a salted
 * hash (see {@link Secrets}); the plain secret exists once, in the response that creates or rotates it.
 */
public class ClientInfo {

    private ClientInfo() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientInfo.class);
    private static final Map<String, WrappeClientInfoRecord> clientInfoMap = new ConcurrentHashMap<>();
    private static volatile Map<String, WrappeClientInfoRecord> byClientId = Map.of();

    static {
        load();
    }

    /** OAuth settings kept in the client row's payload column. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        public List<String> scopes = new ArrayList<>();
        public Integer accessTokenTtlSeconds;      // null = gateway default
        public Integer refreshTokenTtlSeconds;     // null = gateway default
        public boolean refreshTokens = true;
        public boolean enabled = true;
        /** Tokens issued before this instant (ms) are rejected — "revoke all tokens". */
        public long notBefore;
        public String secretHint;
        public long secretUpdatedAt;
        public long createdAt;
    }

    public static class WrappeClientInfoRecord {
        private String id;
        private String name;
        private String description;
        private String appid;
        private String clientid;
        private String clientsecret;   // hash
        private long expiry;           // ms, 0 = never
        private Meta meta = new Meta();

        WrappeClientInfoRecord(String name, String description, String appid, String clientid, String clientsecret, long expiry, String id) {
            this.name = name;
            this.description = description;
            this.appid = appid;
            this.clientid = clientid;
            this.clientsecret = clientsecret;
            this.expiry = expiry;
            this.id = id != null ? id : UUID.randomUUID().toString();
        }

        @JsonIgnore
        public String getClientsecret() { return clientsecret; }
        public void setClientsecret(String clientsecret) { this.clientsecret = clientsecret; }
        public long getExpiry() { return expiry; }
        public void setExpiry(long expiry) { this.expiry = expiry; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getAppid() { return appid; }
        public void setAppid(String appid) { this.appid = appid; }
        public String getClientid() { return clientid; }
        public void setClientid(String clientid) { this.clientid = clientid; }
        @JsonIgnore
        public Meta meta() { return meta; }

        // flattened for the admin API / UI
        public List<String> getScopes() { return meta.scopes; }
        public Integer getAccessTokenTtlSeconds() { return meta.accessTokenTtlSeconds; }
        public Integer getRefreshTokenTtlSeconds() { return meta.refreshTokenTtlSeconds; }
        public boolean isRefreshTokens() { return meta.refreshTokens; }
        public boolean isEnabled() { return meta.enabled; }
        public long getNotBefore() { return meta.notBefore; }
        public String getSecretHint() { return meta.secretHint; }
        public long getSecretUpdatedAt() { return meta.secretUpdatedAt; }
        public long getCreatedAt() { return meta.createdAt; }

        /** Usable right now: enabled and not past its expiry date. */
        @JsonIgnore
        public boolean isUsable() {
            return meta.enabled && (expiry <= 0 || expiry > System.currentTimeMillis());
        }

        public boolean checkSecret(String presented) {
            return Secrets.matches(presented, clientsecret);
        }
    }

    public static Map<String, WrappeClientInfoRecord> get() {
        return clientInfoMap;
    }

    /** Lookup by the public client_id (what OAuth clients send). */
    public static WrappeClientInfoRecord byClientId(String clientId) {
        return clientId == null ? null : byClientId.get(clientId);
    }

    /** Creates a client with a fresh secret; returns the record and the plain secret (shown once). */
    public static Map.Entry<WrappeClientInfoRecord, String> create(String name, String description, String appid, String clientid,
                                                                  long expiry, Meta meta) {
        return create(name, description, appid, clientid, expiry, meta, null);
    }

    /** As above; {@code importSecret} (≥ 24 characters) keeps an existing secret when moving clients from elsewhere. */
    public static Map.Entry<WrappeClientInfoRecord, String> create(String name, String description, String appid, String clientid,
                                                                  long expiry, Meta meta, String importSecret) {
        if (clientid == null || clientid.isBlank()) clientid = Secrets.newClientId();
        if (!clientid.matches("[A-Za-z0-9._~-]{4,128}"))
            throw new IllegalArgumentException("client id may only use letters, digits and . _ ~ - (4-128 characters)");
        if (byClientId(clientid) != null) throw new IllegalArgumentException("a client with this client id already exists");
        if (importSecret != null && importSecret.length() < 24)
            throw new IllegalArgumentException("an imported client secret must be at least 24 characters; leave it out to generate one");
        String secret = importSecret != null ? importSecret : Secrets.newSecret();
        WrappeClientInfoRecord r = new WrappeClientInfoRecord(name, description, appid, clientid, Secrets.hash(secret), expiry,
                UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        r.meta = meta != null ? meta : new Meta();
        r.meta.secretHint = Secrets.hint(secret);
        r.meta.secretUpdatedAt = now;
        r.meta.createdAt = now;
        persist(r, true);
        put(r);
        Application.load();
        return Map.entry(r, secret);
    }

    /** Issues a new secret; the old one stops working immediately, and so do tokens issued with it. */
    public static String rotateSecret(String id) {
        WrappeClientInfoRecord r = require(id);
        String secret = Secrets.newSecret();
        r.clientsecret = Secrets.hash(secret);
        r.meta.secretHint = Secrets.hint(secret);
        r.meta.secretUpdatedAt = System.currentTimeMillis();
        r.meta.notBefore = r.meta.secretUpdatedAt;
        persist(r, false);
        com.scaleguard.server.security.oauth.RefreshTokens.revokeClient(r.clientid);
        return secret;
    }

    /** Invalidates every access and refresh token issued to the client so far. */
    public static void revokeTokens(String id) {
        WrappeClientInfoRecord r = require(id);
        r.meta.notBefore = System.currentTimeMillis();
        persist(r, false);
        com.scaleguard.server.security.oauth.RefreshTokens.revokeClient(r.clientid);
    }

    public static WrappeClientInfoRecord update(String id, String name, String description, Long expiry, Meta changes) {
        WrappeClientInfoRecord r = require(id);
        if (name != null && !name.isBlank()) r.name = name;
        if (description != null) r.description = description;
        if (expiry != null) r.expiry = expiry;
        if (changes != null) {
            boolean disabling = r.meta.enabled && !changes.enabled;
            r.meta.scopes = changes.scopes != null ? changes.scopes : r.meta.scopes;
            r.meta.accessTokenTtlSeconds = changes.accessTokenTtlSeconds;
            r.meta.refreshTokenTtlSeconds = changes.refreshTokenTtlSeconds;
            r.meta.refreshTokens = changes.refreshTokens;
            r.meta.enabled = changes.enabled;
            if (disabling) com.scaleguard.server.security.oauth.RefreshTokens.revokeClient(r.clientid);
        }
        persist(r, false);
        Application.load();
        return r;
    }

    private static WrappeClientInfoRecord require(String id) {
        WrappeClientInfoRecord r = id == null ? null : clientInfoMap.get(id);
        if (r == null) r = byClientId(id);
        if (r == null) throw new NoSuchElementException("client not found");
        return r;
    }

    private static void put(WrappeClientInfoRecord r) {
        clientInfoMap.put(r.id, r);
        reindex();
    }

    private static void reindex() {
        Map<String, WrappeClientInfoRecord> m = new HashMap<>();
        for (WrappeClientInfoRecord r : clientInfoMap.values()) if (r.clientid != null) m.put(r.clientid, r);
        byClientId = Map.copyOf(m);
    }

    /** Replaces the in-memory clients with the database's (another cluster node changed one). */
    public static synchronized void reload() {
        try {
            Set<String> ids = new HashSet<>();
            for (ClientInfoEntry e : ClientInfoEntriesDB.getInstance().readAll()) {
                ids.add(e.getId());
                clientInfoMap.put(e.getId(), fromEntry(e));
            }
            clientInfoMap.keySet().retainAll(ids);
            reindex();
        } catch (Exception e) {
            LOGGER.error("error reloading client info entries from db", e);
        }
    }

    private static void load() {
        try {
            for (ClientInfoEntry e : ClientInfoEntriesDB.getInstance().readAll()) {
                WrappeClientInfoRecord r = fromEntry(e);
                if (e.getClientsecret() != null && !Secrets.isHashed(e.getClientsecret())) {
                    // Older versions stored secrets in plain text: hash them in place (clients keep working).
                    r.meta.secretHint = Secrets.hint(e.getClientsecret());
                    r.clientsecret = Secrets.hash(e.getClientsecret());
                    if (r.meta.createdAt == 0) r.meta.createdAt = e.getMts();
                    if (r.meta.secretUpdatedAt == 0) r.meta.secretUpdatedAt = e.getMts();
                    try {
                        persist(r, false);
                        LOGGER.info("Client {}: stored secret replaced by its hash", r.clientid);
                    } catch (Exception ex) {
                        LOGGER.warn("Could not hash the stored secret of client {}: {}", r.clientid, ex.toString());
                    }
                }
                clientInfoMap.put(r.id, r);
            }
            reindex();
        } catch (Exception e) {
            LOGGER.error("error loading client info entries from db", e);
        }
    }

    private static WrappeClientInfoRecord fromEntry(ClientInfoEntry e) {
        WrappeClientInfoRecord r = new WrappeClientInfoRecord(e.getName(), e.getDescription(), e.getAppid(), e.getClientid(),
                e.getClientsecret(), e.getExpiry(), e.getId());
        if (e.getPayload() != null && !e.getPayload().isBlank()) {
            try {
                r.meta = com.scaleguard.utils.Constants.OBJECT_MAPPER.readValue(e.getPayload(), Meta.class);
            } catch (Exception ex) {
                LOGGER.warn("Client {}: unreadable OAuth settings, using defaults: {}", e.getClientid(), ex.toString());
            }
        }
        if (r.meta.scopes == null) r.meta.scopes = new ArrayList<>();
        return r;
    }

    private static void persist(WrappeClientInfoRecord r, boolean isNew) {
        try {
            ClientInfoEntry entry = new ClientInfoEntry();
            long now = System.currentTimeMillis();
            entry.setId(r.id);
            entry.setName(r.name);
            entry.setDescription(r.description == null ? "" : r.description);
            entry.setAppid(r.appid);
            entry.setClientid(r.clientid);
            entry.setClientsecret(r.clientsecret);
            entry.setExpiry(r.expiry);
            entry.setPayload(com.scaleguard.utils.Constants.OBJECT_MAPPER.writeValueAsString(r.meta));
            entry.setMts(r.meta.createdAt > 0 ? r.meta.createdAt : now);
            entry.setUts(now);
            if (isNew) ClientInfoEntriesDB.getInstance().create(entry);
            else ClientInfoEntriesDB.getInstance().save(entry);
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
    }

    public static void remove(String id) {
        WrappeClientInfoRecord rec = require(id);
        try {
            ClientInfoEntriesDB.getInstance().delete(rec.getId());
            clientInfoMap.remove(rec.getId());
            reindex();
            com.scaleguard.server.security.oauth.RefreshTokens.revokeClient(rec.clientid);
            Application.load();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
