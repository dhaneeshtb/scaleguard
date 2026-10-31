"""DR mode: primary site (nodes 1-3) + DR standby site (nodes 5-6)."""
import os
import json, os, subprocess, time, urllib.request
os.chdir(os.path.dirname(os.path.abspath(__file__)))
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ct.py')).read().split('A,B=')[0])
P = {1: 7181, 2: 7182, 3: 7183, 5: 7185, 6: 7186}
ok = fail = 0
def check(name, cond, detail=""):
    global ok, fail
    if cond: ok += 1; print("  PASS", name)
    else: fail += 1; print("  FAIL", name, detail)
toks = {}
def tok(n):
    if n not in toks: toks[n] = login(P[n])
    return toks[n]
def cl(n): return json.loads(req(P[n], "GET", "/cluster?scaleguard=true", tok=tok(n))[1])
def master(n):
    try: return cl(n)["master"]
    except Exception: return None
def routes(n):
    return sorted(r["name"] for r in json.loads(req(P[n], "GET", "/config/sourcesystems?scaleguard=true", tok=tok(n))[1])["sourcesystems"])
def wait(c, secs=20):
    end = time.time() + secs
    while time.time() < end:
        try:
            if c(): return True
        except Exception: pass
        time.sleep(0.25)
    return False
def sh(c): subprocess.run(c, shell=True)
def get(port):
    rq = urllib.request.Request(f"http://127.0.0.1:{port}/small", headers={"Host": "svc.test:7190"})
    try: return urllib.request.urlopen(rq, timeout=5).status
    except urllib.error.HTTPError as e: return e.code
def route(n, name):
    return req(P[n], "POST", "/config/sourcesystems?scaleguard=true", {"name": name, "host": name.lower() + ".test", "port": "7190",
               "scheme": "http", "basePath": "/", "target": "tg1"}, tok(n))[0]

sh("./stop.sh; rm -rf home* node-*.log")
for n, peers in ((1, "7182,7183"), (2, "7181,7183"), (3, "7181,7182")): sh(f"./start.sh {n} {P[n]} {peers}")
check("primary master elected", wait(lambda: master(1) is not None and master(1) == master(2) == master(3), 30))
m = master(1)
req(P[1], "POST", "/config/hostgroups?scaleguard=true", {"groupId": "p1", "scheme": "http", "host": "127.0.0.1", "port": "9200"}, tok(1))
req(P[1], "POST", "/config/targetsystems?scaleguard=true", {"groupId": "tg1", "name": "T1", "scheme": "http", "basePath": "/", "hostGroupId": "p1"}, tok(1))
route(1, "Svc")
req(P[1], "POST", "/ratelimit?scaleguard=true", {"enabled": True, "perMinute": 1000000, "burstSeconds": 10}, tok(1))
for i in range(50): get(7181)

print("1. DR standbys join")
for n, peers in ((5, "7181,7182,7183,7186"), (6, "7181,7182,7183,7185")):
    sh(f"EXTRA=-Dscaleguard.cluster.dr=true ./start.sh {n} {P[n]} {peers}")
check("DR nodes have the primary's config", wait(lambda: routes(5) == routes(1) == routes(6), 40), (routes(5), routes(1)))
c5 = cl(5)
check("node-5 is a standby following the primary master", c5["ledger"]["dr"]["standby"] and c5["master"] == m, (c5["ledger"]["dr"], c5["master"]))
check("master unchanged, DR didn't take part", master(1) == m and cl(1)["election"]["voters"] == 3)
check("rate-limit budgets per site (primary 3 nodes, DR 2)", cl(1)["liveNodes"] == 3 and cl(5)["liveNodes"] == 2, (cl(1)["liveNodes"], cl(5)["liveNodes"]))
check("lag is zero", wait(lambda: cl(5)["ledger"]["dr"]["lagEntries"] == 0, 10))

print("2. changes and analytics reach the DR site")
route(2, "Later")
check("new route on DR", wait(lambda: "Later" in routes(5) and "Later" in routes(6), 10))
def an(n):
    j = json.loads(req(P[n], "POST", "/analytics/query?scaleguard=true", {"sql": "SELECT count(*) FROM access_log WHERE path='/small'"}, tok(n))[1])
    return j["rows"][0][0]
check("analytics on DR match the primary", wait(lambda: an(5) == an(1) == an(6) and an(1) >= 45, 20), (an(1), an(5), an(6)))
st = route(5, "FromDR")
check("a change made on a DR node is applied by the primary master", st == 200 and wait(lambda: "FromDR" in routes(1), 5), st)

print("3. primary site lost")
for n in (1, 2, 3): sh(f"./kill9.sh {n}")
check("DR sees no master", wait(lambda: master(5) is None, 15))
s, b = req(P[5], "POST", "/ratelimit?scaleguard=true", {"enabled": True, "perMinute": 1000000, "burstSeconds": 10}, tok(5))
check("DR refuses changes with a promote hint", s == 503 and "promote" in b, (s, b))
check("DR still serves traffic", get(7185) == 200)
check("no election among standbys", cl(5)["election"]["role"] == "follower" and cl(6)["election"]["role"] == "follower")

print("4. promote the DR site")
t0 = time.time()
s, b = req(P[5], "POST", "/cluster/dr/promote?scaleguard=true", {}, tok(5))
check("promote accepted", s == 200, (s, b))
check("DR site elects its own master", wait(lambda: master(5) in ("node-5", "node-6") and master(5) == master(6), 20), (master(5), master(6)))
print("   new master %s after %.1f s" % (master(5), time.time() - t0))
check("node-6 also promoted", not cl(6)["ledger"]["dr"]["standby"])
st = route(6, "AfterPromotion")
check("changes accepted on the promoted site", st == 200 and wait(lambda: "AfterPromotion" in routes(5), 5), st)
check("nothing lost: every primary route present", all(r in routes(5) for r in ("Svc", "Later", "FromDR")))

print("5. promotion survives a restart")
sh("./kill9.sh 6"); time.sleep(1); sh(f"EXTRA=-Dscaleguard.cluster.dr=true ./start.sh 6 7186 7181,7182,7183,7185"); toks.pop(6, None)
check("node-6 restarts as a primary node, not a standby", wait(lambda: not cl(6)["ledger"]["dr"]["standby"] and master(6) == master(5), 40))

print("6. an old primary node comes back")
sh("./start.sh 1 7181 7182,7183"); toks.pop(1, None)
check("old node follows the promoted master", wait(lambda: master(1) == master(5), 40), (master(1), master(5)))
check("and gets the changes made after promotion", wait(lambda: "AfterPromotion" in routes(1), 20))

print(f"\n{ok} passed, {fail} failed")
