# ScaleGuard Admin API

Everything the admin UI does goes through this API, so anything you can click you can also script.
A machine-readable version is in [`openapi.yaml`](openapi.yaml) (OpenAPI 3.0). Import it into Postman,
Insomnia or Swagger UI.

- [Conventions](#conventions)
- [Authentication](#authentication)
- [Routing configuration](#routing-configuration): source systems, target systems, host groups, quick mapping
- [Backend health](#backend-health)
- [Rate limiting](#rate-limiting)
- [Web application firewall](#web-application-firewall), including [OWASP CRS import and anomaly scoring](#owasp-crs-import-and-anomaly-scoring)
- [Request analytics](#request-analytics): access logs, dashboards and SQL over the built-in DuckDB
- [Cluster](#cluster): multi-node high availability
- [DNS](#dns) and [tunnels](#tunnels)
- [Certificates](#certificates)
- [App security: OAuth 2.0](#app-security-oauth-20): apps, clients, token settings (guide: [OAUTH.md](OAUTH.md))
- [Async engines and queue events](#async-engines-and-queue-events)
- [Dynamic registration](#dynamic-registration)
- [System settings](#system-settings)
- [Public endpoints](#public-endpoints): health, metrics, stats
- [Protocols: HTTP/2, HTTP/3 and gRPC](#protocols-http2-http3-and-grpc)

## Conventions

| | |
|---|---|
| Base URL | Any ScaleGuard listener, e.g. `http://localhost:8081` or `https://lb.example.com` |
| `?scaleguard=true` | Append to every admin call. It forces the request to the admin API even when the `Host` header matches one of your proxied routes (otherwise the request would be proxied to that route's backend). On a host name with no route it's optional. |
| Body | JSON. Only `POST` bodies are read; `DELETE` takes the id in the path. |
| Auth header | `Authorization: <token>` or `Authorization: Bearer <token>` |
| Errors | Non-2xx with `{"message": "..."}` (DNS uses `{"error": "..."}`). `403` = missing or invalid token. |
| Rate limit | Admin calls are limited to `scaleguard.adminRateLimit` per client IP (default 3000/min, 10 s burst). Over it: `429` with `Retry-After`. |
| CORS | Allowed from any origin on the plain-HTTP listener (the hosted admin UI calls the API cross-origin). |

```bash
SG=http://localhost:8081
TOKEN=$(curl -s -X POST "$SG/signin?scaleguard=true" \
  -d "{\"username\":\"$SCALEGUARD_ADMIN_USER\",\"password\":\"$SCALEGUARD_ADMIN_PASSWORD\"}" | jq -r .token)
curl -s "$SG/backends?scaleguard=true" -H "Authorization: $TOKEN"
```

## Authentication

### `POST /signin`
No auth. Body `{"username": "...", "password": "..."}` → `{"token": "<JWT>"}`.
Credentials come from `SCALEGUARD_ADMIN_USER` / `SCALEGUARD_ADMIN_PASSWORD` (or `-DadminUser` / `-DadminPassword`).
Tokens are valid for `scaleguard.tokenTtlHours` (default 168 h = 7 days).

## Routing configuration

A request is matched to a **source system** (host + port + base path), which points at a **target system**
(load-balancing settings), which spreads traffic over the **host groups** sharing its `groupId` (the backends).

| Method | Path | Description |
|---|---|---|
| `GET` | `/config` | All source systems: `{"sourcesystems": [...]}` |
| `GET` | `/config/{type}` | All records of a type: `{"<type>": [...]}`. `type` = `sourcesystems`, `targetsystems` or `hostgroups` |
| `GET` | `/config/{type}/{id}` | One record, same envelope |
| `POST` | `/config/{type}` | Create (no `id`) or update (with `id`). Updates are partial: fields you leave out keep their values. Returns the saved record, including a generated `id`. |
| `DELETE` | `/config/{type}/{id}` | Delete |
| `POST` | `/config/renewcert/{sourceId}` | Re-issue the route's certificate now |
| `POST` | `/config/quickmapping` | Create a source, target and host group in one call (below) |

Changes take effect immediately; no restart.

### Source system (route)

| Field | Type | Notes |
|---|---|---|
| `id` | string | Generated on create |
| `name` | string | Display name |
| `host` | string | Host name to match, e.g. `api.example.com` |
| `port` | string | Listener port, e.g. `"443"` |
| `scheme` | `http` \| `https` | |
| `basePath` | string | Path prefix to match, default `/` |
| `target` | string | `groupId` of the target system |
| `autoProcure` | boolean | Order a Let's Encrypt certificate automatically |
| `certificateId` | string | Use an existing certificate |
| `async` / `asyncEngine` | boolean / string | Deliver requests through an async engine (Kafka / embedded queue) |
| `secappid` | string | Require an OAuth 2.0 access token issued for this app (see [App security](#app-security-oauth-20)). Requests without one get `401` |
| `oauthScopes` | string | Space-separated scopes the token must carry on this route; otherwise `403 insufficient_scope`. Blank = any token for the app |
| `jwtKeylookup`, `headerLookup`, `keyLookupMap` | | Route by JWT claim or header value |
| `tunnel` | boolean | Route served through an SSH tunnel |
| `rateLimit` | integer | Requests/min per client for this route. Omit or `-1` = use the default, `0` = unlimited |
| `http2` | boolean | Default `true`. HTTP/2 for this host: ALPN `h2` on HTTPS (decided per SNI name), h2c on HTTP. nginx `http2 on` |
| `http3` | boolean | Default `true`, HTTPS only. HTTP/3 over QUIC on the same UDP port, advertised with `Alt-Svc`. When off, the QUIC handshake for this name is refused and no `Alt-Svc` is sent. nginx `listen … quic` + `http3 on` |
| `ipAllow` | string | Comma-separated IPs/CIDRs. When set, only these clients may use the route; others get `403` (nginx `allow …; deny all`) |
| `ipDeny` | string | Comma-separated IPs/CIDRs refused on this route (client or connecting peer), in addition to the gateway blocklist (nginx `deny`) |
| `grpc` | boolean | Default `true` (needs HTTP/2). Proxy `application/grpc` calls end to end over HTTP/2. When off, gRPC calls get status `12 UNIMPLEMENTED`. nginx `grpc_pass` |

A request that arrives over a protocol its route has turned off (e.g. HTTP/2 to a name that shares a connection with
another host) gets `421 Misdirected Request`, which makes clients retry on a fresh connection.

### Target system

| Field | Type | Notes |
|---|---|---|
| `id`, `name` | string | |
| `groupId` | string | Referenced by source systems' `target` |
| `hostGroupId` | string | `groupId` of the host groups (backends) to use |
| `scheme` | `http` \| `https` \| `tcp` | Protocol to the backends. For gRPC, `https` = TLS (ALPN h2), `http` = h2c |
| `basePath` | string | |
| `lbStrategy` | `ROUND_ROBIN` \| `LEAST_CONNECTIONS` \| `WEIGHTED` \| `ACTIVE_STANDBY` | Default `ROUND_ROBIN` |
| `enableCache`, `cachedResources` | boolean, list | Response caching (`cachedResources[].ttl` in seconds) |
| `includeHeaders` / `excludeHeaders` | object | Headers to add to / remove from proxied requests |
| `connectTimeoutMs` | integer | Connect timeout per backend. `-1` = default (`scaleguard.connectTimeoutMs`, 5000). nginx `proxy_connect_timeout` |
| `readTimeoutSeconds` | integer | How long a backend that owes a response may stay silent before the client gets `504`. `-1` = default (`scaleguard.readTimeoutSeconds`, 60). nginx `proxy_read_timeout` |
| `retries` | integer | Connect failures retried on another backend. `-1` = default (`scaleguard.connectRetries`, 1), `0` = off. nginx `proxy_next_upstream` |
| `tlsVerify` | boolean | `https` targets: verify the backend certificate against the JVM trust store and the server name. Default `false` (nginx `proxy_ssl_verify off`) |
| `tlsServerName` | string | `https` targets: SNI name sent and verified, instead of each backend's host (nginx `proxy_ssl_name`). Empty = backend host |

Backends receive HTTP/1.1 with keep-alive whatever the client used (HTTP/1.1, HTTP/2 or HTTP/3); gRPC goes to the
backends over HTTP/2 (h2c for `http`, h2 over TLS for `https`) — the nginx `proxy_pass` + `grpc_pass` model. TLS
handshake failures and backends that close before answering return `502` with the reason.

### Host group (backend)

| Field | Type | Notes |
|---|---|---|
| `id` | string | |
| `groupId` | string | Target systems pick backends by this |
| `scheme`, `host`, `port` | string | Backend address |
| `health` | string | Health-check URL polled every `host.health.interval.seconds` (default 10). Any 2xx = healthy |
| `weight` | integer | For `WEIGHTED` (default 1) |
| `type` | `Active` \| `Standby` | For `ACTIVE_STANDBY` |
| `priority` | integer | Lower = preferred within a tier |
| `active` | boolean | Default `true`. `false` = takes no traffic (nginx `down`) |
| `type` | `Active` \| `Standby` | `Standby` servers are backups: used only when no active server can take the request, with any strategy (nginx `backup`) |
| `maxConns` | integer | Max open connections from ScaleGuard to this server; when reached, requests go elsewhere (or `503` if all are full). `0`/`-1` = unlimited. nginx `max_conns` |
| `maxFails` | integer | Failed requests in a row before passive health checking ejects this server. `-1` = the [Backend health](#backend-health) setting. nginx `max_fails` |
| `failTimeoutSeconds` | integer | First ejection length for this server (doubles on repeats). `-1` = the Backend health setting. nginx `fail_timeout` |

```bash
curl -s -X POST "$SG/config/hostgroups?scaleguard=true" -H "Authorization: $TOKEN" \
  -d '{"groupId":"orders-hosts","scheme":"http","host":"10.0.0.11","port":"8080","health":"http://10.0.0.11:8080/health"}'
curl -s -X POST "$SG/config/targetsystems?scaleguard=true" -H "Authorization: $TOKEN" \
  -d '{"groupId":"orders","name":"Orders API","scheme":"http","hostGroupId":"orders-hosts","lbStrategy":"LEAST_CONNECTIONS"}'
curl -s -X POST "$SG/config/sourcesystems?scaleguard=true" -H "Authorization: $TOKEN" \
  -d '{"name":"orders","host":"orders.example.com","port":"443","scheme":"https","basePath":"/","target":"orders","autoProcure":true}'
```

### Quick mapping

`POST /config/quickmapping` with `{"sourceURL": "https://app.example.com", "targets": ["http://10.0.0.5:3000", "http://10.0.0.6:3000"], "name": "app", "tunnel": false}`
creates host groups `h-<name>`, target `t-<name>` and source `s-<name>`. Fails if the name is taken.

## Backend health

ScaleGuard polls each backend's `health` URL (active checks) and watches real requests (passive checks).
After `consecutiveFailures` failed requests in a row a backend is **ejected**. A failed request is a
connection error or reset, a status in `failureStatuses`, or gRPC `UNAVAILABLE`. An ejected backend gets no
traffic for `baseEjectionSeconds`, doubling on each repeat up to `maxEjectionSeconds`. If every backend of a
target is ejected, they are used anyway (panic mode). A failed connect is retried once on another backend.

| Method | Path | Description |
|---|---|---|
| `GET` | `/backends` | Settings, summary and per-host state |
| `POST` | `/backends/settings` | Update settings (partial) |
| `POST` | `/backends/{hostId}/reset` | Put an ejected host back into rotation now |

```json
POST /backends/settings
{"enabled": true, "consecutiveFailures": 5, "baseEjectionSeconds": 10, "maxEjectionSeconds": 300, "failureStatuses": "502,503,504"}
```

`GET /backends` → `{"settings": {...}, "summary": {"hosts": 2, "ejected": 1, "down": 0}, "hosts": [ ... ]}`. Each host has:
`id`, `groupId`, `address`, `healthUrl`, `targets`, `state` (`healthy` | `probing` | `ejected` | `down`),
`activeCheck` (`none` | `passing` | `failing`), `ejectedForSeconds`, `ejections`, `totalEjections`,
`consecutiveFailures`, `requests`, `failures`, `lastFailure`, `lastFailureAt` (epoch ms) and `activeConnections`.
`probing` means the ejection has ended and the host is back in rotation, but one more failure ejects it again.

## Rate limiting

Token bucket per client IP per route. A client over the limit gets `429` with `Retry-After`; gRPC calls get status `8 RESOURCE_EXHAUSTED`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/ratelimit` | `{"settings", "stats", "routes"}` |
| `POST` | `/ratelimit` | Update settings (partial) |

```json
POST /ratelimit
{"enabled": true, "perMinute": 6000, "burstSeconds": 10, "trustedProxies": "127.0.0.0/8,::1/128,10.0.0.0/8"}
```

- `perMinute`: 1 to 10,000,000. `burstSeconds`: 1 to 3600. A client can send `perMinute / 60 × burstSeconds` requests at once (at least 10).
- `trustedProxies`: comma-separated IPs or CIDRs. `X-Forwarded-For` is only believed from these peers.
- Per-route overrides: set `rateLimit` on the source system.
- `clusterMode` (only matters in a [cluster](#cluster)): `shared` (default) — one budget for the whole cluster, nodes exchange per-client spend every 500 ms;
  `strict` — one budget that is never exceeded: every 100 ms (`scaleguard.cluster.strictRateSyncMs`) each node takes a slice of the
  client's remaining tokens in proportion to where its requests arrive; a client that moves node may be refused for up to ~100 ms;
  `divided` — each node allows `limit ÷ live nodes`; `local` — each node counts on its own.
- `stats`: `allowed`, `rejected`, `trackedClients`, `limitedClients[]` (`client`, `route`, `rejected`, `limitPerMinute`, `lastRejectedSecondsAgo`).

### Blocked and exempt addresses

Gateway-wide IP access lists (IPv4/IPv6 addresses and CIDR ranges), shared by all routes and cluster nodes.

| Method | Path | Description |
|---|---|---|
| `POST` | `/ratelimit/access` | Add entries: `{"list": "blocked" \| "exempt", "values": "203.0.113.7, 198.51.100.0/24", "note": "…", "ttlSeconds": 3600}` |
| `POST` | `/ratelimit/access/remove` | Remove one: `{"list": "blocked" \| "exempt", "value": "198.51.100.0/24"}` |

- **blocked**: refused with `403` (gRPC `7 PERMISSION_DENIED`, analytics outcome `ip_blocked`). Matched against the real client
  address *and* the connecting peer, so a proxy or CDN edge can be blocked as a whole. Like nginx `deny`.
- **exempt**: never rate limited. Matched against the real client address only (X-Forwarded-For counts only from trusted proxies).
- `values` takes several entries separated by commas, spaces or new lines; anything that isn't an IP/CIDR → `400` listing it.
  `ttlSeconds` > 0 makes a temporary entry that expires by itself; `0` = permanent. Re-adding an entry replaces it.
- Applies to proxied traffic (HTTP/1.1, HTTP/2, HTTP/3, gRPC), not the admin API, so you can't lock yourself out.
- `GET /ratelimit` returns them under `access`: `{"blocked": [{"value", "note", "addedAt", "expiresAt"}], "exempt": [...],
  "blockedRequests", "recentlyBlocked": [{"client", "requests", "lastSecondsAgo"}]}`.
- Per route: `ipAllow` / `ipDeny` on the source system (below).

## Web application firewall

21 built-in rules (SQL injection, XSS, path traversal, command injection, SSRF, Log4Shell, scanners, …) plus your
own regex rules. The WAF is off per route until you enable it. Blocked HTTP requests get `403` with an
`X-ScaleGuard-Incident` header; blocked gRPC calls get status `7 PERMISSION_DENIED`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/waf` | `{"rules", "routes", "stats", "events"}`: all rules with hit counts, each route's policy, totals, recent events |
| `POST` | `/waf/policy/{sourceId}` | Save a route's policy |
| `POST` | `/waf/rules` | Create or update a custom rule |
| `DELETE` | `/waf/rules/{id}` | Delete a custom rule |
| `POST` | `/waf/test` | Dry-run a request against a policy |

Policy:
```json
{"enabled": true, "mode": "block", "inspectBody": true, "maxBodyBytes": 65536,
 "rules": {"sqli-union": {"enabled": true, "action": "block"}, "scanner-ua": {"enabled": false}}}
```
`mode`: `block` (enforce) or `detect` (log only). `rules` holds per-rule overrides; rules not listed use their defaults.
`action` per rule: `block` or `log`.

Custom rule:
```json
{"id": "no-internal", "name": "Block /internal", "description": "Internal endpoints",
 "pattern": "^/internal/", "targets": ["PATH"], "action": "BLOCK"}
```
`targets`: any of `PATH`, `QUERY`, `HEADERS`, `USER_AGENT`, `BODY`. Patterns are Java regexes matched against
normalised input (URL-decoded up to 3 times, HTML entities decoded, SQL comments removed). Invalid regexes → `400`.

Test:
```json
POST /waf/test
{"sourceId": "s1", "uri": "/search?q=' OR '1'='1", "headers": {"User-Agent": "curl/8"}, "body": null}
→ {"blocked": true, "matches": [{"ruleId": "sqli-tautology", ...}]}
```
Pass `"policy": {...}` instead of `sourceId` to test an unsaved policy. The response also has `score` (the anomaly score in `anomaly` mode).

### OWASP CRS import and anomaly scoring

Import the [OWASP Core Rule Set](https://coreruleset.org/) (or any ModSecurity `SecRule` files) and score requests
the way CRS does: every matching rule adds its severity (critical 5, error 4, warning 3, notice 2) and the request is
blocked once the total reaches the route's threshold.

| Method | Path | Description |
|---|---|---|
| `POST` | `/waf/rulesets` | Import (or replace) a rule set |
| `DELETE` | `/waf/rulesets/{name}` | Delete a rule set; routes using it stop using it |

```json
POST /waf/rulesets
{"name": "crs-4", "files": {"REQUEST-942-APPLICATION-ATTACK-SQLI.conf": "<file contents>", "sql-errors.data": "<file contents>"}}
→ {"imported": 189, "byParanoia": {"1": 120, ...}, "byCategory": {...}, "skipped": {"libinjection operator": 6, ...}, "examples": [...], "waf": {...}}
```
Upload the `REQUEST-9xx-*.conf` files plus the `.data` files they reference with `@pmFromFile`. Supported operators:
`@rx`, `@pm`, `@pmFromFile`, `@contains`, `@beginsWith`, `@endsWith`, `@streq`. Rules using other operators
(libinjection, negations, IP matching), file uploads or multipart bodies are skipped and counted in `skipped`.
Imported patterns run with a per-match time budget (`scaleguard.waf.matchBudgetMicros`, default 20000) so a slow
regex can't stall the proxy.

Policy fields for scoring:
```json
{"enabled": true, "mode": "anomaly", "anomalyThreshold": 5, "paranoiaLevel": 1, "ruleSets": ["crs-4"], ...}
```
- `mode`: `block` (any blocking rule blocks), `anomaly` (sum severities, block at `anomalyThreshold`) or `detect` (log only).
- `paranoiaLevel` 1–4: imported rules above this level don't run. Level 1 is safe for most sites; 2+ needs tuning.
- `ruleSets`: imported sets enabled on this route, on top of the built-in and custom rules.

## Request analytics

Every proxied request (HTTP/1.1, HTTP/2, HTTP/3 and gRPC) is written to an embedded DuckDB database on the node
(`scaleguard.analytics.path`, default `~/.scaleguard/analytics.duckdb`), off the request path. Admin and health
calls aren't recorded. Responses carry an `X-Trace-Id` header (the W3C `traceparent` trace id, kept from the client
if it sent one) so a request can be found in the log. Disable with `-Dscaleguard.analytics=false`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/analytics/summary` | Totals, time series and top lists for the dashboard |
| `GET` | `/analytics/logs` | Access log rows, newest first, with filters and paging |
| `GET` | `/analytics/export` | The same rows as CSV or NDJSON (up to 100,000) |
| `POST` | `/analytics/query` | Read-only SQL over the `access_log` view |
| `GET`/`POST` | `/analytics/settings` | `{"enabled", "retentionDays"}` plus storage `status`, this `node` and cluster `replication` status |
| `GET` | `/analytics/replicate` | Used by other cluster nodes: rows of `origin` (default this node) after `after` (seq), up to `limit` (≤ 50,000), plus this node's `cursors` per origin |

Common query parameters: `range` (`15m`, `1h`, `6h`, `24h`, `7d`, `30d`), `route` (source system id) and `node`.

**In a cluster every node has every node's requests, and they flow through the master.** Each node writes the
requests it served to its own DuckDB file and numbers them. Every 2 s (`scaleguard.analytics.replicateMs`):
- the **master** copies each other node's new rows (`GET /analytics/replicate?origin=<node>&after=<seq>` on that node);
- every **other node** copies all nodes' rows from the master alone (`origin=<each node>` on the master), so a request
  served anywhere reaches every node in about two copy intervals;
- while no master is elected, nodes copy straight from each other so no node goes dark.

No extra database is involved.
- A node that was offline, or joins later, catches up by itself, back-filling up to the retention window.
- Requests are never counted twice.
- `node=<name>` filters to the requests that node served, including nodes that have since left.
- `GET /analytics/settings` → `replication` shows the `master`, the `route` this node copies by, each origin's rows
  copied, `via` (the master or the node itself), how far behind it is, the last copy and any error.
- `-Dscaleguard.analytics.replicate=false` turns it off. Each node then keeps only its own log, and `node=<name>` is
  forwarded to that node instead.
- Every node stores the whole cluster's rows, so a node's analytics write load grows with the cluster's total traffic.

- `summary` → `totals` (`requests`, `rps`, `errors4xx`, `errors5xx`, `wafBlocked`, `rateLimited`, `clients`, `bytesIn`, `bytesOut`, `avgMs`, `p50`, `p95`, `p99`),
  `series` (per-bucket counts and latency), `routes`, `paths`, `clients`, `backends`, `statuses`, `outcomes`, `protocols`, `wafRules`, `status`.
- `logs` filters: `status` (`2xx`…`5xx` or an exact code), `outcome` (`proxied`, `rate_limited`, `waf_blocked`, `auth_denied`, `no_backend`, `error`, `cancelled`),
  `ip`, `method`, `trace`, `q` (text in path or query), `limit` (≤ 500), `before` (cursor from `next`). → `{"rows": [...], "next": <cursor|null>}`.
- `export`: add `format=csv` or `format=ndjson`.

```json
POST /analytics/query
{"sql": "SELECT path, count(*) AS n, quantile_cont(duration_ms, 0.95) AS p95 FROM access_log WHERE ts > now() - INTERVAL 1 HOUR GROUP BY 1 ORDER BY n DESC LIMIT 10"}
→ {"columns": ["path", "n", "p95"], "rows": [["/api/orders", 5123, 41.7], ...], "truncated": false, "ms": 12}
```
Only one read statement (`SELECT`, `WITH`, `FROM`, `SUMMARIZE`, `DESCRIBE`, `PIVOT`) is allowed; it runs in a
transaction that is always rolled back, with file access disabled, a 15 s timeout and at most 1,000 rows.
Columns: `ts` (UTC), `trace_id`, `client_ip`, `method`, `host`, `path`, `query`, `protocol`, `route_id`, `route_name`,
`backend`, `status`, `grpc_status`, `bytes_in`, `bytes_out`, `duration_ms`, `outcome`, `waf_rule`, `user_agent`, `referer`, `tls`, `node`.

## Cluster

ScaleGuard nodes form a cluster without any shared database. Full guide: [CLUSTER.md](CLUSTER.md).

```
# on each node: the other voting nodes (use 3 or more), and how they reach this one
-Dscaleguard.cluster.peers=10.0.0.11:80,10.0.0.12:80  -Dscaleguard.cluster.advertise=http://10.0.0.10:80
```

- **Master election**: the voting nodes (this node + `scaleguard.cluster.peers`) elect a master by majority vote,
  Raft-style (terms, one vote per term, a candidate needs a ledger at least as complete as the voter's). A master that
  can't reach a majority steps down. With 3 nodes, losing any one re-elects in about 3–6 s.
- **Configuration ledger**: every change is appended to a DuckDB ledger (`~/.scaleguard/ledger.duckdb`) as the full row,
  chained by SHA-256 to the previous entry. Admin changes (`POST`/`PUT`/`DELETE`) sent to any node are made on the
  master; the node answers after it has applied the master's entry, so it reads its own write. Nodes pull missing
  entries from each other within milliseconds, verify every link and apply rows last-writer-wins by hybrid logical
  clock. A copy whose hash doesn't match is refused and fetched from another node.
- **No master** (no majority reachable): changes are refused with `503`; traffic keeps flowing on the current
  configuration.
- **Refresh tokens** are issued, redeemed and revoked on the master, so a token can't be used twice on two nodes.
- **Rate limits**: per-client spend is pushed node to node; see `clusterMode` under [Rate limiting](#rate-limiting).
- **Analytics**: flow through the master to every node; see [Request analytics](#request-analytics).
- **Disaster recovery**: nodes started with `scaleguard.cluster.dr=true` (and the primary nodes as peers) are standbys:
  they copy configuration and analytics, serve traffic, follow the master, but never vote and keep their own
  rate-limit budgets. `POST /cluster/dr/promote` turns the DR site into the primary.

| Method | Path | Description |
|---|---|---|
| `GET` | `/cluster` | Mode, master, election, members, ledger chains, DR status and sync status |
| `POST` | `/cluster/resync` | Reload everything on this node from its store now (ledger mode: also pull missing entries) |
| `GET` | `/cluster/ledger/verify` | Re-hash every chain on this node → `{"ok", "chains": [{"origin", "node", "entries", "ok", "problem"?}], "tookMs"}` |
| `POST` | `/cluster/dr/promote` | On a DR standby: promote the DR site. `{"force": true}` promotes even while the primary master answers (409 otherwise) |
| `GET` | `/cluster/ledger?origin=&after=&limit=` | Node to node: entries of one chain |
| `POST` | `/cluster/gossip`, `/cluster/vote`, `/cluster/rates`, `/cluster/dr/join`, `/cluster/oauth/{issue,redeem,revoke}` | Node to node: membership, election, rate limits, DR promotion, refresh tokens |

`GET /cluster` (ledger mode) → `{"mode": "ledger", "enabled", "master", "liveNodes", "self", "nodes": [{"node", "address",
"alive", "leader", "dr", "ledgerEntries", "lastSeenMs", "info": {...}}], "election": {"role", "term", "master", "quorum",
"voters", "elections", "lastElection"}, "ledger": {"origin", "genesis", "entries", "rows", "chains": [{"origin", "node",
"seq", "hash"}], "inSync", "diverged": [], "lastPullError"?, "dr": {"standby", "primaryNodes", "standbyNodes",
"lagEntries", "lastReplicatedAt", "promotedAt"?}}, "sync": {...}}`

Settings: `scaleguard.nodeName` (default `<hostname>:<http port>`), `scaleguard.cluster.advertise` (URL other nodes use
to reach this node's admin API), `scaleguard.cluster.peers`, `scaleguard.cluster.dr`, `scaleguard.cluster.quorum`
(override the majority, e.g. `1` for a 2-node cluster that accepts split-brain risk), `scaleguard.cluster.heartbeatMs`
(1000), `scaleguard.cluster.nodeTimeoutMs` (5000), `scaleguard.ledger.path`, `scaleguard.ledger.baseline=true` (merge
this node's existing configuration into a cluster it joins), `scaleguard.cluster=false` to opt out. All nodes must use
the same admin credentials (nodes call each other with tokens signed by them).

**PostgreSQL mode** (earlier releases) still works: with `~/.scaleguard/postgres.properties` and no
`scaleguard.cluster.peers`, nodes share the database, a PostgreSQL advisory lock picks the leader and
`LISTEN/NOTIFY` spreads changes. Force either mode with `scaleguard.cluster.mode=ledger|postgres`.

## DNS

Authoritative DNS for your zones plus forwarding for allowed clients. Start and stop it from the DNS page or `POST /dns/start` (`dnsServer=true` is only the default until that is set). Record types: A, AAAA, CNAME, MX, TXT, NS, SRV, CAA, PTR. A value is checked against its type (`400 {"error"}` if it doesn't fit): MX `10 mail.example.com`, SRV `10 5 5060 sip.example.com`, CAA `0 issue letsencrypt.org`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/dns` | All records grouped by name |
| `GET` | `/dns/status` | `{"enabled", "running", "port"}`: desired state (cluster-wide) and whether this node's server is up |
| `GET` | `/dns/test?domain=example.com` | Checks run from this node: server running, answers for the zone, public NS delegation points at our name servers, their glue/A records match our IPs and answer on port 53. Returns `{domain, ok, checks:[{name, ok, detail}], instructions:[...]}`. Cannot prove port 53 is open from the internet |
| `POST` | `/dns/start`, `/dns/stop` | Set the desired state; saved as `dns.enabled` and replicated, so every connected node starts/stops its server. `500` if the start fails here (e.g. port in use) |
| `GET` | `/dns/settings` | `{settings, defaults}`: effective server settings (port, forwarders, recursion, allowed clients, cache, rate limit, advertised IPs, name servers, blocked domains) |
| `POST` | `/dns/settings` | `{"dnsRateLimit": "50", …}` (any subset; blank = default). Validated, saved as `dns.settings`, replicated; every node restarts its DNS server to apply. `400` on a bad value, `500` if the restart fails here |
| `GET` | `/dns/stats` | Server counters (queries, cache hits, upstream timeouts, rate-limited, …) |
| `POST` | `/dns` | Add a record |
| `DELETE` | `/dns/{id}` | Delete a record |
| `DELETE` | `/dns/cache` | Clear the forwarding cache |

```json
POST /dns
{"name": "api.example.com", "type": "a", "ip": "203.0.113.10", "ttl": 300}
```
`type`: `base` (zone apex: makes ScaleGuard authoritative for the zone), `a`, `aaaa`, `txt`, `cname`, `mx`, `ns`
or `record`. `ttl` 1 to 604800 (default from the server config).

## Tunnels

| Method | Path | Description |
|---|---|---|
| `GET` | `/tunnel` | DNS records of tunnelled services |
| `POST` | `/tunnel` | `{"name", "port", "host", "baseDNS"}`: expose a service through an SSH tunnel under the base zone |
| `DELETE` | `/tunnel/{id}` | Remove a tunnelled service |

## Certificates

| Method | Path | Description |
|---|---|---|
| `GET` | `/certificates` | All certificates and orders |
| `POST` | `/certificates` | Order: `{"domainNames": ["example.com", "*.example.com"], "challengeType": "http" \| "dns"}` |
| `GET`/`POST` | `/certificates/{id}/verify` | Complete the challenge. Returns `{"status": "..."}` |
| `POST` | `/certificates/{id}/renew` | Renew now |
| `GET` | `/certificates/{id}/download` | Certificate and key material |
| `DELETE` | `/certificates/{id}` | Delete |

## App security: OAuth 2.0

Routes with `secappid` require `Authorization: Bearer <access token>`. Apps stand for the APIs you protect; clients are
the services that call them. The full guide, with the token flow and samples, is in [OAUTH.md](OAUTH.md).

| Method | Path | Description |
|---|---|---|
| `GET` | `/apps` | Apps, each with its `clients` |
| `POST` | `/apps` | `{"name", "description"}` |
| `DELETE` | `/apps/{id}` | Deletes the app **and its clients** |
| `GET` | `/clients` | Clients (never includes secrets; `secretHint` shows the last characters, `activeRefreshTokens` the live refresh tokens) |
| `POST` | `/clients` | Create: `{"appid", "name", "description"?, "clientid"?, "scopes"?: [..], "expiry"?: epoch ms (0 = never), "accessTokenTtlSeconds"?, "refreshTokenTtlSeconds"?, "refreshTokens"?: true, "enabled"?: true, "clientsecret"?}`. Returns `201 {"client", "client_secret", "credentials": {...}}`. **The secret is only in this response.** `clientid` is generated when omitted; `clientsecret` imports an existing secret (≥ 24 characters), otherwise one is generated. `404` if the app doesn't exist |
| `POST` | `/clients/{id}` | Update `name`, `description`, `expiry`, `scopes`, token lifetimes (`-1` = default), `refreshTokens`, `enabled`. Disabling revokes its refresh tokens |
| `POST` | `/clients/{id}/secret` | Rotate the secret. Returns `201` with the new `client_secret` (shown once). The old secret and every token issued so far stop working |
| `POST` | `/clients/{id}/revoke` | Revoke every access and refresh token issued to the client so far |
| `DELETE` | `/clients/{id}` | Delete the client (its tokens stop working) |
| `GET` | `/oauth` | `{"settings", "keys": [{kid, created, retired?, active}], "endpoints"}` |
| `POST` | `/oauth/settings` | `{"accessTokenTtlSeconds", "refreshTokenTtlSeconds", "issuer", "pathPrefix", "legacyHeaders"}` (any subset). `400` with a message when out of range |
| `POST` | `/oauth/keys/rotate` | New RS256 signing key; older keys keep verifying for 7 days |

`{id}` is the client's record `id` or its `clientid`.

### Token endpoints

Public: the client authenticates with HTTP Basic (`client_id:client_secret`) or form fields `client_id` + `client_secret`.
They're served on the admin port and on every route with `secappid`, under `/oauth2` (configurable). Bodies are
`application/x-www-form-urlencoded` (JSON is also accepted). Responses carry `Cache-Control: no-store`. Errors are
`{"error", "error_description"}` with the RFC 6749 codes: `invalid_request`, `invalid_client` (`401`), `invalid_grant`,
`unauthorized_client`, `unsupported_grant_type`, `invalid_scope`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/oauth2/token` | `grant_type=client_credentials` (`scope` optional, defaults to all allowed) or `grant_type=refresh_token&refresh_token=…`. Returns `{"access_token", "token_type": "Bearer", "expires_in", "scope", "refresh_token"?, "refresh_token_expires_in"?}` |
| `POST` | `/oauth2/revoke` | RFC 7009: `token=…` (access or refresh). Always `200` |
| `POST` | `/oauth2/introspect` | RFC 7662: `token=…`. Allowed for clients of the same app, or with an admin token. `{"active": false}` or `{"active": true, "client_id", "scope", "aud", "exp", …}` |
| `GET` | `/oauth2/jwks` | RFC 7517 public keys |
| `GET` | `/.well-known/oauth-authorization-server` | RFC 8414 metadata |

## Async engines and queue events

| Method | Path | Description |
|---|---|---|
| `GET` | `/asyncengines` | Configured engines |
| `POST` | `/asyncengines` | `{"id"?, "name", "type", "description", "payload": {...}}` (type e.g. `kafka` or `embedded`) |
| `DELETE` | `/asyncengines/{id}` | Delete an engine |
| `GET` | `/queueevents` | Last 200 queued events, newest first. Filters: `?status=pending\|dispatching\|success\|failed\|dead`, `?engineId=` |
| `GET` | `/queueevents/{id}` | One event |

## Dynamic registration

Lets backends add or remove themselves (e.g. from a container start script).

| Method | Path | Body |
|---|---|---|
| `POST` | `/register/{targetGroupId}/bind` | Host group JSON (`host`, `port`, `scheme`, `health`, …) |
| `POST` | `/register/{targetGroupId}/unbind` | Same host group JSON |

## System settings

| Method | Path | Description |
|---|---|---|
| `GET` | `/systems` | Stored system properties |
| `POST` | `/systems/configure` | `{"hostName": "lb.example.com", "id"?}`: set the server's own host name (and its certificate) |
| `POST` | `/systems` | Save system properties |

## Public endpoints

No auth.

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | `{"status": "healthy"}` |
| `GET` | `/healthz` | Uptime, route counts, reachable hosts, memory, cache, enabled protocols (`protocols.http3.udpPorts`). `503` if no backend is reachable |
| `GET` | `/metrics` | Prometheus text format |
| `GET` | `/stats` | Per-route request counts and response times |
| `GET` | `/info` | Version |
| `GET` | `/adminui/` | Admin UI |
| `GET` | `/.well-known/acme-challenge/{token}` | ACME HTTP-01 responses |
| `POST` | `/oauth2/token`, `/oauth2/revoke`, `/oauth2/introspect` | OAuth 2.0 token service (client authentication, see [above](#token-endpoints)) |
| `GET` | `/oauth2/jwks`, `/.well-known/oauth-authorization-server` | Token signing keys and discovery metadata |

## Protocols: HTTP/2, HTTP/3 and gRPC

- **HTTPS listener**: ALPN offers `h2` and `http/1.1`; browsers and gRPC clients get HTTP/2 automatically.
- **HTTP/3 (QUIC)**: every HTTPS port is also served over UDP on the same port number, with the same certificates
  (picked per SNI). HTTPS responses carry `Alt-Svc: h3=":443"; ma=86400`, so browsers switch to HTTP/3 on their
  next request. HTTP/3 requests go through the same routing, WAF, rate limits, caching and analytics
  (`protocol = 'HTTP/3'`); backends still receive HTTP/1.1. Open the UDP port in your firewall
  (`docker run -p 443:443 -p 443:443/udp`). Settings: `scaleguard.http3=false` turns it off;
  `scaleguard.http3.retry=true` requires QUIC address validation (protects against spoofed-source floods, costs one
  round trip per new connection); `scaleguard.http3.maxStreams` (default 256 concurrent requests per connection);
  `scaleguard.http3.idleTimeoutSeconds` (60); `scaleguard.http3.congestion` (`cubic`, `bbr`, `reno`);
  `scaleguard.http3.sockets` (UDP sockets per port on Linux, default one per core). gRPC over HTTP/3 isn't supported;
  gRPC clients use HTTP/2.
- **HTTP listener**: HTTP/1.1 and HTTP/2 with prior knowledge (h2c), as used by gRPC without TLS.
- Plain HTTP/2 requests are proxied to backends over HTTP/1.1, exactly like HTTP/1.1 requests.
- **gRPC** (`content-type: application/grpc*`) is proxied message by message to the backends over HTTP/2:
  unary, client streaming, server streaming and bidirectional calls, with trailers passed through.
  Routing, client auth, rate limits and the WAF (path and headers) apply. When ScaleGuard itself refuses a call, it
  answers with a gRPC status: `12 UNIMPLEMENTED` (no route), `8 RESOURCE_EXHAUSTED` (rate limited),
  `7 PERMISSION_DENIED` (WAF or auth), `14 UNAVAILABLE` (backend unreachable).
- Settings: `-Dscaleguard.http2=false` turns HTTP/2 off; `-Dscaleguard.http2.maxConcurrentStreams` (default 250).
