package com.scaleguard.server.dns;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Is ScaleGuard set up as the name server for this domain?" Runs from this node, so it proves the server answers
 * and that the public DNS delegates the domain to our name servers, but not that port 53 is open from the internet.
 */
final class DnsDiagnostics {

    private static final String PUBLIC_RESOLVER = "1.1.1.1";

    private DnsDiagnostics() {}

    static Map<String, Object> run(String rawDomain) {
        String domain = DnsWire.normalize(rawDomain);
        String shown = domain.endsWith(".") ? domain.substring(0, domain.length() - 1) : domain;
        List<Map<String, Object>> checks = new ArrayList<>();
        int port = DnsConfig.get().port;

        // our name server names: dnsNameServers plus the "base" entries added in the UI
        Set<String> ours = new LinkedHashSet<>(DnsConfig.get().nameServers);
        DNSAddressBook.get().forEach((n, l) -> {
            if (l.stream().anyMatch(DNSAddressBook.WrappedDNSRecord::isBase)) ours.add(n);
        });
        List<String> ips = DNSAddressBook.advertisedIps();

        check(checks, "DNS server running on this node", DnsService.running(),
                DnsService.running() ? "listening on port " + port : "not running; use Start DNS server");
        check(checks, "Name server configured", !ours.isEmpty(),
                ours.isEmpty() ? "no name server hostname yet" : String.join(", ", ours));

        // 1. our own server answers for the zone
        try {
            List<String> soa = lookup("127.0.0.1", port, domain, "SOA");
            check(checks, "Local server answers for " + shown, !soa.isEmpty(),
                    soa.isEmpty() ? "no SOA for " + shown + ": declare it under Domains served" : "SOA " + soa.get(0));
        } catch (Exception e) {
            check(checks, "Local server answers for " + shown, false, e.getMessage());
        }

        // 1b. it also resolves to an address: a declared domain answers with the connected nodes' IPs
        try {
            List<String> a = lookup("127.0.0.1", port, domain, "A");
            check(checks, "Domain resolves to an address", !a.isEmpty(),
                    a.isEmpty() ? "no A answer for " + shown + ": add an A record, or declare it under Domains served (needs the latest build)" : String.join(", ", a));
        } catch (Exception e) {
            check(checks, "Domain resolves to an address", false, e.getMessage());
        }

        // 2. the public DNS delegates the domain to us
        try {
            List<String> delegated = lookup(PUBLIC_RESOLVER, 53, domain, "NS").stream().map(DnsWire::normalize).toList();
            boolean any = delegated.stream().anyMatch(ours::contains);
            check(checks, "Domain delegated to ScaleGuard", any,
                    delegated.isEmpty() ? "the public DNS has no NS records for " + shown
                            : "public NS: " + String.join(", ", delegated) + (any ? "" : " (none of ours)"));
        } catch (Exception e) {
            check(checks, "Domain delegated to ScaleGuard", false, "could not query " + PUBLIC_RESOLVER + ": " + e.getMessage());
        }

        // 3. each of our name server names resolves publicly to this server's IPs, and answers there
        for (String ns : ours) {
            try {
                List<String> addrs = lookup(PUBLIC_RESOLVER, 53, ns, "A");
                boolean match = addrs.stream().anyMatch(ips::contains);
                check(checks, "Glue/A record for " + ns, match,
                        addrs.isEmpty() ? "no public A record: create it at your registrar or DNS host"
                                : addrs + (match ? "" : " but this server advertises " + ips));
                for (String ip : addrs) {
                    if (!ips.contains(ip)) continue;
                    try {
                        boolean up = !lookup(ip, 53, domain, "SOA").isEmpty();
                        check(checks, "Answers on " + ip + ":53", up, up ? "reachable from this node" : "no answer for " + shown);
                    } catch (Exception e) {
                        check(checks, "Answers on " + ip + ":53", false, e.getMessage() + " (firewall: allow UDP+TCP 53)");
                    }
                }
            } catch (Exception e) {
                check(checks, "Glue/A record for " + ns, false, e.getMessage());
            }
        }

        boolean ok = checks.stream().allMatch(c -> (Boolean) c.get("ok"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", shown);
        out.put("ok", ok);
        out.put("checks", checks);
        out.put("instructions", instructions(shown, ours, ips, port));
        return out;
    }

    /** Whole configuration: the server-level checks, then {@link #run} for every declared domain. */
    static Map<String, Object> runAll() {
        List<Map<String, Object>> checks = new ArrayList<>();
        DnsConfig cfg = DnsConfig.get();
        boolean running = DnsService.running();
        check(checks, "DNS server running on this node", running, running ? "listening on port " + cfg.port : "not running; use Start DNS server");

        Set<String> ours = new LinkedHashSet<>(cfg.nameServers);
        check(checks, "Name servers configured", !ours.isEmpty(), ours.isEmpty() ? "add at least one under Server settings" : String.join(", ", ours));

        List<String> ips = DNSAddressBook.advertisedIps();
        check(checks, "Public IPs detected", !ips.isEmpty(), ips.isEmpty() ? "none found on this host's interfaces; set dnsAdvertiseIps in application.properties" : String.join(", ", ips));

        if (cfg.recursion) {
            for (java.net.InetSocketAddress up : cfg.upstreams) {
                String where = up.getHostString() + ":" + up.getPort();
                try {
                    boolean ok = !lookup(up.getHostString(), up.getPort(), "example.com.", "A").isEmpty();
                    check(checks, "Forwarder " + where, ok, ok ? "answers" : "no answer for example.com");
                } catch (Exception e) {
                    check(checks, "Forwarder " + where, false, e.getMessage());
                }
            }
        }

        if (com.scaleguard.server.cluster.ClusterManager.enabled()) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> nodes = (List<Map<String, Object>>) com.scaleguard.server.cluster.ClusterManager.get().snapshot().get("nodes");
                for (Map<String, Object> n : nodes == null ? List.<Map<String, Object>>of() : nodes) {
                    if (!Boolean.TRUE.equals(n.get("alive"))) continue;
                    boolean self = Boolean.TRUE.equals(n.get("self"));
                    Object info = n.get("info");
                    boolean up = self ? running : info instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("dnsRunning"));
                    check(checks, "Node " + n.get("node") + " DNS server", up == DnsService.desired(),
                            up ? "running" : "stopped" + (DnsService.desired() ? " (should be running)" : ""));
                }
            } catch (RuntimeException ignored) {
                // membership unreadable right now: the per-node checks are skipped, not failed
            }
        }

        List<Map<String, Object>> domains = new ArrayList<>();
        DNSAddressBook.get().forEach((name, l) -> {
            if (l.stream().anyMatch(r -> "zone".equalsIgnoreCase(r.getType()))) domains.add(run(name));
        });
        check(checks, "Domains declared", !domains.isEmpty(), domains.isEmpty() ? "none yet: add one under Domains served" : domains.size() + " domain(s)");

        boolean ok = checks.stream().allMatch(c -> (Boolean) c.get("ok")) && domains.stream().allMatch(d -> (Boolean) d.get("ok"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("checks", checks);
        out.put("domains", domains);
        return out;
    }

    private static List<String> instructions(String domain, Set<String> ns, List<String> ips, int port) {
        String names = ns.isEmpty() ? "ns1." + domain : String.join(", ", ns);
        String ipList = String.join(", ", ips);
        int dot = domain.indexOf('.');
        String label = dot > 0 ? domain.substring(0, dot) : domain;
        String parent = dot > 0 ? domain.substring(dot + 1) : domain;
        return List.of(
                "1. Start the DNS server here and open UDP and TCP port " + port + " to the internet on this host and every node.",
                "2. Under Server settings, add the name servers (" + names + ") and declare " + domain + " under Domains served.",
                "3. Subdomain of a domain you own (e.g. " + domain + "): at the DNS host of " + parent + " add an A record for each name server inside it (" + names + " -> " + ipList + ") and an NS record with host \"" + label + "\" pointing to " + names + ". The rest of " + parent + " keeps working as before.",
                "4. A registered domain itself: at the registrar set its name servers to " + names + " and register them as glue (personal name servers) with " + ipList + ". This moves the whole domain here, so add all its records first.",
                "5. Add records for " + domain + " here. Delegation can take up to 48 hours to spread; run the test again after.");
    }

    private static void check(List<Map<String, Object>> checks, String name, boolean ok, String detail) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("ok", ok);
        c.put("detail", detail);
        checks.add(c);
    }

    /** Asks one specific server; NXDOMAIN gives an empty list, timeouts and refusals throw. */
    private static List<String> lookup(String server, int port, String name, String type) throws Exception {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put(Context.PROVIDER_URL, "dns://" + server + ":" + port);
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext ctx = new InitialDirContext(env);
        try {
            Attribute attr = ctx.getAttributes(name, new String[]{type}).get(type);
            List<String> out = new ArrayList<>();
            if (attr != null) {
                NamingEnumeration<?> e = attr.getAll();
                while (e.hasMore()) out.add(e.next().toString());
            }
            return out;
        } catch (NameNotFoundException e) {
            return List.of();
        } finally {
            ctx.close();
        }
    }
}
