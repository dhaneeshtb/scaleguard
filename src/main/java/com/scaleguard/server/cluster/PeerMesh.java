package com.scaleguard.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.http.auth.AuthUtils;
import com.scaleguard.server.http.utils.AppProperties;
import com.scaleguard.utils.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Peer-to-peer cluster transport for {@link Ledger} mode: no shared database.
 *
 * <ul>
 *   <li><b>Membership</b> by gossip: every {@code heartbeatMs} (1 s) each node POSTs {@code /cluster/gossip} to every
 *       member it knows (plus the seed addresses in {@code scaleguard.cluster.peers}); the message carries its address,
 *       metrics, ledger chain heads and the members it knows, so one seed is enough to discover the whole cluster.
 *       A member not heard from within {@code nodeTimeoutMs} (5 s) is down.</li>
 *   <li><b>Leader</b>: the live member with the smallest node name — deterministic, so no election round-trips.</li>
 *   <li><b>Ledger sync</b>: when a peer's head for some chain is ahead of ours, pull the missing entries
 *       ({@code GET /cluster/ledger}) from it, verify and apply them. A local change pokes every peer at once, so
 *       changes usually reach the cluster in a few milliseconds; the heartbeat is the safety net.</li>
 *   <li><b>Rate limits</b>: per-client spend is pushed to every live peer ({@code POST /cluster/rates}).</li>
 * </ul>
 * Inter-node calls authenticate with a short-lived admin token, so every node needs the same admin credentials.
 */
public final class PeerMesh {

    private static final Logger LOG = LoggerFactory.getLogger(PeerMesh.class);
    private static final PeerMesh INSTANCE = new PeerMesh();

    public static PeerMesh get() {
        return INSTANCE;
    }

    static final long HEARTBEAT_MS = Math.max(200, Long.getLong("scaleguard.cluster.heartbeatMs", 1000));
    static final long NODE_TIMEOUT_MS = Math.max(HEARTBEAT_MS * 3, Long.getLong("scaleguard.cluster.nodeTimeoutMs", 5000));
    private static final int PAGE = Integer.getInteger("scaleguard.ledger.page", 2000);

    /** What this node knows about another member. */
    static final class Member {
        final String node;
        volatile String address;
        volatile long startedAt;
        volatile long lastHeard;            // local millis; 0 = never
        volatile Map<String, Object> info = Map.of();
        volatile Map<String, long[]> headSeqs = Map.of();
        volatile Map<String, String> headHashes = Map.of();
        volatile String error;
        volatile boolean dr;                // a disaster-recovery standby: replicates, never votes, separate rate budgets

        Member(String node, String address) {
            this.node = node;
            this.address = address;
        }

        boolean alive(long now) {
            return lastHeard > 0 && now - lastHeard < NODE_TIMEOUT_MS;
        }
    }

    private final String self = NodeIdentity.name();
    private final String selfAddress = NodeIdentity.adminUrl();
    private final long startedAt = System.currentTimeMillis();
    private final Map<String, Member> members = new ConcurrentHashMap<>();
    private final Set<String> seeds = new LinkedHashSet<>();
    /** Addresses of the other nodes that vote (this site's primary nodes); seeds by default, the DR site after promotion. */
    private final Set<String> voters = new LinkedHashSet<>();
    /** This node is a disaster-recovery standby ({@code scaleguard.cluster.dr=true}) until promoted. */
    private volatile boolean dr;
    private volatile long promotedAt;
    private final Map<String, String> seedErrors = new ConcurrentHashMap<>();
    private final Map<String, String> forks = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private final ExecutorService puller = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sg-ledger-pull");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pullQueued = new AtomicBoolean();
    private final AtomicBoolean gossipQueued = new AtomicBoolean();
    private final LongAdder gossipRounds = new LongAdder();
    private final LongAdder pulls = new LongAdder();
    private volatile String lastPullError;
    private volatile long lastPullErrorAt;
    private volatile int liveCount = 1;

    // ---- master election (Raft-style: terms, one vote per term, majority wins, leader keeps a quorum or steps down)
    enum Role { FOLLOWER, CANDIDATE, LEADER }
    private final Object election = new Object();
    private volatile Role role = Role.FOLLOWER;
    private volatile long term;
    private volatile String votedFor;
    private volatile String leaderNode;
    private volatile String leaderAddress;
    private volatile long lastLeaderContact;
    private volatile long electionDeadline;
    private final AtomicBoolean electing = new AtomicBoolean();
    private final LongAdder elections = new LongAdder();
    private volatile String lastElection;
    private volatile boolean leader;
    private volatile long leaderSince;
    private volatile boolean everClustered;
    private volatile String token;
    private volatile long tokenAt;
    private ScheduledExecutorService ticker;
    private volatile java.util.function.Supplier<Map<String, Object>> metrics = Map::of;

    private PeerMesh() {
        String p = System.getProperty("scaleguard.cluster.peers");
        if (p == null || p.isBlank()) p = AppProperties.get("scaleguard.cluster.peers");
        if (p != null) {
            for (String s : p.split("[,\\s]+")) {
                String a = normalize(s);
                if (a != null && !a.equals(selfAddress)) seeds.add(a);
            }
        }
        everClustered = !seeds.isEmpty();
        voters.addAll(seeds);
        String d = System.getProperty("scaleguard.cluster.dr");
        if (d == null || d.isBlank()) d = AppProperties.get("scaleguard.cluster.dr");
        dr = "true".equalsIgnoreCase(d == null ? "" : d.trim());
    }

    boolean isDr() {
        return dr;
    }

    static String normalize(String a) {
        if (a == null || a.isBlank()) return null;
        a = a.trim();
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://" + a;
        return a.endsWith("/") ? a.substring(0, a.length() - 1) : a;
    }

    // ------------------------------------------------------------------ state for ClusterManager

    /** True once this node has seed peers or has heard from another member. */
    boolean clustered() {
        return everClustered;
    }

    boolean isLeader() {
        return role == Role.LEADER;
    }

    /** The elected master's node name (this node's when it leads), or null while no master is elected. */
    String leaderNode() {
        if (role == Role.LEADER) return self;
        return System.currentTimeMillis() - lastLeaderContact < NODE_TIMEOUT_MS ? leaderNode : null;
    }

    String leaderAddress() {
        return role == Role.LEADER ? selfAddress : leaderAddress;
    }

    /** Votes needed to win: a majority of this node plus its configured peers ({@code scaleguard.cluster.quorum} overrides). */
    int quorum() {
        Integer q = Integer.getInteger("scaleguard.cluster.quorum");
        int n = voters.size() + 1;
        return q != null && q > 0 ? Math.min(q, n) : n / 2 + 1;
    }

    long leaderSince() {
        return leaderSince;
    }

    int liveNodes() {
        return liveCount;
    }

    Set<String> seeds() {
        return seeds;
    }

    void metrics(java.util.function.Supplier<Map<String, Object>> m) {
        metrics = m == null ? Map::of : m;
    }

    Map<String, String> livePeers() {
        Map<String, String> out = new TreeMap<>();
        long now = System.currentTimeMillis();
        for (Member m : members.values()) if (m.alive(now) && m.address != null) out.put(m.node, m.address);
        return out;
    }

    String addressOf(String node) {
        Member m = members.get(node);
        return m != null && m.alive(System.currentTimeMillis()) ? m.address : null;
    }

    // ------------------------------------------------------------------ lifecycle

    synchronized void start(Runnable tickWork, long tickMs) {
        if (ticker != null) return;
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sg-cluster");
            t.setDaemon(true);
            return t;
        });
        String promoted = Ledger.get().metaValue("drPromotedVoters");
        if (promoted != null) {                        // this node was a DR standby and has been promoted: it stays primary
            dr = false;
            voters.clear();
            for (String a : promoted.split(",")) if (!a.isBlank() && !a.equals(selfAddress)) voters.add(a);
            String at = Ledger.get().metaValue("drPromotedAt");
            promotedAt = at == null ? 0 : Long.parseLong(at);
        }
        term = Ledger.get().term();
        String v = Ledger.get().votedFor();
        votedFor = v == null || v.isEmpty() ? null : v;
        // give an existing master one round to announce itself before asking for votes
        electionDeadline = seeds.isEmpty() ? 0 : System.currentTimeMillis() + electionTimeout();
        ticker.scheduleWithFixedDelay(tickWork, 0, tickMs, TimeUnit.MILLISECONDS);
        LOG.info("Cluster (ledger): node '{}' at {}{}; seeds {}; heartbeat {} ms, node timeout {} ms",
                self, selfAddress, dr ? " as a DR standby" : "", seeds.isEmpty() ? "none (waiting for nodes to join)" : seeds,
                HEARTBEAT_MS, NODE_TIMEOUT_MS);
    }

    /** Called on the cluster thread every heartbeat. */
    void heartbeat() {
        gossipRound();
        decideGenesis();
        checkQuorum();
        prune();
    }

    /** Every cluster tick: start an election when no master has been heard from within the election timeout. */
    void electionTick() {
        if (role == Role.LEADER || dr || !Ledger.get().isOpen()) return;
        if (System.currentTimeMillis() >= electionDeadline && electing.compareAndSet(false, true)) {
            Thread.ofVirtual().name("sg-election").start(() -> {
                try {
                    runElection();
                } catch (Throwable e) {
                    LOG.warn("Cluster: election failed: {}", e.toString());
                } finally {
                    electing.set(false);
                }
            });
        }
    }

    /** A local change was logged: tell the peers now instead of at the next heartbeat. */
    public void poke() {
        if (ticker == null || !everClustered) return;
        if (gossipQueued.compareAndSet(false, true)) {
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(5);   // coalesce a burst of writes into one round
                } catch (InterruptedException ignored) {
                    return;
                }
                gossipQueued.set(false);
                gossipRound();
            });
        }
    }

    // ------------------------------------------------------------------ gossip

    ObjectNode selfMessage() {
        ObjectNode m = JSON.object();
        m.put("node", self);
        m.put("address", selfAddress);
        m.put("startedAt", startedAt);
        m.put("term", term);
        m.put("role", role.name().toLowerCase());
        m.put("dr", dr);
        if (leaderNode() != null) m.put("master", leaderNode());
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            info.putAll(metrics.get());
        } catch (Throwable ignored) {
            // best effort
        }
        Runtime rt = Runtime.getRuntime();
        info.put("version", PeerMesh.class.getPackage().getImplementationVersion());
        info.put("cpus", rt.availableProcessors());
        info.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        info.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        m.set("info", JSON.toJson(info));
        ObjectNode heads = m.putObject("heads");
        for (Map.Entry<String, Ledger.Head> e : Ledger.get().heads().entrySet()) {
            heads.putArray(e.getKey()).add(e.getValue().seq()).add(e.getValue().hash());
        }
        ObjectNode known = m.putObject("members");
        long now = System.currentTimeMillis();
        for (Member mm : members.values()) if (mm.address != null && (mm.alive(now) || mm.lastHeard == 0)) known.put(mm.node, mm.address);
        return m;
    }

    private void gossipRound() {
        Set<String> targets = new LinkedHashSet<>();
        Set<String> mapped = new LinkedHashSet<>();
        long now = System.currentTimeMillis();
        for (Member m : members.values()) {
            if (m.address == null) continue;
            mapped.add(m.address);
            // members down for a long time are tried less often
            if (m.alive(now) || m.lastHeard == 0 || now - m.lastHeard < 60_000 || (now / HEARTBEAT_MS) % 10 == 0) targets.add(m.address);
        }
        for (String s : seeds) if (!mapped.contains(s) || targets.contains(s)) targets.add(s);
        if (targets.isEmpty()) {
            recountLive();
            return;
        }
        String body = JSON.toString(selfMessage());
        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (String addr : targets) {
            HttpRequest req = request(addr, "/cluster/gossip").timeout(Duration.ofMillis(Math.max(1000, HEARTBEAT_MS * 2)))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            calls.add(http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((r, ex) -> {
                if (ex != null || r.statusCode() != 200) {
                    String err = ex != null ? rootMessage(ex) : "HTTP " + r.statusCode();
                    seedErrors.put(addr, err);
                    for (Member m : members.values()) if (addr.equals(m.address)) m.error = err;
                    return null;
                }
                seedErrors.remove(addr);
                JsonNode msg = JSON.parse(r.body());
                if (msg != null) heard(msg, addr);
                return null;
            }));
        }
        try {
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(Math.max(1500, HEARTBEAT_MS * 2), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // slow peers count as missed heartbeats
        }
        gossipRounds.increment();
        recountLive();
    }

    /** Incoming gossip from another node: learn from it and answer with what we know. */
    public ObjectNode onGossip(JsonNode msg) {
        if (msg != null) heard(msg, null);
        return selfMessage();
    }

    private void heard(JsonNode msg, String via) {
        String node = msg.path("node").asText(null);
        if (node == null || node.isBlank() || node.equals(self)) return;
        everClustered = true;
        String addr = normalize(msg.path("address").asText(via));
        Member m = members.computeIfAbsent(node, n -> new Member(n, null));
        boolean wasAlive = m.alive(System.currentTimeMillis());
        m.address = addr != null ? addr : via;
        m.startedAt = msg.path("startedAt").asLong();
        m.dr = msg.path("dr").asBoolean(false);
        m.lastHeard = System.currentTimeMillis();
        m.error = null;
        JsonNode info = msg.path("info");
        if (info.isObject()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> im = JSON.toObject(Map.class, info.toString());
            m.info = im == null ? Map.of() : im;
        }
        Map<String, long[]> seqs = new LinkedHashMap<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = msg.path("heads").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            seqs.put(e.getKey(), new long[]{e.getValue().path(0).asLong()});
            hashes.put(e.getKey(), e.getValue().path(1).asText());
        }
        m.headSeqs = seqs;
        m.headHashes = hashes;
        for (Iterator<Map.Entry<String, JsonNode>> it = msg.path("members").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equals(self)) continue;
            members.computeIfAbsent(e.getKey(), n -> new Member(n, normalize(e.getValue().asText())));
        }
        observeTerm(node, m.address, msg.path("term").asLong(0), "leader".equals(msg.path("role").asText()));
        if (!wasAlive) {
            LOG.info("Cluster: node '{}' ({}) is up", node, m.address);
            recountLive();
        }
        checkHeads(m);
    }

    /** Pull if the member is ahead on any chain; flag a fork if it holds a different entry at a seq we both have. */
    private void checkHeads(Member m) {
        Map<String, Ledger.Head> ours = Ledger.get().heads();
        boolean behind = false;
        for (Map.Entry<String, long[]> e : m.headSeqs.entrySet()) {
            Ledger.Head h = ours.get(e.getKey());
            long theirs = e.getValue()[0];
            long have = h == null ? 0 : h.seq();
            if (theirs > have) behind = true;
            else if (theirs > 0) {
                String mine = theirs == have ? h.hash() : null;
                String theirHash = m.headHashes.get(e.getKey());
                if (mine == null) {
                    // compare at their head (older than ours); only on mismatch suspicion, cheap enough at 1/s
                    mine = Ledger.get().hashAt(e.getKey(), theirs);
                }
                String k = m.node + " · " + e.getKey();
                if (mine != null && !mine.equals(theirHash)) {
                    if (forks.put(k, "node '" + m.node + "' holds a different entry " + theirs + " in chain " + e.getKey()) == null) {
                        LOG.error("Ledger: node '{}' diverges from this node in chain {} at entry {} — its copy was altered", m.node, e.getKey(), theirs);
                    }
                } else {
                    forks.remove(k);
                }
            }
        }
        if (behind) schedulePull();
    }

    // ------------------------------------------------------------------ pulling entries

    void schedulePull() {
        if (pullQueued.compareAndSet(false, true)) puller.submit(() -> {
            pullQueued.set(false);
            try {
                pullAll();
            } catch (Throwable e) {
                pullError("pull failed: " + e.getMessage());
            }
        });
    }

    private void pullAll() {
        long now = System.currentTimeMillis();
        boolean progress = true;
        for (int round = 0; progress && round < 1000; round++) {
            progress = false;
            Map<String, Ledger.Head> ours = Ledger.get().heads();
            Map<String, Long> best = new TreeMap<>();
            for (Member m : members.values()) {
                if (!m.alive(now)) continue;
                for (Map.Entry<String, long[]> e : m.headSeqs.entrySet()) best.merge(e.getKey(), e.getValue()[0], Math::max);
            }
            for (Map.Entry<String, Long> e : best.entrySet()) {
                String origin = e.getKey();
                Ledger.Head h = ours.get(origin);
                long have = h == null ? 0 : h.seq();
                if (e.getValue() <= have || origin.equals(Ledger.get().origin())) continue;
                // ask the members that have more of this chain, the chain's own node first
                List<Member> sources = new ArrayList<>();
                for (Member m : members.values()) {
                    long[] s = m.headSeqs.get(origin);
                    if (m.alive(now) && s != null && s[0] > have) sources.add(m);
                }
                sources.sort((a, b) -> Boolean.compare(origin.startsWith(b.node + "#"), origin.startsWith(a.node + "#")));
                for (Member src : sources) {
                    Ledger.ImportResult r = pullFrom(src, origin, have);
                    if (r == null) continue;               // unreachable: try the next one
                    if (r.stored() > 0) {
                        progress = true;
                        ClusterManager.get().fireRemote(r.scopes(), String.join(", ", r.fromNodes()));
                        if (r.error() == null) Ledger.get().clearRejection(origin);
                    }
                    if (r.error() != null) {
                        pullError(r.error());
                        continue;                          // that copy is bad: try another member
                    }
                    break;
                }
            }
        }
    }

    private Ledger.ImportResult pullFrom(Member src, String origin, long after) {
        String path = "/cluster/ledger?origin=" + URLEncoder.encode(origin, StandardCharsets.UTF_8) + "&after=" + after + "&limit=" + PAGE;
        try {
            HttpResponse<String> r = http.send(request(src.address, path).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                pullError("node '" + src.node + "' answered HTTP " + r.statusCode() + " to a ledger pull");
                return null;
            }
            JsonNode body = JSON.parse(r.body());
            JsonNode entries = body == null ? null : body.path("entries");
            pulls.increment();
            return Ledger.get().importEntries(src.node, entries);
        } catch (Exception e) {
            pullError("couldn't pull from '" + src.node + "': " + rootMessage(e));
            return null;
        }
    }

    private void pullError(String msg) {
        if (!msg.equals(lastPullError) || System.currentTimeMillis() - lastPullErrorAt > 60_000) LOG.warn("Ledger: {}", msg);
        lastPullError = msg;
        lastPullErrorAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ genesis, leader, housekeeping

    /**
     * Decides once whether this ledger starts the cluster's history from this node's existing configuration
     * ("baseline") or adopts the cluster's ("joined"). A node with no seeds founds a cluster; a node whose reachable
     * seeds all have empty ledgers founds one together with them; otherwise it joins and takes the cluster's rows.
     */
    private void decideGenesis() {
        Ledger l = Ledger.get();
        if (!l.isOpen() || l.genesis() != null) return;
        boolean force = Boolean.getBoolean("scaleguard.ledger.baseline");
        long now = System.currentTimeMillis();
        boolean heardAny = false, peersHaveData = false;
        for (Member m : members.values()) {
            if (!m.alive(now)) continue;
            heardAny = true;
            for (Map.Entry<String, long[]> e : m.headSeqs.entrySet()) {
                if (e.getValue()[0] > 0 && !e.getKey().equals(l.origin())) peersHaveData = true;
            }
        }
        if (!force && peersHaveData) {
            l.setGenesis("joined");
            LOG.info("Ledger: joining an existing cluster — taking its configuration");
            schedulePull();
            return;
        }
        if (force || seeds.isEmpty() || heardAny) {
            int n = LedgerTables.baseline();
            l.setGenesis("baseline");
            LOG.info("Ledger: recorded this node's {} existing configuration rows as the cluster's starting point", n);
        }
        // else: seeds configured but none reachable yet — keep waiting, don't publish possibly-stale local rows
    }

    private void recountLive() {
        long now = System.currentTimeMillis();
        int live = 1;
        for (Member m : members.values()) if (m.alive(now) && m.dr == dr) live++;   // each site has its own rate budgets
        if (live != liveCount) LOG.info("Cluster: {} live node(s)", live);
        liveCount = live;
    }

    private long electionTimeout() {
        // randomised so two followers rarely time out together (split votes)
        return HEARTBEAT_MS * 3 + java.util.concurrent.ThreadLocalRandom.current().nextLong(HEARTBEAT_MS * 3);
    }

    /** Raft's rule: a message from a newer term makes this node a follower in that term; a master's message resets the timer. */
    private void observeTerm(String node, String address, long t, boolean claimsLeader) {
        synchronized (election) {
            if (t > term) adoptTerm(t, "node '" + node + "' is in term " + t);
            if (claimsLeader && t == term) {
                if (role != Role.FOLLOWER) {
                    role = Role.FOLLOWER;
                    leader = false;
                }
                if (!node.equals(leaderNode)) LOG.info("Cluster: master is '{}' (term {})", node, t);
                leaderNode = node;
                leaderAddress = address;
                lastLeaderContact = System.currentTimeMillis();
                electionDeadline = lastLeaderContact + electionTimeout();
            }
        }
    }

    private void adoptTerm(long t, String why) {
        boolean wasLeader = role == Role.LEADER;
        term = t;
        votedFor = null;
        role = Role.FOLLOWER;
        leader = false;
        leaderNode = null;
        leaderAddress = null;
        Ledger.get().saveVote(term, null);
        if (wasLeader) LOG.warn("Cluster: stepping down as master — {}", why);
    }

    private void runElection() throws Exception {
        long myTerm;
        long entries = Ledger.get().totalEntries();
        synchronized (election) {
            if (role == Role.LEADER || System.currentTimeMillis() < electionDeadline) return;
            term++;
            myTerm = term;
            votedFor = self;
            role = Role.CANDIDATE;
            leaderNode = null;
            leaderAddress = null;
            Ledger.get().saveVote(term, self);
            electionDeadline = System.currentTimeMillis() + electionTimeout();
        }
        elections.increment();
        int q = quorum();
        Set<String> granted = new LinkedHashSet<>();
        granted.add(self);
        if (granted.size() < q) {
            Set<String> targets = new LinkedHashSet<>(voters);
            ObjectNode req = JSON.object();
            req.put("term", myTerm);
            req.put("candidate", self);
            req.put("address", selfAddress);
            req.put("entries", entries);
            String body = JSON.toString(req);
            List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
            for (String addr : targets) {
                calls.add(http.sendAsync(request(addr, "/cluster/vote").timeout(Duration.ofMillis(Math.max(800, HEARTBEAT_MS)))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString()));
            }
            for (CompletableFuture<HttpResponse<String>> c : calls) {
                try {
                    HttpResponse<String> r = c.get(Math.max(1000, HEARTBEAT_MS + 200), TimeUnit.MILLISECONDS);
                    if (r.statusCode() != 200) continue;
                    JsonNode v = JSON.parse(r.body());
                    if (v == null) continue;
                    synchronized (election) {
                        if (v.path("term").asLong() > term) {
                            adoptTerm(v.path("term").asLong(), "a voter is in a newer term");
                            lastElection = "lost term " + myTerm + ": node '" + v.path("node").asText() + "' is in a newer term";
                            return;
                        }
                    }
                    String voter = v.path("node").asText();
                    Member vm = members.get(voter);
                    if (v.path("granted").asBoolean() && !self.equals(voter) && (vm == null || !vm.dr)) granted.add(voter);
                } catch (Exception ignored) {
                    // unreachable voter: no vote
                }
            }
        }
        synchronized (election) {
            if (term != myTerm || role != Role.CANDIDATE) return;
            if (granted.size() >= q) {
                role = Role.LEADER;
                leader = true;
                leaderSince = System.currentTimeMillis();
                leaderNode = self;
                leaderAddress = selfAddress;
                lastElection = "won term " + myTerm + " with " + granted.size() + " of " + (voters.size() + 1) + " votes " + granted;
                LOG.info("Cluster: this node ('{}') is the master — {}", self, lastElection);
            } else {
                role = Role.FOLLOWER;
                lastElection = "no majority in term " + myTerm + ": " + granted.size() + " of " + q + " votes needed " + granted;
                LOG.info("Cluster: {}", lastElection);
            }
        }
        if (role == Role.LEADER) poke();            // announce now
    }

    /** RequestVote: one vote per term, only for a candidate whose ledger is at least as complete as ours. */
    public ObjectNode onVote(JsonNode req) {
        ObjectNode out = JSON.object();
        out.put("node", self);
        long t = req.path("term").asLong();
        String cand = req.path("candidate").asText("");
        long theirEntries = req.path("entries").asLong();
        if (dr) {
            out.put("term", term);
            out.put("granted", false);
            out.put("reason", "DR standby (doesn't vote)");
            return out;
        }
        synchronized (election) {
            long now = System.currentTimeMillis();
            boolean liveMaster = role == Role.LEADER
                    || (leaderNode != null && !leaderNode.equals(cand) && now - lastLeaderContact < HEARTBEAT_MS * 2);
            if (liveMaster) {
                // a node that merely lost touch must not depose a master everyone else still hears
                out.put("term", term);
                out.put("granted", false);
                out.put("reason", "master '" + leaderNode() + "' is alive");
                return out;
            }
            if (t > term) adoptTerm(t, "candidate '" + cand + "' started term " + t);
            boolean grant = t == term && (votedFor == null || votedFor.equals(cand))
                    && theirEntries >= Ledger.get().totalEntries() && !cand.isEmpty();
            if (grant) {
                votedFor = cand;
                Ledger.get().saveVote(term, cand);
                electionDeadline = now + electionTimeout();
            }
            out.put("term", term);
            out.put("granted", grant);
            if (!grant) out.put("reason", t < term ? "stale term" : votedFor != null && !votedFor.equals(cand)
                    ? "already voted for '" + votedFor + "'" : "its ledger is behind this node's");
        }
        return out;
    }

    /** A master that can't reach a majority steps down, so a partitioned old master stops accepting writes. */
    private void checkQuorum() {
        if (role != Role.LEADER) return;
        long now = System.currentTimeMillis();
        int reachable = 1;
        for (Member m : members.values()) if (m.alive(now) && !m.dr && voters.contains(m.address)) reachable++;
        if (reachable < quorum() && now - leaderSince > NODE_TIMEOUT_MS) {
            synchronized (election) {
                if (role != Role.LEADER) return;
                role = Role.FOLLOWER;
                leader = false;
                leaderNode = null;
                leaderAddress = null;
                electionDeadline = now + electionTimeout();
                lastElection = "stepped down in term " + term + ": only " + reachable + " of " + quorum() + " needed nodes reachable";
                LOG.warn("Cluster: {}", lastElection);
            }
        }
    }

    /** After forwarding a write to the master: fetch its heads and pull until this node has applied the change. */
    void catchUpWith(String master, long timeoutMs) {
        String addr = addressOf(master);
        if (addr == null) return;
        try {
            HttpResponse<String> r = http.send(request(addr, "/cluster/gossip").timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toString(selfMessage()))).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode msg = r.statusCode() == 200 ? JSON.parse(r.body()) : null;
            if (msg == null) return;
            heard(msg, addr);
            pullQueued.set(false);
            puller.submit(this::pullAll).get(timeoutMs, TimeUnit.MILLISECONDS);
            ClusterManager.get().awaitReloads(timeoutMs);
        } catch (Exception e) {
            LOG.debug("catch-up with master failed: {}", e.toString());
        }
    }

    private void prune() {
        long now = System.currentTimeMillis();
        for (Iterator<Member> it = members.values().iterator(); it.hasNext(); ) {
            Member m = it.next();
            if (m.lastHeard > 0 && now - m.lastHeard > TimeUnit.DAYS.toMillis(1)) it.remove();
        }
    }

    // ------------------------------------------------------------------ rate-limit exchange

    void pushRates(String body) {
        long now = System.currentTimeMillis();
        for (Member m : members.values()) {
            if (!m.alive(now) || m.address == null || m.dr != dr) continue;
            HttpRequest req = request(m.address, "/cluster/rates").timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        }
    }

    // ------------------------------------------------------------------ helpers

    private HttpRequest.Builder request(String base, String path) {
        String sep = path.contains("?") ? "&" : "?";
        return HttpRequest.newBuilder(URI.create(base + path + sep + "scaleguard=true"))
                .header("Authorization", token())
                .header("X-ScaleGuard-Forwarded-By", URLEncoder.encode(self, StandardCharsets.UTF_8));
    }

    private String token() {
        long now = System.currentTimeMillis();
        if (token == null || now - tokenAt > 30_000) {
            token = AuthUtils.createJWT(120_000);
            tokenAt = now;
        }
        return token;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ disaster recovery

    /** Ledger entries this node is missing compared with the most complete live member (the replication lag). */
    long lagEntries() {
        long now = System.currentTimeMillis();
        Map<String, Ledger.Head> ours = Ledger.get().heads();
        Map<String, Long> best = new TreeMap<>();
        for (Member m : members.values()) {
            if (!m.alive(now)) continue;
            for (Map.Entry<String, long[]> e : m.headSeqs.entrySet()) best.merge(e.getKey(), e.getValue()[0], Math::max);
        }
        long lag = 0;
        for (Map.Entry<String, Long> e : best.entrySet()) {
            Ledger.Head h = ours.get(e.getKey());
            lag += Math.max(0, e.getValue() - (h == null ? 0 : h.seq()));
        }
        return lag;
    }

    Map<String, Object> drStatus() {
        Map<String, Object> d = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        d.put("standby", dr);
        if (promotedAt > 0) d.put("promotedAt", promotedAt);
        List<String> standbys = new ArrayList<>();
        List<String> primaries = new ArrayList<>();
        for (Member m : new TreeMap<>(members).values()) {
            if (!m.alive(now)) continue;
            (m.dr ? standbys : primaries).add(m.node);
        }
        (dr ? standbys : primaries).add(0, self);
        d.put("primaryNodes", primaries);
        d.put("standbyNodes", standbys);
        d.put("lagEntries", lagEntries());
        Object last = Ledger.get().status().get("lastImportAt");
        if (last != null) d.put("lastReplicatedAt", last);
        String master = leaderNode();
        d.put("primaryMasterReachable", master != null && !dr || (master != null && addressOf(master) != null));
        return d;
    }

    /**
     * Promotes the DR site: every live DR standby (this one included) becomes a voting primary node, the site's voters
     * become exactly those nodes, and they elect a master in a term far above the old site's, so an old-site node that
     * comes back follows the new master instead of competing with it. Refused while the old site still has a reachable
     * master, unless forced.
     */
    public ObjectNode promote(boolean force) {
        ObjectNode out = JSON.object();
        if (!dr) {
            out.put("ok", false);
            out.put("message", "This node isn't a DR standby.");
            return out;
        }
        String master = leaderNode();
        if (master != null && addressOf(master) != null && !force) {
            out.put("ok", false);
            out.put("message", "The primary site's master '" + master + "' is still reachable. Promoting now would leave two sites "
                    + "accepting changes. Stop the primary site first, or promote with force.");
            return out;
        }
        long now = System.currentTimeMillis();
        List<String> site = new ArrayList<>();
        site.add(selfAddress);
        for (Member m : members.values()) if (m.dr && m.alive(now) && m.address != null) site.add(m.address);
        long newTerm = term + 1000;
        ObjectNode req = JSON.object();
        req.put("term", newTerm);
        req.putArray("site").addAll(site.stream().map(com.fasterxml.jackson.databind.node.TextNode::new).toList());
        String body = JSON.toString(req);
        List<String> promoted = new ArrayList<>();
        promoted.add(self);
        for (Member m : members.values()) {
            if (!m.dr || !m.alive(now) || m.address == null) continue;
            try {
                HttpResponse<String> r = http.send(request(m.address, "/cluster/dr/join").timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) promoted.add(m.node);
            } catch (Exception e) {
                LOG.warn("Cluster: couldn't promote DR node '{}': {}", m.node, rootMessage(e));
            }
        }
        becomePrimary(site, newTerm);
        out.put("ok", true);
        out.put("message", "DR site promoted: " + promoted + " now elect a master among themselves (term " + newTerm + ").");
        out.putArray("nodes").addAll(promoted.stream().map(com.fasterxml.jackson.databind.node.TextNode::new).toList());
        return out;
    }

    /** Another DR node is promoting the site this node belongs to. */
    public ObjectNode joinPromotion(JsonNode req) {
        ObjectNode out = JSON.object();
        if (!dr) {
            out.put("ok", false);
            return out;
        }
        List<String> site = new ArrayList<>();
        for (JsonNode a : req.path("site")) site.add(normalize(a.asText()));
        becomePrimary(site, req.path("term").asLong(term + 1000));
        out.put("ok", true);
        return out;
    }

    private void becomePrimary(List<String> site, long newTerm) {
        synchronized (election) {
            dr = false;
            voters.clear();
            for (String a : site) if (a != null && !a.equals(selfAddress)) voters.add(a);
            promotedAt = System.currentTimeMillis();
            Ledger.get().setMetaValue("drPromotedVoters", String.join(",", site));
            Ledger.get().setMetaValue("drPromotedAt", Long.toString(promotedAt));
            if (newTerm > term) {
                term = newTerm;
                votedFor = null;
                Ledger.get().saveVote(term, null);
            }
            role = Role.FOLLOWER;
            leaderNode = null;
            leaderAddress = null;
            lastLeaderContact = 0;
            electionDeadline = System.currentTimeMillis() + java.util.concurrent.ThreadLocalRandom.current().nextLong(HEARTBEAT_MS);
        }
        recountLive();
        LOG.warn("Cluster: DR promotion — this node is now a primary node; voters {}", voters);
    }

    /** Members for the admin view (self first). */
    List<Map<String, Object>> memberView() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("node", self);
        me.put("address", selfAddress);
        me.put("startedAt", startedAt);
        me.put("lastSeenMs", 0);
        me.put("alive", true);
        me.put("leader", role == Role.LEADER);
        me.put("role", role.name().toLowerCase());
        me.put("self", true);
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            info.putAll(metrics.get());
        } catch (Throwable ignored) {
            // best effort
        }
        Runtime rt = Runtime.getRuntime();
        info.put("cpus", rt.availableProcessors());
        info.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        info.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        me.put("info", info);
        me.put("ledgerEntries", Ledger.get().totalEntries());
        me.put("dr", dr);
        out.add(me);
        String master = leaderNode();
        for (Member m : new TreeMap<>(members).values()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("node", m.node);
            n.put("address", m.address);
            n.put("startedAt", m.startedAt);
            n.put("lastSeenMs", m.lastHeard == 0 ? null : now - m.lastHeard);
            n.put("alive", m.alive(now));
            n.put("leader", m.alive(now) && m.node.equals(master));
            n.put("self", false);
            n.put("info", m.info);
            long entries = 0;
            for (long[] s : m.headSeqs.values()) entries += s[0];
            n.put("ledgerEntries", entries);
            n.put("dr", m.dr);
            if (m.error != null && !m.alive(now)) n.put("error", m.error);
            out.add(n);
        }
        return out;
    }

    Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> el = new LinkedHashMap<>();
        el.put("role", role.name().toLowerCase());
        el.put("term", term);
        el.put("master", leaderNode());
        el.put("votedFor", votedFor);
        el.put("quorum", quorum());
        el.put("voters", voters.size() + 1);
        el.put("elections", elections.sum());
        if (role == Role.LEADER) el.put("masterSince", leaderSince);
        else if (lastLeaderContact > 0) el.put("lastMasterContactMs", System.currentTimeMillis() - lastLeaderContact);
        if (lastElection != null) el.put("lastElection", lastElection);
        m.put("election", el);
        m.put("dr", drStatus());
        m.put("seeds", seeds);
        if (!seedErrors.isEmpty()) m.put("unreachable", new TreeMap<>(seedErrors));
        m.put("gossipRounds", gossipRounds.sum());
        m.put("pulls", pulls.sum());
        if (lastPullError != null && System.currentTimeMillis() - lastPullErrorAt < TimeUnit.MINUTES.toMillis(10)) {
            m.put("lastPullError", lastPullError);
            m.put("lastPullErrorAt", lastPullErrorAt);
        }
        m.put("diverged", new ArrayList<>(forks.values()));
        // is every live member at the same chain heads as us?
        long now = System.currentTimeMillis();
        Map<String, Ledger.Head> ours = Ledger.get().heads();
        boolean inSync = true;
        for (Member mm : members.values()) {
            if (!mm.alive(now)) continue;
            Set<String> origins = new LinkedHashSet<>(ours.keySet());
            origins.addAll(mm.headSeqs.keySet());
            for (String o : origins) {
                long a = ours.containsKey(o) ? ours.get(o).seq() : 0;
                long[] b = mm.headSeqs.get(o);
                if (a != (b == null ? 0 : b[0])) inSync = false;
            }
        }
        m.put("inSync", inSync && forks.isEmpty());
        return m;
    }
}
