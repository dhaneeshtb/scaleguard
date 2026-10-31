package com.scaleguard.server.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.lang.GString;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The explicit API handed to a script: req, res, ctx, log, http. These are the only host objects it ever sees, and the
 * run-time guard lets it call only the public methods declared here.
 */
final class FunctionApi {

    private FunctionApi() {}

    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_LOG_LINES = 50, MAX_LOG_CHARS = 500;

    /** Converts script values into plain JSON-able Java values (GString → String, depth-limited). */
    static Object plain(Object o, int depth) {
        if (depth > 32) throw new IllegalArgumentException("value is nested too deeply");
        if (o == null || o instanceof String || o instanceof Number || o instanceof Boolean) return o;
        if (o instanceof GString || o instanceof CharSequence || o instanceof Character) return o.toString();
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) r.put(String.valueOf(e.getKey()), plain(e.getValue(), depth + 1));
            return r;
        }
        if (o instanceof Iterable<?> it) {
            List<Object> r = new ArrayList<>();
            for (Object x : it) r.add(plain(x, depth + 1));
            return r;
        }
        if (o.getClass().isArray()) {
            List<Object> r = new ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(o); i++) r.add(plain(java.lang.reflect.Array.get(o, i), depth + 1));
            return r;
        }
        throw new IllegalArgumentException("cannot convert a " + o.getClass().getSimpleName() + " to JSON");
    }

    static Object fromJson(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> m.put(e.getKey(), fromJson(e.getValue())));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.forEach(x -> l.add(fromJson(x)));
            return l;
        }
        if (n.isIntegralNumber()) return n.canConvertToInt() ? (Object) n.intValue() : (Object) n.longValue();
        if (n.isNumber()) return n.decimalValue();
        if (n.isBoolean()) return n.booleanValue();
        return n.asText();
    }

    static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(plain(o, 0));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("cannot convert to JSON");
        }
    }

    static Object parseJson(String s) {
        try {
            return fromJson(JSON.readTree(s));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON");
        }
    }

    static final class TooLarge extends IllegalStateException {
        TooLarge(String m) { super(m); }
    }

    // ------------------------------------------------------------------ req

    public static final class Req implements SandboxApi {
        private final String method, path, query, uri, clientIp, host;
        private final Map<String, String> headers;
        private final byte[] body;
        private Map<String, String> params;

        Req(String method, String path, String query, String clientIp, String host, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query == null ? "" : query;
            this.uri = this.query.isEmpty() ? path : path + "?" + this.query;
            this.clientIp = clientIp;
            this.host = host;
            Map<String, String> h = new LinkedHashMap<>();
            headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v));
            this.headers = Collections.unmodifiableMap(h);
            this.body = body == null ? new byte[0] : body;
        }

        public String getMethod() { return method; }
        public String getPath() { return path; }
        public String getQuery() { return query; }
        public String getUri() { return uri; }
        public String getClientIp() { return clientIp; }
        public String getHost() { return host; }
        public Map<String, String> getHeaders() { return headers; }
        public String header(String name) { return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT)); }
        public String getBody() { return new String(body, StandardCharsets.UTF_8); }
        public int getLength() { return body.length; }

        public Map<String, String> getParams() {
            if (params == null) {
                Map<String, String> m = new LinkedHashMap<>();
                for (String kv : query.split("&")) {
                    if (kv.isEmpty()) continue;
                    int i = kv.indexOf('=');
                    try {
                        String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
                        m.putIfAbsent(k, i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
                    } catch (IllegalArgumentException ignored) {
                        // malformed escape: skip the pair
                    }
                }
                params = Collections.unmodifiableMap(m);
            }
            return params;
        }

        public String param(String name) { return getParams().get(name); }

        /** The body parsed as JSON (Map / List / value); null for an empty body. */
        public Object json() {
            return body.length == 0 ? null : parseJson(getBody());
        }
    }

    // ------------------------------------------------------------------ res

    public static final class Res implements SandboxApi {
        private int status = 200;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body;
        private final int max;

        Res(int max) { this.max = max; }

        private static final Set<String> HOP = Set.of("content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer", "keep-alive");

        public int getStatus() { return status; }

        public void setStatus(int s) {
            if (s < 200 || s > 599) throw new IllegalArgumentException("status must be 200..599");
            status = s;
        }

        public Res status(int s) { setStatus(s); return this; }

        public Res header(String name, Object value) {
            if (name == null || !name.matches("[A-Za-z0-9\\-_]+") || HOP.contains(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("header not allowed: " + name);
            String v = String.valueOf(value);
            if (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) throw new IllegalArgumentException("invalid header value");
            headers.put(name, v);
            return this;
        }

        private Res set(byte[] b, String type) {
            if (b.length > max) throw new TooLarge("response body exceeds the limit of " + max + " bytes");
            body = b;
            if (headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-type"))) headers.put("Content-Type", type);
            return this;
        }

        public Res text(Object s) { return set(String.valueOf(s).getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8"); }
        public Res html(Object s) { return set(String.valueOf(s).getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"); }
        public Res json(Object o) { return set(toJson(o).getBytes(StandardCharsets.UTF_8), "application/json"); }
        public Res send(Object o) {
            return o instanceof CharSequence || o instanceof Number || o instanceof Boolean ? text(o) : json(o);
        }

        public Res redirect(String url) { return redirect(url, 302); }

        public Res redirect(String url, int code) {
            if (code < 300 || code > 399) throw new IllegalArgumentException("redirect status must be 3xx");
            status = code;
            return header("Location", url);
        }

        int statusCode() { return status; }
        Map<String, String> headerMap() { return headers; }
        byte[] bodyBytes() { return body; }
    }

    // ------------------------------------------------------------------ log

    public static final class Log implements SandboxApi {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        private void add(String level, Object msg) {
            if (lines.size() >= MAX_LOG_LINES) return;
            String s = String.valueOf(msg);
            lines.add(level + " " + (s.length() > MAX_LOG_CHARS ? s.substring(0, MAX_LOG_CHARS) + "…" : s));
        }

        public void debug(Object m) { add("DEBUG", m); }
        public void info(Object m) { add("INFO", m); }
        public void warn(Object m) { add("WARN", m); }
        public void error(Object m) { add("ERROR", m); }
    }

    // ------------------------------------------------------------------ http

    public static final class HttpResult implements SandboxApi {
        private final SandboxHttp.Response r;

        HttpResult(SandboxHttp.Response r) { this.r = r; }

        public int getStatus() { return r.status(); }
        public boolean isOk() { return r.status() >= 200 && r.status() < 300; }
        public Map<String, String> getHeaders() { return Collections.unmodifiableMap(r.headers()); }
        public String getBody() { return new String(r.body(), StandardCharsets.UTF_8); }
        public Object json() { return parseJson(getBody()); }
    }

    public static final class Http implements SandboxApi {
        private final SandboxHttp client;

        Http(SandboxHttp client) { this.client = client; }

        public HttpResult get(String url) { return request("GET", url, null, null); }
        public HttpResult get(String url, Map<String, Object> headers) { return request("GET", url, null, headers); }
        public HttpResult delete(String url) { return request("DELETE", url, null, null); }
        public HttpResult post(String url, Object body) { return request("POST", url, body, null); }
        public HttpResult post(String url, Object body, Map<String, Object> headers) { return request("POST", url, body, headers); }
        public HttpResult put(String url, Object body) { return request("PUT", url, body, null); }
        public HttpResult put(String url, Object body, Map<String, Object> headers) { return request("PUT", url, body, headers); }

        /** Maps and lists are sent as JSON, text as-is. */
        public HttpResult request(String method, String url, Object body, Map<String, Object> headers) {
            Map<String, String> h = new LinkedHashMap<>();
            if (headers != null) headers.forEach((k, v) -> h.put(String.valueOf(k), String.valueOf(v)));
            byte[] b = null;
            if (body != null) {
                if (body instanceof CharSequence) {
                    b = body.toString().getBytes(StandardCharsets.UTF_8);
                } else {
                    b = toJson(body).getBytes(StandardCharsets.UTF_8);
                    if (h.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-type"))) h.put("Content-Type", "application/json");
                }
            }
            try {
                return new HttpResult(client.execute(method, url, h, b));
            } catch (SandboxHttp.HttpException e) {
                throw new IllegalStateException(e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ ctx

    public static final class Ctx implements SandboxApi {
        private final Req req;
        private final Res res;
        private final Log log;
        private final Http http;
        private final FunctionDef def;

        Ctx(Req req, Res res, Log log, Http http, FunctionDef def) {
            this.req = req;
            this.res = res;
            this.log = log;
            this.http = http;
            this.def = def;
        }

        public Req getReq() { return req; }
        public Res getRes() { return res; }
        public Log getLog() { return log; }
        public Http getHttp() { return http; }
        public Map<String, String> getConfig() { return Collections.unmodifiableMap(def.config); }

        /** A gateway secret, only if this function's settings list it; otherwise null. */
        public String secret(String name) {
            return def.secrets.contains(name) ? FunctionSecrets.get(name) : null;
        }

        public String json(Object o) { return toJson(o); }
        public Object parseJson(String s) { return FunctionApi.parseJson(s); }
        public long now() { return System.currentTimeMillis(); }
        public String uuid() { return UUID.randomUUID().toString(); }
        public String urlEncode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        public String urlDecode(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
        public String base64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
        public String fromBase64(String s) { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }

        public String sha256(String s) {
            try {
                return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        public String hmacSha256(String key, String data) {
            try {
                javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
                m.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return hex(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        private static String hex(byte[] b) {
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        }
    }

}
