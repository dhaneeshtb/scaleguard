# ScaleGuard Admin API

Everything the admin UI does goes through this API, so anything you can click you can also script.
A machine-readable version is in [`openapi.yaml`](openapi.yaml) (OpenAPI 3.0). Import it into Postman,
Insomnia or Swagger UI.

- [Conventions](#conventions)
- [Authentication](#authentication)
- [Routing configuration](#routing-configuration): source systems, target systems, host groups, quick mapping
- [Backend health](#backend-health)
- [Rate limiting](#rate-limiting)
- [Web application firewall](#web-application-firewall)
- [DNS](#dns) and [tunnels](#tunnels)
- [Certificates](#certificates)
- [Applications and clients](#applications-and-clients)
- [Async engines and queue events](#async-engines-and-queue-events)
- [Dynamic registration](#dynamic-registration)
- [System settings](#system-settings)
- [Public endpoints](#public-endpoints): health, metrics, stats
- [Protocols: HTTP/2 and gRPC](#protocols-http2-and-grpc)

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
| `secappid` | string | Require client credentials of this application (see [Applications](#applications-and-clients)) |
| `jwtKeylookup`, `headerLookup`, `keyLookupMap` | | Route by JWT claim or header value |
| `tunnel` | boolean | Route served through an SSH tunnel |
| `rateLimit` | integer | Requests/min per client for this route. Omit or `-1` = use the default, `0` = unlimited |

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
- `stats`: `allowed`, `rejected`, `trackedClients`, `limitedClients[]` (`client`, `route`, `rejected`, `limitPerMinute`, `lastRejectedSecondsAgo`).

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
Pass `"policy": {...}` instead of `sourceId` to test an unsaved policy.

## DNS

Authoritative DNS for your zones plus forwarding for allowed clients. Enable with `dnsServer=true`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/dns` | All records grouped by name |
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

## Applications and clients

Client credentials for routes protected with `secappid`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/apps` | Applications |
| `POST` | `/apps` | `{"name", "description"}` |
| `DELETE` | `/apps/{id}` | |
| `GET` | `/clients` | Clients |
| `POST` | `/clients` | `{"appid", "name", "description", "clientid", "clientsecret", "expiry", "id"?}`. `404` if the app doesn't exist |
| `DELETE` | `/clients/{id}` | |

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
| `GET` | `/healthz` | Uptime, route counts, reachable hosts, memory, cache. `503` if no backend is reachable |
| `GET` | `/metrics` | Prometheus text format |
| `GET` | `/stats` | Per-route request counts and response times |
| `GET` | `/info` | Version |
| `GET` | `/adminui/` | Admin UI |
| `GET` | `/.well-known/acme-challenge/{token}` | ACME HTTP-01 responses |

## Protocols: HTTP/2 and gRPC

- **HTTPS listener**: ALPN offers `h2` and `http/1.1`; browsers and gRPC clients get HTTP/2 automatically.
- **HTTP listener**: HTTP/1.1 and HTTP/2 with prior knowledge (h2c), as used by gRPC without TLS.
- Plain HTTP/2 requests are proxied to backends over HTTP/1.1, exactly like HTTP/1.1 requests.
- **gRPC** (`content-type: application/grpc*`) is proxied message by message to the backends over HTTP/2:
  unary, client streaming, server streaming and bidirectional calls, with trailers passed through.
  Routing, client auth, rate limits and the WAF (path and headers) apply. When ScaleGuard itself refuses a call, it
  answers with a gRPC status: `12 UNIMPLEMENTED` (no route), `8 RESOURCE_EXHAUSTED` (rate limited),
  `7 PERMISSION_DENIED` (WAF or auth), `14 UNAVAILABLE` (backend unreachable).
- Settings: `-Dscaleguard.http2=false` turns HTTP/2 off; `-Dscaleguard.http2.maxConcurrentStreams` (default 250).
