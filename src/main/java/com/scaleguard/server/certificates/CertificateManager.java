package com.scaleguard.server.certificates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scaleguard.server.db.CertificateOrdersDB;
import com.scaleguard.server.db.DBModelSystem;
import com.scaleguard.server.http.reverse.ChallengeVerifiers;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.challenge.TokenChallenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import java.util.UUID;

public class CertificateManager {
    private static final Logger LOG = LoggerFactory.getLogger(CertificateManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    AcmeContext context;

    public CertificateManager() {
        try {
            context = AcmeUtils.getContext();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // In-memory cache for loadAll — certificates rarely change but the UI polls frequently
    private volatile JsonNode cachedAll = null;
    private volatile long cacheTimestamp = 0;
    private static final long CACHE_TTL_MS = 30_000; // 30 seconds

    public void invalidateCache() {
        cachedAll = null;
        cacheTimestamp = 0;
    }

    public JsonNode loadAll() throws AcmeException, IOException {
        long now = System.currentTimeMillis();
        if (cachedAll != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedAll;
        }

        // Single query: read ALL records at once instead of N+1
        ArrayNode an = mapper.createArrayNode();
        CertificateOrdersDB.getInstance().readAll().forEach(dms -> {
            try {
                if (dms.getPayload() != null && !dms.getPayload().isEmpty()) {
                    an.add(mapper.readTree(dms.getPayload()));
                }
            } catch (IOException e) {
                LOG.warn("Failed to parse certificate payload for id={}", dms.getId(), e);
            }
        });

        cachedAll = an;
        cacheTimestamp = now;
        return an;
    }

    public JsonNode orderCertificate(List<String> domains, String id) throws AcmeException, IOException {
        invalidateCache();
        LoadOrder loader = new LoadOrder(context);
        return loader.createOrder(domains, id == null ? UUID.randomUUID().toString() : id);
    }

    public JsonNode checkStatus(String orderId) throws AcmeException, IOException {
        LoadOrder loader = new LoadOrder(context);
        return loader.loadOrderStatus(orderId);
    }

    public JsonNode readCertificates(String orderId) throws IOException {
        return AcmeUtils.readCertificate(orderId);
    }

    public void delete(String orderId) throws AcmeException, IOException {
        AcmeUtils.deleteCertificate(orderId);
        invalidateCache();
    }

    public JsonNode renewCertificate(String orderId) throws AcmeException, IOException {
        // Read existing DB record — domains are stored in the 'name' field as comma-separated values
        List<DBModelSystem> records;
        try {
            records = CertificateOrdersDB.getInstance().readItems("id", orderId);
        } catch (Exception e) {
            throw new IOException("Failed to read certificate: " + orderId, e);
        }
        if (records == null || records.isEmpty()) {
            throw new IOException("Certificate not found: " + orderId);
        }

        DBModelSystem record = records.get(0);
        String domainStr = record.getName();

        // Fallback: if name is empty, parse from the payload's json field
        if (domainStr == null || domainStr.trim().isEmpty()) {
            String payload = record.getPayload();
            if (payload != null) {
                JsonNode payloadNode = mapper.readTree(payload);
                JsonNode jsonField = payloadNode.get("json");
                if (jsonField != null) {
                    // json field is a stringified JSON — parse it
                    JsonNode inner = jsonField.isTextual() ? mapper.readTree(jsonField.asText()) : jsonField;
                    JsonNode identifiers = inner.get("identifiers");
                    if (identifiers != null && identifiers.isArray()) {
                        List<String> extracted = new ArrayList<>();
                        identifiers.forEach(id -> {
                            if (id.has("value")) extracted.add(id.get("value").asText());
                        });
                        domainStr = String.join(",", extracted);
                    }
                }
            }
        }

        if (domainStr == null || domainStr.trim().isEmpty()) {
            throw new IOException("No domains found for certificate: " + orderId);
        }

        List<String> domains = new ArrayList<>();
        for (String d : domainStr.split(",")) {
            String trimmed = d.trim();
            if (!trimmed.isEmpty()) domains.add(trimmed);
        }

        LOG.info("Renewing certificate {} for domains: {}", orderId, domains);

        // Delete old order and create fresh one with same ID
        AcmeUtils.deleteCertificate(orderId);
        invalidateCache();
        return orderCertificate(domains, orderId);
    }

    public Order getOrder(String orderId) throws AcmeException, IOException {
        LoadOrder loader = new LoadOrder(context);
        return loader.loadOrder(orderId);
    }

    //c71175ea-bc39-44c1-a90e-210083ad549f
    public String verifyOrder(String id, String challengeType) throws AcmeException, IOException {
        Order order = getOrder(id);
        order.update();
        List<Challenge> challenges = new ArrayList<>();
        LOG.info("Location {}", order.getLocation());
        //String challengeType="http";//http or dns


        if (order.getStatus().equals(Status.PENDING)) {
            order.getAuthorizations().forEach(auth -> {
                TokenChallenge challenge = null;
                Http01Challenge challengeHTTP = auth.findChallenge(Http01Challenge.class).orElse(null);
                Dns01Challenge challengeDNS = auth.findChallenge(Dns01Challenge.class).orElse(null);
                if ("http".equalsIgnoreCase(challengeType) && challengeHTTP != null) {
                    challenge = challengeHTTP;
                } else if ("dns".equalsIgnoreCase(challengeType) && challengeDNS != null) {
                    challenge = challengeDNS;

                }
//                TokenChallenge challenge= "http".equalsIgnoreCase(challengeType)?challengeHTTP:challengeDNS;
                if (challenge != null) {
                    try {
                        JsonNode x = AcmeUtils.readCachedCertificate(id);
                        if ("dns".equalsIgnoreCase(challengeType)) {
                            ChallengeVerifiers.verifyDNS(x.get("dnsChallenge"));
                        } else {
                            ChallengeVerifiers.verifyHTTP(x.get("httpChallenge"));
                        }

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    try {
                        challenge.trigger();
                        challenges.add(challenge);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        if (order.getStatus().equals(Status.READY)) {

            LOG.info("Executing Domain Order ###");
            order.execute(context.getDomainKeyPair());
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        int attempts = 10;
        while (order.getStatus() != Status.VALID && attempts-- > 0) {
            LOG.info("Checking status" + order.getStatus());

            if (order.getStatus() == Status.INVALID) {


                LOG.info("Challenge failed... Giving up.");
                break;
            } else if (order.getStatus().equals(Status.READY)) {
                LOG.info("Executing inner ready" + order.getStatus());
                order.execute(context.getDomainKeyPair());
            }
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            order.update();
        }

        if (order.getStatus().equals(Status.VALID)) {
            LOG.info("Final status {}", order.getStatus());

            AcmeUtils.saveCertificate(id, order, context);
        } else {
            AcmeUtils.saveOrder(order, id);
        }
        invalidateCache();
        return order.getStatus().toString();

    }


}
