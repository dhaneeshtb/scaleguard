package com.scaleguard.server.certificates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.db.CertificateOrdersDB;
import com.scaleguard.server.db.DBModelSystem;
import com.scaleguard.server.db.SourceSystemDB;
import com.scaleguard.server.http.router.ConfigManager;
import com.scaleguard.server.http.router.SourceSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Background daemon that periodically checks SSL/TLS certificate expiry dates
 * and automatically renews certificates before they expire.
 *
 * <p>Let's Encrypt certificates are valid for 90 days. This watcher checks
 * every 12 hours and renews certificates that are within 30 days of expiring,
 * following the Let's Encrypt recommended renewal window.</p>
 *
 * <p>Only certificates linked to source systems with {@code autoProcure=true}
 * are eligible for auto-renewal.</p>
 */
public class CertificateRenewalWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(CertificateRenewalWatcher.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * How often to check for expiring certificates (in milliseconds).
     * Default: 12 hours. Configurable via system property.
     */
    private static final long CHECK_INTERVAL_MS = Long.parseLong(
            System.getProperty("cert.renewal.interval.hours", "12")) * 60 * 60 * 1000;

    /**
     * How many days before expiry to trigger renewal.
     * Default: 30 days (Let's Encrypt recommends renewing at 60 days remaining = 30 days before expiry).
     */
    private static final int RENEW_BEFORE_DAYS = Integer.parseInt(
            System.getProperty("cert.renewal.days.before", "30"));

    private static CertificateRenewalWatcher instance;

    private volatile boolean running = false;

    private CertificateRenewalWatcher() {
    }

    public static synchronized CertificateRenewalWatcher getInstance() {
        if (instance == null) {
            instance = new CertificateRenewalWatcher();
        }
        return instance;
    }

    /**
     * Start the background renewal watcher as a daemon thread.
     * Safe to call multiple times — only starts once.
     */
    public void start() {
        if (running) {
            LOG.info("Certificate renewal watcher is already running");
            return;
        }
        running = true;

        Thread watcherThread = new Thread(this::watchLoop, "cert-renewal-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();

        LOG.info("🔐 Certificate auto-renewal watcher started " +
                "(check interval: {}h, renew before: {} days)",
                CHECK_INTERVAL_MS / (60 * 60 * 1000), RENEW_BEFORE_DAYS);
    }

    /**
     * Stop the watcher. The daemon thread will exit on the next iteration.
     */
    public void stop() {
        running = false;
        LOG.info("Certificate renewal watcher stopping...");
    }

    private void watchLoop() {
        // Wait 60 seconds after startup before first check — let the server fully initialize
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        while (running) {
            try {
                checkAndRenewCertificates();
            } catch (Exception e) {
                LOG.error("Error during certificate renewal check", e);
            }

            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOG.info("Certificate renewal watcher stopped");
    }

    /**
     * Check all certificates and renew those approaching expiry.
     * This is the core method — also callable manually for on-demand checks.
     */
    public void checkAndRenewCertificates() {
        LOG.info("Checking certificates for auto-renewal...");
        int checked = 0;
        int renewed = 0;
        int failed = 0;

        try {
            List<DBModelSystem> certRecords = CertificateOrdersDB.getInstance().readAll();

            for (DBModelSystem certRecord : certRecords) {
                checked++;
                try {
                    if (shouldRenew(certRecord)) {
                        String certId = certRecord.getId();
                        LOG.info("Certificate {} is approaching expiry, attempting renewal...", certId);

                        if (renewViaSourceSystem(certId)) {
                            renewed++;
                            LOG.info("✅ Certificate {} renewed successfully", certId);
                        } else {
                            // Fall back to direct certificate renewal
                            try {
                                CertificatesRoute.getCm().renewCertificate(certId);
                                renewed++;
                                LOG.info("✅ Certificate {} renewed via direct renewal", certId);
                            } catch (Exception e) {
                                failed++;
                                LOG.error("❌ Failed to renew certificate {}: {}", certId, e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    failed++;
                    LOG.error("Error processing certificate {}: {}", certRecord.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to load certificate records for renewal check", e);
        }

        LOG.info("Certificate renewal check complete: checked={}, renewed={}, failed={}",
                checked, renewed, failed);
    }

    /**
     * Determine if a certificate should be renewed based on its expiry time.
     */
    boolean shouldRenew(DBModelSystem certRecord) {
        if (certRecord.getPayload() == null || certRecord.getPayload().isEmpty()) {
            return false;
        }

        try {
            JsonNode payload = mapper.readTree(certRecord.getPayload());

            // Check if the certificate has a valid status — only renew VALID certificates
            if (payload.has("json")) {
                JsonNode inner = payload.get("json");
                if (inner.isTextual()) {
                    inner = mapper.readTree(inner.asText());
                }
                if (inner.has("status")) {
                    String status = inner.get("status").asText();
                    if (!"valid".equalsIgnoreCase(status)) {
                        return false; // Don't renew pending/invalid/expired orders
                    }
                }
            }

            // Check expiry time
            if (payload.has("expiryTime")) {
                long expiryTimeMs = payload.get("expiryTime").asLong();
                long now = System.currentTimeMillis();
                long renewThresholdMs = TimeUnit.DAYS.toMillis(RENEW_BEFORE_DAYS);
                long timeUntilExpiry = expiryTimeMs - now;

                if (timeUntilExpiry <= 0) {
                    LOG.warn("Certificate {} has ALREADY EXPIRED (expired {}ms ago)",
                            certRecord.getId(), Math.abs(timeUntilExpiry));
                    return true; // Expired — definitely try to renew
                }

                if (timeUntilExpiry <= renewThresholdMs) {
                    long daysRemaining = TimeUnit.MILLISECONDS.toDays(timeUntilExpiry);
                    LOG.info("Certificate {} expires in {} days (threshold: {} days) — eligible for renewal",
                            certRecord.getId(), daysRemaining, RENEW_BEFORE_DAYS);
                    return true;
                }

                return false; // Not yet approaching expiry
            }

            // No expiryTime recorded — can't determine, skip
            LOG.debug("Certificate {} has no expiryTime field, skipping", certRecord.getId());
            return false;

        } catch (Exception e) {
            LOG.warn("Failed to parse certificate payload for {}: {}", certRecord.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Try to renew via the linked SourceSystem (which handles the full flow:
     * order → challenge → verify → save → reload CertificateStore).
     *
     * @return true if renewal succeeded via a source system, false if no matching source found
     */
    private boolean renewViaSourceSystem(String certId) {
        try {
            List<DBModelSystem> allSources = SourceSystemDB.getInstance().readAll();
            for (DBModelSystem srcRecord : allSources) {
                try {
                    SourceSystem ss = mapper.readValue(srcRecord.getPayload(), SourceSystem.class);
                    if (ss.isAutoProcure() && certId.equals(ss.getCertificateId())) {
                        LOG.info("Auto-renewing certificate for source system: {} (host: {})",
                                ss.getId(), ss.getHost());
                        ConfigManager.renewSourceCertificate(ss);
                        return true;
                    }
                } catch (Exception e) {
                    LOG.warn("Error checking source system {}: {}", srcRecord.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to read source systems for renewal: {}", e.getMessage());
        }
        return false;
    }
}
