package com.scaleguard.server.dns;

import com.scaleguard.server.http.utils.AppProperties;
import com.scaleguard.server.http.utils.CidrSet;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DNS server settings, read from application.properties, environment variables or -D system properties.
 *
 * <pre>
 * dnsServer=true                      start the DNS server (default false)
 * dnsPort=53                          UDP + TCP port
 * dnsProxy=1.1.1.1,8.8.8.8            upstream resolvers for names outside our zones (host or host:port)
 * dnsRecursion=true                   forward non-zone names for allowed clients
 * dnsRecursionAllow=127.0.0.0/8,...   CIDRs allowed to use recursion (default: loopback + private ranges)
 * dnsRateLimit=100                    queries per second per client (/32 for IPv4, /64 for IPv6); 0 = off
 * dnsRateBurst=200                    burst size for the rate limiter
 * dnsCacheSize=100000                 max cached upstream answers
 * dnsUpstreamTimeoutMs=1500           per-attempt upstream timeout
 * dnsAdvertiseIps=203.0.113.10        IPs returned for "base" zones (default: this host's public interface IPs)
 * dnsNameServers=ns1.example.com.     NS names served at each zone apex (optional)
 * dnsBlockedDomains=example.com.      suffixes refused outright (optional)
 * dnsTcpMaxConnections=2000           concurrent TCP clients
 * </pre>
 */
final class DnsConfig {

    final int port;
    final List<InetSocketAddress> upstreams;
    final boolean recursion;
    final CidrSet recursionAllow;
    final int rateLimit;
    final int rateBurst;
    final int cacheSize;
    final long upstreamTimeoutMs;
    final List<String> advertiseIps;
    final List<String> nameServers;
    final List<String> blockedSuffixes;
    final int tcpMaxConnections;

    private static volatile DnsConfig instance;
    /** Settings saved from the admin UI (replicated); they beat the dns* properties. */
    private static volatile java.util.Map<String, String> saved = java.util.Map.of();

    /** Forgets the cached config so the next get() re-reads saved settings (DnsService restarts the server after). */
    static synchronized void reset() {
        instance = null;
    }

    static DnsConfig get() {
        DnsConfig c = instance;
        if (c == null) {
            synchronized (DnsConfig.class) {
                if (instance == null) {
                    saved = DnsSettings.load();
                    instance = new DnsConfig();
                }
                c = instance;
            }
        }
        return c;
    }

    static boolean enabled() {
        return "true".equalsIgnoreCase(AppProperties.get("dnsServer").trim());
    }

    private DnsConfig() {
        port = intProp("dnsPort");
        upstreams = parseUpstreams(raw("dnsProxy"));
        recursion = !"false".equalsIgnoreCase(raw("dnsRecursion"));
        recursionAllow = CidrSet.parse(raw("dnsRecursionAllow"));
        rateLimit = intProp("dnsRateLimit");
        rateBurst = Math.max(rateLimit, intProp("dnsRateBurst"));
        cacheSize = intProp("dnsCacheSize");
        upstreamTimeoutMs = intProp("dnsUpstreamTimeoutMs");
        advertiseIps = list(raw("dnsAdvertiseIps"));
        nameServers = list(raw("dnsNameServers")).stream().map(DnsWire::normalize).toList();
        blockedSuffixes = list(raw("dnsBlockedDomains")).stream().map(DnsWire::normalize).toList();
        tcpMaxConnections = intProp("dnsTcpMaxConnections");
    }

    /** Saved setting, else the dns* property, else the default. */
    String raw(String key) {
        String v = saved.get(key);
        if (v == null || v.isBlank()) v = AppProperties.get(key);
        return v == null || v.isBlank() ? DnsSettings.DEFAULTS.get(key) : v.trim();
    }

    private int intProp(String key) {
        try { return Integer.parseInt(raw(key)); } catch (NumberFormatException e) { return Integer.parseInt(DnsSettings.DEFAULTS.get(key)); }
    }

    private static List<String> list(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<InetSocketAddress> parseUpstreams(String csv) {
        List<InetSocketAddress> out = new ArrayList<>();
        for (String s : list(csv)) {
            String host = s;
            int port = 53;
            if (s.startsWith("[")) { // [v6]:port
                int close = s.indexOf(']');
                host = s.substring(1, close);
                if (close + 2 < s.length() && s.charAt(close + 1) == ':') port = Integer.parseInt(s.substring(close + 2));
            } else if (s.indexOf(':') > 0 && s.indexOf(':') == s.lastIndexOf(':')) { // v4:port or name:port
                host = s.substring(0, s.indexOf(':'));
                port = Integer.parseInt(s.substring(s.indexOf(':') + 1));
            }
            out.add(new InetSocketAddress(host, port));
        }
        return out;
    }

    boolean isBlocked(String qname) {
        for (String s : blockedSuffixes) {
            if (qname.equals(s) || qname.endsWith("." + s)) return true;
        }
        return false;
    }
}
