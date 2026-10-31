# Cloud functions

Small Groovy programs that answer a route directly, instead of proxying to a host group. Create them under
**Platform → Cloud functions**, then pick a route and press *Use for this route*. A function target is an ordinary target
(`scheme: function`, `host: <function id>`): the request has already passed IP lists, rate limiting, the WAF and
OAuth / client authentication, CORS and per-route protocols apply, and the response is written to the access log with the
usual trace headers. The route's host and base path are the function's URL; the function's *methods* setting decides which
HTTP methods it answers (empty = all; others get `405` with `Allow`). `req.method` lets the script branch itself.

```groovy
if (req.method == 'POST') {
    def body = req.json()                       // Map / List
    return res.status(201).json([hello: body.name, from: req.clientIp])
}
return [path: req.path, q: req.param('q'), region: config.region]
```

## The script API

Scripts get `req`, `res`, `ctx`, `log`, `http`, `config` and nothing else.

| Object | Members |
|---|---|
| `req` | `method`, `path`, `query`, `uri`, `host`, `clientIp`, `headers` (lower-case keys), `header(name)`, `params`, `param(name)`, `body` (text), `length`, `json()` |
| `res` | `status(n)` / `status = n`, `header(k, v)`, `text(s)`, `html(s)`, `json(obj)`, `send(obj)`, `redirect(url[, 3xx])`. Returning a value instead sends it (text, or JSON for maps/lists) |
| `ctx` | `secret(name)`, `config`, `json(obj)`, `parseJson(s)`, `now()`, `uuid()`, `sha256(s)`, `hmacSha256(key, s)`, `base64(s)`, `fromBase64(s)`, `urlEncode(s)`, `urlDecode(s)` |
| `log` | `debug/info/warn/error(msg)`: shown under *Recent runs* (50 lines × 500 chars per run) |
| `http` | `get/post/put/delete/request(...)` → `{status, ok, headers, body, json()}`; only if the admin enabled the network |

Allowed language: variables, control flow, closures, script-level methods, strings, numbers, `List`/`Map`/`Set` and their
usual methods, `Math`, a few value classes (`BigDecimal`, `StringBuilder`, …). Not allowed: `import`, class/interface
declarations, annotations, arrays (`new int[n]`), `new` of anything but plain collections, computed method or property
names (`obj."$x"()`), `.@field`, `.&method`, `this`, and any method not on the allowlist (`SandboxPolicy.METHODS`).

## Permissions the admin sets (per function)

Everything powerful is **off by default** and shown with a warning in the editor.

| Setting | Default | Meaning |
|---|---|---|
| Outbound network | off | Without it `http.*` throws. With it, only the listed hosts |
| Allowed hosts | empty = any public host | `[http://\|https://]host[:port]`, `*.example.com` for subdomains; no scheme = `https` on 443. Scheme, host *and* port must match. Empty list = any public host (the private-range and metadata blocks still apply) |
| Private networks | off | Loopback, RFC 1918, CGNAT, IPv6 ULA / NAT64 / 6to4. Link-local (incl. `169.254.169.254`) and `fd00:ec2::/32` are **never** reachable |
| Max outbound calls / response size | 5 / 1 MB | per run |
| Time limit | 5 s (max `scaleguard.functions.maxTimeoutMs`, 60 s) | per run, excludes the one-time compile |
| Max request / response body | 1 MB / 1 MB | `413` / `502` |
| Max concurrent runs | 10 | more get `503` + `Retry-After` |
| Allocation budget | 512 MB | bytes the run may allocate in total (volume, not live heap) |
| Secrets | none | `ctx.secret('NAME')` returns a gateway secret only if the name is ticked; unlisted names read as `null`. Values are write-only in the API and UI |
| Config | empty | plain `KEY=value`, readable as `config.KEY` |
| Methods | any | see above |

### Outbound calls (SSRF)

`http` is not `java.net.http`: it resolves the name itself, rejects the call if **any** returned address is blocked, then
connects to that very address (so DNS cannot change between check and use: no rebinding window), verifies TLS against the
original name, and follows at most 3 redirects, **re-checking the allowlist and addresses on every hop**. No proxies,
credentials in URLs, other schemes, or request-smuggling headers (`Host`, `Content-Length`, `Transfer-Encoding`, … are set by the client).

## Sandbox model — and what it cannot promise

Layers, all enabled together (`functions/SandboxCompiler`, `SandboxPolicy`, `GuardedMetaClass`):

1. **No annotations.** Source containing `@` outside a string or comment is refused before Groovy sees it
   (`@Grab`, `@ASTTest` etc. run code inside the compiler). Imports and extra classes are refused at CONVERSION.
2. **Compile-time allowlist.** A visitor over the resolved AST accepts only allowlisted types, constructors, static receivers and method
   names, and only constant property / method names.
3. **Run-time allowlist.** The same lists are re-checked on every dynamic dispatch (method, property, constructor, static call) by a
   guarded MetaClass, with classic call sites so nothing is cached past the check. This is what stops tricks the compiler cannot see
   (`'x'[computed]`, values smuggled through collections). Receivers that are not plain values, collections, closures or the API objects
   (e.g. `Class`, `Method`, `Thread`, `ClassLoader`) cannot be touched at all. The guard is active only on the thread running a function.
4. **Restricted class loader.** The script's loader refuses `java.io`, `java.nio`, `java.net`, `java.lang.reflect`, `System`, `Runtime`,
   `Process*`, `ClassLoader`, `sun.*`, `jdk.*`, `io.netty.*` and gateway classes by name.
5. **Limits.** Groovy's `ThreadInterrupt` weaves an interrupt check into every loop, method and closure; a watchdog interrupts at the
   deadline or when the thread's allocation counter passes the budget; scripts cannot create threads or sleep. Runs execute on a bounded
   pool of platform threads (not virtual threads: a spinning script cannot be preempted off a virtual thread's carrier) with a per-function
   concurrency cap and a 512 KB stack, so runaway recursion ends in a normal error.
6. **Small API.** Request, response, log, http, ctx are the only host objects; each lets scripts call only the public methods declared on it.

Honest limits:

- **It is not a security boundary like a process or container.** The scripts share the gateway's JVM. Java 21 has no usable SecurityManager,
  and Groovy is a dynamic language: the allowlist is the defence, and a bug in it, in Groovy, or in a Java API we allowed would be an escape.
  Treat function authors as trusted staff (admin-level), not as tenants.
- A script stuck inside host code that ignores interrupts (e.g. a pathological regex) cannot be stopped. The client still gets `504` at the
  deadline (+0.5 s); the thread keeps its concurrency slot until it ends, so such a function throttles itself instead of the gateway.
- Memory is limited by allocation *volume* per thread, checked every 20 ms. One giant allocation (`'x' * 2000000000`) can still hit the heap
  before the check; the run fails with an out-of-memory error, but size the JVM (`-Xmx`) accordingly.
- Timing side channels, CPU shared with the proxy, and noisy neighbours are not addressed. Every function consumes gateway CPU.
- Outbound calls originate from the gateway's network position: *Private networks* gives the function that reach. Leave it off unless required.
- Secrets are stored in `system_properties` unencrypted, like other gateway settings, and replicate to all nodes.
- Run history and logs live in memory on the node that ran the request.

## Reviewing a function before you enable it

1. Read the code. Anything surprising about `http`, `ctx.secret` or loops is a reason to stop.
2. Enable the network only if needed; list exact hosts when you can (an empty list lets the function send data to any public server), and prefer `https`.
3. Keep *private networks* off unless the target really is internal; then list only that host and port.
4. Tick only the secrets the code reads (search for `ctx.secret`). Remember anyone who can edit the function can exfiltrate those secrets to an allowed host.
5. Lower the time limit, body sizes and concurrency to what the function needs.
6. Attach it to a route with authentication, rate limiting and the WAF turned on. Test with the *Test* panel and check *Recent runs*.
7. Turn the function off (or `scaleguard.functions=false`) to stop all of them at once.

## Scaling, reload and clusters

- Each function is compiled once per (id, code hash) and its Class is shared by all runs: no per-request compilation. Saving checks the
  code compiles and passes the allowlist first, so bad code is never stored.
- An edit swaps the definition atomically. Runs in flight finish on the old class; new runs get the new one. Settings-only edits recompile nothing.
- Functions are the replicated table `cloud_functions` (ledger and Postgres modes) and secrets `functions.secret.*` in `system_properties`;
  the cluster reload hooks (`cloud_functions`, `system_properties/functions`) rebuild the in-memory set on every node. Admin writes go to the master as for other config.
- Defaults: pool of `max(4, 2×cores)` threads (`scaleguard.functions.threads`), queue 8 per thread; a full queue answers `503`.

## Tests

`SandboxEscapeTest` (file / System / reflection / class loading / threads / loops / allocation / recursion), `SandboxHttpTest` (allowlist,
private ranges, metadata, redirects, rebinding, size and call limits; local servers only), `FunctionLifecycleTest` (persistence, hot reload,
replicated reload, admin API, request handler). Run with `mvn -o test -Dtest='Sandbox*Test,FunctionLifecycleTest' -DargLine=-Duser.home=<tmpdir>`.
