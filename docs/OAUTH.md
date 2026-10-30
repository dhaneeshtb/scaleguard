# Protecting APIs with OAuth 2.0

ScaleGuard has a built-in OAuth 2.0 authorization server for machine-to-machine access. You create an **app** for each
API you protect and one **client** per service that calls it. A client exchanges its ID and secret for a short-lived
**access token** and sends that token with every request. ScaleGuard checks the token at the edge, so your backend only
sees authenticated traffic.

| | |
|---|---|
| Grants | Client credentials ([RFC 6749 §4.4](https://www.rfc-editor.org/rfc/rfc6749#section-4.4)) and refresh token ([§6](https://www.rfc-editor.org/rfc/rfc6749#section-6)) |
| Client authentication | `client_secret_basic` (HTTP Basic, recommended) or `client_secret_post` (form fields) |
| Access tokens | JWT, RS256-signed, [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068) profile (`typ: at+jwt`) |
| Refresh tokens | Opaque, single-use, rotated on every use; reusing one revokes the whole chain ([RFC 9700 §4.14](https://www.rfc-editor.org/rfc/rfc9700#section-4.14)) |
| Sending tokens | `Authorization: Bearer …` ([RFC 6750](https://www.rfc-editor.org/rfc/rfc6750)) |
| Revocation | [RFC 7009](https://www.rfc-editor.org/rfc/rfc7009), for access and refresh tokens |
| Introspection | [RFC 7662](https://www.rfc-editor.org/rfc/rfc7662) |
| Public keys | JWK Set ([RFC 7517](https://www.rfc-editor.org/rfc/rfc7517)), so backends can verify tokens themselves |
| Discovery | [RFC 8414](https://www.rfc-editor.org/rfc/rfc8414) metadata at `/.well-known/oauth-authorization-server` |

Secrets are never stored in readable form. ScaleGuard keeps a salted SHA-256 hash, and the secret is shown exactly
once, when the client is created or its secret is rotated.

## 1. Set it up in the admin UI

1. Open **App Security**. Create an app, e.g. `orders-api`.
2. Click **Add client**. Name it after the calling service (`billing-svc`), list the scopes it may use
   (`orders:read orders:write`), and choose its expiry, token lifetime and whether it gets refresh tokens.
3. A dialog shows the **client ID and secret**. Copy them or click **Download JSON** / **Download .env**. This is the
   only time the secret is shown. The dialog also has ready-to-run samples for curl, Python, Node.js, Java and Go.
4. Open the route (source system) that serves the API. Under **App security**, choose `orders-api`. Optionally list
   **Required scopes** (e.g. `orders:read`). From now on the route answers `401` to requests without a valid token.

The token endpoints are served on the admin address **and on every route that has an app attached**. A client of
`https://api.example.com` gets its tokens from `https://api.example.com/oauth2/token`.

The same steps with the admin API:

```bash
ADMIN=https://lb.example.com:8443
TOKEN=$(curl -s -X POST "$ADMIN/signin?scaleguard=true" -d '{"username":"admin","password":"…"}' | jq -r .token)

APP=$(curl -s -X POST "$ADMIN/apps?scaleguard=true" -H "Authorization: $TOKEN" \
  -d '{"name":"orders-api","description":"Order management API"}' | jq -r .id)

curl -s -X POST "$ADMIN/clients?scaleguard=true" -H "Authorization: $TOKEN" \
  -d "{\"appid\":\"$APP\",\"name\":\"billing-svc\",\"scopes\":[\"orders:read\",\"orders:write\"]}" \
  | jq '.credentials' > billing-svc.credentials.json     # client_id + client_secret, shown once

# Protect the route (partial update: other fields keep their values)
curl -s -X POST "$ADMIN/config/sourcesystems?scaleguard=true" -H "Authorization: $TOKEN" \
  -d "{\"id\":\"<route id>\",\"secappid\":\"$APP\",\"oauthScopes\":\"orders:read\"}"
```

## 2. Get a token

```bash
curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials \
  https://api.example.com/oauth2/token
```

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6ImF0K2p3dCIsImtpZCI6InNnLVF6Mi1MV19mU2JzayJ9.…",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "orders:read orders:write",
  "refresh_token": "sgr_7Q0…",
  "refresh_token_expires_in": 2592000
}
```

Add `-d scope="orders:read"` to ask for fewer scopes than the client is allowed. With HTTP Basic, URL-encode the ID
and secret before joining them with `:` (RFC 6749 §2.3.1). ScaleGuard's IDs and secrets only use URL-safe characters,
so this matters only for imported secrets.

## 3. Call the API

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" https://api.example.com/orders
```

The backend receives the original `Authorization` header plus:

| Header | Value |
|---|---|
| `X-ScaleGuard-Client-Id` | The calling client's ID |
| `X-ScaleGuard-Scope` | The token's scopes, space-separated |

Clients can't fake these headers, because ScaleGuard removes any incoming copies. gRPC backends get them as
`x-scaleguard-client-id` / `x-scaleguard-scope` metadata.

Failures follow RFC 6750:

| Situation | HTTP | gRPC |
|---|---|---|
| No token | `401`, `WWW-Authenticate: Bearer realm="scaleguard"` | `16 UNAUTHENTICATED` |
| Bad signature, expired, revoked, other app's token, client disabled | `401`, `error="invalid_token"` | `16 UNAUTHENTICATED` |
| Token lacks a scope the route requires | `403`, `error="insufficient_scope", scope="…"` | `7 PERMISSION_DENIED` |

## 4. Refresh

Each refresh token works **once**. Every refresh returns a new access token *and* a new refresh token. If an old
refresh token is presented again, ScaleGuard assumes it was stolen and revokes the whole chain.

```bash
curl -s -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d grant_type=refresh_token -d refresh_token="$REFRESH_TOKEN" \
  https://api.example.com/oauth2/token
```

A refreshed token can narrow its scopes (`-d scope=…`) but never gain new ones. If the client's allowed scopes were
reduced since the original grant, the refreshed token is reduced too.

> The OAuth spec says client-credentials grants *should not* include refresh tokens, since the client can always
> request a new token with its secret. ScaleGuard issues them by default because they let a client keep its secret out
> of most requests. Turn **Issue refresh tokens** off for a client to follow the spec strictly.

## 5. Revoke

```bash
# One token (access or refresh). Always answers 200, as RFC 7009 requires
curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d token="$TOKEN" https://api.example.com/oauth2/revoke
```

In the admin UI, or through the API:

| Action | Effect |
|---|---|
| **Rotate secret** (`POST /clients/{id}/secret`) | New secret, shown once. The old secret and **every token issued so far** stop working immediately |
| **Revoke all tokens** (`POST /clients/{id}/revoke`) | Every token issued so far stops working; the secret stays valid |
| **Disable** (`POST /clients/{id}` `{"enabled": false}`) | The client can't get tokens and its tokens are refused until enabled again |
| **Delete** | Client and tokens gone. Deleting an app deletes its clients |

## Samples

Each sample keeps the secret in an environment variable and renews the token before it expires. The admin UI fills in
your token URL and client ID.

**Python** (`pip install requests`)

```python
import os, time, requests

TOKEN_URL = "https://api.example.com/oauth2/token"
CLIENT_ID = os.environ["SCALEGUARD_CLIENT_ID"]
CLIENT_SECRET = os.environ["SCALEGUARD_CLIENT_SECRET"]

class ScaleGuardAuth(requests.auth.AuthBase):
    """Adds a bearer token, fetching and refreshing it as needed."""
    def __init__(self):
        self.token, self.refresh, self.expires = None, None, 0

    def _fetch(self, data):
        r = requests.post(TOKEN_URL, data=data, auth=(CLIENT_ID, CLIENT_SECRET), timeout=10)
        r.raise_for_status()
        t = r.json()
        self.token, self.refresh = t["access_token"], t.get("refresh_token")
        self.expires = time.time() + t["expires_in"] - 30   # renew 30 s early

    def __call__(self, req):
        if time.time() >= self.expires:
            try:
                if not self.refresh:
                    raise ValueError
                self._fetch({"grant_type": "refresh_token", "refresh_token": self.refresh})
            except Exception:
                self._fetch({"grant_type": "client_credentials"})
        req.headers["Authorization"] = f"Bearer {self.token}"
        return req

session = requests.Session()
session.auth = ScaleGuardAuth()
print(session.get("https://api.example.com/orders").json())
```

**Node.js 18+**

```js
const TOKEN_URL = "https://api.example.com/oauth2/token";
const { SCALEGUARD_CLIENT_ID: id, SCALEGUARD_CLIENT_SECRET: secret } = process.env;
const basic = "Basic " + Buffer.from(`${encodeURIComponent(id)}:${encodeURIComponent(secret)}`).toString("base64");
let token, refresh, expires = 0;

async function fetchToken(params) {
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { Authorization: basic, "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(params),
  });
  if (!res.ok) throw new Error(`token request failed: ${res.status} ${await res.text()}`);
  const t = await res.json();
  token = t.access_token; refresh = t.refresh_token; expires = Date.now() + (t.expires_in - 30) * 1000;
}

export async function accessToken() {
  if (Date.now() < expires) return token;
  try {
    if (!refresh) throw new Error();
    await fetchToken({ grant_type: "refresh_token", refresh_token: refresh });
  } catch {
    await fetchToken({ grant_type: "client_credentials" });
  }
  return token;
}

const res = await fetch("https://api.example.com/orders", { headers: { Authorization: `Bearer ${await accessToken()}` } });
```

**Go**, using the standard OAuth 2.0 library:

```go
cfg := clientcredentials.Config{               // golang.org/x/oauth2/clientcredentials
	ClientID:     os.Getenv("SCALEGUARD_CLIENT_ID"),
	ClientSecret: os.Getenv("SCALEGUARD_CLIENT_SECRET"),
	TokenURL:     "https://api.example.com/oauth2/token",
	Scopes:       []string{"orders:read"},
}
client := cfg.Client(context.Background())     // gets and renews tokens automatically
res, err := client.Get("https://api.example.com/orders")
```

**Java / Spring Boot**: any OAuth 2.0 client library works. For example, with Spring Security's `client_credentials` support:

```yaml
spring.security.oauth2.client:
  registration.scaleguard:
    client-id: ${SCALEGUARD_CLIENT_ID}
    client-secret: ${SCALEGUARD_CLIENT_SECRET}
    authorization-grant-type: client_credentials
    scope: orders:read
  provider.scaleguard.token-uri: https://api.example.com/oauth2/token
```

## Verifying tokens in the backend (optional)

ScaleGuard already rejects bad tokens, so most backends can simply trust `X-ScaleGuard-Client-Id`. For defense in
depth, a backend can verify the JWT itself against `/oauth2/jwks`. Check that:

- the signature verifies with the key named by `kid`;
- `alg` is `RS256` and `typ` is `at+jwt`;
- `aud` is the app's ID;
- `exp` hasn't passed;
- `iss` matches the issuer configured under **Token settings**, if one is set.

Access-token claims:

| Claim | Meaning |
|---|---|
| `iss` | Issuer: the configured URL, or the address the token was requested on |
| `sub`, `client_id` | Client ID |
| `aud` | App ID |
| `scope` | Granted scopes, space-separated |
| `iat`, `nbf`, `exp` | Issued at / valid from / expires (seconds) |
| `jti` | Unique token ID (used for revocation) |
| `app` | App name |

Revocation, disabling and secret rotation take effect at the gateway immediately. A backend that verifies tokens
offline only notices them when the token expires, so keep access tokens short-lived (the default is one hour).

## Settings

**App Security → Token settings**, or `POST /oauth/settings`:

| Setting | Default | |
|---|---|---|
| `accessTokenTtlSeconds` | `3600` | 60 – 86 400. A client can override it |
| `refreshTokenTtlSeconds` | `2592000` (30 days) | 300 – 31 536 000. A client can override it |
| `issuer` | blank | The `iss` claim. Blank = the address the token was requested on. Set it when backends check `iss` |
| `pathPrefix` | `/oauth2` | Where the endpoints live |
| `legacyHeaders` | `true` | Also accept the old `client_id` / `client_secret` request headers on protected routes. Turn off once clients use tokens |

**Signing keys**: **Rotate** (`POST /oauth/keys/rotate`) creates a new RS256 key for new tokens. Older keys stay in the
JWK Set and keep verifying for seven days, so rotation doesn't log anyone out. In a cluster, keys, settings, clients,
refresh tokens and revocations live in the shared database, so every node issues and accepts the same tokens.

## Upgrading from client_id / client_secret headers

Earlier versions protected a route by having clients send `client_id` and `client_secret` headers on every request.

- **Existing secrets are hashed** in place the first time the new version starts. Clients keep working.
- **The old headers are still accepted** while `legacyHeaders` is on. ScaleGuard strips `client_secret` before forwarding.
- **Move each client** to `POST /oauth2/token` + `Authorization: Bearer`. Its ID and secret don't change.
- **Turn off legacyHeaders** once every client has moved.
