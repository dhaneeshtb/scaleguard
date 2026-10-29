import json, urllib.request, urllib.error
import os
ADMIN = os.environ.get("SG_ADMIN_URL", "http://localhost:8081")   # admin/base port (-Dport)
PROXY = os.environ.get("SG_PROXY_URL", "http://localhost:8080")   # source route port
def _login():
    body = json.dumps({"username": os.environ.get("SG_ADMIN_USER", "admin"), "password": os.environ["SG_ADMIN_PASSWORD"]}).encode()
    return json.load(urllib.request.urlopen(urllib.request.Request(ADMIN + "/signin?scaleguard=true", data=body, method="POST")))["token"]
T = _login()
def api(path, body=None, method=None):
    req = urllib.request.Request(ADMIN + path + "?scaleguard=true", data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": T}, method=method or ("POST" if body is not None else "GET"))
    try: return json.load(urllib.request.urlopen(req))
    except urllib.error.HTTPError as e: return {"_status": e.code, **json.load(e)}
def get(uri, headers=None):
    req = urllib.request.Request(PROXY + uri, headers=headers or {})
    try:
        r = urllib.request.urlopen(req); return r.status, dict(r.headers), r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()
def show(label, ok, extra=""): print(("PASS " if ok else "FAIL ") + label + ("  " + str(extra) if not ok or extra else ""))

api("/ratelimit", {"enabled": False})
ATTACK = "/search?q=1%27%20UNION%20SELECT%20password%20FROM%20users--"
s, h, b = get(ATTACK); show("WAF off by default: attack reaches backend", s == 200, s)
api("/waf/policy/s1", {"enabled": True, "mode": "block"})
s, h, b = get(ATTACK); show("WAF on: attack blocked with 403 + incident id", s == 403 and "X-ScaleGuard-Incident" in h, (s, b[:90]))
s, h, b = get("/search?q=o%27reilly%20books"); show("WAF on: normal request still passes", s == 200, s)
api("/waf/policy/s1", {"enabled": True, "mode": "detect"})
s, h, b = get(ATTACK); show("Detect-only mode: attack allowed but logged", s == 200, s)
api("/waf/policy/s1", {"enabled": True, "mode": "block", "rules": {"sqli-union": {"enabled": False}, "sqli-comment": {"enabled": False}}})
s, h, b = get(ATTACK); show("Per-rule disable: request passes once matching rules are off", s == 200, s)
api("/waf/policy/s1", {"enabled": True, "mode": "block", "rules": {"sqli-union": {"enabled": True, "action": "log"}, "sqli-comment": {"enabled": True, "action": "log"}}})
s, h, b = get(ATTACK); show("Per-rule action=log: passes, logged", s == 200, s)
r = api("/waf/rules", {"name": "Block internal admin path", "pattern": "^/internal/", "targets": ["PATH"], "action": "BLOCK", "description": "Not for the public"})
cid = [x for x in r["rules"] if not x["builtIn"]][0]["id"]
api("/waf/policy/s1", {"enabled": True, "mode": "block"})
s, h, b = get("/internal/metrics"); show("Custom rule blocks /internal/", s == 403, s)
bad = api("/waf/rules", {"name": "broken", "pattern": "(unclosed"}); show("Invalid regex rejected with a clear message", bad.get("_status") == 400, bad.get("message"))
api("/waf/rules/" + cid, method="DELETE")
s, h, b = get("/internal/metrics"); show("Deleted custom rule no longer applies", s == 200, s)
s, h, b = get("/", {"User-Agent": "sqlmap/1.7"}); show("Scanner user-agent blocked", s == 403, s)
v = api("/waf")
ev = v["events"]
show("Events recorded (blocked + logged)", any(e["action"] == "blocked" for e in ev) and any(e["action"] == "logged" for e in ev), len(ev))
print("latest event:", json.dumps(ev[0])[:260])
print("stats:", {k: v["stats"][k] for k in ("inspected", "blocked", "logged")})
pol = [r for r in v["routes"] if r["id"] == "s1"][0]["policy"]; show("Policy persisted & returned", pol["enabled"] and pol["mode"] == "block", pol)
