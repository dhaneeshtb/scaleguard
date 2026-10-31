package com.scaleguard.server.dns;

import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import com.scaleguard.utils.JSON;
import io.netty.util.NetUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator-editable DNS server settings, stored as one JSON object in the replicated setting {@code dns.settings}
 * (scope {@code system_properties/dns}). A saved value beats the {@code dns*} property of the same name; a blank one
 * falls back to the property, then the default. Every node applies a change by restarting its DNS server.
 */
final class DnsSettings {

    static final String P_SETTINGS = "dns.settings";

    /** Editable keys with their defaults, in display order. */
    static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("dnsPort", "53");
        DEFAULTS.put("dnsProxy", "1.1.1.1,8.8.8.8");
        DEFAULTS.put("dnsLoadBalance", "true");
        DEFAULTS.put("dnsRecursion", "true");
        DEFAULTS.put("dnsRecursionAllow", "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,fc00::/7");
        DEFAULTS.put("dnsRateLimit", "100");
        DEFAULTS.put("dnsRateBurst", "200");
        DEFAULTS.put("dnsCacheSize", "100000");
        DEFAULTS.put("dnsUpstreamTimeoutMs", "1500");
        DEFAULTS.put("dnsTcpMaxConnections", "2000");
        DEFAULTS.put("dnsNameServers", "");
        DEFAULTS.put("dnsBlockedDomains", "");
    }

    private static final String HOST = "^([a-zA-Z0-9_]([a-zA-Z0-9_-]{0,61}[a-zA-Z0-9_])?\\.)*[a-zA-Z0-9_]([a-zA-Z0-9_-]{0,61}[a-zA-Z0-9_])?\\.?$";

    private DnsSettings() {}

    /** The saved JSON text ("" when never saved); compared by DnsService to see whether a restart is needed. */
    static String rawJson() {
        try {
            for (SystemProperty p : SystemPropertyDB.getInstance().readItems("id", P_SETTINGS)) {
                if (p.getValue() != null) return p.getValue();
            }
        } catch (Exception ignored) {
            // no database yet: properties and defaults apply
        }
        return "";
    }

    static Map<String, String> load() {
        String json = rawJson();
        if (json.isBlank()) return Map.of();
        try {
            Map<String, String> out = new LinkedHashMap<>();
            JSON.parse(json).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Validates and saves (replicated); returns an error message instead when a value is invalid. */
    static String save(Map<String, String> in) throws Exception {
        Map<String, String> merged = new LinkedHashMap<>(load());
        List<String> oldNs = List.of(split(merged.getOrDefault("dnsNameServers", "")));
        for (Map.Entry<String, String> e : in.entrySet()) {
            if (!DEFAULTS.containsKey(e.getKey())) return "unknown setting " + e.getKey();
            String v = e.getValue() == null ? "" : e.getValue().trim();
            String bad = validate(e.getKey(), v);
            if (bad != null) return bad;
            merged.put(e.getKey(), v);
        }
        long now = System.currentTimeMillis();
        SystemProperty p = new SystemProperty();
        p.setId(P_SETTINGS);
        p.setName(P_SETTINGS);
        p.setGroupname("dns");
        p.setValue(JSON.toString(merged));
        p.setMts(now);
        p.setUts(now);
        SystemPropertyDB.getInstance().save(List.of(p));
        // each name server hostname must resolve to this server's IPs: a "base" record (replicated like any record)
        List<String> newNs = List.of(split(merged.getOrDefault("dnsNameServers", "")));
        for (String ns : newNs) DNSAddressBook.add(ns, "base", "base", DNSAddressBook.DEFAULT_TTL);
        for (String ns : oldNs) if (!newNs.contains(ns)) DNSAddressBook.remove(ns, "base");
        return null;
    }

    static String validate(String key, String v) {
        if (v.isEmpty()) return null; // blank = back to the default
        switch (key) {
            case "dnsPort": return range(key, v, 1, 65535);
            case "dnsRateLimit": return range(key, v, 0, 1_000_000);
            case "dnsRateBurst": return range(key, v, 1, 10_000_000);
            case "dnsCacheSize": return range(key, v, 0, 10_000_000);
            case "dnsTcpMaxConnections": return range(key, v, 1, 1_000_000);
            case "dnsUpstreamTimeoutMs": return range(key, v, 100, 30_000);
            case "dnsRecursion": case "dnsLoadBalance":
                return v.matches("(?i)true|false") ? null : key + " must be true or false";
            case "dnsProxy":
                for (String s : split(v)) if (!validUpstream(s)) return "forwarder '" + s + "' must be an IP or host, optionally :port";
                return null;
            case "dnsRecursionAllow":
                for (String s : split(v)) if (!validCidr(s)) return "'" + s + "' is not an IP or CIDR range";
                return null;
            default: // dnsNameServers, dnsBlockedDomains
                for (String s : split(v)) if (!s.matches(HOST)) return "'" + s + "' is not a valid domain name";
                return null;
        }
    }

    private static String range(String key, String v, long lo, long hi) {
        try {
            long n = Long.parseLong(v);
            if (n >= lo && n <= hi) return null;
        } catch (NumberFormatException ignored) { /* falls through */ }
        return key + " must be a number from " + lo + " to " + hi;
    }

    private static String[] split(String csv) {
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static boolean validCidr(String s) {
        int slash = s.indexOf('/');
        byte[] a = NetUtil.createByteArrayFromIpAddressString(slash > 0 ? s.substring(0, slash) : s);
        if (a == null) return false;
        if (slash < 0) return true;
        try {
            int p = Integer.parseInt(s.substring(slash + 1));
            return p >= 0 && p <= a.length * 8;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean validUpstream(String s) {
        if (s.startsWith("[")) { // [v6]:port
            int close = s.indexOf(']');
            return close > 1 && NetUtil.createByteArrayFromIpAddressString(s.substring(1, close)) != null
                    && (close == s.length() - 1 || (s.charAt(close + 1) == ':' && s.substring(close + 2).matches("\\d{1,5}")));
        }
        if (NetUtil.createByteArrayFromIpAddressString(s) != null) return true; // bare v4 / v6
        int colon = s.lastIndexOf(':');
        String host = colon > 0 ? s.substring(0, colon) : s;
        return host.matches(HOST) && (colon < 0 || s.substring(colon + 1).matches("\\d{1,5}"));
    }

    /** Effective value of every key (saved, else property, else default) for the settings page. */
    static Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        DEFAULTS.keySet().forEach(k -> values.put(k, DnsConfig.get().raw(k)));
        out.put("settings", values);
        out.put("defaults", DEFAULTS);
        out.put("detectedIps", ClusterDnsIps.all()); // what a blank dnsAdvertiseIps resolves to right now
        return out;
    }
}
