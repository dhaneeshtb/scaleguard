package com.scaleguard.server.functions;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The only way a function reaches the network. Not java.net.http: the client resolves the name itself, checks every
 * address, then connects to that exact address, so a DNS answer cannot change between check and use (rebinding). Redirects
 * are followed by hand and each hop goes through the same allowlist and address checks. No proxies, no cookies, no HTTP/2.
 */
final class SandboxHttp {

    /** [scheme://]host[:port]; no scheme = https; host may be "*.example.com" (subdomains only). */
    record HostRule(String scheme, String host, boolean wildcard, int port) {
        static HostRule parse(String s) {
            if (s == null) return null;
            s = s.trim().toLowerCase(Locale.ROOT);
            String scheme = "https";
            if (s.startsWith("https://")) s = s.substring(8);
            else if (s.startsWith("http://")) { scheme = "http"; s = s.substring(7); }
            else if (s.contains("://")) return null;
            if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            int port = -1;
            int colon = s.lastIndexOf(':');
            if (colon > 0 && !s.endsWith("]")) {
                try { port = Integer.parseInt(s.substring(colon + 1)); } catch (NumberFormatException e) { return null; }
                if (port < 1 || port > 65535) return null;
                s = s.substring(0, colon);
            }
            boolean wild = s.startsWith("*.");
            if (wild) s = s.substring(2);
            if (!s.matches("[a-z0-9]([a-z0-9.\\-]*[a-z0-9])?") && !s.matches("\\[[0-9a-f:.]+]")) return null;
            return new HostRule(scheme, s, wild, port);
        }

        boolean matches(String sch, String h, int p) {
            if (!scheme.equals(sch)) return false;
            if (p != (port > 0 ? port : "https".equals(scheme) ? 443 : 80)) return false;
            return wildcard ? h.endsWith("." + host) : h.equals(host);
        }
    }

    static final class HttpException extends RuntimeException {
        HttpException(String m) { super(m); }
    }

    record Response(int status, Map<String, String> headers, byte[] body) {}

    private final List<HostRule> rules = new ArrayList<>();
    private final boolean network, allowPrivate;
    private final int maxBytes;
    private final long deadlineNanos;
    private int callsLeft;
    private final Function<String, InetAddress[]> resolver;

    SandboxHttp(FunctionDef d, long deadlineNanos, Function<String, InetAddress[]> resolver) {
        this.network = d.networkEnabled;
        this.allowPrivate = d.allowPrivateNetworks;
        this.maxBytes = d.maxHttpBytes;
        this.callsLeft = d.maxHttpCalls;
        this.deadlineNanos = deadlineNanos;
        this.resolver = resolver;
        for (String h : d.allowedHosts) {
            HostRule r = HostRule.parse(h);
            if (r != null) rules.add(r);
        }
    }

    static final Function<String, InetAddress[]> SYSTEM_DNS = host -> {
        try {
            return InetAddress.getAllByName(host);
        } catch (IOException e) {
            throw new HttpException("cannot resolve " + host);
        }
    };

    /** True for addresses a function may never reach unless the admin allows private networks. */
    static boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int o0 = b[0] & 0xff, o1 = b[1] & 0xff;
            return o0 == 0 || o0 >= 240 || (o0 == 100 && o1 >= 64 && o1 <= 127) || (o0 == 198 && (o1 == 18 || o1 == 19))
                    || (o0 == 192 && o1 == 0 && (b[2] & 0xff) == 0);
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true;                       // fc00::/7 unique local
            if (b[0] == 0 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) return true;   // NAT64
            if (b[0] == 0x20 && b[1] == 0x02) return true;                // 6to4
        }
        return false;
    }

    /** Link-local (169.254/16, fe80::/10 — includes the cloud metadata address) and the AWS IPv6 metadata address: never reachable. */
    static boolean isNeverAllowed(InetAddress a) {
        if (a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        return a instanceof Inet6Address && b[0] == (byte) 0xfd && b[1] == 0x00 && b[2] == 0x0e && b[3] == (byte) 0xc2;   // fd00:ec2::/32
    }

    Response execute(String method, String url, Map<String, String> headers, byte[] body) {
        if (!network) throw new HttpException("outbound network calls are disabled for this function");
        String m = method.toUpperCase(Locale.ROOT);
        if (!m.matches("GET|POST|PUT|PATCH|DELETE|HEAD")) throw new HttpException("unsupported method " + method);
        for (int hop = 0; ; hop++) {
            if (callsLeft-- <= 0) throw new HttpException("too many outbound calls in one run");
            Response r = once(m, url, headers, body);
            String loc = r.headers().get("location");
            if (r.status() >= 300 && r.status() < 400 && loc != null && r.status() != 304) {
                if (hop >= 3) throw new HttpException("too many redirects");
                url = URI.create(url).resolve(loc.trim()).toString();     // next hop is validated from scratch by once()
                if (r.status() != 307 && r.status() != 308) { m = "GET"; body = null; }
                continue;
            }
            return r;
        }
    }

    private Response once(String method, String url, Map<String, String> headers, byte[] body) {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new HttpException("invalid url");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new HttpException("only http and https are allowed");
        if (u.getUserInfo() != null) throw new HttpException("credentials in the url are not allowed");
        String host = u.getHost();
        if (host == null) throw new HttpException("invalid url");
        host = host.toLowerCase(Locale.ROOT);
        int port = u.getPort() > 0 ? u.getPort() : scheme.equals("https") ? 443 : 80;
        final String h = host;
        final int p = port;
        if (!rules.isEmpty() && rules.stream().noneMatch(r -> r.matches(scheme, h, p))) {
            throw new HttpException(scheme + "://" + host + ":" + port + " is not in this function's allowed hosts");
        }
        InetAddress[] addrs = host.startsWith("[") ? literal(host) : resolver.apply(host);
        if (addrs == null || addrs.length == 0) throw new HttpException("cannot resolve " + host);
        for (InetAddress a : addrs) {
            if (isNeverAllowed(a)) throw new HttpException("address " + a.getHostAddress() + " is blocked");
            if (!allowPrivate && isPrivate(a)) throw new HttpException("address " + a.getHostAddress() + " is private; the admin has not allowed private networks");
        }
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) path += "?" + u.getRawQuery();

        IOException last = null;
        for (InetAddress a : addrs) {
            try (Socket raw = new Socket()) {
                raw.connect(new InetSocketAddress(a, port), (int) Math.max(1, Math.min(10_000, remainingMs())));
                Socket s = raw;
                if (scheme.equals("https")) {
                    SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
                    SSLParameters sp = ssl.getSSLParameters();
                    sp.setEndpointIdentificationAlgorithm("HTTPS");
                    sp.setServerNames(List.of(new SNIHostName(host)));
                    ssl.setSSLParameters(sp);
                    ssl.setSoTimeout((int) Math.max(1, remainingMs()));
                    ssl.startHandshake();
                    s = ssl;
                }
                return exchange(s, method, host, port, scheme, path, headers, body);
            } catch (IOException e) {
                last = e;
            }
        }
        throw new HttpException("request failed: " + (last == null ? "no address" : last.getClass().getSimpleName()));
    }

    private static InetAddress[] literal(String bracketed) {
        try {
            return new InetAddress[]{InetAddress.getByName(bracketed)};
        } catch (IOException e) {
            throw new HttpException("invalid address");
        }
    }

    private long remainingMs() {
        long r = (deadlineNanos - System.nanoTime()) / 1_000_000;
        if (r <= 0) throw new HttpException("function time limit reached");
        return r;
    }

    private static final java.util.Set<String> FORBIDDEN_HEADERS = java.util.Set.of("host", "content-length", "transfer-encoding",
            "connection", "upgrade", "te", "trailer", "proxy-authorization", "accept-encoding");

    private Response exchange(Socket s, String method, String host, int port, String scheme, String path,
                              Map<String, String> headers, byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean defaultPort = port == ("https".equals(scheme) ? 443 : 80);
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: ").append(host).append(defaultPort ? "" : ":" + port)
                .append("\r\nConnection: close\r\nAccept-Encoding: identity\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey(), v = e.getValue() == null ? "" : e.getValue();
                if (!k.matches("[A-Za-z0-9\\-_]+") || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) throw new HttpException("invalid header " + k);
                if (FORBIDDEN_HEADERS.contains(k.toLowerCase(Locale.ROOT))) continue;
                sb.append(k).append(": ").append(v).append("\r\n");
            }
        }
        if (body != null && body.length > 0) sb.append("Content-Length: ").append(body.length).append("\r\n");
        else if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("DELETE")) sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");
        var out = s.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) out.write(body);
        out.flush();

        InputStream in = s.getInputStream();
        s.setSoTimeout((int) remainingMs());
        String status = line(in, 8192);
        if (status == null || !status.startsWith("HTTP/1.")) throw new HttpException("bad response");
        String[] sp = status.split(" ", 3);
        int code;
        try { code = Integer.parseInt(sp[1]); } catch (RuntimeException e) { throw new HttpException("bad response"); }
        Map<String, String> rh = new LinkedHashMap<>();
        int hbytes = 0;
        for (String l; (l = line(in, 16384)) != null && !l.isEmpty(); ) {
            if ((hbytes += l.length()) > 65536) throw new HttpException("response headers too large");
            int c = l.indexOf(':');
            if (c > 0) rh.merge(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim(), (a, b) -> a + ", " + b);
        }
        if (method.equals("HEAD") || code == 204 || code == 304 || code / 100 == 1) return new Response(code, rh, new byte[0]);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        String te = rh.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            while (true) {
                String szl = line(in, 1024);
                if (szl == null) throw new HttpException("truncated response");
                int semi = szl.indexOf(';');
                long sz;
                try { sz = Long.parseLong((semi > 0 ? szl.substring(0, semi) : szl).trim(), 16); } catch (NumberFormatException e) { throw new HttpException("bad chunk"); }
                if (sz == 0) break;
                copy(in, bo, sz);
                line(in, 4);
            }
        } else if (rh.containsKey("content-length")) {
            long len;
            try { len = Long.parseLong(rh.get("content-length")); } catch (NumberFormatException e) { throw new HttpException("bad content-length"); }
            if (len > maxBytes) throw new HttpException("response body exceeds the limit of " + maxBytes + " bytes");
            copy(in, bo, len);
        } else {
            copy(in, bo, Long.MAX_VALUE);
        }
        return new Response(code, rh, bo.toByteArray());
    }

    private void copy(InputStream in, ByteArrayOutputStream bo, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            {
                int r = in.read(buf, 0, (int) Math.min(buf.length, n));
                if (r < 0) {
                    if (n == Long.MAX_VALUE) return;
                    throw new HttpException("truncated response");
                }
                bo.write(buf, 0, r);
                n -= r;
                if (bo.size() > maxBytes) throw new HttpException("response body exceeds the limit of " + maxBytes + " bytes");
                remainingMs();
            }
        }
    }

    private static String line(InputStream in, int max) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            if (sb.length() >= max) throw new HttpException("line too long");
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
