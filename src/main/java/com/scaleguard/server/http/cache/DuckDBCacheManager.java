package com.scaleguard.server.http.cache;

import com.scaleguard.server.concurrent.VirtualThreads;
import com.scaleguard.server.http.router.TargetSystem;
import com.scaleguard.server.licencing.licensing.LicenceUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two-tier HTTP response cache backed by DuckDB.
 *
 * <h3>Architecture</h3>
 * <pre>
 *  Netty I/O thread
 *       │
 *       ▼
 *  ┌─────────────────────────────┐
 *  │   L1  (in-process heap)     │  ConcurrentHashMap of byte[] snapshots
 *  │   Hot responses served here │  Bounded to L1_MAX_ENTRIES (default 2 000)
 *  │   ~0 µs  per hit            │  LRU-like eviction via access-time tracking
 *  └──────────┬──────────────────┘
 *             │ MISS
 *             ▼
 *  ┌─────────────────────────────┐
 *  │   L2  (DuckDB on disk)      │  Persistent across restarts
 *  │   Large dataset support     │  Hit promotes entry to L1
 *  │   ~1–5 ms per hit           │
 *  └─────────────────────────────┘
 * </pre>
 *
 * <h3>Write path — fully off the Netty I/O thread</h3>
 * <ol>
 *   <li>Body bytes are snapshot from the Netty {@link ByteBuf} <em>on</em> the I/O thread
 *       (a single {@code getBytes()} call — zero blocking).</li>
 *   <li>The snapshot is immediately installed in L1 — the I/O thread is released.</li>
 *   <li>A virtual thread performs the DuckDB INSERT asynchronously — DuckDB I/O
 *       never stalls a Netty worker thread.</li>
 * </ol>
 *
 * <h3>Read path — no blocking on the Netty I/O thread for hot entries</h3>
 * <ol>
 *   <li>L1 hit: reconstruct a fresh {@link FullHttpResponse} from stored bytes — heap copy,
 *       no reference counting, no JDBC, no I/O.  ~100 ns.</li>
 *   <li>L1 miss / L2 hit: DuckDB lookup on a virtual thread (via
 *       {@link #lookupAsync}); the I/O thread only touches L1.</li>
 *   <li>Total miss: return empty response so the proxy fetches from the backend.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * L1 is a {@link ConcurrentHashMap} — lock-free reads, fine-grained write locks.
 * DuckDB writes are serialised by a single {@link ReentrantLock} per connection.
 *
 * <h3>Configuration</h3>
 * <table>
 *   <tr><th>JVM property</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@code duckdb.cache.path}</td><td>~/.scaleguard/cache.duckdb</td><td>File path</td></tr>
 *   <tr><td>{@code duckdb.cache.l1.size}</td><td>2000</td><td>Max L1 entries</td></tr>
 *   <tr><td>{@code duckdb.cache.pool}</td><td>4</td><td>DuckDB connection pool size</td></tr>
 * </table>
 */
public class DuckDBCacheManager implements CacheManager {

    private static final Logger LOG = LoggerFactory.getLogger(DuckDBCacheManager.class);

    // ── JVM property keys ────────────────────────────────────────────────────
    public static final String DB_PATH_PROPERTY   = "duckdb.cache.path";
    public static final String L1_SIZE_PROPERTY   = "duckdb.cache.l1.size";
    public static final String POOL_SIZE_PROPERTY = "duckdb.cache.pool";

    /** Default TTL in seconds. */
    private static final int DEFAULT_TTL_SECONDS  = 300;

    // ── Singleton ────────────────────────────────────────────────────────────
    private static volatile DuckDBCacheManager INSTANCE;

    public static DuckDBCacheManager getInstance() {
        if (INSTANCE == null) {
            synchronized (DuckDBCacheManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DuckDBCacheManager();
                }
            }
        }
        return INSTANCE;
    }

    // ── L1 in-process cache ──────────────────────────────────────────────────

    /**
     * Immutable snapshot of one cached HTTP response stored as plain byte[].
     * No Netty reference counting — safe to read from any thread concurrently.
     */
    static final class L1Entry {
        final byte[]  body;
        final int     statusCode;
        final String  contentType;
        final String  headersJson;
        final long    expiresAt;
        volatile long lastAccess;

        L1Entry(byte[] body, int statusCode, String contentType,
                String headersJson, long expiresAt) {
            this.body        = body;
            this.statusCode  = statusCode;
            this.contentType = contentType;
            this.headersJson = headersJson;
            this.expiresAt   = expiresAt;
            this.lastAccess  = System.nanoTime();
        }

        boolean isAlive() {
            return System.currentTimeMillis() < expiresAt;
        }
    }

    private final int l1MaxEntries;
    /** L1 store — key → snapshot.  ConcurrentHashMap: lock-free reads. */
    private final ConcurrentHashMap<String, L1Entry> l1 = new ConcurrentHashMap<>();
    /** Monotonically incrementing write counter used to approximate LRU eviction. */
    private final AtomicLong l1WriteCount = new AtomicLong(0);

    // ── L2 DuckDB connection pool ────────────────────────────────────────────

    private final int poolSize;
    private final Connection[]    pool;
    private final ReentrantLock[] poolLocks;
    private final String          dbPath;

    // ── Constructor ──────────────────────────────────────────────────────────

    private DuckDBCacheManager() {
        // Resolve config from system properties
        String configuredPath = System.getProperty(DB_PATH_PROPERTY, "").trim();
        dbPath = configuredPath.isEmpty() ? LicenceUtil.dataDir() + "/cache.duckdb" : configuredPath;

        l1MaxEntries = Integer.parseInt(System.getProperty(L1_SIZE_PROPERTY, "2000"));
        poolSize     = Integer.parseInt(System.getProperty(POOL_SIZE_PROPERTY, "4"));
        pool         = new Connection[poolSize];
        poolLocks    = new ReentrantLock[poolSize];
        for (int i = 0; i < poolSize; i++) {
            poolLocks[i] = new ReentrantLock();
        }

        try {
            Class.forName("org.duckdb.DuckDBDriver");
            initSchema();
            DuckDBCacheEvictor.start(this);
            LOG.info("DuckDB cache ready  db={} l1_max={} pool={}", dbPath, l1MaxEntries, poolSize);
        } catch (Exception e) {
            LOG.error("DuckDB cache init failed at '{}': {}", dbPath, e.getMessage(), e);
            throw new RuntimeException("DuckDB cache init failed", e);
        }
    }

    // ── Connection management ────────────────────────────────────────────────

    /** Select a pool slot using caller thread-id, wrapped into [0, poolSize). */
    private int slot() {
        long tid = Thread.currentThread().getId();
        // Use abs + mod; threadId can be arbitrary (especially virtual threads)
        return (int) (Math.abs(tid) % poolSize);
    }

    private Connection connection(int idx) throws SQLException {
        if (pool[idx] == null || pool[idx].isClosed()) {
            Properties props = new Properties();
            props.setProperty("duckdb.read_only", "false");
            pool[idx] = DriverManager.getConnection("jdbc:duckdb:" + dbPath, props);
            // WAL and memory mapping are DuckDB's defaults; no extra pragmas needed
        }
        return pool[idx];
    }

    private void initSchema() throws Exception {
        poolLocks[0].lock();
        try {
            Connection conn = connection(0);
            try (Statement st = conn.createStatement()) {
                st.execute(
                    "CREATE TABLE IF NOT EXISTS response_cache (" +
                    "  cache_key    VARCHAR PRIMARY KEY," +
                    "  group_id     VARCHAR," +
                    "  cached_at    BIGINT  NOT NULL," +
                    "  expires_at   BIGINT  NOT NULL," +
                    "  status_code  INTEGER NOT NULL," +
                    "  content_type VARCHAR," +
                    "  headers_json VARCHAR," +
                    "  body         BLOB" +
                    ")"
                );
                st.execute("CREATE INDEX IF NOT EXISTS rc_group_idx   ON response_cache (group_id)");
                st.execute("CREATE INDEX IF NOT EXISTS rc_expires_idx ON response_cache (expires_at)");
            }
        } finally {
            poolLocks[0].unlock();
        }
    }

    // ── CacheManager API ─────────────────────────────────────────────────────

    /**
     * Lookup a cached response.
     *
     * <p><b>Fast path (L1 hit):</b> entirely in-process, no JDBC, no lock contention.
     * Returns a freshly constructed {@link FullHttpResponse} from the stored byte[],
     * so Netty can release it without affecting concurrent readers.
     *
     * <p><b>Slow path (L2 / DuckDB):</b> executed synchronously when L1 misses.
     * On an L2 hit the entry is promoted back to L1.
     */
    @Override
    public CachedResponse lookup(TargetSystem info, String key) {
        // ── L1 fast path ──────────────────────────────────────────────────
        L1Entry l1e = l1.get(key);
        if (l1e != null) {
            if (l1e.isAlive()) {
                l1e.lastAccess = System.nanoTime();         // update LRU timestamp
                LOG.debug("L1 HIT  key={}", key);
                return toCachedResponse(key, l1e);
            } else {
                l1.remove(key);                             // expired — evict from L1
            }
        }

        // ── L2 DuckDB path ────────────────────────────────────────────────
        int idx = slot();
        poolLocks[idx].lock();
        try {
            Connection conn = connection(idx);
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status_code, content_type, headers_json, body, expires_at" +
                    "  FROM response_cache" +
                    " WHERE cache_key = ? AND expires_at > ?")) {
                ps.setString(1, key);
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        L1Entry promoted = new L1Entry(
                            rs.getBytes("body"),
                            rs.getInt("status_code"),
                            rs.getString("content_type"),
                            rs.getString("headers_json"),
                            rs.getLong("expires_at")
                        );
                        l1Put(key, promoted);              // promote to L1
                        LOG.debug("L2 HIT  key={} (promoted to L1)", key);
                        return toCachedResponse(key, promoted);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("DuckDB lookup error key={}: {}", key, e.getMessage(), e);
        } finally {
            poolLocks[idx].unlock();
        }

        // Total miss
        LOG.debug("MISS    key={}", key);
        CachedResponse empty = new CachedResponse();
        empty.setKey(key);
        return empty;
    }

    @Override
    public void save(TargetSystem info, String key, Object msg) {
        saveFresh(info, key, msg, DEFAULT_TTL_SECONDS);
    }

    @Override
    public void saveFresh(TargetSystem info, String key, Object msg) {
        saveFresh(info, key, msg, DEFAULT_TTL_SECONDS);
    }

    /**
     * Store a response in both tiers.
     *
     * <p>The Netty {@link ByteBuf} is snapshotted (copied to a plain Java byte[])
     * <em>before</em> this method returns — the I/O thread is never blocked on disk I/O.
     * The DuckDB INSERT is submitted to a virtual thread and runs asynchronously.
     */
    @Override
    public void saveFresh(TargetSystem info, String key, Object msg, int ttlSeconds) {
        if (!(msg instanceof FullHttpResponse)) return;

        FullHttpResponse response = (FullHttpResponse) msg;
        int statusCode = response.status().code();
        if (statusCode < 200 || statusCode >= 300) return;

        int ttl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;

        // ── Snapshot body bytes on the I/O thread (fast, no blocking) ─────
        byte[] bodyBytes   = snapshotBody(response.content());
        String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE, "");
        String headersJson = serialiseHeaders(response.headers());
        long   now         = System.currentTimeMillis();
        long   expiresAt   = now + (long) ttl * 1000L;
        String groupId     = info != null ? info.getGroupId() : null;

        // ── Install in L1 immediately (I/O thread, zero blocking) ─────────
        l1Put(key, new L1Entry(bodyBytes, statusCode, contentType, headersJson, expiresAt));
        LOG.debug("L1 STORE key={} ttl={}s size={}b", key, ttl, bodyBytes.length);

        // ── Async L2 write — virtual thread, never blocks I/O ─────────────
        VirtualThreads.submit("duckdb-cache-write", () ->
            writeToL2(key, groupId, now, expiresAt, statusCode, contentType, headersJson, bodyBytes)
        );
    }

    // ── Maintenance ──────────────────────────────────────────────────────────

    /**
     * Delete expired rows from DuckDB and evict stale L1 entries.
     * Called by {@link DuckDBCacheEvictor} every 5 minutes.
     */
    public int evictExpired() {
        // L1 sweep — remove expired entries
        long now = System.currentTimeMillis();
        l1.entrySet().removeIf(e -> !e.getValue().isAlive());

        // L2 sweep
        int idx = 0;
        poolLocks[idx].lock();
        try {
            Connection conn = connection(idx);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM response_cache WHERE expires_at <= ?")) {
                ps.setLong(1, now);
                int deleted = ps.executeUpdate();
                if (deleted > 0) LOG.info("DuckDB evicted {} expired entries", deleted);
                return deleted;
            }
        } catch (Exception e) {
            LOG.error("DuckDB eviction error: {}", e.getMessage(), e);
            return 0;
        } finally {
            poolLocks[idx].unlock();
        }
    }

    /** Basic cache statistics for the {@code /stats} endpoint. */
    public String stats() {
        long now = System.currentTimeMillis();
        long l1Live = l1.values().stream().filter(L1Entry::isAlive).count();

        int idx = 0;
        poolLocks[idx].lock();
        try {
            Connection conn = connection(idx);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) AS total," +
                    "       SUM(CASE WHEN expires_at > ? THEN 1 ELSE 0 END) AS live," +
                    "       COALESCE(SUM(LENGTH(body)),0) AS total_bytes" +
                    "  FROM response_cache")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return String.format(
                            "DuckDB cache: l1_live=%d l2_total=%d l2_live=%d l2_size_bytes=%d db=%s",
                            l1Live,
                            rs.getLong("total"),
                            rs.getLong("live"),
                            rs.getLong("total_bytes"),
                            dbPath);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("DuckDB stats error: {}", e.getMessage(), e);
        } finally {
            poolLocks[idx].unlock();
        }
        return "DuckDB cache: stats unavailable";
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Insert or update a row in DuckDB (L2).
     * Always runs on a virtual thread — never on the Netty I/O thread.
     */
    private void writeToL2(String key, String groupId,
                           long now, long expiresAt,
                           int statusCode, String contentType,
                           String headersJson, byte[] body) {
        int idx = slot();
        poolLocks[idx].lock();
        try {
            Connection conn = connection(idx);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO response_cache" +
                    "  (cache_key, group_id, cached_at, expires_at, status_code," +
                    "   content_type, headers_json, body)" +
                    "  VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, groupId);
                ps.setLong  (3, now);
                ps.setLong  (4, expiresAt);
                ps.setInt   (5, statusCode);
                ps.setString(6, contentType);
                ps.setString(7, headersJson);
                ps.setBytes (8, body);
                ps.executeUpdate();
                LOG.debug("L2 STORE key={} size={}b", key, body.length);
            }
        } catch (Exception e) {
            LOG.error("DuckDB write error key={}: {}", key, e.getMessage(), e);
        } finally {
            poolLocks[idx].unlock();
        }
    }

    /**
     * Add an entry to L1, enforcing the size limit by evicting the
     * least-recently-accessed entry when the map is full.
     */
    private void l1Put(String key, L1Entry entry) {
        // Fast path: key already present or map has room
        if (l1.size() < l1MaxEntries || l1.containsKey(key)) {
            l1.put(key, entry);
            l1WriteCount.incrementAndGet();
            return;
        }
        // Evict the LRU entry — find entry with smallest lastAccess
        String evictKey = null;
        long   oldest   = Long.MAX_VALUE;
        for (Map.Entry<String, L1Entry> me : l1.entrySet()) {
            if (me.getValue().lastAccess < oldest) {
                oldest   = me.getValue().lastAccess;
                evictKey = me.getKey();
            }
        }
        if (evictKey != null) {
            l1.remove(evictKey);
            LOG.debug("L1 evict LRU key={}", evictKey);
        }
        l1.put(key, entry);
        l1WriteCount.incrementAndGet();
    }

    /**
     * Build a {@link CachedResponse} that wraps a freshly allocated
     * {@link FullHttpResponse} reconstructed from an {@link L1Entry}.
     *
     * <p>A new {@link ByteBuf} is created for every call so each
     * response can be independently released by Netty without affecting
     * other concurrent readers of the same L1 entry.
     */
    private static CachedResponse toCachedResponse(String key, L1Entry e) {
        // Allocate a new heap buffer from the stored bytes — safe for concurrent use
        ByteBuf buf = e.body != null && e.body.length > 0
                ? Unpooled.copiedBuffer(e.body)
                : Unpooled.EMPTY_BUFFER;

        HttpResponseStatus status   = HttpResponseStatus.valueOf(e.statusCode);
        FullHttpResponse   response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);

        if (e.contentType != null && !e.contentType.isEmpty()) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, e.contentType);
        }
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        response.headers().set("X-Cache", "HIT");

        List<Object> list = new ArrayList<>(1);
        list.add(response);

        CachedResponse cr = new CachedResponse();
        cr.setKey(key);
        cr.setResponse(list);
        return cr;
    }

    /**
     * Snapshot the readable bytes of a {@link ByteBuf} into a plain {@code byte[]}.
     * Does NOT advance the reader index — the ByteBuf can still be forwarded
     * to the inbound channel after this call.
     */
    private static byte[] snapshotBody(ByteBuf buf) {
        if (buf == null || !buf.isReadable()) return new byte[0];
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);   // getBytes — does NOT advance readerIndex
        return bytes;
    }

    /**
     * Serialise response headers to a minimal JSON string for storage.
     * Format: {@code {"Header-Name":"value","Another":"value2"}}
     */
    private static String serialiseHeaders(HttpHeaders headers) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> h : headers) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson(h.getKey())).append("\":\"")
              .append(escapeJson(h.getValue())).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
