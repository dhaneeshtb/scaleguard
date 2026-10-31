package com.scaleguard.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.db.ConnectionUtil;
import com.scaleguard.utils.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Which local tables the {@link Ledger} replicates, and how a row is read from / written to the local store.
 *
 * <p>Capture: after a write commits, the writer calls {@link #capture} with the keys it touched; the current row is
 * read back (or found missing, i.e. deleted) and appended to the ledger. Logging the resulting row rather than the SQL
 * makes replay idempotent and independent of how the write was expressed. Apply: a remote entry replaces the row by
 * key (delete + insert in one transaction), using only columns the local table has, so mixed versions still sync.
 */
public final class LedgerTables {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerTables.class);
    private static final char SEP = '\u001f';

    private LedgerTables() {}

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    /**
     * A replicated table.
     *
     * @param keys    primary-key columns
     * @param skip    columns never copied (local auto-increment ids)
     * @param order   ORDER BY used when several local rows share a key (newest first)
     * @param scopes  cluster reload scopes for a set of keys
     * @param ensure  creates the table locally if needed
     */
    public record Spec(String table, List<String> keys, Set<String> skip, String order,
                       Function<Collection<String>, Collection<String>> scopes, Runnable ensure) {}

    private static final Map<String, Spec> SPECS = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> COLUMNS = new ConcurrentHashMap<>();
    private static volatile Runnable registerBuiltIns = () -> {};

    public static void register(Spec s) {
        SPECS.put(s.table(), s);
    }

    /** Registers a table keyed by {@code id} (every {@code DBManager} table). */
    public static void registerById(String table, Function<Collection<String>, Collection<String>> scopes, Runnable ensure) {
        register(new Spec(table, List.of("id"), Set.of(), null, scopes, ensure));
    }

    public static void setBuiltIns(Runnable r) {
        registerBuiltIns = r;
    }

    static Collection<Spec> specs() {
        registerBuiltIns.run();
        return SPECS.values();
    }

    static boolean active() {
        return ClusterManager.ledgerMode() && Ledger.get().isOpen();
    }

    // ------------------------------------------------------------------ capture (local writes)

    /** Logs the current state of the given rows (single-column key). Never throws: replication is best-effort per write. */
    public static void capture(String table, Collection<String> ids) {
        if (!active() || ids == null || ids.isEmpty()) return;
        List<List<String>> keys = new ArrayList<>();
        for (String id : ids) keys.add(List.of(id));
        captureKeys(table, keys);
    }

    /** Logs the current state of rows with a composite key (values in {@link Spec#keys()} order). */
    public static void captureKeys(String table, List<List<String>> keys) {
        if (!active() || keys == null || keys.isEmpty()) return;
        Spec s = SPECS.get(table);
        if (s == null) return;
        try {
            Ledger.get().locked(() -> {
                try (Connection c = ConnectionUtil.getConnection()) {
                    String where = String.join(" AND ", s.keys().stream().map(k -> k + " = ?").toList());
                    String sql = "SELECT * FROM " + table + " WHERE " + where + (s.order() == null ? "" : " ORDER BY " + s.order());
                    for (List<String> key : new LinkedHashSet<>(keys)) {
                        String rkey = String.join(String.valueOf(SEP), key);
                        String row = null;
                        try (PreparedStatement ps = c.prepareStatement(sql)) {
                            for (int i = 0; i < key.size(); i++) ps.setString(i + 1, key.get(i));
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) row = rowJson(rs, s);
                            }
                        }
                        String scopes = String.join(",", s.scopes().apply(List.of(key.get(0))));
                        Ledger.get().appendLocal(table, rkey, row == null ? "delete" : "upsert", scopes, row);
                    }
                }
            });
            PeerMesh.get().poke();
        } catch (Exception e) {
            LOG.warn("Ledger: could not record change to {} {}: {}", table, keys, e.toString());
        }
    }

    /** First node of a new cluster: logs every existing row so nodes that join later receive this node's configuration. */
    static int baseline() {
        int n = 0;
        for (Spec s : specs()) {
            try {
                if (s.ensure() != null) s.ensure().run();
                List<List<String>> keys = new ArrayList<>();
                try (Connection c = ConnectionUtil.getConnection(); Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT DISTINCT " + String.join(", ", s.keys()) + " FROM " + s.table())) {
                    while (rs.next()) {
                        List<String> k = new ArrayList<>();
                        for (int i = 1; i <= s.keys().size(); i++) k.add(rs.getString(i));
                        if (!k.contains(null)) keys.add(k);
                    }
                }
                captureKeys(s.table(), keys);
                n += keys.size();
            } catch (Exception e) {
                LOG.warn("Ledger: baseline of {} failed: {}", s.table(), e.toString());
            }
        }
        return n;
    }

    private static String rowJson(ResultSet rs, Spec s) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        ObjectNode o = JSON.object();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String col = md.getColumnName(i).toLowerCase(Locale.ROOT);
            if (s.skip().contains(col)) continue;
            Object v = rs.getObject(i);
            if (v == null) o.putNull(col);
            else if (v instanceof Integer || v instanceof Long || v instanceof Short) o.put(col, ((Number) v).longValue());
            else if (v instanceof Number) o.put(col, ((Number) v).doubleValue());
            else if (v instanceof Boolean) o.put(col, (Boolean) v);
            else o.put(col, String.valueOf(v));
        }
        return JSON.toString(o);
    }

    // ------------------------------------------------------------------ apply (remote entries)

    static void applyRemote(String table, String rkey, String op, String row, Ledger.RowState previous, String fromNode) throws Exception {
        Spec s = SPECS.get(table);
        if (s == null) {
            specs();                              // built-ins not registered yet on this node
            s = SPECS.get(table);
            if (s == null) throw new IllegalStateException("unknown replicated table " + table);
        }
        conflict(table, rkey, op, row, previous, fromNode);
        if (s.ensure() != null) s.ensure().run();
        List<String> key = List.of(rkey.split(String.valueOf(SEP), -1));
        try (Connection c = ConnectionUtil.getConnection()) {
            Set<String> cols = columns(c, table);
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                String where = String.join(" AND ", s.keys().stream().map(k -> k + " = ?").toList());
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE " + where)) {
                    for (int i = 0; i < key.size(); i++) ps.setString(i + 1, key.get(i));
                    ps.executeUpdate();
                }
                if ("upsert".equals(op) && row != null) {
                    JsonNode r = JSON.parse(row);
                    List<String> names = new ArrayList<>();
                    List<Object> values = new ArrayList<>();
                    for (Iterator<Map.Entry<String, JsonNode>> it = r.fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> f = it.next();
                        String col = f.getKey().toLowerCase(Locale.ROOT);
                        if (!cols.contains(col) || s.skip().contains(col)) continue;
                        names.add(col);
                        JsonNode v = f.getValue();
                        values.add(v.isNull() ? null : v.isIntegralNumber() ? (Object) v.asLong()
                                : v.isNumber() ? (Object) v.asDouble() : v.isBoolean() ? (Object) v.asBoolean() : v.asText());
                    }
                    String sql = "INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
                            + String.join(", ", names.stream().map(x -> "?").toList()) + ")";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (int i = 0; i < values.size(); i++) ps.setObject(i + 1, values.get(i));
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    /**
     * Rows two nodes changed concurrently that last-writer-wins can't settle safely. Today: a refresh token redeemed on
     * two nodes before they synced (a replay of a stolen token) — the token's whole chain is revoked on both.
     */
    static void conflict(String table, String rkey, String op, String row, Ledger.RowState local, String fromNode) {
        if (!"oauth_refresh_tokens".equals(table) || row == null || local == null) return;
        String self = NodeIdentity.name();
        if (!self.equals(local.node()) || self.equals(fromNode)) return;
        try {
            JsonNode r = JSON.parse(row);
            // a redemption (used, not revoked) arriving for a token this node itself redeemed: two nodes redeemed it
            if (r.path("used").asLong() != 1 || r.path("revoked").asLong() != 0) return;
            try (Connection c = ConnectionUtil.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT used, family, revoked FROM oauth_refresh_tokens WHERE id = ?")) {
                ps.setString(1, rkey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 1 && rs.getInt(3) == 0) {
                        String family = rs.getString(2);
                        LOG.warn("Ledger: refresh token redeemed on both {} and {} — revoking its chain", self, fromNode);
                        Thread.ofVirtual().start(() -> com.scaleguard.server.security.oauth.RefreshTokens.revokeFamilyClusterWide(family));
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("refresh-token conflict check failed: {}", e.toString());
        }
    }

    private static Set<String> columns(Connection c, String table) throws Exception {
        Set<String> cols = COLUMNS.get(table);
        if (cols != null) return cols;
        Set<String> out = new LinkedHashSet<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) out.add(md.getColumnName(i).toLowerCase(Locale.ROOT));
        }
        COLUMNS.put(table, out);
        return out;
    }
}
