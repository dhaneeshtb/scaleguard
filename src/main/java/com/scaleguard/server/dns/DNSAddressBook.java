package com.scaleguard.server.dns;

import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.DNSEntriesDB;
import com.scaleguard.server.db.DNSEntry;
import com.scaleguard.server.dns.DnsWire.Rr;
import com.scaleguard.server.system.SystemManager;
import io.netty.util.NetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The zones ScaleGuard is authoritative for.
 *
 * <p>Records are keyed by lower-case absolute name ("app.example.com."). A record also answers for
 * every name below it that has no closer record of its own, so a "base" entry for example.com makes
 * ScaleGuard answer *.example.com with its own public IPs (this is what tunnels rely on).
 *
 * <p>Record values:
 * <ul>
 *   <li>"base" — this server's advertised IPs (A for IPv4, AAAA for IPv6)</li>
 *   <li>an IPv4 / IPv6 literal — A / AAAA</li>
 *   <li>anything else — TXT (e.g. ACME DNS-01 tokens)</li>
 *   <li>or an explicit type "a", "aaaa", "txt", "cname", "mx" ("10 mail.example.com."), "ns", "ptr",
 *       "srv" ("10 5 5060 sip.example.com."), "caa" ("0 issue letsencrypt.org")</li>
 * </ul>
 *
 * <p>Reads are lock-free (copy-on-write lists); writes are serialised.
 */
public class DNSAddressBook {

    private DNSAddressBook() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(DNSAddressBook.class);
    public static final long DEFAULT_TTL = 600L;
    private static final int MAX_CNAME_DEPTH = 8;
    private static final long SOA_TTL = 3600;
    private static final int SOA_MINIMUM = 60;

    private static final Map<String, List<WrappedDNSRecord>> dnsAddressMap = new ConcurrentHashMap<>();
    private static final AtomicLong serial = new AtomicLong(System.currentTimeMillis() / 1000);

    static {
        load();
    }

    public static class WrappedDNSRecord {
        private String name;
        private String ip;
        private boolean base;
        private String id;
        private String type;
        private long ttl;

        WrappedDNSRecord(String name, String ip, String type, long ttl) {
            this.ip = ip;
            this.name = name;
            this.type = type;
            this.ttl = ttl;
            this.base = "base".equalsIgnoreCase(ip);
        }

        public boolean isBase() { return base; }
        public void setBase(boolean base) { this.base = base; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public long getTtl() { return ttl; }
        public void setTtl(long ttl) { this.ttl = ttl; }
        public String getType() { return type; }
        public String getName() { return name; }
        public String getIp() { return ip; }
    }

    /** Result of an authoritative lookup. */
    record AuthAnswer(int rcode, List<Rr> answers, List<Rr> authority) {}

    // ------------------------------------------------------------------ management API (unchanged signatures)

    public static void add(String name, String ip, String type, long ttl) {
        add(name, ip, type, ttl, UUID.randomUUID().toString(), true);
    }

    private static synchronized void add(String rawName, String ip, String type, long ttl, String id, boolean save) {
        String name = DnsWire.normalize(rawName);
        if (name == null || ".".equals(name) || ip == null) return;
        List<WrappedDNSRecord> list = dnsAddressMap.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>());
        WrappedDNSRecord existing = null;
        for (WrappedDNSRecord r : list) {
            if ("base".equalsIgnoreCase(ip) ? r.isBase() : r.getIp().equalsIgnoreCase(ip)) { existing = r; break; }
        }
        WrappedDNSRecord rec;
        boolean isNew = existing == null;
        if (isNew) {
            rec = new WrappedDNSRecord(name, ip, type, ttl);
            rec.setId(id);
            list.add(rec);
        } else {
            existing.ttl = ttl;
            existing.type = type;
            existing.ip = ip;
            existing.base = "base".equalsIgnoreCase(ip);
            rec = existing;
        }
        bumpSerial();
        if (save) persist(rec, isNew);
    }

    public static Map<String, List<WrappedDNSRecord>> get() {
        return Collections.unmodifiableMap(dnsAddressMap);
    }

    /** Replaces the in-memory zone with the database's (another cluster node changed a record). */
    public static synchronized void reload() {
        try {
            List<DNSEntry> entries = DNSEntriesDB.getInstance().readAll();
            Map<String, List<WrappedDNSRecord>> next = new java.util.HashMap<>();
            for (DNSEntry r : entries) {
                String name = DnsWire.normalize(r.getName());
                if (name == null || ".".equals(name) || r.getValue() == null) continue;
                WrappedDNSRecord rec = new WrappedDNSRecord(name, r.getValue(), r.getType(), r.getTtl() > 0 ? r.getTtl() : DEFAULT_TTL);
                rec.setId(r.getId());
                next.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(rec);
            }
            dnsAddressMap.putAll(next);
            dnsAddressMap.keySet().retainAll(next.keySet());
            bumpSerial();
            LOGGER.info("Reloaded {} DNS records for {} names", entries.size(), dnsAddressMap.size());
        } catch (Exception e) {
            LOGGER.error("error reloading DNS entries from db", e);
        }
    }

    private static void load() {
        try {
            List<DNSEntry> entries = DNSEntriesDB.getInstance().readAll();
            entries.forEach(r -> add(r.getName(), r.getValue(), r.getType(), r.getTtl() > 0 ? r.getTtl() : DEFAULT_TTL, r.getId(), false));
            LOGGER.info("Loaded {} DNS records for {} names", entries.size(), dnsAddressMap.size());
        } catch (Exception e) {
            LOGGER.error("error loading DNS entries from db", e);
        }
    }

    /** One database write per change: insert for a new record, update by id otherwise. */
    private static void persist(WrappedDNSRecord r, boolean isNew) {
        try {
            DNSEntry entry = new DNSEntry();
            entry.setId(r.getId());
            entry.setName(r.getName());
            entry.setTtl(r.getTtl());
            entry.setValue(r.getIp());
            entry.setType(r.getType());
            long now = System.currentTimeMillis();
            entry.setMts(now);
            if (isNew) {
                entry.setUts(now);
                DNSEntriesDB.getInstance().create(entry);
            } else {
                DNSEntriesDB.getInstance().edit(entry);
            }
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
    }

    public static synchronized void remove(String rawName, String ip) {
        String name = DnsWire.normalize(rawName);
        List<WrappedDNSRecord> list = dnsAddressMap.get(name);
        if (list == null) return;
        List<String> ids = new ArrayList<>();
        list.removeIf(s -> {
            boolean match = "base".equalsIgnoreCase(ip) || s.getIp().equalsIgnoreCase(ip);
            if (match) ids.add(s.getId());
            return match;
        });
        if (list.isEmpty()) dnsAddressMap.remove(name);
        bumpSerial();
        for (String id : ids) {
            try {
                DNSEntriesDB.getInstance().delete(id);
            } catch (Exception e) {
                throw new GenericServerProcessingException(e);
            }
        }
    }

    public static synchronized void remove(String id) throws Exception {
        if (id == null) return;
        boolean changed = false;
        for (Map.Entry<String, List<WrappedDNSRecord>> e : dnsAddressMap.entrySet()) {
            if (e.getValue().removeIf(s -> id.equalsIgnoreCase(s.getId()))) changed = true;
        }
        dnsAddressMap.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (changed) bumpSerial();
        DNSEntriesDB.getInstance().delete(id);
    }

    public static boolean isEntryExist(String name) {
        return closestOwner(DnsWire.normalize(name)) != null;
    }

    private static void bumpSerial() {
        long now = System.currentTimeMillis() / 1000;
        serial.updateAndGet(prev -> Math.max(prev + 1, now));
    }

    // ------------------------------------------------------------------ lookups

    /**
     * Authoritative answer for qname/qtype, or null when the name is outside our zones
     * (the caller then forwards or refuses).
     */
    static AuthAnswer resolve(String qname, int qtype) {
        return resolve(DnsWire.normalize(qname), qtype, 0);
    }

    private static AuthAnswer resolve(String qname, int qtype, int depth) {
        String owner = closestOwner(qname);
        if (owner == null) return null;
        String apex = zoneApex(owner);
        List<Rr> all = expand(dnsAddressMap.getOrDefault(owner, List.of()), qname);

        // Synthesised apex records
        if (qname.equals(apex)) {
            if (qtype == DnsWire.TYPE_SOA || qtype == DnsWire.TYPE_ANY) all = with(all, soa(apex));
            if (qtype == DnsWire.TYPE_NS || qtype == DnsWire.TYPE_ANY) all = with(all, nsRecords(apex));
        }

        List<Rr> answers = new ArrayList<>();
        if (qtype != DnsWire.TYPE_CNAME && qtype != DnsWire.TYPE_ANY) {
            Rr cname = all.stream().filter(r -> r.type() == DnsWire.TYPE_CNAME).findFirst().orElse(null);
            if (cname != null) {
                answers.add(cname);
                String target = DnsWire.nameFromRdata(cname.rdata());
                if (target != null && depth < MAX_CNAME_DEPTH) {
                    AuthAnswer chased = resolve(DnsWire.normalize(target), qtype, depth + 1);
                    if (chased != null) answers.addAll(chased.answers());
                }
                return new AuthAnswer(DnsWire.RCODE_NOERROR, answers, List.of());
            }
        }
        for (Rr r : all) {
            if (qtype == DnsWire.TYPE_ANY || r.type() == qtype) answers.add(r);
        }
        if (answers.isEmpty()) {
            // NODATA: the name exists but has no records of this type (RFC 2308) — include SOA for negative caching.
            Rr soa = soa(apex);
            return new AuthAnswer(DnsWire.RCODE_NOERROR, List.of(), List.of(soa.withTtl(Math.min(soa.ttl(), SOA_MINIMUM))));
        }
        return new AuthAnswer(DnsWire.RCODE_NOERROR, answers, List.of());
    }

    /** Walks up the labels of qname (a.b.example.com. → b.example.com. → example.com. → com.) to the closest stored name. */
    private static String closestOwner(String qname) {
        if (qname == null) return null;
        String n = qname;
        while (n.length() > 1) {
            List<WrappedDNSRecord> l = dnsAddressMap.get(n);
            if (l != null && !l.isEmpty()) return n;
            int dot = n.indexOf('.');
            if (dot < 0 || dot == n.length() - 1) return null;
            n = n.substring(dot + 1);
        }
        return null;
    }

    /** Nearest ancestor-or-self that holds a "base" record; otherwise the owner itself. */
    private static String zoneApex(String owner) {
        String n = owner;
        while (n.length() > 1) {
            List<WrappedDNSRecord> l = dnsAddressMap.get(n);
            if (l != null && l.stream().anyMatch(WrappedDNSRecord::isBase)) return n;
            int dot = n.indexOf('.');
            if (dot < 0 || dot == n.length() - 1) break;
            n = n.substring(dot + 1);
        }
        return owner;
    }

    private static List<Rr> expand(List<WrappedDNSRecord> records, String ownerName) {
        List<Rr> out = new ArrayList<>();
        for (WrappedDNSRecord r : records) {
            long ttl = r.getTtl() > 0 ? r.getTtl() : DEFAULT_TTL;
            if (r.isBase()) {
                for (String ip : advertisedIps()) addAddress(out, ownerName, ip, ttl);
                continue;
            }
            String value = r.getIp() == null ? "" : r.getIp().trim();
            String t = r.getType() == null ? "" : r.getType().trim().toLowerCase();
            switch (t) {
                case "a", "aaaa" -> addAddress(out, ownerName, value, ttl);
                case "txt" -> out.add(new Rr(ownerName, DnsWire.TYPE_TXT, DnsWire.CLASS_IN, ttl, DnsWire.txtRdata(value)));
                case "cname" -> out.add(new Rr(ownerName, DnsWire.TYPE_CNAME, DnsWire.CLASS_IN, ttl, DnsWire.encodeName(DnsWire.normalize(value))));
                case "ns" -> out.add(new Rr(ownerName, DnsWire.TYPE_NS, DnsWire.CLASS_IN, ttl, DnsWire.encodeName(DnsWire.normalize(value))));
                case "ptr" -> out.add(new Rr(ownerName, DnsWire.TYPE_PTR, DnsWire.CLASS_IN, ttl, DnsWire.encodeName(DnsWire.normalize(value))));
                case "srv" -> { // "priority weight port target"
                    String[] p = value.split("\\s+", 4);
                    if (p.length == 4) out.add(new Rr(ownerName, DnsWire.TYPE_SRV, DnsWire.CLASS_IN, ttl,
                            DnsWire.srvRdata(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), DnsWire.normalize(p[3]))));
                }
                case "caa" -> { // `flags tag "value"`
                    String[] p = value.split("\\s+", 3);
                    if (p.length == 3) out.add(new Rr(ownerName, DnsWire.TYPE_CAA, DnsWire.CLASS_IN, ttl,
                            DnsWire.caaRdata(Integer.parseInt(p[0]), p[1].toLowerCase(), p[2].replaceAll("^\"|\"$", ""))));
                }
                case "mx" -> {
                    String[] parts = value.split("\\s+", 2);
                    int pref = 10;
                    String host = value;
                    if (parts.length == 2) {
                        try { pref = Integer.parseInt(parts[0]); host = parts[1]; } catch (NumberFormatException ignored) { }
                    }
                    out.add(new Rr(ownerName, DnsWire.TYPE_MX, DnsWire.CLASS_IN, ttl, DnsWire.mxRdata(pref, DnsWire.normalize(host))));
                }
                default -> {
                    if (NetUtil.isValidIpV4Address(value) || NetUtil.isValidIpV6Address(value)) addAddress(out, ownerName, value, ttl);
                    else if (!value.isEmpty()) out.add(new Rr(ownerName, DnsWire.TYPE_TXT, DnsWire.CLASS_IN, ttl, DnsWire.txtRdata(value)));
                }
            }
        }
        return out;
    }

    private static void addAddress(List<Rr> out, String owner, String ipRaw, long ttl) {
        String ip = ipRaw;
        int pct = ip.indexOf('%');
        if (pct > 0) ip = ip.substring(0, pct); // strip IPv6 scope id
        byte[] b = NetUtil.createByteArrayFromIpAddressString(ip);
        if (b == null) return;
        int type = b.length == 4 ? DnsWire.TYPE_A : DnsWire.TYPE_AAAA;
        out.add(new Rr(owner, type, DnsWire.CLASS_IN, ttl, b));
    }

    static List<String> advertisedIps() {
        List<String> configured = DnsConfig.get().advertiseIps;
        return configured.isEmpty() ? SystemManager.readNetworksCached() : configured;
    }

    private static Rr soa(String apex) {
        List<String> ns = DnsConfig.get().nameServers;
        String mname = ns.isEmpty() ? "ns1." + apex : ns.get(0);
        byte[] rdata = DnsWire.soaRdata(mname, "hostmaster." + apex, serial.get(), 3600, 600, 1_209_600, SOA_MINIMUM);
        return new Rr(apex, DnsWire.TYPE_SOA, DnsWire.CLASS_IN, SOA_TTL, rdata);
    }

    private static List<Rr> nsRecords(String apex) {
        List<Rr> out = new ArrayList<>();
        for (String ns : DnsConfig.get().nameServers) {
            out.add(new Rr(apex, DnsWire.TYPE_NS, DnsWire.CLASS_IN, SOA_TTL, DnsWire.encodeName(ns)));
        }
        return out;
    }

    private static List<Rr> with(List<Rr> base, Rr extra) {
        List<Rr> l = new ArrayList<>(base);
        l.add(extra);
        return l;
    }

    private static List<Rr> with(List<Rr> base, List<Rr> extra) {
        if (extra.isEmpty()) return base;
        List<Rr> l = new ArrayList<>(base);
        l.addAll(extra);
        return l;
    }
}
