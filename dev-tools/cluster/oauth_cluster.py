import os
import json, base64, threading, time, urllib.request, urllib.parse
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ct.py')).read().split('A,B=')[0])
P = [7181, 7182, 7183]
T = {p: login(p) for p in P}
def post(p, path, body): s, b = req(p, "POST", path + "?scaleguard=true", body, T[p]); return s, json.loads(b) if b.startswith("{") else b
s, app = post(7181, "/apps", {"name": "o-%d" % time.time(), "description": "x"})
s, cl = post(7182, "/clients", {"name": "svc", "appid": app["id"], "scopes": "orders:read"})
c = cl["credentials"]
rid = [r for r in json.loads(req(7181, "GET", "/config/sourcesystems?scaleguard=true", tok=T[7181])[1])["sourcesystems"] if r["name"] == "Svc"][0]["id"]
print("route", post(7183, "/config/sourcesystems", {"id": rid, "secappid": app["id"], "oauthScopes": "orders:read"})[0])
time.sleep(1.5)
basic = "Basic " + base64.b64encode((urllib.parse.quote(c["client_id"], safe="") + ":" + urllib.parse.quote(c["client_secret"], safe="")).encode()).decode()
def token(port, form):
    r = urllib.request.Request(f"http://127.0.0.1:{port}/oauth2/token", data=urllib.parse.urlencode(form).encode(), method="POST",
        headers={"Host": "svc.test:7190", "Authorization": basic, "Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(r, timeout=5) as resp: return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e: return e.code, json.loads(e.read() or b"{}")
ok = 0; bad = 0
def check(n, cond, d=""):
    global ok, bad
    print(("PASS " if cond else "FAIL ") + n, "" if cond else d); ok += cond; bad += (not cond)
s, t = token(7181, {"grant_type": "client_credentials"})
check("client_credentials on node-1 (client created via node-2)", s == 200 and "refresh_token" in t, (s, t))
time.sleep(0.5)
s, t2 = token(7183, {"grant_type": "refresh_token", "refresh_token": t["refresh_token"]})
check("refresh issued on node-1 redeemed on node-3", s == 200, (s, t2))
time.sleep(0.5)
s, t3 = token(7182, {"grant_type": "refresh_token", "refresh_token": t2["refresh_token"]})
check("rotated token redeemed on node-2", s == 200, (s, t3))
time.sleep(0.5)
s, x = token(7181, {"grant_type": "refresh_token", "refresh_token": t2["refresh_token"]})
check("reusing an old token on another node is refused", s == 400, (s, x))
s, x = token(7183, {"grant_type": "refresh_token", "refresh_token": t3["refresh_token"]})
check("…and revokes the chain everywhere", s == 400, (s, x))
# simultaneous replay on two nodes
s, a = token(7181, {"grant_type": "client_credentials"}); time.sleep(0.5)
res = {}
def go(p): res[p] = token(p, {"grant_type": "refresh_token", "refresh_token": a["refresh_token"]})
ts = [threading.Thread(target=go, args=(p,)) for p in (7182, 7183)]; [t.start() for t in ts]; [t.join() for t in ts]
won = [p for p in res if res[p][0] == 200]
print("   simultaneous redemption succeeded on", won)
time.sleep(2)
after = [token(p, {"grant_type": "refresh_token", "refresh_token": res[p][1]["refresh_token"]})[0] for p in won]
check("a token redeemed on two nodes at once gets its chain revoked", len(won) == 1 or all(s == 400 for s in after), (won, after))
print(ok, "passed", bad, "failed")
print({p: (res[p][0], str(res[p][1])[:150]) for p in res})
