# dev-tools — test harnesses for ScaleGuard

Everything here runs locally without real certificates, DNS delegation or a database server.
Build the server first (`mvn -q -DskipTests package`) and use the fat jar on the classpath (`SG_JAR`).

```
export SG_JAR=$PWD/target/scaleguard-1.0-SNAPSHOT.jar
export SG_ADMIN_PASSWORD=TestPass123
```

## 1. HTTP proxy, WAF and rate limiting

```
cd dev-tools
go run go/backend.go :9000 &                         # upstream: "/" → "hello, world", "/echo" → forwarded headers
javac -d /tmp/sgdev -cp $SG_JAR java/LbHarness.java
java -Dport=8081 -Dsport=8443 -DadminUser=admin -DadminPassword=$SG_ADMIN_PASSWORD \
     -cp /tmp/sgdev:$SG_JAR LbHarness 127.0.0.1:9000 &
```
`LbHarness` starts the real `AppServer` with an in-memory route: `localhost:8080` → target `t1` → host group `h1`
(the backends you pass). Admin API on `:8081`. Route id is `s1`.

| Check | Command | Expect |
|---|---|---|
| WAF rules (via `/waf/test`) | `python3 py/waf_test.py` | 44/44 attacks blocked, 37/37 benign passed |
| WAF on the live path | `python3 py/waf_live.py` | 12 PASS (start with `s1` WAF disabled) |
| Rate limiter | set 600/min via `POST /ratelimit`, then `go run go/count.go http://localhost:8080/ 8 75` | ~100 allowed per 10 s window, steady across minute boundaries |
| Spoofing | `go run go/count.go http://localhost:8080/ 8 10 random` with loopback *not* trusted | still limited |
| Throughput | `go run go/load.go http://localhost:8080/ 50 10` vs `http://127.0.0.1:9000/` | proxy overhead |

Raise the limit for load tests: `POST /ratelimit {"enabled":false}` or `-Dscaleguard.rateLimit=2000000000`.
Pick a transport with `-Dscaleguard.transport=io_uring|epoll|nio`.

## 2. HTTP/2 and gRPC

```
go run go/h2backend.go :9100 &                         # h2c + HTTP/1.1 backend with a hand-rolled gRPC echo service
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout key.pem -out cert.pem \
        -days 30 -subj /CN=localhost -addext subjectAltName=DNS:localhost
java ... -Dharness.tlsCert=cert.pem -Dharness.tlsKey=key.pem -cp /tmp/sgdev:$SG_JAR LbHarness 127.0.0.1:9100 &
go run go/h2check.go http://localhost:8080             # h2c: 9 checks
go run go/h2check.go https://localhost:8443 tls waf    # TLS + ALPN h2 (route s2); "waf" adds the PERMISSION_DENIED check
go run go/h2check.go http://127.0.0.1:8080 x expect=12 # no route → UNIMPLEMENTED; expect=8:30 with a low rate limit
go run go/h2check.go http://localhost:8080 x bench=50:8 # unary calls/s
```
TLS to backends: `go run go/h2backend.go :9443 cert.pem key.pem` and start LbHarness with `-Dharness.backendScheme=https`.
grpc-go can't be fetched in some sandboxes, so the gRPC framing (5-byte prefix, grpc-status trailers) is hand-written.

### Passive health checks
`go run go/flaky.go :9201 A /tmp/a.flag &` and `go run go/flaky.go :9202 B /tmp/b.flag &` answer their name, or 503 while
the flag file exists. Start LbHarness with `127.0.0.1:9201,127.0.0.1:9202`, then `touch /tmp/b.flag` → after 5 × 503s B is
ejected (`GET /backends`) and all traffic goes to A; `rm` it → B is back after 10 s. Killing a backend process causes no
client errors (connect failures retry on the other host).

## 3. DNS server

```
python3 py/upstream.py &        # fake upstream resolver on 127.0.0.1:5399 (UDP+TCP), uses name compression on purpose
javac -d /tmp/sgdev -cp $SG_JAR java/DnsHarness.java java/LoadGen.java
java -DdnsServer=true -DdnsPort=5353 -DdnsProxy=127.0.0.1:5399 -DdnsRecursionAllow=127.0.0.0/8 \
     -DdnsAdvertiseIps=203.0.113.10,2001:db8::1 -DdnsUpstreamTimeoutMs=400 -DdnsNameServers=ns1.acme.test. \
     -cp /tmp/sgdev:$SG_JAR DnsHarness &
python3 py/test_dns.py                         # 29 checks: zones, forwarding, cache, NXDOMAIN, TC/TCP, coalescing, malformed input
java -cp /tmp/sgdev LoadGen api.acme.test 1 2 8   # UDP answers/sec
```
`DnsHarness` seeds zone `acme.test.` (base, A/AAAA, TXT, CNAME, MX). `java/stub/` holds an in-memory
`DNSEntriesDB` for environments without the SQLite native library (put `stub` classes first on the classpath).
`java/UnitChecks.java` covers the DNS rate limiter, record add/update/remove DB call counts and SQL escaping.

## 4. HTTP/3 (QUIC)

Start LbHarness with a certificate as in §2; it then also listens on UDP `:8443` (`HTTP/3 (QUIC) listening on UDP port 8443`).
```
curl -sk -D - -o /dev/null https://localhost:8443/ | grep -i alt-svc   # h3=":8443"; ma=86400 on HTTP/1.1 and HTTP/2
javac -d /tmp/sgdev -cp $SG_JAR java/H3Client.java
java -cp /tmp/sgdev:$SG_JAR H3Client localhost 8443 /              # status, headers, body over HTTP/3
java -cp /tmp/sgdev:$SG_JAR H3Client localhost 8443 / 5000 50      # 5000 requests, 50 concurrent streams
java -cp /tmp/sgdev:$SG_JAR H3Client 127.0.0.1 8443 / 1 1 localhost:8443   # no SNI: falls back to a loaded certificate
```
For load tests use an independent QUIC stack: Envoy with an HTTP/3 upstream (`http3_protocol_options` +
`envoy.transport_sockets.quic`, `sni: localhost`) in front, driven by `wrk` over HTTP/1.1. Requests show up in analytics
with `protocol = 'HTTP/3'`.

## 5. Request analytics, OWASP CRS and clustering

- Analytics: send traffic (`go run go/loadvar.go http://localhost:8080 50 30` for varied paths, cookies and agents), then
  `GET /analytics/summary?range=15m`, `/analytics/logs`, `POST /analytics/query {"sql": "SELECT protocol, count(*) FROM access_log GROUP BY 1"}`.
  Write-only DML/COPY/`read_csv` must be rejected and leave the data intact.
- CRS: `POST /waf/rulesets {"name":"crs","files":{...}}` with the `REQUEST-9xx-*.conf` + `.data` files from coreruleset,
  set the route policy to `{"mode":"anomaly","paranoiaLevel":1,"ruleSets":["crs"]}`, then `WAF_POLICY='{...}' python3 py/waf_test.py`
  (expect 44/44 attacks; at PL1 34+/37 benign).
- Cluster: run PostgreSQL, and per node a separate home with `~/.scaleguard/postgres.properties`
  (`driver=org.postgresql.Driver`, `url=jdbc:postgresql://127.0.0.1:5432/scaleguard`, `username`, `password`):
  `java -Duser.home=/tmp/nodeA -Dport=7081 -Dscaleguard.nodeName=node-a -Dscaleguard.cluster.advertise=http://127.0.0.1:7081 …`
  and the same for node-b on 7082. Check: a route created on A is served by B within ~0.5 s; `GET /cluster` lists both and
  one leader; `kill -9` the leader → the other takes over; with `ratelimit.clusterMode=shared` a client's budget is shared
  (send a burst to A, then to B with the same `X-Forwarded-For`: B is limited). Use a route port no node binds as its
  base port: nodes on one machine share it through SO_REUSEPORT.

## 6. Head-to-head benchmark

`bench/run.sh` runs NGINX, HAProxy, Envoy, Caddy, Traefik and ScaleGuard one at a time on CPU 1 against the same NGINX
backend, with `wrk` on CPU 0, and prints req/s, p50/p99, CPU and RSS for a 12-byte and a 16 KB response.
Put the binaries (`nginx`, `haproxy`, `envoy`, `caddy`, `traefik`, `wrk`) in `bench/bin` (or set `BIN`), compile
`java/LbHarness.java` into `SG_DEV`, then:
```
cd bench && ./run.sh setup
for p in nginx haproxy envoy caddy traefik scaleguard scaleguard-analytics; do SG_JAR=$SG_JAR SG_DEV=/tmp/sgdev ./run.sh $p; done
```
Repeat 3× and take the median; `C=1024` for many connections, `SG_HEAP=256m` for a memory-realistic ScaleGuard.

**Multi-core (e.g. a 4 vCPU / 4 GB proxy).** Give the proxy its own CPUs and the load generator + backend the rest:
```
# 8-vCPU machine: proxy on 0-3, wrk + backend on 4-7; every proxy gets 4 workers
PROXY_CPUS=0-3 LOAD_CPUS=4-7 ./run.sh setup
PROXY_CPUS=0-3 LOAD_CPUS=4-7 SG_HEAP=2g SG_JAR=$SG_JAR SG_DEV=/tmp/sgdev ./run.sh scaleguard
PROXY_CPUS=0-3 LOAD_CPUS=4-7 ./run.sh nginx
# or a 4-vCPU proxy machine + a separate load machine: prints the wrk commands to run there
PROXY_CPUS=0-3 LOAD_HOST=10.0.0.5 SG_HEAP=2g ./run.sh scaleguard
```
Each run also prints `cpu_us/req` (proxy CPU time per request): on a shared or noisy machine it moves far less than
req/s, so compare builds with it. Warm-up defaults to 30 s because the JVM is still compiling its hot code before that.

## 7. Admin UI preview without a server

`ui-preview/build-preview.sh` builds the UI with a hash router, inlines it with `mock.js` (fake API responses
for every page) and writes `admin-ui/design-preview.html`, which opens straight from disk.
Use it to screenshot/review UI changes (e.g. with Playwright).
