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

## 2. DNS server

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

## 3. Admin UI preview without a server

`ui-preview/build-preview.sh` builds the UI with a hash router, inlines it with `mock.js` (fake API responses
for every page) and writes `admin-ui/design-preview.html`, which opens straight from disk.
Use it to screenshot/review UI changes (e.g. with Playwright).
