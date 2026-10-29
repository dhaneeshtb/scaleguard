import socket, struct, sys, time, threading, random
import os
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from dnsproto import query, parse, rdata_text

SERVER = ("127.0.0.1", int(os.environ.get("SG_DNS_PORT", "5353")))
OK = []; FAIL = []
def check(name, cond, detail=""):
    (OK if cond else FAIL).append(name)
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  -> " + str(detail)))

def udp(name, t, rd=True, edns=None, src=None, timeout=3):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if src: s.bind((src, 0))
    s.settimeout(timeout)
    q = query(name, t, rd=rd, edns=edns)
    s.sendto(q, SERVER if not src else (src, 5353))
    try:
        data, _ = s.recvfrom(65535)
    except socket.timeout:
        return None
    finally:
        s.close()
    r = parse(data); r["size"] = len(data); r["sent_id"] = struct.unpack("!H", q[:2])[0]
    return r

def tcp(name, t):
    s = socket.create_connection(SERVER, timeout=5)
    q = query(name, t)
    s.sendall(struct.pack("!H", len(q)) + q)
    l = struct.unpack("!H", s.recv(2))[0]; data = b""
    while len(data) < l: data += s.recv(l - len(data))
    s.close(); return parse(data)

def answers(r, t=None): return [rdata_text(x) for x in r["an"] if t is None or x["type"] == t]
def upstream_count(): time.sleep(0.3); return int(open(os.path.join(HERE, "upstream_count")).read())

print("--- authoritative zone")
r = udp("acme.test.", "A"); check("base zone A -> advertised IPv4 only", r and answers(r) == ["203.0.113.10"] and r["aa"], r and answers(r))
r = udp("acme.test.", "AAAA"); check("base zone AAAA -> advertised IPv6 only", r and answers(r) == ["2001:db8::1"], r and answers(r))
r = udp("API.Acme.Test.", "A"); check("case-insensitive record A", r and answers(r) == ["10.1.2.3"], r and answers(r))
r = udp("api.acme.test.", "AAAA"); check("record AAAA from IPv6 value", r and answers(r) == ["2001:db8::10"], r and answers(r))
r = udp("_acme-challenge.acme.test.", "TXT"); check("TXT for ACME token", r and answers(r) == ["token-abc123"], r and answers(r))
r = udp("www.acme.test.", "A"); check("CNAME chased inside zone", r and [x["type"] for x in r["an"]] == ["CNAME", "A"] and answers(r, "A") == ["10.1.2.3"], r and [(x["type"], rdata_text(x)) for x in r["an"]])
r = udp("acme.test.", "MX"); check("MX record", r and answers(r) == ["10 mx.acme.test."], r and answers(r))
r = udp("api.acme.test.", "TXT"); check("NODATA: NOERROR, no answer, SOA in authority", r and r["rcode"] == 0 and not r["an"] and r["ns"] and r["ns"][0]["type"] == "SOA", r)
r = udp("acme.test.", "SOA"); check("SOA at apex", r and r["an"] and r["an"][0]["type"] == "SOA" and rdata_text(r["an"][0]).startswith("ns1.acme.test. hostmaster.acme.test."), r and answers(r))
r = udp("acme.test.", "NS"); check("NS at apex", r and answers(r) == ["ns1.acme.test."], r and answers(r))
r = udp("deep.sub.acme.test.", "A"); check("names under a base zone answered", r and answers(r) == ["203.0.113.10"] and r["aa"], r and answers(r))
r = udp("acme.test.", "A"); check("response id matches query id", r and r["id"] == r["sent_id"])

print("--- zone matching bug (old code matched by plain endsWith)")
r = udp("evilacme.test.", "A"); check("evilacme.test is NOT treated as acme.test", r and not r["aa"] and "203.0.113.10" not in answers(r), r and (r["aa"], answers(r)))

print("--- forwarding, decompression, cache")
c0 = upstream_count()
r = udp("example.org.", "A"); check("forwarded A", r and answers(r) == ["93.184.216.34"] and r["ra"] and not r["aa"], r and answers(r))
r = udp("example.org.", "MX"); check("forwarded MX with compressed names decoded correctly", r and sorted(answers(r)) == ["10 mail.example.org.", "20 mail2.example.org."], r and answers(r))
r = udp("example.org.", "SOA"); check("forwarded SOA with compressed names", r and answers(r) and answers(r)[0].startswith("ns.example.org. admin.example.org. 7"), r and answers(r))
c1 = upstream_count()
check("3 upstream calls for 3 new questions", c1 - c0 == 3, c1 - c0)
time.sleep(1.2)
r = udp("example.org.", "A"); r2 = udp("example.org.", "MX")
check("repeat queries served from cache (no upstream call)", upstream_count() == c1, upstream_count() - c1)
check("cached TTL counts down", r and r["an"][0]["ttl"] < 300, r and r["an"][0]["ttl"])

r = udp("missing.nx.test.", "A"); check("NXDOMAIN passed through with SOA", r and r["rcode"] == 3 and r["ns"], r)
c2 = upstream_count(); r = udp("missing.nx.test.", "A")
check("NXDOMAIN negatively cached", upstream_count() == c2 and r["rcode"] == 3)

t0 = time.time(); r = udp("fail.test.", "A"); check("upstream SERVFAIL -> SERVFAIL", r and r["rcode"] == 2, r)
t0 = time.time(); r = udp("slow.test.", "A", timeout=6); dt = time.time() - t0
check("dead upstream -> SERVFAIL after retries (not a hang)", r and r["rcode"] == 2 and dt < 3, (r and r["rcode"], round(dt, 2)))

print("--- coalescing")
c3 = upstream_count(); name = "burst-%d.coalesce.test." % random.randint(1, 1_000_000)
res = []
ths = [threading.Thread(target=lambda: res.append(udp(name, "A"))) for _ in range(40)]
[t.start() for t in ths]; [t.join() for t in ths]
check("40 identical concurrent queries -> 1 upstream query", upstream_count() - c3 == 1 and all(x and x["rcode"] == 0 for x in res), (upstream_count() - c3, sum(1 for x in res if x)))

print("--- truncation, EDNS, TCP")
r = udp("big.test.", "TXT"); check("big answer over plain UDP -> TC set, no records", r and r["tc"] and not r["an"], r and (r["tc"], len(r["an"])))
r = udp("big.test.", "TXT", edns=4096); check("EDNS client gets OPT back; still TC above 1232 bytes", r and r["tc"] and any(x["type"] == "OPT" for x in r["ar"]), r and (r["tc"], r["size"]))
r = tcp("big.test.", "TXT"); check("same question over TCP -> all 40 records (via upstream TCP fallback)", len(r["an"]) == 40, len(r["an"]))
r = tcp("api.acme.test.", "A"); check("authoritative over TCP", answers(r) == ["10.1.2.3"])

print("--- malformed input")
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
for junk in [b"\x00", b"\xff" * 30, query("x.test.", "A")[:15], b"\x12\x34\x81\x80" + b"\x00" * 8]:
    s.sendto(junk, SERVER)
s.close(); time.sleep(0.3)
r = udp("acme.test.", "A"); check("server still answers after malformed packets", r and answers(r) == ["203.0.113.10"])

print("--- outside clients (non-loopback source address)")
ext = [ip for ip in socket.gethostbyname_ex(socket.gethostname())[2] if not ip.startswith("127.")]
if not ext:
    print("SKIP no non-loopback address in this container")
else:
    src = ext[0]
    r = udp("example.org.", "A", src=src); check("open recursion closed: outside client REFUSED", r and r["rcode"] == 5 and not r["ra"], r and r["rcode"])
    r = udp("api.acme.test.", "A", src=src); check("outside client still gets our zone (authoritative)", r and answers(r) == ["10.1.2.3"])
    got, tc, none = 0, 0, 0
    for i in range(80):
        r = udp("api.acme.test.", "A", src=src, timeout=0.3)
        if r is None: none += 1
        elif r["tc"]: tc += 1
        else: got += 1
    check("rate limit (20/s, burst 20): flood mostly dropped, some TC slips", got <= 26 and none > 20 and tc > 10, dict(answered=got, tc=tc, dropped=none))

print("\n%d passed, %d failed" % (len(OK), len(FAIL)))
if FAIL: print("FAILED:", FAIL)
