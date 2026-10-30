package com.scaleguard.server.certificates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.concurrent.VirtualThreads;
import com.scaleguard.server.db.CertificateOrdersDB;
import com.scaleguard.server.db.DBModelSystem;
import com.scaleguard.server.db.SourceSystemDB;
import com.scaleguard.server.http.router.ConfigManager;
import com.scaleguard.server.http.router.SourceSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
     * How often to check and auto-verify pending certificates (in milliseconds).
     * Default: 60 seconds. Configurable via system property.
     */
    private static final long PENDING_CHECK_INTERVAL_MS = Long.parseLong(
            System.getProperty("cert.pending.interval.seconds", "60")) * 1000;

    /**
     * How many days before expiry to trigger renewal.
     * Default: 30 days (Let's Encrypt recommends renewing at 60 days remaining = 30 days before expiry).
     */
    private static final int RENEW_BEFORE_DAYS = Integer.parseInt(
            System.getProperty("cert.renewal.days.before", "30"));

    private static CertificateRenewalWatcher instance;

    // FIX #7: Use AtomicBoolean to prevent double-start races.
    // compareAndSet guarantees only one thread can transition false→true at a time,
    // so a stop()+start() sequence cannot launch a second watchLoop while the first
    // is still sleeping in its current PENDING_CHECK_INTERVAL_MS cycle.
    private final AtomicBoolean running = new AtomicBoolean(false);

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
        // FIX #7: compareAndSet guarantees only one thread transitions false→true.
        // A concurrent stop()+start() cannot launch a second watchLoop while the first
        // is still sleeping.
        if (!running.compareAndSet(false, true)) {
            LOG.info("Certificate renewal watcher is already running");
            return;
        }
        // Virtual thread: no OS thread held during sleep intervals
        VirtualThreads.submit("cert-renewal-watcher", this::watchLoop);
        LOG.info("\uD83D\uDD10 Certificate auto-renewal and verification watcher started " +
                "(renewal check: {}h, pending check: {}s, renew before: {} days)",
                CHECK_INTERVAL_MS / (60 * 60 * 1000), PENDING_CHECK_INTERVAL_MS / 1000, RENEW_BEFORE_DAYS);
    }

    /**
     * Stop the watcher. The daemon thread will exit on the next iteration.
     */
    public void stop() {
        running.set(false);
        LOG.info("Certificate renewal watcher stopping...");
    }

    private void watchLoop() {
        // Wait 15 seconds after startup before first check — let the server fully initialize
        try {
            Thread.sleep(15_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        long lastRenewalCheck = 0;

        while (running.get()) {
            long now = System.currentTimeMillis();

            // In a cluster only the leader talks to the ACME CA, so certificates aren't ordered twice
            if (!com.scaleguard.server.cluster.ClusterManager.isLeader()) {
                try {
                    Thread.sleep(PENDING_CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            // Check and auto-verify pending certificates on every cycle
            try {
                checkAndVerifyPendingCertificates();
            } catch (Exception e) {
                LOG.error("Error during pending certificate verification check", e);
            }

            // Check expiring/expired certificates every CHECK_INTERVAL_MS
            if (now - lastRenewalCheck >= CHECK_INTERVAL_MS) {
                try {
                    LOG.info("Running certificate renewal check...");
                    checkAndRenewCertificates();
                    lastRenewalCheck = now;
                } catch (Exception e) {
                    LOG.error("Error during certificate renewal check", e);
                }
            }

            try {
                Thread.sleep(PENDING_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOG.info("Certificate renewal watcher stopped");
    }

    /**
     * Check for any pending or ready certificate orders and attempt to auto-verify them.
     *
     * <p>FIX #2: Each pending certificate is verified on its own virtual thread so that
     * one slow/blocking {@code verifyOrder()} call (up to ~34 s) cannot delay verification
     * of the other pending certificates in the same cycle.</p>
     */
    public void checkAndVerifyPendingCertificates() {
        LOG.debug("Checking pending certificates for auto-verification...");
        int pending = 0;

        // FIX #2: Use AtomicIntegers so per-thread closures can update them safely.
        AtomicInteger verifiedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        try {
            List<DBModelSystem> certRecords = CertificateOrdersDB.getInstance().readAll();

            for (DBModelSystem certRecord : certRecords) {
                try {
                    if (isPending(certRecord)) {
                        pending++;
                        final String certId = certRecord.getId();
                        LOG.info("Found pending certificate {}, dispatching async verification...", certId);

                        // FIX #2: Each verification runs in its own virtual thread.
                        // verifyOrder() can block for up to 34 s; parallelising prevents
                        // one certificate from stalling all others.
                        VirtualThreads.submit("cert-verify-" + certId, () -> {
                            try {
                                String status = CertificatesRoute.getCm().verifyOrder(certId, "http");
                                if ("valid".equalsIgnoreCase(status)) {
                                    verifiedCount.incrementAndGet();
                                    LOG.info("✅ Pending certificate {} auto-verified successfully", certId);
                                } else {
                                    LOG.warn("Certificate {} verification returned status: {}", certId, status);
                                }
                            } catch (Exception e) {
                                failedCount.incrementAndGet();
                                LOG.warn("Failed to auto-verify pending certificate {}: {}", certId, e.getMessage());
                            }
                        });
                    }
                } catch (Exception e) {
                    LOG.error("Error checking pending certificate {}: {}", certRecord.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to load certificate records for pending verification check", e);
        }

        if (pending > 0) {
            LOG.info("Pending certificate check complete: {} dispatched for async verification", pending);
        }
    }

    /**
     * Determine if a certificate order is pending verification.
     */
    boolean isPending(DBModelSystem certRecord) {
        if (certRecord.getPayload() == null || certRecord.getPayload().isEmpty()) {
            return false;
        }

        try {
            JsonNode payload = mapper.readTree(certRecord.getPayload());
            if (payload.has("json")) {
                JsonNode inner = payload.get("json");
                if (inner.isTextual()) {
                    inner = mapper.readTree(inner.asText());
                }
                if (inner.has("status")) {
                    String status = inner.get("status").asText();
                    // FIX #4: "processing" is an intermediate ACME state (order submitted,
                    // CA is validating) — treat it as pending so the watcher keeps polling.
                    return "pending".equalsIgnoreCase(status)
                            || "ready".equalsIgnoreCase(status)
                            || "processing".equalsIgnoreCase(status);
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
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
                            // FIX #5: renewCertificate() only re-places the ACME order; the
                            // certificate is NOT yet issued.  The background pending-verifier
                            // will complete verification on the next cycle — don't claim success.
                            try {
                                CertificatesRoute.getCm().renewCertificate(certId);
                                renewed++;
                                LOG.info("✅ Certificate {} re-ordered via direct renewal — " +
                                        "background verifier will complete verification", certId);
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

            // Check expiry time FIRST — already-expired certificates should always
            // be renewed regardless of their ACME order status (which may have
            // transitioned away from "valid" once the cert expired).
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
                    // Check status BEFORE deciding to renew — only renew "valid" certs
                    // that are near expiry; skip pending/invalid orders.
                    if (payload.has("json")) {
                        JsonNode inner = payload.get("json");
                        if (inner.isTextual()) {
                            inner = mapper.readTree(inner.asText());
                        }
                        if (inner.has("status")) {
                            String status = inner.get("status").asText();
                            if (!"valid".equalsIgnoreCase(status)) {
                                LOG.info("Certificate {} expires in {} days but has status '{}' — skipping renewal",
                                        certRecord.getId(), daysRemaining, status);
                                return false;
                            }
                        }
                    }
                    LOG.info("Certificate {} expires in {} days (threshold: {} days) — eligible for renewal",
                            certRecord.getId(), daysRemaining, RENEW_BEFORE_DAYS);
                    return true;
                }
            }

            // FIX #10: Remove dead if-block — the outer if (payload.has("expiryTime")) above
            // already handled the only path that returns true; reaching here means there is no
            // expiryTime.  Log unconditionally (no wrapping if) and return false.
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
