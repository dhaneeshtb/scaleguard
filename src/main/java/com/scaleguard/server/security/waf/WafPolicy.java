package com.scaleguard.server.security.waf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WAF settings for one site/route (source system).
 *
 * <ul>
 *   <li>{@code enabled} — inspect this route's traffic at all</li>
 *   <li>{@code mode} — "block" (rules act as configured) or "detect" (nothing is blocked; matches are only logged)</li>
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

    boolean detectOnly() {
        return "detect".equalsIgnoreCase(mode);
    }

    RuleSetting setting(String ruleId) {
        return rules == null ? null : rules.get(ruleId);
    }
}
