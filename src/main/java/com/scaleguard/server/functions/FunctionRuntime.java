package com.scaleguard.server.functions;

import com.scaleguard.server.http.utils.AppProperties;
import groovy.lang.Binding;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Runs cloud functions. Definitions live in the replicated cloud_functions table and are mirrored in memory; a script is
 * compiled once per (function id, code hash) and the Class is shared by all executions. Editing a function swaps the map
 * entry atomically: runs already in flight keep the old Class, new runs get the new one. Executions run on a bounded pool
 * of platform threads (a spinning script cannot be preempted off a virtual thread's carrier), with a per-function
 * concurrency cap, a hard deadline enforced by the interrupts compiled into the script plus a watchdog, and an
 * allocation budget measured per thread.
 */
public final class FunctionRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(FunctionRuntime.class);
    private static final FunctionRuntime INSTANCE = new FunctionRuntime();

    public static FunctionRuntime get() {
        return INSTANCE;
    }

    public record Request(String method, String path, String query, String clientIp, String host, Map<String, String> headers, byte[] body) {}

    public static final class Result {
        public int status = 200;
        public Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];
        /** Why the run failed (null = ok): timeout, memory, busy, disabled, error … */
        public String error;
        public String detail;
        public List<String> logs = new ArrayList<>();
        public long ms;
        public String runId = UUID.randomUUID().toString().substring(0, 8);
        public long at = System.currentTimeMillis();
        String method, path;
    }

    private static final class Compiled {
        final Class<? extends Script> cls;
        final String error;

        Compiled(Class<? extends Script> c, String e) { cls = c; error = e; }
    }

    /** Per-function state that survives edits: in-flight count and the run history. */
    private static final class Slot {
        final AtomicInteger inflight = new AtomicInteger();
        final ConcurrentLinkedDeque<Result> runs = new ConcurrentLinkedDeque<>();
        final AtomicLong total = new AtomicLong(), failed = new AtomicLong();
    }

    private static final class Run {
        final FunctionDef def;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        volatile Thread thread;
        volatile long deadline;
        volatile int killed;           // 1 = time limit, 2 = memory budget
        long startAlloc;

        Run(FunctionDef d) { def = d; }
    }

    static final int HISTORY = 50;
    private static final long GRACE_MS = 500;
    private volatile Map<String, FunctionDef> defs = Map.of();
    private final Map<String, Compiled> compiled = new ConcurrentHashMap<>();
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final Set<Run> active = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor pool;
    private volatile Function<String, InetAddress[]> resolver = SandboxHttp.SYSTEM_DNS;
    private final com.sun.management.ThreadMXBean mx;

    private FunctionRuntime() {
        String cfg = AppProperties.get("scaleguard.functions.threads");
        int n = Math.max(2, cfg != null && cfg.matches("\\d+") ? Integer.parseInt(cfg) : Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        AtomicInteger seq = new AtomicInteger();
        pool = new ThreadPoolExecutor(n, n, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(n * 8), r -> {
            Thread t = new Thread(null, r, "sg-fn-" + seq.incrementAndGet(), 1 << 19);   // 512 KB stack bounds recursion
            t.setDaemon(true);
            return t;
        });
        com.sun.management.ThreadMXBean m = null;
        try {
            if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t && t.isThreadAllocatedMemorySupported()) {
                t.setThreadAllocatedMemoryEnabled(true);
                m = t;
            }
        } catch (Throwable ignored) {
            // no allocation accounting on this JVM: the time limit and response caps still apply
        }
        mx = m;
        Thread w = new Thread(this::watch, "sg-fn-watchdog");
        w.setDaemon(true);
        w.start();
    }

    /** Test hook: use this definition without touching the database. */
    void putForTest(FunctionDef d) {
        Map<String, FunctionDef> m = new LinkedHashMap<>(defs);
        m.put(d.id, d);
        defs = m;
    }

    /** Test hook: resolve names without touching real DNS. */
    void setResolver(Function<String, InetAddress[]> r) {
        resolver = r;
    }

    // ------------------------------------------------------------------ definitions

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(String.valueOf(AppProperties.get("scaleguard.functions")));
    }

    public FunctionDef find(String id) {
        return id == null ? null : defs.get(id);
    }

    public List<FunctionDef> all() {
        List<FunctionDef> l = new ArrayList<>(defs.values());
        l.sort((a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
        return l;
    }

    private static String codeHash(FunctionDef d) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d.code.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Compiled compiled(FunctionDef d) {
        return compiled.computeIfAbsent(d.id + ":" + codeHash(d), k -> {
            try {
                return new Compiled(SandboxCompiler.compile(d.code, d.name), null);
            } catch (SandboxCompiler.CompileException e) {
                return new Compiled(null, e.getMessage());
            } catch (Throwable e) {
                return new Compiled(null, e.toString());
            }
        });
    }

    /** Re-reads the table (also the cluster reload hook). Never throws; a bad row is skipped. */
    public synchronized void reload() {
        try {
            Map<String, FunctionDef> fresh = new LinkedHashMap<>();
            for (CloudFunction cf : CloudFunctionDB.getInstance().readAll()) {
                try {
                    FunctionDef d = FunctionApi.JSON.readValue(cf.getPayload(), FunctionDef.class);
                    d.id = cf.getId();
                    d.updated = cf.getMts();
                    if (d.methods == null) d.methods = new ArrayList<>();
                    if (d.allowedHosts == null) d.allowedHosts = new ArrayList<>();
                    if (d.secrets == null) d.secrets = new ArrayList<>();
                    if (d.config == null) d.config = new LinkedHashMap<>();
                    fresh.put(d.id, d);
                } catch (Exception e) {
                    LOG.warn("Skipping unreadable cloud function {}: {}", cf.getId(), e.toString());
                }
            }
            FunctionSecrets.reload();
            defs = fresh;
            fresh.values().forEach(this::compiled);
            Set<String> keep = new java.util.HashSet<>();
            fresh.values().forEach(d -> keep.add(d.id + ":" + codeHash(d)));
            compiled.keySet().retainAll(keep);          // runs in flight still hold their own Class
            slots.keySet().retainAll(fresh.keySet());
        } catch (Throwable e) {
            LOG.error("Could not reload cloud functions: {}", e.toString());
        }
    }

    /** @return an error message, or null when saved */
    public synchronized String save(FunctionDef d) throws Exception {
        String bad = d.validate();
        if (bad != null) return bad;
        if (d.id == null || d.id.isBlank()) d.id = UUID.randomUUID().toString();
        Compiled c = compiled(d);
        if (c.error != null) {
            compiled.remove(d.id + ":" + codeHash(d));
            return "compile error: " + c.error;
        }
        CloudFunction cf = new CloudFunction();
        cf.setId(d.id);
        cf.setName(d.name);
        long now = System.currentTimeMillis();
        cf.setMts(now);
        cf.setUts(now);
        d.updated = now;
        cf.setPayload(FunctionApi.JSON.writeValueAsString(d));
        CloudFunctionDB.getInstance().save(cf);
        reload();
        return null;
    }

    public synchronized void delete(String id) throws Exception {
        CloudFunctionDB.getInstance().delete(id);
        reload();
    }

    /** Compile-check without saving (editor "check" button). */
    public String check(String code) {
        try {
            SandboxCompiler.compile(code, "check");
            return null;
        } catch (SandboxCompiler.CompileException e) {
            return e.getMessage();
        }
    }

    public Map<String, Object> stats(String id) {
        Slot s = slots.get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inflight", s == null ? 0 : s.inflight.get());
        m.put("total", s == null ? 0 : s.total.get());
        m.put("failed", s == null ? 0 : s.failed.get());
        return m;
    }

    public List<Map<String, Object>> runs(String id) {
        Slot s = slots.get(id);
        List<Map<String, Object>> out = new ArrayList<>();
        if (s == null) return out;
        for (Result r : s.runs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", r.runId);
            m.put("at", r.at);
            m.put("method", r.method);
            m.put("path", r.path);
            m.put("status", r.status);
            m.put("ms", r.ms);
            m.put("error", r.error);
            m.put("detail", r.detail);
            m.put("logs", r.logs);
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ execution

    private static Result failure(int status, String error, String detail) {
        Result r = new Result();
        r.status = status;
        r.error = error;
        r.detail = detail;
        r.headers.put("Content-Type", "application/json");
        r.body = ("{\"error\":\"" + error + "\",\"run\":\"" + r.runId + "\"}").getBytes(StandardCharsets.UTF_8);
        return r;
    }

    /** Never throws. The returned future always completes (with an error Result on failure). */
    public CompletableFuture<Result> invoke(String id, Request req) {
        FunctionDef d = find(id);
        if (d == null || !d.enabled || !enabled()) {
            return CompletableFuture.completedFuture(failure(503, "function_unavailable", null));
        }
        if (!d.allows(req.method())) {
            Result r = failure(405, "method_not_allowed", null);
            r.headers.put("Allow", d.methods.isEmpty() ? "" : String.join(", ", d.methods).toUpperCase());
            return CompletableFuture.completedFuture(r);
        }
        if (req.body() != null && req.body().length > d.maxRequestBytes) {
            return CompletableFuture.completedFuture(failure(413, "request_too_large", null));
        }
        Slot slot = slots.computeIfAbsent(id, k -> new Slot());
        if (slot.inflight.incrementAndGet() > d.maxConcurrent) {
            slot.inflight.decrementAndGet();
            Result r = failure(503, "busy", null);
            r.headers.put("Retry-After", "1");
            return CompletableFuture.completedFuture(record(slot, r, req));
        }
        Run run = new Run(d);
        active.add(run);
        try {
            pool.execute(() -> execute(run, d, req, slot));
        } catch (RejectedExecutionException e) {
            active.remove(run);
            slot.inflight.decrementAndGet();
            Result r = failure(503, "busy", null);
            r.headers.put("Retry-After", "1");
            return CompletableFuture.completedFuture(record(slot, r, req));
        }
        return run.future;
    }

    private Result record(Slot slot, Result r, Request req) {
        r.method = req.method();
        r.path = req.path();
        slot.total.incrementAndGet();
        if (r.error != null) slot.failed.incrementAndGet();
        slot.runs.addFirst(r);
        while (slot.runs.size() > HISTORY) slot.runs.pollLast();
        return r;
    }

    private void execute(Run run, FunctionDef d, Request req, Slot slot) {
        long t0 = System.nanoTime();
        Thread me = Thread.currentThread();
        FunctionApi.Log log = new FunctionApi.Log();
        Result out;
        try {
            Compiled c = compiled(d);        // compiling (first call after an edit) is not charged to the script's budget
            run.startAlloc = mx == null ? 0 : mx.getThreadAllocatedBytes(me.getId());
            run.deadline = System.nanoTime() + d.timeoutMs * 1_000_000L;
            run.thread = me;
            if (me.isInterrupted()) Thread.interrupted();
            if (c.error != null) {
                out = failure(500, "compile_error", c.error);
            } else {
                out = call(c, d, req, log, run);
            }
        } catch (Throwable t) {
            out = failure(500, "error", String.valueOf(t));
        }
        Thread.interrupted();           // clear a late interrupt before the pooled thread is reused
        run.thread = null;
        if (run.killed == 1) out = failure(504, "timeout", "exceeded " + d.timeoutMs + " ms");
        else if (run.killed == 2) out = failure(500, "memory_limit", "exceeded " + d.maxMemoryMb + " MB of allocation");
        out.logs = new ArrayList<>(log.lines);
        out.ms = (System.nanoTime() - t0) / 1_000_000;
        try {
            record(slot, out, req);
        } finally {
            active.remove(run);
            slot.inflight.decrementAndGet();
            run.future.complete(out);
        }
    }

    private Result call(Compiled c, FunctionDef d, Request req, FunctionApi.Log log, Run run) {
        FunctionApi.Req rq = new FunctionApi.Req(req.method(), req.path(), req.query(), req.clientIp(), req.host(), req.headers(), req.body());
        FunctionApi.Res rs = new FunctionApi.Res(d.maxResponseBytes);
        FunctionApi.Http http = new FunctionApi.Http(new SandboxHttp(d, run.deadline, resolver));
        FunctionApi.Ctx ctx = new FunctionApi.Ctx(rq, rs, log, http, d);
        Binding b = new Binding();
        b.setVariable("req", rq);
        b.setVariable("res", rs);
        b.setVariable("ctx", ctx);
        b.setVariable("log", log);
        b.setVariable("http", http);
        b.setVariable("config", ctx.getConfig());
        try {
            Script script = InvokerHelper.createScript(c.cls, b);
            Object ret;
            SandboxPolicy.ACTIVE.set(Boolean.TRUE);
            try {
                ret = script.run();
            } finally {
                SandboxPolicy.ACTIVE.remove();
            }
            Result r = new Result();
            r.status = rs.statusCode();
            r.headers = new LinkedHashMap<>(rs.headerMap());
            byte[] body = rs.bodyBytes();
            if (body == null && ret != null) {
                FunctionApi.Res tmp = new FunctionApi.Res(d.maxResponseBytes);
                tmp.send(ret);
                body = tmp.bodyBytes();
                r.headers.putIfAbsent("Content-Type", tmp.headerMap().get("Content-Type"));
            }
            r.body = body == null ? new byte[0] : body;
            if (r.body.length > d.maxResponseBytes) return failure(502, "response_too_large", null);
            return r;
        } catch (FunctionApi.TooLarge e) {
            return failure(502, "response_too_large", e.getMessage());
        } catch (StackOverflowError e) {
            return failure(500, "stack_overflow", "recursion too deep");
        } catch (OutOfMemoryError e) {
            return failure(500, "memory_limit", "out of memory");
        } catch (Throwable e) {
            if (Boolean.getBoolean("scaleguard.functions.debug")) e.printStackTrace();
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            if (Boolean.getBoolean("scaleguard.functions.debug")) root.printStackTrace();
            String msg = root.getClass().getSimpleName() + ": " + root.getMessage();
            return failure(500, "error", msg.length() > 500 ? msg.substring(0, 500) : msg);
        }
    }

    private void watch() {
        while (true) {
            try {
                Thread.sleep(20);
                long now = System.nanoTime();
                for (Run r : active) {
                    Thread t = r.thread;
                    if (t == null) continue;
                    if (r.killed == 0 && now > r.deadline) {
                        r.killed = 1;
                        t.interrupt();
                    } else if (r.killed == 0 && mx != null && r.def.maxMemoryMb > 0
                            && mx.getThreadAllocatedBytes(t.getId()) - r.startAlloc > r.def.maxMemoryMb * 1_048_576L) {
                        r.killed = 2;
                        t.interrupt();
                    }
                    if (r.killed != 0) t.interrupt();
                    // a thread stuck in host code (e.g. a regex) can't be stopped: answer the client, keep the permit until it ends
                    if (r.killed != 0 && now > r.deadline + GRACE_MS * 1_000_000L && !r.future.isDone()) {
                        r.future.complete(failure(r.killed == 1 ? 504 : 500, r.killed == 1 ? "timeout" : "memory_limit", "script did not stop"));
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                LOG.debug("watchdog: {}", e.toString());
            }
        }
    }

    static Map<String, String> headersOf(io.netty.handler.codec.http.HttpHeaders h) {
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, String> e : h) m.merge(e.getKey().toLowerCase(), e.getValue(), (a, b) -> a + ", " + b);
        return Collections.unmodifiableMap(m);
    }
}
