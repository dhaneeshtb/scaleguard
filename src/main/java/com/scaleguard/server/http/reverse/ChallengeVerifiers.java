package com.scaleguard.server.http.reverse;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.router.HostGroupWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ChallengeVerifiers {
    private static final Logger LOG = LoggerFactory.getLogger(ChallengeVerifiers.class);

    public static void verifyDNS(JsonNode challengeNode){
        String dr = challengeNode.get("domainRecordName").asText();
        String dnsRecordValue = challengeNode.get("dnsRecordValue").asText();
        String resp = DnsTxtLookup.getTXTDNSRecord(dr);
        if (resp != null && resp.trim().equalsIgnoreCase(dnsRecordValue)) {
            LOG.info("Configuration success for " + dr + " with " + dnsRecordValue);
        }else{
            throw new RuntimeException("DNS configuration not found for " + dr + " with txt record " + dnsRecordValue);
        }

    }

    public static void verifyHTTP(JsonNode challengeNode){
        String domain = challengeNode.get("domain").asText();
        String path = challengeNode.get("path").asText();
        String token = challengeNode.get("token").asText();
        String content = challengeNode.get("content").asText();

        String url = "http://" + domain + path + token;
        // FIX #3: Use the throwing overload so a network failure surfaces as a proper
        // RuntimeException with the URL and cause, instead of silently returning ""
        // and then misleadingly reporting a "content mismatch".
        try {
            String response = HostGroupWatcher.getResponseDataOrThrow(url);
            if (!response.equalsIgnoreCase(content)) {
                throw new RuntimeException("invalid content or unavailable at " + url);
            } else {
                LOG.info("Configuration success for " + url);
            }
        } catch (IOException e) {
            throw new RuntimeException("HTTP challenge endpoint unreachable at " + url + ": " + e.getMessage(), e);
        }
    }
}
