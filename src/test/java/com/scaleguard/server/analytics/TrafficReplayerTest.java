package com.scaleguard.server.analytics;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public class TrafficReplayerTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String cmp(String exp, String act, Set<String> ignore) {
        return TrafficReplayer.compare(200, "[]", b(exp), false, 200, null, b(act), ignore);
    }

    @Test
    public void statusMismatch() {
        assertEquals("status 200 → 500", TrafficReplayer.compare(200, "[]", b("x"), false, 500, null, b("x"), Set.of()));
    }

    @Test
    public void jsonEqualIgnoringOrderAndFields() {
        assertNull(cmp("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}", Set.of()));
        assertNull(cmp("{\"id\":\"x1\",\"items\":[{\"ts\":1,\"v\":3}]}", "{\"id\":\"y9\",\"items\":[{\"ts\":2,\"v\":3}]}", Set.of("id", "ts")));
    }

    @Test
    public void reportsFirstDifferingPath() {
        assertEquals("JSON differs at $.items[0].v", cmp("{\"items\":[{\"v\":3}]}", "{\"items\":[{\"v\":4}]}", Set.of()));
        assertEquals("JSON differs at $.gone (missing)", cmp("{\"gone\":1}", "{}", Set.of()));
        assertEquals("JSON differs at $.list (length 1 → 2)", cmp("{\"list\":[1]}", "{\"list\":[1,2]}", Set.of()));
    }

    @Test
    public void nonJsonBodies() {
        assertNull(cmp("hello", "hello", Set.of()));
        assertEquals("body differs (5 → 5 bytes)", cmp("hello", "world", Set.of()));
    }

    @Test
    public void gzipComparedDecompressed() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bo)) { g.write(b("{\"a\":1}")); }
        assertNull(TrafficReplayer.compare(200, "[[\"Content-Encoding\",\"gzip\"]]", bo.toByteArray(), false, 200, null, b("{\"a\":1}"), Set.of()));
    }

    @Test
    public void truncatedRecordingComparesStatusOnly() {
        assertNull(TrafficReplayer.compare(200, "[]", b("part"), true, 200, null, b("whole body"), Set.of()));
    }
}
