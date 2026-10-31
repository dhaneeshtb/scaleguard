# ScaleGuard clustering, master election and disaster recovery

ScaleGuard nodes form a cluster with nothing else to run: no shared database, no etcd, no ZooKeeper. Every node keeps
its own copy of the configuration plus a tamper-evident DuckDB **ledger** of every change, the nodes elect a **master**
by majority vote, and a separate site can stand by for **disaster recovery**.

## Quick start: three nodes

```bash
# node A (10.0.0.10)
java -Dport=80 -Dscaleguard.nodeName=a -Dscaleguard.cluster.advertise=http://10.0.0.10:80 \
     -Dscaleguard.cluster.peers=10.0.0.11:80,10.0.0.12:80 -DadminUser=admin -DadminPassword=… -jar scaleguard.jar
# node B (10.0.0.11): peers=10.0.0.10:80,10.0.0.12:80     node C (10.0.0.12): peers=10.0.0.10:80,10.0.0.11:80
```

- Every node needs the same admin user and password: nodes call each other with tokens signed with them.
- `advertise` is the address the other nodes use to reach this node's admin port.
- Use an odd number of voting nodes, 3 or more. With 2 nodes, losing one leaves no majority: traffic keeps flowing,
  but configuration changes pause until it's back. (`-Dscaleguard.cluster.quorum=1` allows changes on a lone node,
  at the risk of both halves accepting changes during a network split.)
- Put any load balancer, DNS round robin or anycast in front. Every node serves traffic.

The admin UI's **Cluster** page shows the master, the election term, every node, the ledger chains (with a **Verify
chains** button) and the DR site.

## How it works

### Master election

The voting nodes (each node plus its `scaleguard.cluster.peers`) run a Raft-style election:

1. Nodes gossip every second (`POST /cluster/gossip`): address, term, role, ledger chain heads and the members they know,
   so one reachable peer is enough to discover the whole cluster.
2. A node that hasn't heard from a master for 3–6 s (randomised) starts a new **term**, votes for itself and asks the
   others (`POST /cluster/vote`).
3. A node votes once per term, and only for a candidate whose ledger is at least as complete as its own. It refuses
   while it still hears a live master, so a node that merely lost touch can't depose a healthy one.
4. A candidate with a majority becomes master and announces itself at once. Any message from a newer term makes a
   master or candidate step back to follower.
5. A master that can't reach a majority for 5 s steps down, so the minority side of a network split stops accepting
   changes.

The term and vote are stored in the ledger file, so a restarted node never votes twice in a term. The master also runs
the singleton jobs (certificate renewal).

### Configuration ledger

Every configuration write appends one entry to `~/.scaleguard/ledger.duckdb`. This covers routes, services, backends,
settings, WAF, rate limits, DNS, certificates and their files, apps, API clients, refresh tokens and revoked tokens.
Each entry holds:

```
origin · seq · hybrid-logical-clock · table · row key · upsert|delete · reload scopes · full row (JSON) · prev hash · hash
hash = SHA-256(prev hash ‖ origin ‖ seq ‖ hlc ‖ table ‖ key ‖ op ‖ scopes ‖ row)
```

- **One chain per writer.** Each ledger file is a new origin, so a node that loses its file starts a new chain instead
  of forking its old one.
- **Writes go to the master.** An admin `POST`/`PUT`/`DELETE` sent to any node is forwarded to the master, which applies
  it and appends it to its chain. The node that received the request pulls that entry and applies it before
  answering, so the UI reads its own write.
- **Copying.** A node that sees, through gossip, a peer whose chain is ahead pulls the missing entries
  (`GET /cluster/ledger`). The writer pokes all peers immediately, so changes typically arrive within milliseconds.
- **Verification.** Before storing a copy, the node checks every link (sequence, previous hash, content hash). A copy
  that fails is refused and fetched from another node. Nodes also compare chain heads; a different hash at the same
  position is reported as divergence on the Cluster page.
- **Applying.** Each entry replaces the row by key in the local store, last-writer-wins by hybrid logical clock, so
  every node converges to the same rows whatever order entries arrive in. Only the subsystems the entry touches reload;
  backend health and open connections survive.
- `GET /cluster/ledger/verify` re-hashes every chain from its first entry.

**First start.**
- A node with no peers founds the cluster: it records its existing configuration as the cluster's starting point.
- A node joining a cluster that already has data adopts the cluster's configuration; its own defaults are not
  published.
- To merge an existing standalone node's configuration into a cluster, start it once with
  `-Dscaleguard.ledger.baseline=true`.

### Refresh tokens

Refresh tokens are issued, redeemed and revoked on the master, so a refresh token can't be redeemed twice on two nodes.
While no master is reachable, a node handles them itself. If two nodes redeem the same token in that window, the ledger
detects it and revokes the token's whole chain on every node.

### Rate limits

Each node keeps its own token buckets, so the hot path never waits on the network. Nodes push each client's spend to
each other (`POST /cluster/rates`) every 500 ms, or every 100 ms in `strict` mode. See `clusterMode` in
[API.md](API.md#rate-limiting).

### Analytics

Each node writes the requests it served to its own DuckDB file. The **master** collects every node's rows, and every
other node copies the whole cluster's rows from the master. Any node can answer analytics for the whole cluster within
about two copy intervals (2 s each). Without a master, nodes copy from each other directly.

## Disaster recovery (DR)

A DR site is a set of nodes in another region or data centre that stands by for the primary site.

```bash
# DR nodes: list the primary nodes (and each other) as peers
java … -Dscaleguard.cluster.dr=true -Dscaleguard.cluster.advertise=http://10.1.0.10:80 \
     -Dscaleguard.cluster.peers=10.0.0.10:80,10.0.0.11:80,10.0.0.12:80,10.1.0.11:80 -jar scaleguard.jar
```

Primary nodes don't list DR nodes in their peers. They learn about the DR nodes through gossip.

**While standing by**, DR nodes:
- copy every configuration change and the whole cluster's analytics from the primary site;
- serve traffic;
- forward admin changes to the primary master;
- follow the primary master, but never vote and never stand for election;
- keep their own rate-limit budgets, separate from the primary site.

The Cluster page shows the DR site and its **lag** (ledger entries behind), which is your recovery point.

**If the primary site is lost:**
- Without a reachable master, the DR site refuses changes (`503`, with a hint) and keeps serving traffic on the
  configuration it has.
- Promote it with the **Promote DR site to primary** button, or `POST /cluster/dr/promote` on any DR node.
- The promotion is refused while the primary master still answers. `{"force": true}` overrides that; use it only once
  the primary site is fenced off.

**What promotion does:**
1. Every live DR node becomes a voting primary node, and the voters become exactly the DR site's nodes.
2. They elect a master in a term 1000 above the old site's. The promotion survives restarts.
3. When an old primary node comes back, it hears the newer term and follows the new master instead of competing with it.

**Failing back:** restart the old site's nodes with `-Dscaleguard.cluster.dr=true` and the promoted site as peers.
They become standbys of the new primary. When convenient, promote them back the same way.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `scaleguard.cluster.peers` | — | Other voting nodes (`host:port`, comma-separated). On a DR node: the primary nodes and the other DR nodes |
| `scaleguard.cluster.advertise` | `http://<first IPv4>:<port>` | How other nodes reach this node's admin port |
| `scaleguard.nodeName` | `<hostname>:<port>` | Stable node name |
| `scaleguard.cluster.dr` | `false` | Start as a DR standby |
| `scaleguard.cluster.quorum` | majority | Votes needed to elect a master |
| `scaleguard.cluster.heartbeatMs` | `1000` | Gossip interval; election timeout is 3–6 heartbeats |
| `scaleguard.cluster.nodeTimeoutMs` | `5000` | A node not heard from for this long is down |
| `scaleguard.ledger.path` | `~/.scaleguard/ledger.duckdb` | Ledger file |
| `scaleguard.ledger.baseline` | `false` | Merge this node's existing configuration into the cluster it joins |
| `scaleguard.cluster.mode` | auto | `ledger`, or `postgres` for the earlier shared-PostgreSQL clustering |
| `scaleguard.cluster` | `true` | `false` runs the node alone |

PostgreSQL clustering from earlier releases still works: a node with `~/.scaleguard/postgres.properties` and no
`scaleguard.cluster.peers` runs in PostgreSQL mode.

## Tested behaviour

Measured on local clusters of 3 nodes, plus a 4th joining and a 2-node DR site:

- **Election and failover**
  - One master is elected at start.
  - After `kill -9` of the master, a new master was elected in 2.9–3.8 s.
  - The old master rejoined as a follower with the changes it missed.
- **Changes made anywhere**
  - A change made on a follower is applied by the master and readable on that follower when the call returns.
  - 60 concurrent changes from all 3 nodes converged to identical configuration and identical chain heads, and every
    write was made by the master.
- **Quorum loss**
  - With 2 of 3 nodes down: no master, changes refused with 503, traffic still proxied.
  - A master was elected again when the nodes returned.
- **Tampering**
  - An edited ledger entry is reported by verify.
  - A new node joining with one seed refused the tampered copy, fetched a good one from another node, adopted the
    cluster's configuration and verified clean.
- **Analytics through the master**: identical per-node counts on all 4 nodes within 4 s.
- **Strict rate limits** across nodes (6000/min): 2202 admitted against an ideal of 2200, at a steady 100/s.
- **Refresh tokens**
  - A token redeemed on one node and replayed on another is refused, and its chain is revoked everywhere.
  - When the same token was redeemed on two nodes at the same instant, only one succeeded.
- **DR**
  - Standbys copy configuration and analytics, never vote, and keep separate rate budgets.
  - With the primary site down they refuse changes with a promote hint and keep serving traffic.
  - Promotion elected a master in 3.7 s and survived restarts; an old primary node that came back followed the new
    master.

Test scripts: `dev-tools/cluster/` (`ledger_test.py`, `dr_test.py`, `oauth_cluster.py`, `rl_test.py`, `an_test.py`).
