package com.scaleguard.server.certificates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.db.DBModelSystem;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Tests for CertificateRenewalWatcher — expiry detection and renewal eligibility.
 */
public class CertificateRenewalWatcherTest {

    private CertificateRenewalWatcher watcher;
    private ObjectMapper mapper;

    @Before
    public void setUp() {
        watcher = CertificateRenewalWatcher.getInstance();
        mapper = new ObjectMapper();
    }

    @Test
    public void testShouldNotRenewWhenExpiryFarAway() {
        // Certificate expires in 60 days — should NOT renew (threshold is 30 days)
        DBModelSystem cert = createCertRecord("cert-1",
                System.currentTimeMillis() + TimeUnit.DAYS.toMillis(60), "valid");
        assertFalse("Should not renew cert expiring in 60 days", watcher.shouldRenew(cert));
    }

    @Test
    public void testShouldRenewWhenWithinThreshold() {
        // Certificate expires in 15 days — SHOULD renew (within 30-day threshold)
        DBModelSystem cert = createCertRecord("cert-2",
                System.currentTimeMillis() + TimeUnit.DAYS.toMillis(15), "valid");
        assertTrue("Should renew cert expiring in 15 days", watcher.shouldRenew(cert));
    }

    @Test
    public void testShouldRenewWhenExpired() {
        // Certificate already expired 5 days ago
        DBModelSystem cert = createCertRecord("cert-3",
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5), "valid");
        assertTrue("Should renew already-expired cert", watcher.shouldRenew(cert));
    }

    @Test
    public void testShouldNotRenewWhenExpiryExactlyAtThreshold() {
        // Certificate expires in exactly 30 days — SHOULD renew (<=30)
        DBModelSystem cert = createCertRecord("cert-4",
                System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30), "valid");
        assertTrue("Should renew cert expiring at exactly the threshold", watcher.shouldRenew(cert));
    }

    @Test
    public void testShouldNotRenewPendingCertificate() {
        // Certificate is pending — should NOT auto-renew
        DBModelSystem cert = createCertRecord("cert-5",
                System.currentTimeMillis() + TimeUnit.DAYS.toMillis(5), "pending");
        assertFalse("Should not renew pending certificate", watcher.shouldRenew(cert));
    }

    @Test
    public void testShouldNotRenewInvalidCertificate() {
        // Certificate is invalid — should NOT auto-renew
        DBModelSystem cert = createCertRecord("cert-6",
                System.currentTimeMillis() + TimeUnit.DAYS.toMillis(5), "invalid");
        assertFalse("Should not renew invalid certificate", watcher.shouldRenew(cert));
    }

    @Test
    public void testShouldNotRenewNullPayload() {
        DBModelSystem cert = new DBModelSystem();
        cert.setId("cert-7");
        cert.setPayload(null);
        assertFalse("Should not renew with null payload", watcher.shouldRenew(cert));
    }

    @Test
    public void testShouldNotRenewEmptyPayload() {
        DBModelSystem cert = new DBModelSystem();
        cert.setId("cert-8");
        cert.setPayload("");
        assertFalse("Should not renew with empty payload", watcher.shouldRenew(cert));
    }

    @Test
    public void testShouldNotRenewWithoutExpiryTime() {
        // Certificate has no expiryTime field
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", "cert-9");
        ObjectNode jsonNode = mapper.createObjectNode();
        jsonNode.put("status", "valid");
        payload.put("json", jsonNode.toString());

        DBModelSystem cert = new DBModelSystem();
        cert.setId("cert-9");
        cert.setPayload(payload.toString());
        assertFalse("Should not renew without expiryTime", watcher.shouldRenew(cert));
    }

    @Test
    public void testSingletonInstance() {
        CertificateRenewalWatcher instance1 = CertificateRenewalWatcher.getInstance();
        CertificateRenewalWatcher instance2 = CertificateRenewalWatcher.getInstance();
        assertSame("Should return same singleton instance", instance1, instance2);
    }

    @Test
    public void testShouldNotRenewMalformedPayload() {
        DBModelSystem cert = new DBModelSystem();
        cert.setId("cert-malformed");
        cert.setPayload("this is not json");
        assertFalse("Should not renew with malformed payload", watcher.shouldRenew(cert));
    }

    // --- Helper ---

    private DBModelSystem createCertRecord(String id, long expiryTimeMs, String status) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", id);
        payload.put("expiryTime", expiryTimeMs);
        payload.put("creationTime", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60));

        ObjectNode jsonNode = mapper.createObjectNode();
        jsonNode.put("status", status);
        payload.put("json", jsonNode.toString());

        DBModelSystem cert = new DBModelSystem();
        cert.setId(id);
        cert.setPayload(payload.toString());
        return cert;
    }
}
