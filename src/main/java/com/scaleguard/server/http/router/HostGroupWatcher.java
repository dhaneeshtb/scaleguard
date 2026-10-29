package com.scaleguard.server.http.router;

import com.scaleguard.server.concurrent.VirtualThreads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class HostGroupWatcher {

    private static final Logger logger
            = LoggerFactory.getLogger(HostGroupWatcher.class);

    /** Active health-check poll interval; override with {@code -Dhost.health.interval.seconds=N} (default 10). */
    private static final long HEALTH_CHECK_INTERVAL_MS =
            Long.parseLong(System.getProperty("host.health.interval.seconds", "10")) * 1000L;

    private final List<HostGroup> allHosts;

    public HostGroupWatcher(List<HostGroup> allHosts){
        this.allHosts=allHosts;
    }

    /**
     * Polls every host's health URL concurrently (one virtual thread per probe, 2 s connect / 5 s read timeout),
     * so one slow backend doesn't delay the others' results. Logs only on state changes.
     * Passive health checking ({@link PassiveHealthManager}) reacts to real request failures between polls.
     */
    public void start(){
        VirtualThreads.submit("host-group-watcher", () -> {
            while(true) {
                try {
                    List<HostGroup> hosts = List.copyOf(allHosts);
                    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(hosts.size());
                    for (HostGroup hg : hosts) {
                        Thread.ofVirtual().name("health-probe").start(() -> {
                            try { probe(hg); } finally { done.countDown(); }
                        });
                    }
                    done.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.error("Health check round failed", e);
                }
                try {
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private static void probe(HostGroup hg) {
        String url = hg.getHealth();
        boolean ok;
        String why = null;
        if (url == null || url.isEmpty()) {
            ok = true;
        } else {
            try {
                int code = statusOf(url);
                ok = code >= 200 && code < 300;
                if (!ok) why = "HTTP " + code;
            } catch (Exception e) {
                ok = false;
                why = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            }
        }
        boolean was = hg.isActivelyHealthy();
        hg.setReachable(ok);
        if (was && !ok) logger.error("Health check failed for {} ({}) — host taken out of rotation", url, why);
        else if (!was && ok) logger.info("Health check passing again for {} — host back in rotation", url);
    }

    /** Status code of a GET, reading at most a little of the body; short timeouts for health probes. */
    static int statusOf(String urlToRead) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlToRead).openConnection();
        try {
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            try (java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (in != null) in.readNBytes(4096);
            }
            return code;
        } finally {
            conn.disconnect();
        }
    }

    public static int getResponse(String urlToRead) throws Exception {
        StringBuilder result = new StringBuilder();
        URL url = new URL(urlToRead);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    result.append(line);
                }
            }
            return conn.getResponseCode();
        }finally {
            conn.disconnect();
        }
    }

    /**
     * Fetch the body of {@code urlToRead} as a String (silent overload).
     *
     * <p>Exceptions are swallowed and an empty string is returned. Use this for
     * non-critical calls such as health-check probes where a failure is acceptable.</p>
     */
    public static String getResponseData(String urlToRead) {
        try {
            return getResponseDataOrThrow(urlToRead);
        } catch (IOException e) {
            logger.warn("HTTP GET failed for '{}': {}", urlToRead, e.getMessage());
            return "";
        }
    }

    /**
     * FIX #3: Throwing overload of {@link #getResponseData}.
     *
     * <p>Use this variant when the caller <em>must</em> know whether the request
     * succeeded — for example, ACME HTTP-01 challenge verification.  Any network
     * or I/O error is propagated as an {@link IOException} so the caller can wrap
     * it with a meaningful error message instead of silently returning {@code ""}
     * and then falsely reporting a content mismatch.</p>
     */
    public static String getResponseDataOrThrow(String urlToRead) throws IOException {
        StringBuilder result = new StringBuilder();
        URL url = new URL(urlToRead);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    result.append(line);
                }
            }
        } finally {
            conn.disconnect();
        }
        return result.toString();
    }

    /** Simple load-test harness — run from CLI only; not used by the server. */
    public static void main(String[] args) {
        AtomicInteger ai = new AtomicInteger(0);
        AtomicInteger aiSuccess = new AtomicInteger(0);
        AtomicLong elapsedMs = new AtomicLong(0);
        ExecutorService es = Executors.newFixedThreadPool(200);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            es.submit(() -> {
                for (int j = 0; j < 100; j++) {
                    try {
                        int rs = getResponse("http://localhost:8080/hckeck");
                        if (rs != 200) {
                            ai.incrementAndGet();
                            logger.warn("Unexpected response code: {}", rs);
                        } else {
                            aiSuccess.incrementAndGet();
                        }
                    } catch (Exception e) {
                        ai.incrementAndGet();
                        logger.warn("Request failed: {}", e.getMessage());
                    }
                }
            });
        }
        es.shutdown();
        try {
            es.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        elapsedMs.set(System.currentTimeMillis() - start);
        logger.info("Failure count -> {}", ai.get());
        logger.info("Success count -> {}", aiSuccess.get());
        logger.info("Time taken    -> {} ms", elapsedMs.get());
    }
}
