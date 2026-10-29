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

    static DnsConfig get() {
        DnsConfig c = instance;
        if (c == null) {
            synchronized (DnsConfig.class) {
                if (instance == null) instance = new DnsConfig();
                c = instance;
            }
        }
        return c;
    }

    static boolean enabled() {
        return "true".equalsIgnoreCase(AppProperties.get("dnsServer").trim());
    }

    private DnsConfig() {
        port = intProp("dnsPort", 53);
        upstreams = parseUpstreams(str("dnsProxy", "1.1.1.1,8.8.8.8"));
        recursion = !"false".equalsIgnoreCase(str("dnsRecursion", "true"));
        recursionAllow = CidrSet.parse(str("dnsRecursionAllow",
                "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,fc00::/7"));
        rateLimit = intProp("dnsRateLimit", 100);
        rateBurst = Math.max(rateLimit, intProp("dnsRateBurst", 200));
        cacheSize = intProp("dnsCacheSize", 100_000);
        upstreamTimeoutMs = intProp("dnsUpstreamTimeoutMs", 1500);
        advertiseIps = list(str("dnsAdvertiseIps", ""));
        nameServers = list(str("dnsNameServers", "")).stream().map(DnsWire::normalize).toList();
        blockedSuffixes = list(str("dnsBlockedDomains", "")).stream().map(DnsWire::normalize).toList();
        tcpMaxConnections = intProp("dnsTcpMaxConnections", 2000);
    }

    private static String str(String key, String def) {
        String v = AppProperties.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int intProp(String key, int def) {
        try { return Integer.parseInt(str(key, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
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
