package com.scaleguard.server.dns;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.utils.JSON;

import java.util.Map;
import java.util.Set;

public class DNSRoute implements RequestRoute {

    private static final Set<String> TYPES = Set.of("record", "base", "a", "aaaa", "txt", "cname", "mx", "ns");
    private static final long MAX_TTL = 604_800; // 1 week

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        String[] tuples = uri.split("\\?")[0].split("/");
        String last = tuples.length > 0 ? tuples[tuples.length - 1] : "";

        if (method.equalsIgnoreCase("get")) {
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

            long ttl = node.has("ttl") ? node.get("ttl").asLong(DNSAddressBook.DEFAULT_TTL) : DNSAddressBook.DEFAULT_TTL;
            if (ttl < 1 || ttl > MAX_TTL) return error("ttl must be between 1 and " + MAX_TTL + " seconds");

            DNSAddressBook.add(name, ip, type, ttl);
            return RequestRoutingResponse.succes(node.toString());
        }
        return RequestRoutingResponse.response(405, "{}");
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static RequestRoutingResponse error(String message) {
        return RequestRoutingResponse.response(400, JSON.toString(Map.of("error", message)));
    }
}
