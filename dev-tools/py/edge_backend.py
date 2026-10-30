"""Backend for response-relay edge cases (port 9301): /chunked, /redirect, /204, HEAD, POST echo, /die (closes mid-body),
/slow (3 s). Put ScaleGuard in front (LbHarness 127.0.0.1:9301) and check each through the proxy."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import time, json
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self,*a): pass
    def do_HEAD(self):
        self.send_response(200); self.send_header("Content-Length","1234"); self.send_header("X-Head","yes"); self.end_headers()
    def do_POST(self):
        n=int(self.headers.get("Content-Length",0)); b=self.rfile.read(n)
        out=json.dumps({"len":len(b),"expect":self.headers.get("Expect")}).encode()
        self.send_response(200); self.send_header("Content-Length",str(len(out))); self.end_headers(); self.wfile.write(out)
    def do_GET(self):
        p=self.path
        if p=="/chunked":
            self.send_response(200); self.send_header("Transfer-Encoding","chunked"); self.end_headers()
            for i in range(50):
                c=(f"chunk-{i:03d}-"+"x"*1000+"\n").encode()
                self.wfile.write(b"%x\r\n%s\r\n" % (len(c), c)); self.wfile.flush(); time.sleep(0.005)
            self.wfile.write(b"0\r\n\r\n")
        elif p=="/redirect":
            self.send_response(302); self.send_header("Location","http://127.0.0.1:9301/elsewhere"); self.send_header("Content-Length","0"); self.end_headers()
        elif p=="/204":
            self.send_response(204); self.end_headers()
        elif p=="/die":
            self.send_response(200); self.send_header("Content-Length","100000"); self.end_headers()
            self.wfile.write(b"y"*30000); self.wfile.flush(); time.sleep(0.2)
            self.connection.shutdown(2); self.close_connection=True
        elif p.startswith("/slow"):
            time.sleep(3); b=b"slow ok"
            self.send_response(200); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
        else:
            b=b"edge ok"
            self.send_response(200); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
ThreadingHTTPServer(("127.0.0.1",9301),H).serve_forever()
