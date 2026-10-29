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
  http/h2/          HTTP/2 + gRPC: ProtocolPipelines (builds HTTP/1.1 & HTTP/2 pipelines), CleartextProtocolDetector (h2c
                    prior-knowledge vs HTTP/1.1), AlpnHandler (TLS: h2 vs http/1.1), StreamDispatcher (per stream: gRPC vs
                    plain), GrpcProxyHandler (frame-by-frame gRPC relay), Http2ClientPool (h2 backend connections)
  http/router/      RouteTable (in-memory routes), ConfigManager (CRUD for sources/targets/host groups),
                    SourceSystem / TargetSystem / HostGroup, RateLimitManager, ClientIpResolver, HostGroupWatcher (active
                    health polls), PassiveHealthManager (ejects failing backends), BackendHealthRoute (/backends), BestHostSelector
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
Listener: `ServerInitializer` (plain: `CleartextProtocolDetector`) / `SSLServerInitializer` (TLS + ALPN: `AlpnHandler`) pick
HTTP/1.1 or HTTP/2 (`-Dscaleguard.http2=false` turns HTTP/2 off). HTTP/1.1: HttpServerCodec → aggregator → CORS → frontend.
HTTP/2: Http2FrameCodec → Http2MultiplexHandler → per stream `StreamDispatcher`:
- `content-type: application/grpc*` (not grpc-web) → `GrpcProxyHandler`: route/rate-limit/WAF(headers+path)/client-auth on the
  headers, then frames relayed as-is to the backend over HTTP/2 (`Http2ClientPool`, one connection per backend per event loop,
  h2c or TLS+ALPN). Refusals are trailers-only gRPC statuses: 12 no route, 8 rate limited, 7 WAF/auth, 14 backend down.
- anything else → Http2StreamFrameToHttpObjectCodec → aggregator → same frontend handler as HTTP/1.1 (backend stays HTTP/1.1).

Frontend handler, **ScaleGuardFrontendHandler.channelRead**:
1. `ClientIpResolver.resolve(peer, X-Forwarded-For)` — XFF trusted only from `trustedProxies`
2. `InboundMessageHandler.matchTarget` → `RouteTarget` (null = internal/admin request)
3. `RateLimitManager.getInstance().check()` → 429 + Retry-After
4. `WafEngine.getInstance().inspect()` (per-route policy) → 403 + X-ScaleGuard-Incident
5. internal → `DefaultResponseHandler.handle`; else proxy to host group (cache / async / tcp variants)

## Admin API conventions
Full reference: `docs/API.md` + `docs/openapi.yaml` (OpenAPI 3.0). **Update both when you add or change an endpoint.**
- Every admin call carries `?scaleguard=true`; first path segment selects the route:
  `RequestRoutingContexts.routesMap` (`/ratelimit`, `/waf`, `/dns`, `/apps`, `/systems`, …) or the
  `/config/{sourcesystems|targetsystems|hostgroups}` branch in DefaultResponseHandler.
- Auth: `POST /signin?scaleguard=true {"username","password"}` → `{"token"}`; send it in `Authorization` (raw or `Bearer `).
  Token lifetime `scaleguard.tokenTtlHours` (default 168).
- Admin user/password: `-DadminUser` / `-DadminPassword` (or SCALEGUARD_ADMIN_USER / _PASSWORD).
- New admin endpoint = implement `RequestRoute`, register in `RequestRoutingContexts`. Return JSON via
  `RequestRoutingResponse.succes(...)` / `.response(status, body)`.

## Settings
- Static config: `AppProperties.get(key)` = application.properties ← env vars ← `-D` system properties.
- UI-editable settings persist in `system_properties` via `SystemPropertyDB` (id = name), e.g. `ratelimit.*`,
  `waf.policy.<sourceId>`, `waf.customRules`. Load once at startup, keep in memory, write-through on change.
- Notable keys: `port`/`sport` (HTTP/HTTPS, default 80/443), `scaleguard.transport` (auto|io_uring|epoll|kqueue|nio),
  `scaleguard.acceptors`, `scaleguard.rateLimit` (first-run default, per min), `scaleguard.adminRateLimit` (3000/min),
  `scaleguard.trustedProxies`, `scaleguard.http2` (default true), `scaleguard.http2.maxConcurrentStreams` (250), `dnsServer=true` + `dns*` keys (documented in DnsConfig.java).

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
- HTTP/2 streams are child channels: don't use `ctx.channel().getClass()` for outbound bootstraps, check TLS with
  `ProtocolPipelines.isTls(ch)` (the SslHandler is on the parent), and don't write `FileRegion`s to them
  (HttpStaticFileServerHandler adds a ChunkedWriteHandler for TLS/HTTP/2).
- Backend health: `HostGroup.isReachable()` = active check passing AND not passively ejected. `isActivelyHealthy()` is the
  poll result only. `BestHostSelector` falls back to actively-healthy hosts when all are ejected (panic mode). Record request
  outcomes with `PassiveHealthManager.recordStatus/recordFailure/recordSuccess(rt.getSelectedHost(), …)`. Use
  `RouteTarget.getSelectedHost()` for the backend of a request; calling `getHostGroup()` again picks a *new* host.
  Connect failures are retried once on another host (`scaleguard.connectRetries`).
- `ConfigManager.copy()` skips static/transient fields, so runtime state on HostGroup (passive health) survives edits.
- Logging: never log per request at INFO (throughput). Throttle (see RateLimitManager) or use DEBUG.

## Verify changes (see dev-tools/README.md for details)
- Compile only: `mvn -q -DskipTests compile`
- Proxy/WAF/rate-limit end-to-end: `LbHarness` + Go backend + `py/waf_test.py`, `py/waf_live.py`, `go/count`
- HTTP/2 + gRPC: `go/h2backend.go` + `go/h2check.go` (unary, streaming both ways, bidi, 4 MiB, 200 concurrent, WAF/no-route/
  rate-limit/backend-down statuses), over h2c and TLS
- DNS: `DnsHarness` + `py/upstream.py` + `py/test_dns.py` (29 checks)
- Load: `go/load http://localhost:8080/ 50 10`; compare against direct backend to see proxy overhead
- Benchmarks seen on a 2-vCPU box (server + backend + load generator sharing CPUs): proxy ~8–12k req/s
  (io_uring ≈ +45% vs epoll), WAF on ≈ −20–25%, DNS ~80–110k answers/s, gRPC unary via proxy ~7k calls/s h2c,
  ~5.8k TLS (one client connection, 50 concurrent calls).
