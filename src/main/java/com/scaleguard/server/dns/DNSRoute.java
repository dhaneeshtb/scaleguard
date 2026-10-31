package com.scaleguard.server.dns;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.utils.JSON;

import java.util.Map;
import java.util.Set;

public class DNSRoute implements RequestRoute {

    private static final Set<String> TYPES = Set.of("record", "base", "a", "aaaa", "txt", "cname", "mx", "ns", "ptr", "srv", "caa");
    private static final long MAX_TTL = 604_800; // 1 week

    /** /dns/test reads ?domain=; the path is still taken from the part before the '?'. */
    @Override
    public boolean receivesQueryString() {
        return true;
    }

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        String[] tuples = uri.split("\\?")[0].split("/");
        String last = tuples.length > 0 ? tuples[tuples.length - 1] : "";

        if (method.equalsIgnoreCase("get")) {
            if ("settings".equalsIgnoreCase(last)) {
                return RequestRoutingResponse.succes(JSON.toString(DnsSettings.view()));
            }
            if ("status".equalsIgnoreCase(last)) {
                DnsServer s = DnsServer.current();
                Map<String, Object> st = new java.util.LinkedHashMap<>();
                st.put("enabled", DnsService.desired());
                st.put("running", s != null);
                st.put("port", s != null ? s.port() : DnsConfig.get().port);
                return RequestRoutingResponse.succes(JSON.toString(st));
            }
            if ("test".equalsIgnoreCase(last)) {
                String q = uri.contains("?") ? uri.substring(uri.indexOf('?') + 1) : "";
                String domain = null;
                for (String kv : q.split("&")) {
                    if (kv.startsWith("domain=")) domain = java.net.URLDecoder.decode(kv.substring(7), java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase();
                }
                if (domain == null || !domain.matches("^([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\\.)+[a-z0-9-]{2,63}\\.?$")) return error("domain is required, e.g. ?domain=example.com");
                final String d = domain;
                return RequestRoutingResponse.later(() -> RequestRoutingResponse.succes(JSON.toString(DnsDiagnostics.run(d))));
            }
            if ("stats".equalsIgnoreCase(last)) {
                DnsServer s = DnsServer.current();
                Map<String, Object> stats = s == null ? Map.of("running", false) : s.statsSnapshot();
                return RequestRoutingResponse.succes(JSON.toString(stats));
            }
            return RequestRoutingResponse.succes(JSON.toString(DNSAddressBook.get()));
        }

        if (method.equalsIgnoreCase("delete")) {
            if ("cache".equalsIgnoreCase(last)) {
                DnsServer s = DnsServer.current();
                if (s != null) s.clearCache();
                return RequestRoutingResponse.succes("{}");
            }
            String id = !last.equalsIgnoreCase("dns") ? last : null;
            if (id != null && !id.isBlank()) {
                DNSAddressBook.remove(id);
            }
            return RequestRoutingResponse.succes("{}");
        }

        if (method.equalsIgnoreCase("post") && "settings".equalsIgnoreCase(last)) {
            Map<String, String> in = new java.util.LinkedHashMap<>();
            JSON.parse(body).fields().forEachRemaining(e -> in.put(e.getKey(), e.getValue().asText()));
            String bad = DnsSettings.save(in);
            if (bad != null) return error(bad);
            try {
                DnsService.apply(); // restarts the server here when it is running; other nodes follow the cluster hook
            } catch (IllegalStateException e) {
                return RequestRoutingResponse.response(500, JSON.toString(Map.of("error", "Saved, but the server could not restart here: " + e.getMessage())));
            }
            return RequestRoutingResponse.succes(JSON.toString(DnsSettings.view()));
        }

        if (method.equalsIgnoreCase("post") && ("start".equalsIgnoreCase(last) || "stop".equalsIgnoreCase(last))) {
            try {
                DnsService.setEnabled("start".equalsIgnoreCase(last));
            } catch (IllegalStateException e) {
                return RequestRoutingResponse.response(500, JSON.toString(Map.of("error", e.getMessage())));
            }
            return RequestRoutingResponse.succes(JSON.toString(Map.of("enabled", DnsService.desired(), "running", DnsService.running())));
        }

        if (method.equalsIgnoreCase("post")) {
            JsonNode node = JSON.parse(body);
            String name = text(node, "name");
            if (name == null || name.isBlank()) return error("name is required");
            name = name.trim().toLowerCase();
            if (!name.endsWith(".")) name = name + ".";
            if (name.length() > 254 || !name.matches("^([a-z0-9_*]([a-z0-9_-]{0,61}[a-z0-9_])?\\.)+$")) {
                return error("name is not a valid domain name");
            }

            String type = text(node, "type");
            type = type == null || type.isBlank() ? "record" : type.trim().toLowerCase();
            if (!TYPES.contains(type)) return error("type must be one of " + TYPES);

            String ip = text(node, "ip");
            if ("base".equals(type) || ip == null || ip.isBlank()) {
                ip = "base";
            } else {
                ip = ip.trim();
                if (ip.length() > 1024) return error("value is too long");
            }

            String bad = "base".equals(type) ? null : validateValue(type, ip);
            if (bad != null) return error(bad);

            long ttl = node.has("ttl") ? node.get("ttl").asLong(DNSAddressBook.DEFAULT_TTL) : DNSAddressBook.DEFAULT_TTL;
            if (ttl < 1 || ttl > MAX_TTL) return error("ttl must be between 1 and " + MAX_TTL + " seconds");

            DNSAddressBook.add(name, ip, type, ttl);
            return RequestRoutingResponse.succes(node.toString());
        }
        return RequestRoutingResponse.response(405, "{}");
    }

    private static final String HOST = "^([a-zA-Z0-9_*]([a-zA-Z0-9_-]{0,61}[a-zA-Z0-9_])?\\.)*[a-zA-Z0-9_]([a-zA-Z0-9_-]{0,61}[a-zA-Z0-9_])?\\.?$";

    /** A readable message when the value does not fit the record type (otherwise the server would silently answer garbage). */
    static String validateValue(String type, String v) {
        switch (type) {
            case "a": return io.netty.util.NetUtil.isValidIpV4Address(v) ? null : "A needs an IPv4 address, e.g. 203.0.113.10";
            case "aaaa": return io.netty.util.NetUtil.isValidIpV6Address(v) ? null : "AAAA needs an IPv6 address, e.g. 2001:db8::1";
            case "cname": case "ns": case "ptr":
                return v.matches(HOST) ? null : type.toUpperCase() + " needs a host name, e.g. target.example.com";
            case "mx": return v.matches("^\\d{1,5}\\s+" + HOST.substring(1)) && Integer.parseInt(v.split("\\s+")[0]) <= 65535 ? null : "MX needs 'priority host', e.g. 10 mail.example.com";
            case "srv": {
                String[] p = v.split("\\s+", 4);
                boolean ok = p.length == 4 && p[0].matches("\\d{1,5}") && p[1].matches("\\d{1,5}") && p[2].matches("\\d{1,5}")
                        && Integer.parseInt(p[0]) <= 65535 && Integer.parseInt(p[1]) <= 65535 && Integer.parseInt(p[2]) <= 65535 && p[3].matches(HOST);
                return ok ? null : "SRV needs 'priority weight port target', e.g. 10 5 5060 sip.example.com";
            }
            case "caa": {
                String[] p = v.split("\\s+", 3);
                boolean ok = p.length == 3 && p[0].matches("\\d{1,3}") && Integer.parseInt(p[0]) <= 255 && p[1].matches("(?i)issue|issuewild|iodef");
                return ok ? null : "CAA needs 'flags tag value', e.g. 0 issue letsencrypt.org";
            }
            default: return v.isBlank() ? "value is required" : null; // txt, record
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static RequestRoutingResponse error(String message) {
        return RequestRoutingResponse.response(400, JSON.toString(Map.of("error", message)));
    }
}
