# ScaleGuard — agent guide

Load balancer / reverse proxy with auto-TLS (ACME), authoritative DNS, WAF, rate limiting, SSH tunnels,
async (Kafka/embedded) delivery and a React admin UI. Single fat JAR, Netty 4.2, Java **21** (README's "11+" is outdated).
Read this first; it's written to save you from re-discovering the codebase.

## Layout
```
src/main/java/com/scaleguard/server/
  http/reverse/     AppServer (main, listeners), ScaleGuardFrontendHandler (per-request pipeline),
                    InboundMessageHandler (route match, X-Forwarded-*), DefaultResponseHandler (admin API dispatch,
                    internal endpoints), RequestRoutingContexts (admin route table), SecureProxyInitializer, Websocket
  http/router/      RouteTable (in-memory routes), ConfigManager (CRUD for sources/targets/host groups),
                    SourceSystem / TargetSystem / HostGroup, RateLimitManager, ClientIpResolver, HostGroupWatcher (health)
  http/utils/       NettyTransport (io_uring/epoll/kqueue/NIO selection), CidrSet, AppProperties
  security/         RateLimitRoute (/ratelimit), waf/ (WafEngine, BuiltInRules, WafPolicy, WafRoute → /waf)
  dns/              DnsServer, DNSAddressBook (zones), UpstreamResolver, DnsWire, DnsCache, DnsRateLimiter, DnsConfig, DNSRoute
  db/               DBManager<T> (reflection SQL over SQLite or Postgres), *DB singletons, SystemPropertyDB (key/value settings)
  certificates/     ACME ordering/renewal          system/  hostname config     application/  security apps & clients
src/main/java/io/netty/handler/ssl/CertificateStore.java   (lives in Netty's package on purpose; keep it there)
admin-ui/           React 18 + CRA + Tailwind + Chakra — see admin-ui/CLAUDE.md
adminui/            built UI served by the JAR at /adminui (output of admin-ui/build.sh)
dev-tools/          test harnesses + load tools — see dev-tools/README.md
```

## Request flow (HTTP)
`ServerInitializer` → HttpServerCodec → HttpObjectAggregator → CORS → **ScaleGuardFrontendHandler.channelRead**:
1. `ClientIpResolver.resolve(peer, X-Forwarded-For)` — XFF trusted only from `trustedProxies`
2. `InboundMessageHandler.matchTarget` → `RouteTarget` (null = internal/admin request)
3. `RateLimitManager.getInstance().check()` → 429 + Retry-After
4. `WafEngine.getInstance().inspect()` (per-route policy) → 403 + X-ScaleGuard-Incident
5. internal → `DefaultResponseHandler.handle`; else proxy to host group (cache / async / tcp variants)

## Admin API conventions
- Every admin call carries `?scaleguard=true`; first path segment selects the route:
  `RequestRoutingContexts.routesMap` (`/ratelimit`, `/waf`, `/dns`, `/apps`, `/systems`, …) or the
  `/config/{sourcesystems|targetsystems|hostgroups}` branch in DefaultResponseHandler.
- Auth: `POST /signin?scaleguard=true {"username","password"}` → `{"token"}`; send it raw in `Authorization`.
- Admin user/password: `-DadminUser` / `-DadminPassword` (or SCALEGUARD_ADMIN_USER / _PASSWORD).
- New admin endpoint = implement `RequestRoute`, register in `RequestRoutingContexts`. Return JSON via
  `RequestRoutingResponse.succes(...)` / `.response(status, body)`.

## Settings
- Static config: `AppProperties.get(key)` = application.properties ← env vars ← `-D` system properties.
- UI-editable settings persist in `system_properties` via `SystemPropertyDB` (id = name), e.g. `ratelimit.*`,
  `waf.policy.<sourceId>`, `waf.customRules`. Load once at startup, keep in memory, write-through on change.
- Notable keys: `port`/`sport` (HTTP/HTTPS, default 80/443), `scaleguard.transport` (auto|io_uring|epoll|kqueue|nio),
  `scaleguard.acceptors`, `scaleguard.rateLimit` (first-run default, per min), `scaleguard.adminRateLimit` (3000/min),
  `scaleguard.trustedProxies`, `dnsServer=true` + `dns*` keys (documented in DnsConfig.java).

## Build & run
```
mvn clean install -DskipTests            # fat jar: target/scaleguard-1.0-SNAPSHOT.jar
cd admin-ui && npm install && ./build.sh # builds UI into ../adminui
./run.sh | ./run.sh stop | ./run.sh status
```
Data (SQLite DB, certs, licence) lives in `~/.scaleguard` (LicenceUtil.dataDir()).

## Gotchas (each cost real time)
- **Jackson here can't serialise Java records** ("No serializer found …"). Convert records to Maps before
  `JSON.toString`. Records are fine internally.
- **`ConfigManager.copy()` copies only non-null fields.** To clear an optional value send a sentinel
  (e.g. `rateLimit: -1` = use default), not null. Updates persist the *merged* object.
- **Netty channels must match the event loop's transport.** Always use `NettyTransport.socketChannel()` /
  `serverChannel()` / `datagramChannel()` / `newGroup()`; never hard-code `Nio*Channel` (that broke WebSockets on epoll).
- `DBManager` builds SQL by string concatenation; all values go through `DBManager.sqlValue()` (quote-escaping). Keep it that way.
- Rate limiting applies to admin/internal requests too, but with its own fixed limit (`scaleguard.adminRateLimit`) so UI settings can't lock admins out.
- WAF patterns: no nested quantifiers (ReDoS); inputs are normalised (URL-decoded ×3, HTML entities, SQL comments) and capped at 8 KB/field.
  Run `dev-tools/py/waf_test.py` after any rule change — it must stay 44/44 attacks, 37/37 benign.
- `ScaleGuardFrontendHandler` releases `msg` in `finally` unless forwarded — keep ownership rules intact when adding early returns.
- Logging: never log per request at INFO (throughput). Throttle (see RateLimitManager) or use DEBUG.

## Verify changes (see dev-tools/README.md for details)
- Compile only: `mvn -q -DskipTests compile`
- Proxy/WAF/rate-limit end-to-end: `LbHarness` + Go backend + `py/waf_test.py`, `py/waf_live.py`, `go/count`
- DNS: `DnsHarness` + `py/upstream.py` + `py/test_dns.py` (29 checks)
- Load: `go/load http://localhost:8080/ 50 10`; compare against direct backend to see proxy overhead
- Benchmarks seen on a 2-vCPU box (server + backend + load generator sharing CPUs): proxy ~8–12k req/s
  (io_uring ≈ +45% vs epoll), WAF on ≈ −20–25%, DNS ~80–110k answers/s.
