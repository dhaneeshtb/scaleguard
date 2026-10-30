"""End-to-end OAuth 2.0 checks (44). Needs a ScaleGuard with route ROUTE_ID (default s1, e.g. LbHarness) in front of a
backend that answers GET /small with 200. The script creates its own app and clients and protects the route.
  SG_ADMIN_PASSWORD=… python3 py/oauth_test.py
Env: SG_PROXY_URL (http://localhost:8080), SG_ADMIN_URL (http://localhost:8081), SG_ADMIN_USER (admin), ROUTE_ID (s1)."""
import json, base64, urllib.request, urllib.parse, time, sys, os
P=os.environ.get("SG_PROXY_URL","http://localhost:8080"); A=os.environ.get("SG_ADMIN_URL","http://localhost:8081")

def _post(url, body, headers):
    r=urllib.request.Request(url, data=json.dumps(body).encode(), method="POST", headers=headers)
    with urllib.request.urlopen(r) as resp: return json.loads(resp.read().decode())

_T=_post(A+"/signin?scaleguard=true", {"username":os.environ.get("SG_ADMIN_USER","admin"),"password":os.environ["SG_ADMIN_PASSWORD"]}, {})["token"]
_H={"Authorization":_T}
_app=_post(A+"/apps?scaleguard=true", {"name":"oauth-test-%d" % int(time.time()), "description":"oauth_test.py"}, _H)
APP=_app["id"]
_c1=_post(A+"/clients?scaleguard=true", {"name":"billing-svc","appid":APP,"scopes":"orders:read orders:write"}, _H)
_c2=_post(A+"/clients?scaleguard=true", {"name":"reporting","appid":APP,"scopes":["orders:read"],"refreshTokens":False,"accessTokenTtlSeconds":120}, _H)
c1=_c1["credentials"]; c2=_c2["credentials"]; CID1=_c1["client"]["id"]
_post(A+"/config/sourcesystems?scaleguard=true", {"id":os.environ.get("ROUTE_ID","s1"),"secappid":APP,"oauthScopes":"orders:read"}, _H)
time.sleep(0.5)
ok=0; bad=0
def req(method,url,data=None,headers={}):
    r=urllib.request.Request(url,data=data,method=method,headers=headers)
    try:
        with urllib.request.urlopen(r) as resp: return resp.status, dict(resp.headers), resp.read().decode()
    except urllib.error.HTTPError as e: return e.code, dict(e.headers), e.read().decode()
def check(name,cond,extra=""):
    global ok,bad
    print(("PASS " if cond else "FAIL ")+name+(("  "+extra) if not cond else "")); 
    if cond: ok+=1
    else: bad+=1
def basic(c): return "Basic "+base64.b64encode((urllib.parse.quote(c['client_id'],safe='')+":"+urllib.parse.quote(c['client_secret'],safe='')).encode()).decode()
form={"Content-Type":"application/x-www-form-urlencoded"}

s,h,b=req("GET",P+"/small"); check("no token → 401", s==401 and 'Bearer realm' in h.get('WWW-Authenticate',''), f"{s} {h.get('WWW-Authenticate')} {b}")
s,h,b=req("GET",P+"/.well-known/oauth-authorization-server"); m=json.loads(b) if s==200 else {}
check("discovery metadata", s==200 and m.get('token_endpoint')=="http://localhost:8080/oauth2/token" and 'client_credentials' in m['grant_types_supported'], b)
s,h,b=req("GET",P+"/oauth2/jwks"); check("jwks has RS256 key", s==200 and json.loads(b)['keys'][0]['alg']=='RS256', b)

# client credentials, basic auth
s,h,b=req("POST",P+"/oauth2/token",b"grant_type=client_credentials",{**form,"Authorization":basic(c1)}); t=json.loads(b)
check("token via Basic", s==200 and t['token_type']=='Bearer' and t['expires_in']==3600 and 'refresh_token' in t and t['scope']=="orders:read orders:write", b)
check("no-store on token response", h.get('Cache-Control')=='no-store')
at=t['access_token']; rt=t['refresh_token']
hdr,pl,_=at.split('.'); H=json.loads(base64.urlsafe_b64decode(hdr+'==')); C=json.loads(base64.urlsafe_b64decode(pl+'=='))
check("JWT header RS256 at+jwt kid", H['alg']=='RS256' and H['typ']=='at+jwt' and 'kid' in H, H)
check("JWT claims", C['sub']==c1['client_id'] and C['client_id']==c1['client_id'] and C['aud']==APP and C['iss']=="http://localhost:8080" and C['exp']-C['iat']==3600 and 'jti' in C, C)

s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+at}); check("token accepted on route", s==200 and b.strip()=="hello world", f"{s} {b}")
# client_secret_post + scope narrowing
s,h,b=req("POST",P+"/oauth2/token",urllib.parse.urlencode({"grant_type":"client_credentials","client_id":c1['client_id'],"client_secret":c1['client_secret'],"scope":"orders:write"}).encode(),form)
t2=json.loads(b); check("token via form + narrowed scope", s==200 and t2['scope']=="orders:write", b)
s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+t2['access_token']}); check("missing route scope → 403 insufficient_scope", s==403 and 'insufficient_scope' in h.get('WWW-Authenticate',''), f"{s} {h.get('WWW-Authenticate')}")
s,h,b=req("POST",P+"/oauth2/token",b"grant_type=client_credentials&scope=admin",{**form,"Authorization":basic(c1)}); check("scope not allowed → invalid_scope", s==400 and json.loads(b)['error']=='invalid_scope', b)
s,h,b=req("POST",P+"/oauth2/token",b"grant_type=client_credentials",{**form,"Authorization":basic({**c1,'client_secret':'wrong'})}); check("bad secret → 401 invalid_client", s==401 and json.loads(b)['error']=='invalid_client' and 'Basic' in h.get('WWW-Authenticate',''), b)
s,h,b=req("POST",P+"/oauth2/token",b"grant_type=password&username=a&password=b",{**form,"Authorization":basic(c1)}); check("unsupported grant", s==400 and json.loads(b)['error']=='unsupported_grant_type', b)
s,h,b=req("GET",P+"/oauth2/token"); check("GET token → 405", s==405, s)
# tampered token
tam=at[:-4]+("AAAA" if not at.endswith("AAAA") else "BBBB")
s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+tam}); check("tampered signature → 401 invalid_token", s==401 and 'invalid_token' in h.get('WWW-Authenticate',''), s)
# alg none attack
none=base64.urlsafe_b64encode(json.dumps({"alg":"none","typ":"at+jwt"}).encode()).decode().rstrip('=')+"."+pl+"."
s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+none}); check("alg=none rejected", s==401, s)
# refresh
s,h,b=req("POST",P+"/oauth2/token",urllib.parse.urlencode({"grant_type":"refresh_token","refresh_token":rt}).encode(),{**form,"Authorization":basic(c1)}); r1=json.loads(b)
check("refresh → new access + rotated refresh", s==200 and r1['refresh_token']!=rt and r1['access_token']!=at, b)
s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+r1['access_token']}); check("refreshed token works", s==200)
s,h,b=req("POST",P+"/oauth2/token",urllib.parse.urlencode({"grant_type":"refresh_token","refresh_token":rt}).encode(),{**form,"Authorization":basic(c1)})
check("reusing old refresh token → invalid_grant", s==400 and json.loads(b)['error']=='invalid_grant', b)
s,h,b=req("POST",P+"/oauth2/token",urllib.parse.urlencode({"grant_type":"refresh_token","refresh_token":r1['refresh_token']}).encode(),{**form,"Authorization":basic(c1)})
check("reuse revoked the whole chain (new one dead too)", s==400, b)
# refresh by another client
s,h,b=req("POST",P+"/oauth2/token",b"grant_type=client_credentials",{**form,"Authorization":basic(c1)}); r2=json.loads(b)
s,h,b=req("POST",P+"/oauth2/token",urllib.parse.urlencode({"grant_type":"refresh_token","refresh_token":r2['refresh_token']}).encode(),{**form,"Authorization":basic(c2)})
check("refresh token of another client rejected", s==400, b)
# client2: no refresh tokens, ttl 120
s,h,b=req("POST",P+"/oauth2/token",b"grant_type=client_credentials",{**form,"Authorization":basic(c2)}); t3=json.loads(b)
check("client without refresh tokens: none issued, ttl 120", s==200 and 'refresh_token' not in t3 and t3['expires_in']==120, b)
# JSON body convenience
s,h,b=req("POST",P+"/oauth2/token",json.dumps({"grant_type":"client_credentials","client_id":c2['client_id'],"client_secret":c2['client_secret']}).encode(),{"Content-Type":"application/json"})
check("JSON body accepted", s==200, b)
# introspection by same-app client
s,h,b=req("POST",P+"/oauth2/introspect",urllib.parse.urlencode({"token":r2['access_token']}).encode(),{**form,"Authorization":basic(c2)}); i=json.loads(b)
check("introspect active", s==200 and i['active'] and i['client_id']==c1['client_id'] and i['scope']=="orders:read orders:write", b)
# revoke access token
s,h,b=req("POST",P+"/oauth2/revoke",urllib.parse.urlencode({"token":r2['access_token']}).encode(),{**form,"Authorization":basic(c1)}); check("revoke → 200", s==200, b)
s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+r2['access_token']}); check("revoked access token → 401", s==401, s)
s,h,b=req("POST",P+"/oauth2/introspect",urllib.parse.urlencode({"token":r2['access_token']}).encode(),{**form,"Authorization":basic(c2)}); check("introspect revoked → inactive", json.loads(b)['active']==False, b)
# revoke refresh token
s,h,b=req("POST",P+"/oauth2/revoke",urllib.parse.urlencode({"token":r2['refresh_token'],"token_type_hint":"refresh_token"}).encode(),{**form,"Authorization":basic(c1)})
s,h,b=req("POST",P+"/oauth2/token",urllib.parse.urlencode({"grant_type":"refresh_token","refresh_token":r2['refresh_token']}).encode(),{**form,"Authorization":basic(c1)}); check("revoked refresh token → invalid_grant", s==400, b)
# legacy headers
s,h,b=req("GET",P+"/small",headers={"client_id":c1['client_id'],"client_secret":c1['client_secret']}); check("legacy client_id/client_secret headers still accepted", s==200, s)
s,h,b=req("GET",P+"/small",headers={"client_id":c1['client_id'],"client_secret":"nope"}); check("legacy wrong secret → 401", s==401, s)
# spoofed identity header stripped: backend nginx doesn't echo; can't verify here
# admin: rotate secret kills old tokens
T=_T; cid=CID1
s,h,b=req("POST",P+"/oauth2/token",b"grant_type=client_credentials",{**form,"Authorization":basic(c1)}); before=json.loads(b)['access_token']
s,h,b=req("POST",A+f"/clients/{cid}/secret?scaleguard=true",b"{}",{"Authorization":T}); rot=json.loads(b)
check("rotate secret returns new secret once", s==201 and rot['client_secret']!=c1['client_secret'], b)
s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+before}); check("token issued before rotation → 401", s==401, s)
s,h,b=req("POST",P+"/oauth2/token",b"grant_type=client_credentials",{**form,"Authorization":basic(c1)}); check("old secret rejected after rotation", s==401, s)
c1n={**c1,"client_secret":rot['client_secret']}
s,h,b=req("POST",P+"/oauth2/token",b"grant_type=client_credentials",{**form,"Authorization":basic(c1n)}); nt=json.loads(b)['access_token']
s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+nt}); check("new secret + immediate token works (same second)", s==200, s)
# list never leaks secrets
s,h,b=req("GET",A+"/clients?scaleguard=true",headers={"Authorization":T}); check("client list has no secrets", s==200 and 'sgs_' not in b and 'sha256$' not in b and 'activeRefreshTokens' in b, b[:300])
# disable client
s,h,b=req("POST",A+f"/clients/{cid}?scaleguard=true",json.dumps({"enabled":False}).encode(),{"Authorization":T})
s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+nt}); check("disabled client's token → 401", s==401, s)
s,h,b=req("POST",A+f"/clients/{cid}?scaleguard=true",json.dumps({"enabled":True}).encode(),{"Authorization":T})
s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+nt}); check("re-enabled → works", s==200, s)
# key rotation keeps old tokens valid
s,h,b=req("POST",A+"/oauth/keys/rotate?scaleguard=true",b"{}",{"Authorization":T}); keys=json.loads(b)['keys']
check("key rotation: 2 keys, 1 active", s==200 and len(keys)==2 and sum(k['active'] for k in keys)==1, b)
s,h,b=req("GET",P+"/small",headers={"Authorization":"Bearer "+nt}); check("token signed by previous key still valid", s==200, s)
s,h,b=req("POST",P+"/oauth2/token",b"grant_type=client_credentials",{**form,"Authorization":basic(c1n)}); kid=json.loads(base64.urlsafe_b64decode(json.loads(b)['access_token'].split('.')[0]+'=='))['kid']
check("new tokens use new key", kid==[k['kid'] for k in keys if k['active']][0], kid)
# settings validation
s,h,b=req("POST",A+"/oauth/settings?scaleguard=true",json.dumps({"accessTokenTtlSeconds":5}).encode(),{"Authorization":T}); check("settings validation → 400", s==400, b)
s,h,b=req("POST",A+"/oauth/settings?scaleguard=true",json.dumps({"pathPrefix":"/apps"}).encode(),{"Authorization":T}); check("prefix clash → 400", s==400, b)
# admin introspection
s,h,b=req("POST",A+"/oauth2/introspect",urllib.parse.urlencode({"token":nt}).encode(),{**form,"Authorization":"Bearer "+T}); check("admin can introspect", s==200 and json.loads(b)['active'], b)
# endpoints on admin port
s,h,b=req("POST",A+"/oauth2/token",b"grant_type=client_credentials",{**form,"Authorization":basic(c1n)}); check("token endpoint on admin port too", s==200, b)
print(f"\n{ok} passed, {bad} failed")
# leave the route open again
_post(A+"/config/sourcesystems?scaleguard=true", {"id":os.environ.get("ROUTE_ID","s1"),"secappid":"","oauthScopes":""}, _H)
urllib.request.urlopen(urllib.request.Request(A+f"/apps/{APP}?scaleguard=true", method="DELETE", headers=_H))
sys.exit(1 if bad else 0)
