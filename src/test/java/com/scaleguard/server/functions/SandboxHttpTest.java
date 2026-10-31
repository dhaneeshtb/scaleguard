package com.scaleguard.server.functions;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Outbound calls: only loopback test servers are involved, never an external host. */
public class SandboxHttpTest {

    private HttpServer a, b;
    private int pa, pb;
    private final AtomicInteger bHits = new AtomicInteger();

    @Before
    public void start() throws Exception {
        a = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        b = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        a.createContext("/hello", ex -> reply(ex, 200, "hi " + ex.getRequestMethod()));
        a.createContext("/echo", ex -> reply(ex, 200, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        a.createContext("/big", ex -> reply(ex, 200, "x".repeat(10_000)));
        a.createContext("/to-b", ex -> { ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + pb + "/secret"); reply(ex, 302, ""); });
        a.createContext("/to-meta", ex -> { ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/"); reply(ex, 302, ""); });
        a.createContext("/loop", ex -> { ex.getResponseHeaders().add("Location", "/loop"); reply(ex, 302, ""); });
        a.createContext("/ok-redirect", ex -> { ex.getResponseHeaders().add("Location", "/hello"); reply(ex, 302, ""); });
        b.createContext("/", ex -> { bHits.incrementAndGet(); reply(ex, 200, "secret"); });
        a.start();
        b.start();
        pa = a.getAddress().getPort();
        pb = b.getAddress().getPort();
    }

    @After
    public void stop() {
        a.stop(0);
        b.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private FunctionDef def(boolean network, boolean priv, String... hosts) {
        FunctionDef d = new FunctionDef();
        d.networkEnabled = network;
        d.allowPrivateNetworks = priv;
        d.allowedHosts = List.of(hosts);
        return d;
    }

    private SandboxHttp client(FunctionDef d) {
        return new SandboxHttp(d, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), SandboxHttp.SYSTEM_DNS);
    }

    private static String refused(Runnable r) {
        try {
            r.run();
        } catch (SandboxHttp.HttpException e) {
            return e.getMessage();
        }
        fail("expected the call to be refused");
        return null;
    }

    @Test
    public void networkIsOffByDefault() {
        String m = refused(() -> client(def(false, true, "http://127.0.0.1:" + pa)).execute("GET", "http://127.0.0.1:" + pa + "/hello", null, null));
        assertTrue(m, m.contains("disabled"));
        assertFalse(new FunctionDef().networkEnabled);
    }

    @Test
    public void allowedHostWorksWhenPrivateNetworksAreAllowed() {
        SandboxHttp c = client(def(true, true, "http://127.0.0.1:" + pa));
        SandboxHttp.Response r = c.execute("GET", "http://127.0.0.1:" + pa + "/hello", null, null);
        assertEquals(200, r.status());
        assertEquals("hi GET", new String(r.body(), StandardCharsets.UTF_8));
        r = client(def(true, true, "http://127.0.0.1:" + pa)).execute("POST", "http://127.0.0.1:" + pa + "/echo", Map.of("X-A", "1"), "ping".getBytes());
        assertEquals("ping", new String(r.body(), StandardCharsets.UTF_8));
    }

    @Test
    public void emptyAllowlistMeansAnyPublicHostButStillNoPrivateAddresses() {
        String m = refused(() -> client(def(true, false)).execute("GET", "http://127.0.0.1:" + pa + "/hello", null, null));
        assertTrue(m, m.contains("private"));
        assertTrue(refused(() -> client(def(true, true)).execute("GET", "http://169.254.169.254/", null, null)).contains("blocked"));
        assertEquals(200, client(def(true, true)).execute("GET", "http://127.0.0.1:" + pa + "/hello", null, null).status());
    }

    @Test
    public void loopbackIsRefusedUnlessThePrivateSwitchIsOn() {
        String m = refused(() -> client(def(true, false, "http://127.0.0.1:" + pa)).execute("GET", "http://127.0.0.1:" + pa + "/hello", null, null));
        assertTrue(m, m.contains("private"));
    }

    @Test
    public void hostsPortsAndSchemesOutsideTheAllowlistAreRefused() {
        FunctionDef d = def(true, true, "http://127.0.0.1:" + pa);
        assertTrue(refused(() -> client(d).execute("GET", "http://127.0.0.1:" + pb + "/", null, null)).contains("allowed hosts"));      // other port
        assertTrue(refused(() -> client(d).execute("GET", "https://127.0.0.1:" + pa + "/", null, null)).contains("allowed hosts"));     // other scheme
        assertTrue(refused(() -> client(d).execute("GET", "http://localhost:" + pa + "/", null, null)).contains("allowed hosts"));      // other name
        assertTrue(refused(() -> client(d).execute("GET", "file:///etc/passwd", null, null)).contains("only http"));
        assertTrue(refused(() -> client(d).execute("GET", "ftp://127.0.0.1/", null, null)).contains("only http"));
        assertTrue(refused(() -> client(d).execute("GET", "http://user:pw@127.0.0.1:" + pa + "/", null, null)).contains("credentials"));
        assertTrue(refused(() -> client(d).execute("GET", "http://127.0.0.1:" + pa + "@evil.test/", null, null)).length() > 0);
    }

    @Test
    public void redirectsAreRevalidatedAtEveryHop() {
        // a -> b: b is loopback on another port, not in the allowlist
        FunctionDef d = def(true, true, "http://127.0.0.1:" + pa);
        assertTrue(refused(() -> client(d).execute("GET", "http://127.0.0.1:" + pa + "/to-b", null, null)).contains("allowed hosts"));
        assertEquals("b was never contacted", 0, bHits.get());
        // even with the metadata address allow-listed, it is never reachable
        FunctionDef both = def(true, true, "http://127.0.0.1:" + pa, "http://169.254.169.254");
        assertTrue(refused(() -> client(both).execute("GET", "http://127.0.0.1:" + pa + "/to-meta", null, null)).contains("blocked"));
        assertTrue(refused(() -> client(d).execute("GET", "http://127.0.0.1:" + pa + "/loop", null, null)).contains("redirects"));
        // an allowed same-host redirect is followed
        assertEquals(200, client(d).execute("GET", "http://127.0.0.1:" + pa + "/ok-redirect", null, null).status());
    }

    @Test
    public void metadataAndLinkLocalAddressesAreNeverReachable() {
        for (String host : List.of("169.254.169.254", "169.254.1.1", "[fe80::1]", "[fd00:ec2::254]")) {
            FunctionDef d = def(true, true, "http://" + host);
            assertTrue(host, refused(() -> client(d).execute("GET", "http://" + host + "/", null, null)).contains("blocked"));
        }
    }

    @Test
    public void addressClassification() throws Exception {
        for (String ip : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "0.0.0.0", "::1",
                "fc00::1", "fd12:3456::1", "::ffff:10.0.0.1", "224.0.0.1", "198.18.0.1", "240.0.0.1", "64:ff9b::7f00:1")) {
            assertTrue(ip, SandboxHttp.isPrivate(InetAddress.getByName(ip)) || SandboxHttp.isNeverAllowed(InetAddress.getByName(ip)));
        }
        for (String ip : List.of("93.184.216.34", "8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "172.32.0.1")) {
            assertFalse(ip, SandboxHttp.isPrivate(InetAddress.getByName(ip)) || SandboxHttp.isNeverAllowed(InetAddress.getByName(ip)));
        }
        assertTrue(SandboxHttp.isNeverAllowed(InetAddress.getByName("169.254.169.254")));
    }

    @Test
    public void aNameThatResolvesToAPrivateAddressIsRefused() throws Exception {
        // DNS rebinding: the name is allow-listed, but its answer is internal. The resolved address is checked, then used for the connection.
        AtomicInteger lookups = new AtomicInteger();
        SandboxHttp c = new SandboxHttp(def(true, false, "http://api.example.test:" + pa), System.nanoTime() + TimeUnit.SECONDS.toNanos(5), host -> {
            lookups.incrementAndGet();
            try { return new InetAddress[]{InetAddress.getByName("127.0.0.1")}; } catch (Exception e) { throw new RuntimeException(e); }
        });
        String m = refused(() -> c.execute("GET", "http://api.example.test:" + pa + "/hello", null, null));
        assertTrue(m, m.contains("private"));
        assertEquals("resolved once per hop; the checked address is the one connected to", 1, lookups.get());

        // with private networks allowed the pinned address is used even though the name does not resolve in real DNS
        SandboxHttp ok = new SandboxHttp(def(true, true, "http://api.example.test:" + pa), System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                host -> { try { return new InetAddress[]{InetAddress.getByName("127.0.0.1")}; } catch (Exception e) { throw new RuntimeException(e); } });
        assertEquals(200, ok.execute("GET", "http://api.example.test:" + pa + "/hello", null, null).status());

        // one bad record among good ones refuses the lot
        SandboxHttp mixed = new SandboxHttp(def(true, false, "http://api.example.test:" + pa), System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                host -> { try { return new InetAddress[]{InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")}; } catch (Exception e) { throw new RuntimeException(e); } });
        assertTrue(refused(() -> mixed.execute("GET", "http://api.example.test:" + pa + "/hello", null, null)).contains("private"));
    }

    @Test
    public void responseSizeCallCountAndWildcards() {
        FunctionDef d = def(true, true, "http://127.0.0.1:" + pa);
        d.maxHttpBytes = 1000;
        assertTrue(refused(() -> client(d).execute("GET", "http://127.0.0.1:" + pa + "/big", null, null)).contains("exceeds"));
        FunctionDef few = def(true, true, "http://127.0.0.1:" + pa);
        few.maxHttpCalls = 1;
        SandboxHttp c = client(few);
        c.execute("GET", "http://127.0.0.1:" + pa + "/hello", null, null);
        assertTrue(refused(() -> c.execute("GET", "http://127.0.0.1:" + pa + "/hello", null, null)).contains("too many"));

        SandboxHttp.HostRule w = SandboxHttp.HostRule.parse("*.example.com");
        assertTrue(w.matches("https", "api.example.com", 443));
        assertFalse(w.matches("https", "example.com", 443));
        assertFalse(w.matches("https", "evilexample.com", 443));
        assertFalse(w.matches("http", "api.example.com", 80));
        assertFalse(w.matches("https", "api.example.com", 8443));
        assertTrue(SandboxHttp.HostRule.parse("https://x.io:8443").matches("https", "x.io", 8443));
        assertNull(SandboxHttp.HostRule.parse("ftp://x.io"));
        assertNull(SandboxHttp.HostRule.parse("bad host"));
        assertNull(SandboxHttp.HostRule.parse("a.com:99999"));
    }

    @Test
    public void scriptsOnlyGetTheAllowlistedClient() throws Exception {
        FunctionRuntime rt = FunctionRuntime.get();
        FunctionDef d = new FunctionDef();
        d.id = UUID.randomUUID().toString();
        d.name = "net";
        d.code = "def r = http.get('http://127.0.0.1:" + pa + "/hello'); return [s: r.status, b: r.body]";
        d.networkEnabled = true;
        d.allowPrivateNetworks = true;
        d.allowedHosts = List.of("http://127.0.0.1:" + pa);
        rt.putForTest(d);
        FunctionRuntime.Result r = rt.invoke(d.id, new FunctionRuntime.Request("GET", "/", "", "1.1.1.1", "h", Map.of(), new byte[0])).get(10, TimeUnit.SECONDS);
        assertNull(r.detail, r.error);
        assertEquals("{\"s\":200,\"b\":\"hi GET\"}", new String(r.body, StandardCharsets.UTF_8));

        d.allowedHosts = List.of("http://127.0.0.1:" + pb);          // not the one called
        r = rt.invoke(d.id, new FunctionRuntime.Request("GET", "/", "", "1.1.1.1", "h", Map.of(), new byte[0])).get(10, TimeUnit.SECONDS);
        assertNotNull(r.error);
        assertTrue(r.detail, r.detail.contains("allowed hosts"));

        d.networkEnabled = false;
        r = rt.invoke(d.id, new FunctionRuntime.Request("GET", "/", "", "1.1.1.1", "h", Map.of(), new byte[0])).get(10, TimeUnit.SECONDS);
        assertTrue(r.detail, r.detail.contains("disabled"));
    }
}
