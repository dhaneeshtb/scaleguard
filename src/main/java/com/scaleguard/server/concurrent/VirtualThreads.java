package com.scaleguard.server.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Central virtual-thread infrastructure for ScaleGuard.
 *
 * Java 21 virtual threads (Project Loom) are used for ALL blocking work:
 *   - DNS setup, SSH, Kafka consumers, cert renewal
 *   - Async request processing (handleAsync / handleSubSystem)
 *   - Any blocking DB / config I/O
 *
 * Netty's NIO event loops are intentionally NOT replaced — they remain the
 * non-blocking IO layer for accepting connections and forwarding data.
 * Virtual threads sit on top of Netty's event loops for blocking fan-out.
 *
 * GraalVM note: virtual threads are fully supported in GraalVM 21+.
 * The native-image agent will capture any reflection used inside tasks.
 */
public final class VirtualThreads {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreads.class);

    /** Single shared virtual-thread executor. Use for ALL blocking tasks. */
    private static final ExecutorService EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    /** Virtual thread factory (for code that needs a raw ThreadFactory). */
    public static final ThreadFactory FACTORY =
            Thread.ofVirtual().name("sg-vt-", 0).factory();

    private VirtualThreads() {}

    /**
     * Submit a blocking task to run on a virtual thread.
     * Returns immediately; the task runs concurrently.
     */
    public static void submit(Runnable task) {
        EXECUTOR.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("[VT] Uncaught exception in virtual thread task", e);
            }
        });
    }

    /**
     * Submit a named blocking task (useful for debugging thread dumps).
     */
    public static void submit(String name, Runnable task) {
        Thread.ofVirtual().name(name).start(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("[VT] Uncaught exception in virtual thread '{}' ", name, e);
            }
        });
    }

    /**
     * Blocking version: run task on virtual thread and wait for completion.
     * Use sparingly — only during startup/shutdown sequences.
     */
    public static void runAndWait(String name, Runnable task) {
        try {
            Thread t = Thread.ofVirtual().name(name).start(task);
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[VT] Interrupted while waiting for '{}'", name);
        }
    }

    /** Executor instance — pass to libraries that accept ExecutorService. */
    public static ExecutorService executor() {
        return EXECUTOR;
    }
}
