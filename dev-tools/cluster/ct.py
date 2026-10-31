import json, urllib.request, time, sys
def req(port, method, path, body=None, tok=None, host=None):
    r = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method, data=None if body is None else json.dumps(body).encode())
    if tok: r.add_header("Authorization", tok)
    if host: r.add_header("Host", host)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp: return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e: return e.code, e.read().decode()
def login(port):
    return json.loads(req(port,"POST","/signin?scaleguard=true",{"username":"admin","password":"TestPass123"})[1])["token"]
