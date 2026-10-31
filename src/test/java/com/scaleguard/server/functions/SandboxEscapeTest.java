package com.scaleguard.server.functions;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Every script here tries to get out of the sandbox; each must fail with an error and must not leak anything from the host. */
public class SandboxEscapeTest {

    static FunctionRuntime.Result run(String code) throws Exception {
        return run(code, d -> {});
    }

    static FunctionRuntime.Result run(String code, java.util.function.Consumer<FunctionDef> tweak) throws Exception {
        FunctionDef d = new FunctionDef();
        d.id = UUID.randomUUID().toString();
        d.name = "t";
        d.code = code;
        d.timeoutMs = 1500;
        tweak.accept(d);
        FunctionRuntime rt = FunctionRuntime.get();
        // compile errors surface from the runtime as compile_error results
        rt.putForTest(d);
        return rt.invoke(d.id, new FunctionRuntime.Request("GET", "/x", "a=1", "203.0.113.5", "h", Map.of("x-test", "1"), new byte[0]))
                .get(10, TimeUnit.SECONDS);
    }

    static String body(FunctionRuntime.Result r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    private static void denied(String code) throws Exception {
        FunctionRuntime.Result r = run(code);
        assertNotNull("expected failure for: " + code + " but got " + body(r), r.error);
        assertEquals("script must not succeed: " + code, 500, r.status);
        // refused by the sandbox itself, not by a typo
        assertTrue(code + " -> " + r.detail, r.detail != null && (r.detail.contains("not allowed") || r.detail.contains("is not available")));
        assertTrue("no host data in " + r.detail, r.detail == null || !r.detail.contains("/Users/") && !r.detail.contains("root:"));
    }

    @Test
    public void harmlessScriptWorks() throws Exception {
        FunctionRuntime.Result r = run("def l = [3,1,2].sort(); return [sum: l.sum(), path: req.path, q: req.param('a'), h: req.header('X-Test'), up: 'ab'.toUpperCase()]");
        assertNull(r.detail, r.error);
        assertEquals("{\"sum\":6,\"path\":\"/x\",\"q\":\"1\",\"h\":\"1\",\"up\":\"AB\"}", body(r));
    }

    @Test
    public void fileSystemIsOutOfReach() throws Exception {
        denied("return new File('/etc/passwd').text");
        denied("return new java.io.File('/etc/passwd').text");
        denied("return java.nio.file.Files.readAllLines(java.nio.file.Paths.get('/etc/passwd'))");
        denied("return java.nio.file.Files.readString(java.nio.file.Path.of('/etc/hosts'))");
        denied("new FileReader('/etc/passwd').text");
        denied("return '/etc/passwd' as File");
    }

    @Test
    public void systemRuntimeProcessAreOutOfReach() throws Exception {
        denied("System.exit(0)");
        denied("return System.getenv()");
        denied("return System.getProperty('user.home')");
        denied("return System.properties");
        denied("Runtime.getRuntime().exec('id')");
        denied("return 'id'.execute().text");
        denied("return ['id'].execute().text");
        denied("new ProcessBuilder('id').start()");
        denied("return System.getenv('HOME')");
        denied("return java.lang.System.currentTimeMillis()");
    }

    @Test
    public void reflectionAndClassLoadingAreBlocked() throws Exception {
        denied("return 'x'.getClass()");
        denied("return 'x'.class");
        denied("return 'x'.metaClass");
        denied("return Class.forName('java.lang.Runtime')");
        denied("return this.class.classLoader");
        denied("return this.getClass().getClassLoader()");
        denied("return req.class");
        denied("return req.getClass().getName()");
        denied("return ctx.metaClass");
        denied("return ctx.class.classLoader.loadClass('java.lang.Runtime')");
        denied("return ctx.getClass().getDeclaredFields()");
        denied("def k = 'cl' + 'ass'; return 'x'[k]");
        denied("def k = 'cl' + 'ass'; return req[k].name");
        denied("def m = 'get' + 'Class'; return 'x'.\"$m\"()");
        denied("return 'x'.&getClass");
        denied("return 'x'.@value");
        denied("return binding.variables");
        denied("return getBinding()");
        denied("return evaluate('1+1')");
        denied("return new GroovyShell().evaluate('1+1')");
        denied("return Eval.me('1+1')");
        denied("return groovy.lang.GroovyClassLoader");
        denied("return [1].invokeMethod('size', null)");
        denied("return this.invokeMethod('run', null)");
        denied("return Math.class.classLoader");
        denied("return 'x'.with { it.class }");
        denied("def c = {}; return c.owner.binding");
        denied("def c = {}; return c.metaClass.methods");
        denied("def c = {}; return c.thisObject.getClass()");
        denied("def c = {}; def k = 'cl' + 'ass'; return c.thisObject[k]");
        denied("def c = {}; def k = 'meta' + 'Class'; return c[k].methods");
        denied("return [1, 2].collect { it.getClass() }");
        denied("return 'http://127.0.0.1/'.toURL().text");
        denied("return new URL('http://127.0.0.1/').text");
        denied("return java.net.InetAddress.getByName('localhost')");
        denied("return java.lang.Class.forName('java.lang.Runtime')");
        denied("def l = [1]; l.metaClass.invokeMethod = { }; return 1");
        denied("String.metaClass.foo = { }; return 1");
        denied("return Integer.metaClass");
        denied("return Integer.MAX_VALUE.class");
    }

    @Test
    public void annotationsImportsAndClassesAreRejectedAtCompileTime() throws Exception {
        denied("@Grab('org.apache.commons:commons-lang3:3.12.0')\nimport org.apache.commons.lang3.StringUtils\nreturn 1");
        denied("@groovy.transform.ASTTest(value={ println 1 })\ndef x = 1\nreturn x");
        denied("import java.io.File\nreturn new File('/').list()");
        denied("import static java.lang.System.exit\nexit(0)");
        denied("class Evil { static void go() { System.exit(0) } }\nEvil.go()");
        denied("def x = new int[Integer.MAX_VALUE]; return x.length");
        denied("return new Object() { def a = 1 }.a");
        denied("return Thread.currentThread()");
        denied("return java.lang.reflect.Array.newInstance(String, 2)");
    }

    @Test
    public void allowlistedImportsWorkOthersDoNot() throws Exception {
        FunctionRuntime.Result r = run("import groovy.json.JsonSlurper\nimport java.time.Duration\ndef d = new JsonSlurper().parseText('{\"a\": [1, 2]}')\nreturn [n: d.a.size(), s: Duration.ofSeconds(90).toMillis()]");
        assertNull(r.detail, r.error);
        assertEquals("{\"n\":2,\"s\":90000}", body(r));
        r = run("import groovy.json.*\nreturn new JsonSlurper().parseText('[1]').size()");
        assertNull(r.detail, r.error);
        r = run("import java.time.Instant\nreturn Instant.ofEpochMilli(0).toString()");
        assertNull(r.detail, r.error);
        denied("import java.io.File\nreturn 1");
        denied("import java.io.*\nreturn 1");
        denied("import java.net.URL\nreturn 1");
        denied("import java.lang.reflect.Method\nreturn 1");
        denied("import java.nio.file.*\nreturn 1");
        denied("import java.lang.Runtime as R\nreturn 1");
        denied("import groovy.lang.GroovyShell\nreturn 1");
        denied("import groovy.json.JsonSlurper\nreturn new JsonSlurper().parse(new File('/etc/passwd'))");
        denied("import groovy.json.JsonSlurper\nreturn new JsonSlurper().parse(new URL('http://127.0.0.1/'))");
        denied("import groovy.json.JsonSlurper\nreturn new JsonSlurper().class");
        denied("import groovy.json.JsonSlurper\nreturn JsonSlurper.class.classLoader");
    }

    @Test
    public void threadsAreForbidden() throws Exception {
        denied("Thread.start { println 'x' }");
        denied("new Thread({ }).start()");
        denied("def t = new Thread({ }); t.start()");
        denied("java.util.concurrent.Executors.newFixedThreadPool(2).submit({ })");
        denied("new Timer().schedule({ } as TimerTask, 1)");
        denied("Thread.sleep(10)");
        denied("sleep(10)");
        denied("return java.util.concurrent.CompletableFuture.runAsync({ })");
    }

    @Test
    public void infiniteLoopsAreStopped() throws Exception {
        long t0 = System.nanoTime();
        FunctionRuntime.Result r = run("while (true) { }");
        assertEquals("timeout", r.error);
        assertEquals(504, r.status);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5000);

        assertStopped(run("for (;;) { def x = 1 + 1 }"));
        assertStopped(run("def f(n) { return f(n + 1) > 0 ? 1 : 0 }\n try { f(0) } catch (Throwable t) { }\n while (true) { }"));
        assertStopped(run("(1..Integer.MAX_VALUE).each { def y = it * 2 }"));
        assertStopped(run("def c = { while(true) {} }; c()"));
        assertStopped(run("try { while (true) { } } catch (Throwable t) { while (true) { } }"));
    }

    private static void assertStopped(FunctionRuntime.Result r) {
        assertTrue(r.error + " " + r.detail, "timeout".equals(r.error) || "memory_limit".equals(r.error));
    }

    @Test
    public void hugeAllocationsAreStopped() throws Exception {
        FunctionRuntime.Result r = run("def s = 'x'; while (true) { s = s + s }", d -> { d.maxMemoryMb = 32; d.timeoutMs = 5000; });
        assertNotNull(r.error);
        assertTrue(r.error, r.error.equals("memory_limit") || r.error.equals("timeout") || r.error.equals("error"));
        r = run("def l = []; while (true) { l << new ArrayList() }", d -> { d.maxMemoryMb = 32; d.timeoutMs = 5000; });
        assertNotNull(r.error);
        // the gateway is still alive and serving
        assertNull(run("return 1").error);
    }

    @Test
    public void deepRecursionDoesNotCrashTheHost() throws Exception {
        FunctionRuntime.Result r = run("def f(n) { return f(n + 1) }\nreturn f(0)");
        assertNotNull(r.error);
        assertNull(run("return 2").error);
    }

    @Test
    public void responseAndRequestLimits() throws Exception {
        FunctionRuntime.Result r = run("res.text('x' * 5000); return null", d -> d.maxResponseBytes = 100);
        assertNotNull(r.error);
        r = run("return 'x' * 5000", d -> d.maxResponseBytes = 100);
        assertEquals(r.detail, "response_too_large", r.error);

        FunctionDef d = new FunctionDef();
        d.id = UUID.randomUUID().toString();
        d.name = "t";
        d.maxRequestBytes = 10;
        FunctionRuntime.get().putForTest(d);
        r = FunctionRuntime.get().invoke(d.id, new FunctionRuntime.Request("POST", "/", "", "1.1.1.1", "h", Map.of(), new byte[11])).get();
        assertEquals(413, r.status);
    }

    @Test
    public void secretsAndConfigAreOnlyWhatTheAdminListed() throws Exception {
        FunctionSecrets.set("DB_PASSWORD", "hunter2");
        FunctionRuntime.Result un = run("return ctx.secret('DB_PASSWORD') == null", d -> {});
        assertEquals(un.detail, "true", body(un));
        FunctionRuntime.Result r = run("return [v: ctx.secret('DB_PASSWORD'), c: config.region]", d -> {
            d.secrets.add("DB_PASSWORD");
            d.config.put("region", "eu");
        });
        assertEquals("{\"v\":\"hunter2\",\"c\":\"eu\"}", body(r));
        FunctionSecrets.delete("DB_PASSWORD");
    }

    @Test
    public void concurrencyLimitAndMethods() throws Exception {
        FunctionDef d = new FunctionDef();
        d.id = UUID.randomUUID().toString();
        d.name = "c";
        d.maxConcurrent = 1;
        d.timeoutMs = 3000;
        d.methods.add("POST");
        d.code = "def t = ctx.now(); while (ctx.now() - t < 800) { }; return 'ok'";
        FunctionRuntime rt = FunctionRuntime.get();
        rt.putForTest(d);
        assertEquals(405, rt.invoke(d.id, new FunctionRuntime.Request("GET", "/", "", "1.1.1.1", "h", Map.of(), new byte[0])).get().status);
        var first = rt.invoke(d.id, new FunctionRuntime.Request("POST", "/", "", "1.1.1.1", "h", Map.of(), new byte[0]));
        assertEquals(503, rt.invoke(d.id, new FunctionRuntime.Request("POST", "/", "", "1.1.1.1", "h", Map.of(), new byte[0])).get().status);
        FunctionRuntime.Result f = first.get();
        assertEquals(f.detail, 200, f.status);
        assertNotEquals("busy", rt.invoke(d.id, new FunctionRuntime.Request("POST", "/", "", "1.1.1.1", "h", Map.of(), new byte[0])).get().error);
    }
}
