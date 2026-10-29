# Tiny DNS packet helpers for tests (hand-rolled, deliberately uses name compression).
import struct, random
T = {"A":1,"NS":2,"CNAME":5,"SOA":6,"MX":15,"TXT":16,"AAAA":28,"ANY":255,"OPT":41}
TN = {v:k for k,v in T.items()}

def enc_name(n):
    out = b""
    for l in n.rstrip(".").split("."):
        if l: out += bytes([len(l)]) + l.encode()
    return out + b"\x00"

def read_name(b, off):
    labels, jumped, end = [], False, None
    while True:
        l = b[off]
        if l & 0xC0 == 0xC0:
            if end is None: end = off + 2
            off = ((l & 0x3F) << 8) | b[off+1]; continue
        off += 1
        if l == 0: break
        labels.append(b[off:off+l].decode("latin1")); off += l
    return ".".join(labels) + ".", (end if end is not None else off)

def query(name, qtype, rd=True, edns=None, qid=None):
    qid = random.randint(0, 65535) if qid is None else qid
    flags = 0x0100 if rd else 0
    ar = 1 if edns else 0
    p = struct.pack("!HHHHHH", qid, flags, 1, 0, 0, ar) + enc_name(name) + struct.pack("!HH", T[qtype], 1)
    if edns: p += b"\x00" + struct.pack("!HHIH", 41, edns, 0, 0)
    return p

def parse(b):
    qid, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", b[:12])
    off = 12; qs = []
    for _ in range(qd):
        n, off = read_name(b, off); t, c = struct.unpack("!HH", b[off:off+4]); off += 4; qs.append((n, t, c))
    secs = []
    for cnt in (an, ns, ar):
        rrs = []
        for _ in range(cnt):
            n, off = read_name(b, off)
            t, c, ttl, rl = struct.unpack("!HHIH", b[off:off+10]); off += 10
            rd_ = b[off:off+rl]; start = off; off += rl
            rrs.append(dict(name=n, type=TN.get(t, t), cls=c, ttl=ttl, rdata=rd_, off=start, raw=b))
        secs.append(rrs)
    return dict(id=qid, flags=flags, rcode=flags & 0xF, aa=bool(flags & 0x400), tc=bool(flags & 0x200),
                ra=bool(flags & 0x80), qs=qs, an=secs[0], ns=secs[1], ar=secs[2])

def rdata_text(rr):
    t, d, b = rr["type"], rr["rdata"], rr["raw"]
    if t == "A": return ".".join(str(x) for x in d)
    if t == "AAAA":
        import ipaddress; return str(ipaddress.IPv6Address(d))
    if t == "TXT":
        out, i = [], 0
        while i < len(d): l = d[i]; out.append(d[i+1:i+1+l].decode()); i += 1 + l
        return "".join(out)
    if t in ("CNAME", "NS"): return read_name(b, rr["off"])[0]
    if t == "MX": return f'{struct.unpack("!H", d[:2])[0]} {read_name(b, rr["off"]+2)[0]}'
    if t == "SOA":
        m, o = read_name(b, rr["off"]); r, o = read_name(b, o)
        return f"{m} {r} " + " ".join(str(x) for x in struct.unpack("!IIIII", b[o:o+20]))
    return d.hex()
