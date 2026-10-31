"""Ledger cluster end-to-end checks: election, forwarding to master, convergence, failover, quorum loss, joiner, tamper."""
import os
import json, os, subprocess, sys, time, threading, urllib.request
os.chdir(os.path.dirname(os.path.abspath(__file__)))
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ct.py')).read().split('A,B=')[0])

PORTS = {1: 7181, 2: 7182, 3: 7183, 4: 7184}
ok = fail = 0
def check(name, cond, detail=""):
    global ok, fail
    if cond: ok += 1; print("  PASS", name)
    else: fail += 1; print("  FAIL", name, detail)

toks = {}
def tok(n):
    if n not in toks: toks[n] = login(PORTS[n])
    return toks[n]
def cluster(n):
    s, b = req(PORTS[n], "GET", "/cluster?scaleguard=true", tok=tok(n)); return json.loads(b)
def routes(n):
    s, b = req(PORTS[n], "GET", "/config/sourcesystems?scaleguard=true", tok=tok(n))
    return sorted((r["name"], r.get("host"), str(r.get("rateLimit"))) for r in json.loads(b)["sourcesystems"])
def wait(cond, secs=15):
    end = time.time() + secs
    while time.time() < end:
        try:
            if cond(): return True
        except Exception: pass
        time.sleep(0.2)
    return False
def master_of(n):
    try: return cluster(n)["master"]
    except Exception: return None
def sh(cmd): subprocess.run(cmd, shell=True, check=False)
def num(n): return int(n.split('-')[1])

print("1. election")
check("one master agreed by all 3", wait(lambda: len({master_of(i) for i in (1, 2, 3)}) == 1 and master_of(1) is not None))
m = num(master_of(1)); slaves = [i for i in (1, 2, 3) if i != m]
print("   master = node-%d" % m)

print("2. write on a slave goes to the master, read-your-writes, reaches everyone")
req(PORTS[m], "POST", "/config/hostgroups?scaleguard=true", {"groupId": "p1", "scheme": "http", "host": "127.0.0.1", "port": "9200"}, tok(m))
req(PORTS[m], "POST", "/config/targetsystems?scaleguard=true", {"groupId": "tg1", "name": "T1", "scheme": "http", "basePath": "/", "hostGroupId": "p1"}, tok(m))
s = slaves[0]
r = urllib.request.Request(f"http://127.0.0.1:{PORTS[s]}/config/sourcesystems?scaleguard=true", method="POST",
    data=json.dumps({"name": "Svc", "host": "svc.test", "port": "7190", "scheme": "http", "basePath": "/", "target": "tg1"}).encode())
r.add_header("Authorization", tok(s))
with urllib.request.urlopen(r, timeout=10) as resp:
    hdr = resp.headers.get("X-ScaleGuard-Master"); st = resp.status
check("slave answered 200 via master", st == 200 and hdr == "node-%d" % m, f"{st} {hdr}")
check("slave reads its own write immediately", ("Svc", "svc.test", "None") in routes(s), routes(s))
check("all nodes have the route", wait(lambda: all(("Svc", "svc.test", "None") in routes(i) for i in (1, 2, 3)), 5))

print("3. concurrent writes from every node converge")
def burst(n):
    for k in range(10):
        req(PORTS[n], "POST", "/ratelimit?scaleguard=true", {"enabled": True, "perMinute": 1000 + n * 100 + k, "burstSeconds": 10}, tok(n))
        req(PORTS[n], "POST", "/config/sourcesystems?scaleguard=true",
            {"name": f"R{n}-{k}", "host": f"r{n}{k}.test", "port": "7190", "scheme": "http", "basePath": "/", "target": "tg1"}, tok(n))
ts = [threading.Thread(target=burst, args=(i,)) for i in (1, 2, 3)]; [t.start() for t in ts]; [t.join() for t in ts]
same = wait(lambda: routes(1) == routes(2) == routes(3) and len(routes(1)) == 31, 10)
check("31 routes identical on all nodes", same, [len(routes(i)) for i in (1, 2, 3)])
def rl(n): return json.loads(req(PORTS[n], "GET", "/ratelimit?scaleguard=true", tok=tok(n))[1])["settings"]["perMinute"]
check("rate-limit setting identical", wait(lambda: rl(1) == rl(2) == rl(3), 5), [rl(i) for i in (1, 2, 3)])
check("ledgers in sync", wait(lambda: all(cluster(i)["ledger"]["inSync"] for i in (1, 2, 3)), 5))
heads = [json.dumps(cluster(i)["ledger"]["chains"], sort_keys=True).replace('"self": true', '"self": false') for i in (1, 2, 3)]
check("identical chain heads", len(set(heads)) == 1)
writers = {c["node"] for c in cluster(1)["ledger"]["chains"] if c["seq"] > 1}
check("config writes were made by the master only", writers == {"node-%d" % m}, writers)

print("4. verification")
for i in (1, 2, 3):
    v = json.loads(req(PORTS[i], "GET", "/cluster/ledger/verify?scaleguard=true", tok=tok(i))[1])
    check(f"node-{i} chains verify", v["ok"], v)

print("5. proxying works on every node")
def get(port):
    rq = urllib.request.Request(f"http://127.0.0.1:{port}/small", headers={"Host": "svc.test:7190"})
    try: return urllib.request.urlopen(rq, timeout=5).status
    except urllib.error.HTTPError as e: return e.code
check("route port answers", get(7190) == 200)

print("6. master dies: a new one is elected, writes keep working, old master rejoins as follower")
old = m
sh(f"./kill9.sh {old}")
t0 = time.time()
rest = [i for i in (1, 2, 3) if i != old]
ok_new = wait(lambda: master_of(rest[0]) not in (None, "node-%d" % old) and master_of(rest[0]) == master_of(rest[1]), 20)
check("new master elected", ok_new, [master_of(i) for i in rest])
print("   failover took %.1f s; new master %s" % (time.time() - t0, master_of(rest[0])))
m = num(master_of(rest[0])); s = [i for i in rest if i != m][0]
st, _ = req(PORTS[s], "POST", "/config/sourcesystems?scaleguard=true", {"name": "AfterFailover", "host": "af.test", "port": "7190", "scheme": "http", "basePath": "/", "target": "tg1"}, tok(s))
check("write via slave after failover", st == 200, st)
sh(f"./start.sh {old} {PORTS[old]} " + ",".join(str(PORTS[i]) for i in (1, 2, 3) if i != old))
toks.pop(old, None)
check("old master back as follower with the new writes", wait(lambda: master_of(old) == "node-%d" % m and ("AfterFailover", "af.test", "None") in routes(old), 40))

print("7. quorum lost: no master, writes refused, traffic still served")
others = [i for i in (1, 2, 3) if i != s]
for i in others: sh(f"./kill9.sh {i}")
check("survivor has no master", wait(lambda: master_of(s) is None, 15), master_of(s))
st, b = req(PORTS[s], "POST", "/ratelimit?scaleguard=true", {"enabled": True, "perMinute": 5}, tok(s))
check("write refused with 503", st == 503, (st, b))
check("traffic still proxied", get(7190) == 200)
for i in others: sh(f"./start.sh {i} {PORTS[i]} " + ",".join(str(PORTS[j]) for j in (1, 2, 3) if j != i)); toks.pop(i, None)
check("master again after nodes return", wait(lambda: all(master_of(i) is not None for i in (1, 2, 3)) and len({master_of(i) for i in (1, 2, 3)}) == 1, 40))
m = num(master_of(1))

print("8. tampered copy is rejected; a new node joins with only one seed and gets everything")
c = cluster(m)["ledger"]
longest = max(c["chains"], key=lambda x: x["seq"])
owner = num(longest["node"])
st, b = req(PORTS[owner], "POST", "/cluster/ledger/tamper?scaleguard=true", {"origin": longest["origin"], "seq": 2, "row": "{\"evil\":1}"}, tok(owner))
check("tamper hook applied", st == 200, (st, b))
v = json.loads(req(PORTS[owner], "GET", "/cluster/ledger/verify?scaleguard=true", tok=tok(owner))[1])
check("verify catches the edited entry", not v["ok"] and any("modified" in (ch.get("problem") or "") for ch in v["chains"]), v)
sh("./start.sh 4 7184 %d" % PORTS[owner])
check("node-4 joined and has every route", wait(lambda: routes(4) == routes(m), 40), (len(routes(4)), len(routes(m))))
c4 = cluster(4)
check("node-4 adopted the cluster (did not baseline its defaults)", c4["ledger"]["genesis"] == "joined", c4["ledger"]["genesis"])
check("node-4 refused the tampered copy", "tampered" in str(c4["ledger"].get("lastPullError")) or "tampered" in str(c4["ledger"].get("rejected")), (c4["ledger"].get("lastPullError"), c4["ledger"].get("rejected")))
v4 = json.loads(req(7184, "GET", "/cluster/ledger/verify?scaleguard=true", tok=tok(4))[1])
check("node-4's copy verifies", v4["ok"], v4)
check("everyone sees 4 nodes", wait(lambda: all(cluster(i)["liveNodes"] == 4 for i in (1, 2, 3, 4)), 10), [cluster(i)["liveNodes"] for i in (1, 2, 3, 4)])

print(f"\n{ok} passed, {fail} failed")
