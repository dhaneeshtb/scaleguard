package com.scaleguard.server.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.router.RouteTarget;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.server.http.router.TargetSystem;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Persistence, hot reload, the admin API and the request handler. Run with -DargLine=-Duser.home=<tmpdir> to isolate the database. */
public class FunctionLifecycleTest {

    @BeforeClass
    public static void home() {
        String h = System.getProperty("user.home");
        org.junit.Assume.assumeTrue("run with -DargLine=-Duser.home=<tmpdir> so the test database is isolated", h.contains("sgfn") || h.contains("tmp") || h.contains("test"));
    }

    private static FunctionDef def(String name, String code) {
        FunctionDef d = new FunctionDef();
        d.name = name;
        d.code = code;
        return d;
    }

    private static FunctionRuntime.Result call(String id, String method, byte[] body) throws Exception {
        return FunctionRuntime.get().invoke(id, new FunctionRuntime.Request(method, "/p", "", "1.1.1.1", "h", Map.of(), body)).get();
    }

    private static String text(FunctionRuntime.Result r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    @Test
    public void saveCompilesFirstAndBadCodeIsNeverStored() throws Exception {
        FunctionRuntime rt = FunctionRuntime.get();
        int before = rt.all().size();
        String e = rt.save(def("bad", "return new File('/etc/passwd').text"));
        assertNotNull(e);
        assertTrue(e, e.contains("not allowed"));
        assertNotNull(rt.save(def("syntax", "def = = =")));
        FunctionDef net = def("net", "return 1");
        net.networkEnabled = true;
        assertNotNull(rt.save(net));
        assertEquals(before, rt.all().size());
    }

    @Test
    public void editsHotReloadWhileOldRunsKeepTheirVersion() throws Exception {
        FunctionRuntime rt = FunctionRuntime.get();
        FunctionDef d = def("hot-" + UUID.randomUUID(), "def t = ctx.now(); while (ctx.now() - t < 700) { }; return 'v1'");
        assertNull(rt.save(d));
        String id = d.id;
        var slow = rt.invoke(id, new FunctionRuntime.Request("GET", "/", "", "1.1.1.1", "h", Map.of(), new byte[0]));
        Thread.sleep(150);
        FunctionDef edit = rt.find(id);
        FunctionDef copy = FunctionApi.JSON.readValue(FunctionApi.JSON.writeValueAsString(edit), FunctionDef.class);
        copy.code = "return 'v2'";
        assertNull(rt.save(copy));
        assertEquals("\"v2\"".replace("\"", ""), text(call(id, "GET", new byte[0])).replace("\"", ""));
        assertEquals("v1", text(slow.get()).replace("\"", ""));
        rt.delete(id);
        assertEquals(503, call(id, "GET", new byte[0]).status);
    }

    @Test
    public void anotherNodesWriteIsPickedUpByReload() throws Exception {
        // what the cluster hook does after a replicated row arrives: write the row behind the runtime's back, then reload
        FunctionDef d = def("replicated", "return 'from peer'");
        d.id = UUID.randomUUID().toString();
        CloudFunction cf = new CloudFunction();
        cf.setId(d.id);
        cf.setName(d.name);
        cf.setPayload(FunctionApi.JSON.writeValueAsString(d));
        cf.setMts(1);
        cf.setUts(1);
        CloudFunctionDB.getInstance().save(cf);
        assertNull(FunctionRuntime.get().find(d.id));
        FunctionRuntime.get().reload();
        assertEquals("from peer", text(call(d.id, "GET", new byte[0])).replace("\"", ""));
        FunctionRuntime.get().delete(d.id);
    }

    @Test
    public void adminRouteCrud() throws Exception {
        FunctionRoute route = new FunctionRoute();
        var r = route.handle("POST", "/functions", FunctionApi.JSON.writeValueAsString(def("api-fn", "return [ok: true]")));
        assertEquals(r.getBody(), 200, r.getStatus());
        JsonNode created = FunctionApi.JSON.readTree(r.getBody());
        String id = created.get("id").asText();
        assertEquals(400, route.handle("POST", "/functions", FunctionApi.JSON.writeValueAsString(def("x", "System.exit(1)"))).getStatus());
        assertEquals(200, route.handle("GET", "/functions/" + id, null).getStatus());
        assertEquals(404, route.handle("GET", "/functions/nope", null).getStatus());
        assertTrue(route.handle("POST", "/functions/check", "{\"code\":\"new File('/')\"}").getBody().contains("\"ok\":false"));
        assertEquals(200, route.handle("PUT", "/functions/secrets/API_KEY", "{\"value\":\"s3cret\"}").getStatus());
        String list = route.handle("GET", "/functions", null).getBody();
        assertTrue(list.contains("API_KEY"));
        assertTrue("secret values are never returned", !list.contains("s3cret"));
        assertEquals(400, route.handle("PUT", "/functions/secrets/bad name", "{\"value\":\"x\"}").getStatus());
        route.handle("DELETE", "/functions/secrets/API_KEY", null);
        var t = route.handle("POST", "/functions/" + id + "/test", "{\"method\":\"GET\"}");
        JsonNode tj = FunctionApi.JSON.readTree(RequestRoutingResponseWait.body(t));
        assertEquals(200, tj.get("status").asInt());
        assertTrue(route.handle("GET", "/functions/" + id + "/runs", null).getBody().contains("\"status\":200"));
        assertEquals(200, route.handle("DELETE", "/functions/" + id, null).getStatus());
    }

    private static final class RequestRoutingResponseWait {
        static String body(com.scaleguard.server.http.reverse.RequestRoutingResponse r) throws Exception {
            return r.getDeferred() != null ? r.getDeferred().get().getBody() : r.getBody();
        }
    }

    // ------------------------------------------------------------------ request handler

    private FullHttpResponse send(FunctionDef d, HttpMethod m, byte[] body) throws Exception {
        TargetSystem ts = new TargetSystem();
        ts.setScheme("function");
        ts.setHost(d.id);
        RouteTarget rt = new RouteTarget(new SourceSystem(), ts);
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                FunctionHandler.handle(ctx, msg, rt, null, "9.9.9.9", Map.of("X-Trace-Id", "abc"));
            }
        });
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, m, "/hello?x=1", Unpooled.wrappedBuffer(body));
        ch.writeInbound(req);
        for (int i = 0; i < 200 && ch.outboundMessages().isEmpty(); i++) {
            Thread.sleep(10);
            ch.runPendingTasks();
        }
        return ch.readOutbound();
    }

    @Test
    public void handlerWritesStatusHeadersBodyAndRespectsMethodsAndLimits() throws Exception {
        FunctionRuntime rt = FunctionRuntime.get();
        FunctionDef d = def("handler-" + UUID.randomUUID(),
                "res.status(201).header('X-Fn', 'yes'); return [m: req.method, q: req.param('x'), ip: req.clientIp, len: req.length]");
        d.methods.add("POST");
        d.maxRequestBytes = 8;
        assertNull(rt.save(d));
        FullHttpResponse r = send(d, HttpMethod.POST, "abc".getBytes());
        assertEquals(201, r.status().code());
        assertEquals("yes", r.headers().get("X-Fn"));
        assertEquals("abc", r.headers().get("X-Trace-Id"));
        assertEquals("application/json", r.headers().get("Content-Type"));
        assertEquals("{\"m\":\"POST\",\"q\":\"1\",\"ip\":\"9.9.9.9\",\"len\":3}", r.content().toString(StandardCharsets.UTF_8));

        r = send(d, HttpMethod.GET, new byte[0]);
        assertEquals(405, r.status().code());
        assertEquals("POST", r.headers().get("Allow"));

        r = send(d, HttpMethod.POST, new byte[9]);
        assertEquals(413, r.status().code());

        FunctionDef off = FunctionApi.JSON.readValue(FunctionApi.JSON.writeValueAsString(rt.find(d.id)), FunctionDef.class);
        off.enabled = false;
        assertNull(rt.save(off));
        assertEquals(503, send(d, HttpMethod.POST, new byte[0]).status().code());
        rt.delete(d.id);
    }
}
