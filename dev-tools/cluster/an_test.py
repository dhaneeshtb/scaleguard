import os
import json, time, urllib.request
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ct.py')).read().split('A,B=')[0])
P = [7181, 7182, 7183, 7184]
toks = {p: login(p) for p in P}
m = json.loads(req(7181, "GET", "/cluster?scaleguard=true", tok=toks[7181])[1])["master"]
print("master", m)
st = req(7181, "POST", "/ratelimit?scaleguard=true", {"enabled": True, "perMinute": 1000000, "burstSeconds": 10, "clusterMode": "shared"}, toks[7181]); print("rl", st[0]); time.sleep(2)
N = 200
for p in P:
    for i in range(N):
        r = urllib.request.Request(f"http://127.0.0.1:{p}/small?n={p}", headers={"Host": "svc.test:7190"})
        urllib.request.urlopen(r, timeout=5).read()
def counts(p):
    s, b = req(p, "POST", "/analytics/query?scaleguard=true", {"sql": "SELECT node, count(*) c FROM access_log WHERE path = '/small' GROUP BY node ORDER BY node"}, toks[p])
    j = json.loads(b); return {r[0]: r[1] for r in j["rows"]}
t0 = time.time()
while time.time() - t0 < 30:
    c = [counts(p) for p in P]
    if all(x == c[0] for x in c) and sum(c[0].values()) >= 4 * N: break
    time.sleep(0.5)
print("converged in %.1f s" % (time.time() - t0))
for p, x in zip(P, c): print(p, x)
s = json.loads(req(7183, "GET", "/analytics/settings?scaleguard=true", tok=toks[7183])[1])["replication"]
print("node-3 route:", s["route"], {k: v.get("via") for k, v in s["peers"].items()})
s = json.loads(req(7181, "GET", "/analytics/settings?scaleguard=true", tok=toks[7181])[1])["replication"]
print("node-1 route:", s["route"])
