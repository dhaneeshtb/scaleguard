"""One client hammering a 2-node cluster through both nodes: how close does the cluster hold it to its limit?"""
import os
PM = int(__import__("os").environ.get("PM", "600"))
import json, urllib.request, time, threading, sys, os
os.chdir(os.path.dirname(os.path.abspath(__file__)))
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ct.py')).read().split('A,B=')[0])
A, B = 7181, 7182
ta, tb = login(A), login(B)
def setup_route():
    req(A, "POST", "/config/hostgroups?scaleguard=true", {"groupId": "p1", "scheme": "http", "host": "127.0.0.1", "port": "9200"}, ta)
    req(A, "POST", "/config/targetsystems?scaleguard=true", {"groupId": "tg1", "name": "T1", "scheme": "http", "basePath": "/", "hostGroupId": "p1"}, ta)
    req(A, "POST", "/config/sourcesystems?scaleguard=true", {"name": "Svc", "host": "svc.test", "port": "7190", "scheme": "http", "basePath": "/", "target": "tg1"}, ta)
def run(mode, seconds, split, client):
    s, b = req(A, "POST", "/ratelimit?scaleguard=true", {"enabled": True, "perMinute": PM, "burstSeconds": 10, "clusterMode": mode}, ta)
    assert s == 200, b
    time.sleep(3)
    end = time.time() + seconds; ok = [0]; lim = [0]; per_sec = {}; lock = threading.Lock()
    def worker(port):
        while time.time() < end:
            r = urllib.request.Request(f"http://127.0.0.1:{port}/small", headers={"Host": "svc.test:7190", "X-Forwarded-For": client})
            try:
                urllib.request.urlopen(r, timeout=5).read(); code = 200
            except urllib.error.HTTPError as e: code = e.code
            except Exception: continue
            with lock:
                if code == 200:
                    ok[0] += 1; sec = int(time.time() - (end - seconds)); per_sec[sec] = per_sec.get(sec, 0) + 1
                elif code == 429: lim[0] += 1
    ts = [threading.Thread(target=worker, args=(A if i % 10 < split * 10 else B,)) for i in range(10)]
    t0 = time.time(); [t.start() for t in ts]; [t.join() for t in ts]; el = time.time() - t0
    ideal = PM/6 + PM/60 * el
    print(f"{mode:7s} split {int(split*100)}/{int((1-split)*100)}: admitted {ok[0]:4d} (ideal ≈ {ideal:.0f}, {100*(ok[0]-ideal)/ideal:+.0f}%), refused {lim[0]}, per second: {[per_sec.get(i,0) for i in range(0, int(seconds))]}")
if sys.argv[1:] == ["setup"]: setup_route()
else:
    n = 0
    for mode in sys.argv[1].split(","):
        for split in (0.5, 0.8):
            n += 1; run(mode, 12, split, f"10.9.{n}.{int(time.time())%250}")
