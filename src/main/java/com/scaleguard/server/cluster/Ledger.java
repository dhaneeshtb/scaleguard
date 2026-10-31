package com.scaleguard.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.licencing.licensing.LicenceUtil;
import com.scaleguard.utils.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * The cluster's replicated, tamper-evident change log, stored in DuckDB ({@code ~/.scaleguard/ledger.duckdb}).
 *
 * <p>Every configuration write on a node (a route, a certificate, a rate-limit setting, an OAuth client or token…)
 * appends one entry: the table, the row key, the operation (upsert or delete) and the full row as JSON. Entries are
 * numbered per <i>origin</i> (one origin per ledger file, so a node that loses its file starts a new chain instead of
 * forking its old one) and each carries the SHA-256 of the previous entry of its origin:
 * <pre>hash = SHA-256(prev_hash ‖ origin ‖ seq ‖ hlc ‖ table ‖ key ‖ op ‖ scopes ‖ row)</pre>
 * so an edited, dropped or reordered entry breaks the chain. Nodes copy each other's entries (see {@link PeerMesh}),
 * verify each chain link before storing it, and compare chain heads to detect divergence.
 *
 * <p>Applying: entries are applied to the local SQLite/Postgres store with last-writer-wins per row, ordered by a
 * hybrid logical clock (wall-clock micros, never going backwards, bumped past every timestamp seen) with the origin as
 * tie-break, so every node converges to the same rows whatever order entries arrive in.
 */
public final class Ledger {

    private static final Logger LOG = LoggerFactory.getLogger(Ledger.class);
    static final String GENESIS = "0".repeat(64);
    private static final Ledger INSTANCE = new Ledger();

    public static Ledger get() {
        return INSTANCE;
    }

    /** One origin's chain head. */
    public record Head(String node, long seq, String hash) {}

    /** Latest applied version of a row: who wrote it and when (hybrid logical clock). */
    record RowState(long hlc, String origin, String node) {
        boolean olderThan(long otherHlc, String otherOrigin) {
            return otherHlc > hlc || (otherHlc == hlc && otherOrigin.compareTo(origin) > 0);
        }
    }

    private Connection db;
    private String path;
    private String origin;
    private final String node = NodeIdentity.name();
    private String genesis;                      // "baseline" | "joined" | null (undecided)
    private final Map<String, Head> heads = new ConcurrentHashMap<>();
    private final Map<String, RowState> state = new ConcurrentHashMap<>();
    private long clock;                          // hybrid logical clock, micros
    private volatile boolean open;
    private final LongAdder appended = new LongAdder();
    private final LongAdder imported = new LongAdder();
    private final LongAdder applied = new LongAdder();
    private final LongAdder superseded = new LongAdder();
    private final Map<String, String> rejected = new ConcurrentHashMap<>();   // origin -> last chain error
    private volatile long lastImportAt;

    private Ledger() {}

    // ------------------------------------------------------------------ lifecycle

    public synchronized void open() throws Exception {
        if (open) return;
        path = System.getProperty("scaleguard.ledger.path", LicenceUtil.dataDir() + "/ledger.duckdb");
        Class.forName("org.duckdb.DuckDBDriver");
        db = DriverManager.getConnection("jdbc:duckdb:" + path);
        try (Statement st = db.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ledger_meta (k VARCHAR PRIMARY KEY, v VARCHAR)");
            st.execute("CREATE TABLE IF NOT EXISTS ledger (origin VARCHAR NOT NULL, seq BIGINT NOT NULL, hlc BIGINT NOT NULL,"
                    + " node VARCHAR, tbl VARCHAR NOT NULL, rkey VARCHAR NOT NULL, op VARCHAR NOT NULL, scopes VARCHAR,"
                    + " row_json VARCHAR, prev_hash VARCHAR NOT NULL, hash VARCHAR NOT NULL, received BIGINT,"
                    + " PRIMARY KEY (origin, seq))");
        }
        origin = meta("origin");
        if (origin == null) {
            origin = node + "#" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xffffffffffL);
            setMeta("origin", origin);
        }
        genesis = meta("genesis");
        // chain heads, and the newest version of every row (for last-writer-wins)
        try (Statement st = db.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT origin, arg_max(node, seq), max(seq), arg_max(hash, seq) FROM ledger GROUP BY origin")) {
                while (rs.next()) heads.put(rs.getString(1), new Head(rs.getString(2), rs.getLong(3), rs.getString(4)));
            }
            try (ResultSet rs = st.executeQuery("SELECT tbl, rkey, arg_max(hlc, (hlc, origin)), arg_max(origin, (hlc, origin)),"
                    + " arg_max(node, (hlc, origin)) FROM ledger GROUP BY tbl, rkey")) {
                while (rs.next()) {
                    long h = rs.getLong(3);
                    state.put(rs.getString(1) + '\0' + rs.getString(2), new RowState(h, rs.getString(4), rs.getString(5)));
                    clock = Math.max(clock, h);
                }
            }
        }
        open = true;
        LOG.info("Ledger: {} ({} origin(s), {} entries, this origin {})", path, heads.size(), totalEntries(), origin);
    }

    public boolean isOpen() {
        return open;
    }

    public String origin() {
        return origin;
    }

    String genesis() {
        return genesis;
    }

    /** Election state, persisted so a restarted node never votes twice in one term. */
    synchronized long term() {
        try {
            String t = open ? meta("term") : null;
            return t == null ? 0 : Long.parseLong(t);
        } catch (Exception e) {
            return 0;
        }
    }

    synchronized String votedFor() {
        try {
            return open ? meta("votedFor") : null;
        } catch (Exception e) {
            return null;
        }
    }

    synchronized void saveVote(long term, String votedFor) {
        if (!open) return;
        try {
            setMeta("term", Long.toString(term));
            setMeta("votedFor", votedFor == null ? "" : votedFor);
        } catch (Exception e) {
            LOG.warn("Ledger: could not store election state: {}", e.toString());
        }
    }

    synchronized String metaValue(String k) {
        try {
            return open ? meta(k) : null;
        } catch (Exception e) {
            return null;
        }
    }

    synchronized void setMetaValue(String k, String v) {
        try {
            if (open) setMeta(k, v);
        } catch (Exception e) {
            LOG.warn("Ledger: could not store {}: {}", k, e.toString());
        }
    }

    synchronized void setGenesis(String g) {
        genesis = g;
        try {
            setMeta("genesis", g);
        } catch (Exception e) {
            LOG.warn("Ledger: could not store genesis: {}", e.toString());
        }
    }

    /** True when this ledger holds no entries at all. */
    public boolean isEmpty() {
        return heads.isEmpty();
    }

    public long totalEntries() {
        long n = 0;
        for (Head h : heads.values()) n += h.seq();
        return n;
    }

    public Map<String, Head> heads() {
        return new TreeMap<>(heads);
    }

    // ------------------------------------------------------------------ local writes

    private long tick() {
        long now = System.currentTimeMillis() * 1000;
        clock = Math.max(clock + 1, now);
        return clock;
    }

    /**
     * Appends this node's own change. Callers hold the ledger lock between reading the row and appending, so two racing
     * writes to one row are logged in the order their reads saw them.
     */
    synchronized void appendLocal(String tbl, String rkey, String op, String scopes, String rowJson) throws Exception {
        if (!open) return;
        Head h = heads.get(origin);
        long seq = h == null ? 1 : h.seq() + 1;
        String prev = h == null ? GENESIS : h.hash();
        long hlc = tick();
        String hash = hash(prev, origin, seq, hlc, tbl, rkey, op, scopes, rowJson);
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO ledger VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, origin);
            ps.setLong(2, seq);
            ps.setLong(3, hlc);
            ps.setString(4, node);
            ps.setString(5, tbl);
            ps.setString(6, rkey);
            ps.setString(7, op);
            ps.setString(8, scopes);
            ps.setString(9, rowJson);
            ps.setString(10, prev);
            ps.setString(11, hash);
            ps.setLong(12, System.currentTimeMillis());
            ps.executeUpdate();
        }
        heads.put(origin, new Head(node, seq, hash));
        state.put(tbl + '\0' + rkey, new RowState(hlc, origin, node));
        appended.increment();
    }

    /** Runs {@code r} under the ledger lock (capture = read the row + append, atomically with respect to other captures). */
    synchronized void locked(LedgerTables.Action r) throws Exception {
        r.run();
    }

    RowState stateOf(String tbl, String rkey) {
        return state.get(tbl + '\0' + rkey);
    }

    // ------------------------------------------------------------------ replication

    /** Entries of one origin after {@code afterSeq}, oldest first, as JSON for a peer. */
    public synchronized ArrayNode read(String fromOrigin, long afterSeq, int limit) throws Exception {
        ArrayNode out = JSON.array();
        if (!open) return out;
        try (PreparedStatement ps = db.prepareStatement("SELECT origin, seq, hlc, node, tbl, rkey, op, scopes, row_json, prev_hash, hash"
                + " FROM ledger WHERE origin = ? AND seq > ? ORDER BY seq LIMIT ?")) {
            ps.setString(1, fromOrigin);
            ps.setLong(2, afterSeq);
            ps.setInt(3, Math.max(1, Math.min(limit, 5000)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode e = out.addObject();
                    e.put("origin", rs.getString(1));
                    e.put("seq", rs.getLong(2));
                    e.put("hlc", rs.getLong(3));
                    e.put("node", rs.getString(4));
                    e.put("tbl", rs.getString(5));
                    e.put("key", rs.getString(6));
                    e.put("op", rs.getString(7));
                    e.put("scopes", rs.getString(8));
                    e.put("row", rs.getString(9));
                    e.put("prev", rs.getString(10));
                    e.put("hash", rs.getString(11));
                }
            }
        }
        return out;
    }

    /** Hash of this node's copy of entry (origin, seq), or null if it doesn't have it. */
    public synchronized String hashAt(String o, long seq) {
        if (!open) return null;
        Head h = heads.get(o);
        if (h != null && h.seq() == seq) return h.hash();
        try (PreparedStatement ps = db.prepareStatement("SELECT hash FROM ledger WHERE origin = ? AND seq = ?")) {
            ps.setString(1, o);
            ps.setLong(2, seq);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Result of importing a page of a peer's entries. */
    record ImportResult(int stored, Set<String> scopes, Set<String> fromNodes, String error) {}

    /**
     * Verifies and stores entries a peer sent (one origin, ascending seq), applying each to the local store unless a
     * newer version of that row is already there. Stops at the first entry that doesn't extend the chain.
     */
    synchronized ImportResult importEntries(String peer, JsonNode entries) {
        Set<String> scopes = new LinkedHashSet<>();
        Set<String> from = new LinkedHashSet<>();
        int stored = 0;
        String error = null;
        if (!open || entries == null) return new ImportResult(0, scopes, from, null);
        try {
            for (JsonNode e : entries) {
                String o = e.path("origin").asText();
                long seq = e.path("seq").asLong();
                Head h = heads.get(o);
                long have = h == null ? 0 : h.seq();
                if (seq <= have) continue;                         // already have it
                String prev = h == null ? GENESIS : h.hash();
                if (o.equals(origin)) {
                    error = "peer " + peer + " sent entries for this node's own chain beyond its head (seq " + seq + ")";
                    break;
                }
                if (seq != have + 1) {
                    error = "gap in " + o + ": expected seq " + (have + 1) + ", got " + seq;
                    break;
                }
                long hlc = e.path("hlc").asLong();
                String n = e.path("node").asText(null);
                String tbl = e.path("tbl").asText();
                String key = e.path("key").asText();
                String op = e.path("op").asText();
                String sc = e.path("scopes").isNull() ? null : e.path("scopes").asText(null);
                String row = e.path("row").isNull() ? null : e.path("row").asText(null);
                String claimedPrev = e.path("prev").asText();
                String claimed = e.path("hash").asText();
                if (!prev.equals(claimedPrev)) {
                    error = "chain break in " + o + " at seq " + seq + ": previous hash doesn't match this node's copy";
                    break;
                }
                String actual = hash(prev, o, seq, hlc, tbl, key, op, sc, row);
                if (!actual.equals(claimed)) {
                    error = "tampered entry in " + o + " at seq " + seq + ": content doesn't match its hash";
                    break;
                }
                // apply to the local store first: a crash before the ledger insert just re-applies it on the next pull
                RowState cur = state.get(tbl + '\0' + key);
                if (cur == null || cur.olderThan(hlc, o)) {
                    LedgerTables.applyRemote(tbl, key, op, row, cur, n);
                    state.put(tbl + '\0' + key, new RowState(hlc, o, n));
                    if (sc != null && !sc.isBlank()) for (String s : sc.split(",")) scopes.add(s);
                    applied.increment();
                } else {
                    LedgerTables.conflict(tbl, key, op, row, cur, n);
                    superseded.increment();
                }
                try (PreparedStatement ps = db.prepareStatement("INSERT INTO ledger VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, o);
                    ps.setLong(2, seq);
                    ps.setLong(3, hlc);
                    ps.setString(4, n);
                    ps.setString(5, tbl);
                    ps.setString(6, key);
                    ps.setString(7, op);
                    ps.setString(8, sc);
                    ps.setString(9, row);
                    ps.setString(10, prev);
                    ps.setString(11, actual);
                    ps.setLong(12, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                heads.put(o, new Head(n, seq, actual));
                clock = Math.max(clock, hlc);
                if (n != null) from.add(n);
                stored++;
            }
        } catch (Exception ex) {
            error = "import from " + peer + " failed: " + ex.getMessage();
        }
        if (stored > 0) {
            imported.add(stored);
            lastImportAt = System.currentTimeMillis();
        }
        if (error != null) {
            String o = entries.size() > 0 ? entries.get(0).path("origin").asText() : "?";
            rejected.put(o, error + " (from " + peer + ")");
        }
        return new ImportResult(stored, scopes, from, error);
    }

    void clearRejection(String o) {
        rejected.remove(o);
    }

    // ------------------------------------------------------------------ verification

    /** Re-hashes every chain from its genesis entry. */
    public synchronized Map<String, Object> verify() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> chains = new ArrayList<>();
        boolean allOk = true;
        long t0 = System.nanoTime();
        if (open) {
            try (Statement st = db.createStatement();
                 ResultSet rs = st.executeQuery("SELECT origin, seq, hlc, tbl, rkey, op, scopes, row_json, prev_hash, hash, node"
                         + " FROM ledger ORDER BY origin, seq")) {
                String cur = null, prev = GENESIS, problem = null, n = null;
                long count = 0, expect = 1;
                while (rs.next()) {
                    String o = rs.getString(1);
                    if (!o.equals(cur)) {
                        if (cur != null) {
                            chains.add(chain(cur, n, count, prev, problem));
                            allOk &= problem == null;
                        }
                        cur = o;
                        prev = GENESIS;
                        problem = null;
                        count = 0;
                        expect = 1;
                    }
                    n = rs.getString(11);
                    count++;
                    if (problem != null) continue;
                    long seq = rs.getLong(2);
                    String h = hash(prev, o, seq, rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8));
                    if (seq != expect) problem = "missing entry " + expect;
                    else if (!prev.equals(rs.getString(9))) problem = "entry " + seq + " doesn't link to entry " + (seq - 1);
                    else if (!h.equals(rs.getString(10))) problem = "entry " + seq + " was modified";
                    prev = rs.getString(10);
                    expect = seq + 1;
                }
                if (cur != null) {
                    chains.add(chain(cur, n, count, prev, problem));
                    allOk &= problem == null;
                }
            } catch (Exception e) {
                out.put("error", e.getMessage());
                allOk = false;
            }
        }
        out.put("ok", allOk);
        out.put("chains", chains);
        out.put("tookMs", (System.nanoTime() - t0) / 1_000_000);
        return out;
    }

    private static Map<String, Object> chain(String o, String node, long count, String head, String problem) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("origin", o);
        m.put("node", node);
        m.put("entries", count);
        m.put("head", head);
        m.put("ok", problem == null);
        if (problem != null) m.put("problem", problem);
        return m;
    }

    // ------------------------------------------------------------------ status

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", open);
        m.put("path", path);
        m.put("origin", origin);
        m.put("genesis", genesis);
        m.put("entries", totalEntries());
        m.put("rows", state.size());
        m.put("appended", appended.sum());
        m.put("imported", imported.sum());
        m.put("applied", applied.sum());
        m.put("superseded", superseded.sum());
        if (lastImportAt > 0) m.put("lastImportAt", lastImportAt);
        List<Map<String, Object>> hs = new ArrayList<>();
        for (Map.Entry<String, Head> e : heads().entrySet()) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("origin", e.getKey());
            h.put("node", e.getValue().node());
            h.put("seq", e.getValue().seq());
            h.put("hash", e.getValue().hash());
            h.put("self", e.getKey().equals(origin));
            hs.add(h);
        }
        m.put("chains", hs);
        if (!rejected.isEmpty()) m.put("rejected", new TreeMap<>(rejected));
        return m;
    }

    // ------------------------------------------------------------------ helpers

    static String hash(String prev, String o, long seq, long hlc, String tbl, String key, String op, String scopes, String row) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{prev, o, Long.toString(seq), Long.toString(hlc), tbl, key, op,
                    scopes == null ? "" : scopes, row == null ? "" : row}) {
                md.update(part.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String meta(String k) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("SELECT v FROM ledger_meta WHERE k = ?")) {
            ps.setString(1, k);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void setMeta(String k, String v) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("INSERT OR REPLACE INTO ledger_meta VALUES (?, ?)")) {
            ps.setString(1, k);
            ps.setString(2, v);
            ps.executeUpdate();
        }
    }

    /** Test hook: corrupt one stored entry's row (to prove verify() and peers notice). Only with -Dscaleguard.ledger.testHooks=true. */
    synchronized boolean tamper(String o, long seq, String newRow) throws Exception {
        if (!Boolean.getBoolean("scaleguard.ledger.testHooks")) return false;
        try (PreparedStatement ps = db.prepareStatement("UPDATE ledger SET row_json = ? WHERE origin = ? AND seq = ?")) {
            ps.setString(1, newRow);
            ps.setString(2, o);
            ps.setLong(3, seq);
            return ps.executeUpdate() == 1;
        }
    }
}
