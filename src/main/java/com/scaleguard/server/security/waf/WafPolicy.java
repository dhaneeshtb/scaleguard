package com.scaleguard.server.security.waf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WAF settings for one site/route (source system).
 *
 * <ul>
 *   <li>{@code enabled} — inspect this route's traffic at all</li>
 *   <li>{@code mode} — "block" (the first blocking rule that matches rejects the request), "anomaly" (every match adds
 *       its severity score — critical 5, error 4, warning 3, notice 2 — and the request is rejected once the total
 *       reaches {@code anomalyThreshold}, as in OWASP CRS) or "detect" (nothing is blocked; matches are only logged)</li>
 *   <li>{@code paranoiaLevel} — 1–4: rules tagged with a higher paranoia level don't run (more rules = more false positives)</li>
 *   <li>{@code ruleSets} — imported rule sets (e.g. OWASP CRS) active on this route, in addition to the built-in rules</li>
 *   <li>{@code rules} — per-rule overrides; rules not listed use their defaults (enabled, default action)</li>
 *   <li>{@code inspectBody} — also inspect request bodies (form, JSON, XML, text) up to {@code maxBodyBytes}</li>
 * </ul>
 */
public class WafPolicy {

    public static class RuleSetting {
        public boolean enabled = true;
        /** "block" or "log"; null = the rule's default action. */
        public String action;

        public RuleSetting() {}

        public RuleSetting(boolean enabled, String action) {
            this.enabled = enabled;
            this.action = action;
        }
    }

    public boolean enabled = false;
    public String mode = "block";
    public boolean inspectBody = true;
    public int maxBodyBytes = 65_536;
    public Map<String, RuleSetting> rules = new LinkedHashMap<>();
    public int anomalyThreshold = 5;
    public int paranoiaLevel = 1;
    public java.util.List<String> ruleSets = new java.util.ArrayList<>();

    boolean anomaly() {
        return "anomaly".equalsIgnoreCase(mode);
    }

    boolean detectOnly() {
        return "detect".equalsIgnoreCase(mode);
    }

    RuleSetting setting(String ruleId) {
        return rules == null ? null : rules.get(ruleId);
    }
}
