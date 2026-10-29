# Fake upstream resolver on 127.0.0.1:5399 (UDP+TCP). Uses name compression in rdata on purpose.
import socketserver, struct, threading, time, sys
import os
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from dnsproto import read_name, T, enc_name

COUNT = {"n": 0}
PTR_Q = b"\xc0\x0c"   # compression pointer to the question name at offset 12

def rr(name_bytes, t, ttl, rdata):
    return name_bytes + struct.pack("!HHIH", T[t], 1, ttl, len(rdata)) + rdata

def answer(data, tcp=False):
    COUNT["n"] += 1
    qid, flags = struct.unpack("!HH", data[:4])
    name, off = read_name(data, 12)
    qtype = struct.unpack("!H", data[off:off+2])[0]
    question = data[12:off+4]
    name = name.lower()
    if name == "slow.test.": return None
    an, ns, rcode, tc = [], [], 0, 0
    if name == "example.org." and qtype == T["A"]:
        an.append(rr(PTR_Q, "A", 300, bytes([93,184,216,34])))
    elif name == "example.org." and qtype == T["MX"]:
        # exchange "mail" + pointer to example.org. -> compressed rdata
        an.append(rr(PTR_Q, "MX", 300, struct.pack("!H", 10) + b"\x04mail" + PTR_Q))
        an.append(rr(PTR_Q, "MX", 300, struct.pack("!H", 20) + b"\x05mail2" + PTR_Q))
    elif name == "example.org." and qtype == T["SOA"]:
        an.append(rr(PTR_Q, "SOA", 300, b"\x02ns" + PTR_Q + b"\x05admin" + PTR_Q + struct.pack("!IIIII", 7, 3600, 600, 86400, 30)))
    elif name == "big.test.":
        for i in range(40):
            s = ("x" * 60 + str(i)).encode()
            an.append(rr(PTR_Q, "TXT", 300, bytes([len(s)]) + s))
        if not tcp: an, tc = [], 1
    elif name.endswith("nx.test."):
        rcode = 3
        ns.append(rr(enc_name("test."), "SOA", 900, enc_name("ns.test.") + enc_name("admin.test.") + struct.pack("!IIIII", 1, 3600, 600, 86400, 30)))
    elif name == "fail.test.":
        rcode = 2
    else:
        an.append(rr(PTR_Q, "CNAME", 120, enc_name("example.org.")))
        an.append(rr(enc_name("example.org."), "A", 300, bytes([93,184,216,34])))
    rflags = 0x8000 | 0x0080 | (flags & 0x0100) | (0x0200 if tc else 0) | rcode
    hdr = struct.pack("!HHHHHH", qid, rflags, 1, len(an), len(ns), 0)
    return hdr + question + b"".join(an) + b"".join(ns)

class U(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        out = answer(data)
        if out: sock.sendto(out, self.client_address)

class TCPH(socketserver.BaseRequestHandler):
    def handle(self):
        l = struct.unpack("!H", self.request.recv(2))[0]
        data = b""
        while len(data) < l: data += self.request.recv(l - len(data))
        out = answer(data, tcp=True)
        if out: self.request.sendall(struct.pack("!H", len(out)) + out)

class TU(socketserver.ThreadingMixIn, socketserver.UDPServer): pass
class TT(socketserver.ThreadingMixIn, socketserver.TCPServer): allow_reuse_address = True

u = TU(("127.0.0.1", 5399), U); t = TT(("127.0.0.1", 5399), TCPH)
threading.Thread(target=u.serve_forever, daemon=True).start()
threading.Thread(target=t.serve_forever, daemon=True).start()
print("upstream ready", flush=True)
while True:
    time.sleep(0.2)
    open(os.path.join(HERE, "upstream_count"), "w").write(str(COUNT["n"]))
