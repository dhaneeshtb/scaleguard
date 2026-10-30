import { useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import { useToast } from "@chakra-ui/react";
import { FiAlertTriangle, FiCheck, FiCopy, FiDownload, FiEdit2, FiKey, FiLink, FiPlus, FiRefreshCw, FiSlash, FiTrash2, FiX, FiCode, FiShield } from "react-icons/fi";
import { useAuth } from "../../contexts/AuthContext";
import { Card, Field, PageHeader, Pill, ToggleRow, inputCls } from "../../components/ui/controls";

/* ------------------------------------------------------------------ types */

interface Client {
    id: string; name: string; description?: string | null; appid: string; clientid: string; expiry: number;
    scopes: string[]; accessTokenTtlSeconds: number | null; refreshTokenTtlSeconds: number | null; refreshTokens: boolean;
    enabled: boolean; notBefore: number; secretHint?: string; secretUpdatedAt: number; createdAt: number; activeRefreshTokens?: number;
}
interface App { id: string; name: string; description: string; clients: Client[] }
interface OAuthView {
    settings: { issuer: string; accessTokenTtlSeconds: number; refreshTokenTtlSeconds: number; legacyHeaders: boolean; pathPrefix: string };
    keys: { kid: string; created: number; retired?: number; active: boolean; algorithm: string }[];
    endpoints: { token: string; revoke: string; introspect: string; jwks: string; metadata: string };
}
interface Route { id: string; name: string; scheme: string; host: string; port: string; secappid?: string; oauthScopes?: string }
interface Issued { client: Client; secret: string; app: string; reason: "created" | "rotated" }

/* ------------------------------------------------------------------ helpers */

const DAY = 86_400;
const EXPIRY_OPTIONS = [{ v: 0, l: "Never" }, { v: 30, l: "30 days" }, { v: 90, l: "90 days" }, { v: 180, l: "180 days" }, { v: 365, l: "1 year" }];
const ACCESS_TTLS = [{ v: -1, l: "Default" }, { v: 300, l: "5 minutes" }, { v: 900, l: "15 minutes" }, { v: 3600, l: "1 hour" }, { v: 43_200, l: "12 hours" }, { v: DAY, l: "24 hours" }];
const REFRESH_TTLS = [{ v: -1, l: "Default" }, { v: DAY, l: "1 day" }, { v: 7 * DAY, l: "7 days" }, { v: 30 * DAY, l: "30 days" }, { v: 90 * DAY, l: "90 days" }];

const duration = (s: number) => s % DAY === 0 ? `${s / DAY} day${s === DAY ? "" : "s"}` : s % 3600 === 0 ? `${s / 3600} hour${s === 3600 ? "" : "s"}` : `${Math.round(s / 60)} min`;
const when = (ms: number) => {
    const d = Math.round((Date.now() - ms) / 1000);
    if (d < 60) return "just now";
    if (d < 3600) return `${Math.round(d / 60)}m ago`;
    if (d < DAY) return `${Math.round(d / 3600)}h ago`;
    return `${Math.round(d / DAY)}d ago`;
};
const inDays = (ms: number) => Math.max(0, Math.ceil((ms - Date.now()) / 86_400_000));
const routeUrl = (r: Route) => {
    const port = r.port && !((r.scheme === "https" && r.port === "443") || (r.scheme === "http" && r.port === "80")) ? `:${r.port}` : "";
    return `${r.scheme}://${r.host}${port}`;
};

function download(name: string, body: string, type: string) {
    const url = URL.createObjectURL(new Blob([body], { type }));
    const a = document.createElement("a");
    a.href = url; a.download = name; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function CopyButton({ text, label }: { text: string; label: string }) {
    const [done, setDone] = useState(false);
    return (
        <button type="button" aria-label={`Copy ${label}`} title={`Copy ${label}`}
            onClick={() => { navigator.clipboard?.writeText(text); setDone(true); setTimeout(() => setDone(false), 1200); }}
            className="shrink-0 rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-white/5 dark:hover:text-slate-200">
            {done ? <FiCheck className="text-emerald-500" /> : <FiCopy />}
        </button>
    );
}

const btn = "inline-flex h-9 items-center gap-1.5 rounded-xl px-3 text-[12.5px] font-semibold transition disabled:opacity-50";
const btnGhost = `${btn} border border-slate-200 text-slate-700 hover:bg-slate-50 dark:border-white/10 dark:text-slate-200 dark:hover:bg-white/5`;
const btnPrimary = `${btn} bg-gradient-to-r from-teal-400 to-cyan-400 text-slate-950 hover:brightness-110`;
const iconBtn = "rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-white/5 dark:hover:text-slate-200";

/* ------------------------------------------------------------------ samples */

type Lang = "curl" | "python" | "node" | "java" | "go";
const LANGS: { v: Lang; l: string }[] = [{ v: "curl", l: "curl" }, { v: "python", l: "Python" }, { v: "node", l: "Node.js" }, { v: "java", l: "Java" }, { v: "go", l: "Go" }];

function samples(lang: Lang, o: { tokenUrl: string; apiUrl: string; clientId: string; secret: string; scope: string; refresh: boolean }) {
    const scopeArg = o.scope ? ` -d scope="${o.scope}"` : "";
    switch (lang) {
        case "curl": return `# 1. Exchange the client credentials for an access token
curl -s -u "${o.clientId}:${o.secret}" \\
  -d grant_type=client_credentials${scopeArg} \\
  ${o.tokenUrl}
# → {"access_token":"eyJ…","token_type":"Bearer","expires_in":3600${o.refresh ? ',"refresh_token":"sgr_…"' : ""}}

# 2. Call the API with the token
curl -H "Authorization: Bearer $ACCESS_TOKEN" ${o.apiUrl}/
${o.refresh ? `
# 3. Before it expires, swap the refresh token for a new pair (each refresh token works once)
curl -s -u "${o.clientId}:${o.secret}" \\
  -d grant_type=refresh_token -d refresh_token="$REFRESH_TOKEN" \\
  ${o.tokenUrl}
` : ""}
# Revoke a token you no longer need
curl -s -u "${o.clientId}:${o.secret}" -d token="$ACCESS_TOKEN" ${o.tokenUrl.replace(/\/token$/, "/revoke")}`;
        case "python": return `# pip install requests
import os, time, requests

TOKEN_URL = "${o.tokenUrl}"
CLIENT_ID = os.environ.get("SCALEGUARD_CLIENT_ID", "${o.clientId}")
CLIENT_SECRET = os.environ["SCALEGUARD_CLIENT_SECRET"]   # keep secrets out of source code

class ScaleGuardAuth(requests.auth.AuthBase):
    """Adds a bearer token, fetching and ${o.refresh ? "refreshing" : "renewing"} it as needed."""
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
                if self.refresh:
                    self._fetch({"grant_type": "refresh_token", "refresh_token": self.refresh})
                else:
                    raise ValueError
            except Exception:
                self._fetch({"grant_type": "client_credentials"${o.scope ? `, "scope": "${o.scope}"` : ""}})
        req.headers["Authorization"] = f"Bearer {self.token}"
        return req

session = requests.Session()
session.auth = ScaleGuardAuth()
print(session.get("${o.apiUrl}/").status_code)`;
        case "node": return `// Node.js 18+ (built-in fetch)
const TOKEN_URL = "${o.tokenUrl}";
const CLIENT_ID = process.env.SCALEGUARD_CLIENT_ID ?? "${o.clientId}";
const CLIENT_SECRET = process.env.SCALEGUARD_CLIENT_SECRET; // keep secrets out of source code
const basic = "Basic " + Buffer.from(\`\${encodeURIComponent(CLIENT_ID)}:\${encodeURIComponent(CLIENT_SECRET)}\`).toString("base64");

let token, refresh, expires = 0;

async function fetchToken(params) {
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { Authorization: basic, "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(params),
  });
  if (!res.ok) throw new Error(\`token request failed: \${res.status} \${await res.text()}\`);
  const t = await res.json();
  token = t.access_token; refresh = t.refresh_token; expires = Date.now() + (t.expires_in - 30) * 1000;
}

async function accessToken() {
  if (Date.now() < expires) return token;
  try {
    if (!refresh) throw new Error("no refresh token");
    await fetchToken({ grant_type: "refresh_token", refresh_token: refresh });
  } catch {
    await fetchToken({ grant_type: "client_credentials"${o.scope ? `, scope: "${o.scope}"` : ""} });
  }
  return token;
}

const res = await fetch("${o.apiUrl}/", { headers: { Authorization: \`Bearer \${await accessToken()}\` } });
console.log(res.status);`;
        case "java": return `// Java 11+ (java.net.http), no extra libraries
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class ScaleGuardClient {
    static final String TOKEN_URL = "${o.tokenUrl}";
    static final String CLIENT_ID = System.getenv().getOrDefault("SCALEGUARD_CLIENT_ID", "${o.clientId}");
    static final String CLIENT_SECRET = System.getenv("SCALEGUARD_CLIENT_SECRET");
    static final HttpClient http = HttpClient.newHttpClient();

    static String token(String form) throws Exception {
        String basic = Base64.getEncoder().encodeToString((enc(CLIENT_ID) + ":" + enc(CLIENT_SECRET)).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException(res.body());
        return res.body();   // JSON: access_token, expires_in${o.refresh ? ", refresh_token" : ""} — parse with your JSON library
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    public static void main(String[] a) throws Exception {
        String json = token("grant_type=client_credentials${o.scope ? `&scope=" + enc("${o.scope}") + "` : ""}");
        String accessToken = json.replaceAll(".*\\"access_token\\":\\"([^\\"]+)\\".*", "$1");
        HttpResponse<String> api = http.send(HttpRequest.newBuilder(URI.create("${o.apiUrl}/"))
                .header("Authorization", "Bearer " + accessToken).build(), HttpResponse.BodyHandlers.ofString());
        System.out.println(api.statusCode());${o.refresh ? `
        // Later: token("grant_type=refresh_token&refresh_token=" + enc(refreshToken)) — each refresh token works once.` : ""}
    }
}`;
        case "go": return `// go get golang.org/x/oauth2
package main

import (
	"context"
	"fmt"
	"os"

	"golang.org/x/oauth2/clientcredentials"
)

func main() {
	cfg := clientcredentials.Config{
		ClientID:     "${o.clientId}",
		ClientSecret: os.Getenv("SCALEGUARD_CLIENT_SECRET"),
		TokenURL:     "${o.tokenUrl}",${o.scope ? `\n\t\tScopes:       []string{${o.scope.split(" ").map(s => `"${s}"`).join(", ")}},` : ""}
	}
	// The client fetches a token on first use and a new one before it expires.
	client := cfg.Client(context.Background())
	res, err := client.Get("${o.apiUrl}/")
	if err != nil {
		panic(err)
	}
	fmt.Println(res.Status)
}`;
    }
}

function SamplePanel({ tokenUrl, apiUrl, client, secret }: { tokenUrl: string; apiUrl: string; client: Client; secret?: string }) {
    const [lang, setLang] = useState<Lang>("curl");
    const code = samples(lang, { tokenUrl, apiUrl, clientId: client.clientid, secret: secret ?? "$CLIENT_SECRET", scope: client.scopes.join(" "), refresh: client.refreshTokens });
    return (
        <div className="min-w-0">
            <div className="mb-2 flex items-center justify-between gap-2">
                <div role="tablist" aria-label="Language" className="flex flex-wrap gap-1">
                    {LANGS.map(l => (
                        <button key={l.v} role="tab" aria-selected={lang === l.v} type="button" onClick={() => setLang(l.v)}
                            className={`h-8 rounded-lg px-2.5 text-[12px] font-semibold ${lang === l.v ? "bg-slate-900 text-white dark:bg-white dark:text-slate-900" : "text-slate-500 hover:bg-slate-100 dark:hover:bg-white/5"}`}>{l.l}</button>
                    ))}
                </div>
                <CopyButton text={code} label="sample code" />
            </div>
            <pre className="max-h-[340px] overflow-auto rounded-xl bg-slate-950 p-3.5 font-mono text-[11.5px] leading-relaxed text-slate-100"><code>{code}</code></pre>
        </div>
    );
}

/* ------------------------------------------------------------------ modals */

function Overlay({ title, onClose, children, wide }: { title: string; onClose: () => void; children: React.ReactNode; wide?: boolean }) {
    useEffect(() => {
        const k = (e: KeyboardEvent) => e.key === "Escape" && onClose();
        window.addEventListener("keydown", k);
        return () => window.removeEventListener("keydown", k);
    }, [onClose]);
    return (
        <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-slate-950/50 p-4 backdrop-blur-sm sm:items-center" onMouseDown={e => e.target === e.currentTarget && onClose()}>
            <div role="dialog" aria-modal="true" aria-label={title} className={`w-full ${wide ? "max-w-3xl" : "max-w-md"} rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-white/10 dark:bg-[#161616]`}>
                <div className="mb-4 flex items-center justify-between gap-3">
                    <h3 className="text-[15px] font-semibold text-slate-900 dark:text-white">{title}</h3>
                    <button type="button" aria-label="Close" onClick={onClose} className={iconBtn}><FiX /></button>
                </div>
                {children}
            </div>
        </div>
    );
}

function Confirm({ title, body, action, danger, onConfirm, onClose }: { title: string; body: React.ReactNode; action: string; danger?: boolean; onConfirm: () => void; onClose: () => void }) {
    return (
        <Overlay title={title} onClose={onClose}>
            <div className="text-[13px] leading-relaxed text-slate-600 dark:text-slate-300">{body}</div>
            <div className="mt-5 flex justify-end gap-2">
                <button type="button" className={btnGhost} onClick={onClose}>Cancel</button>
                <button type="button" className={danger ? `${btn} bg-rose-500 text-white hover:bg-rose-600` : btnPrimary} onClick={() => { onConfirm(); onClose(); }}>{action}</button>
            </div>
        </Overlay>
    );
}

function CredentialsModal({ issued, tokenUrl, apiUrl, onClose }: { issued: Issued; tokenUrl: string; apiUrl: string; onClose: () => void }) {
    const { client, secret } = issued;
    const json = JSON.stringify({ client_id: client.clientid, client_secret: secret, token_url: tokenUrl, grant_type: "client_credentials", scope: client.scopes.join(" ") || undefined, app: issued.app, issued_at: new Date().toISOString() }, null, 2);
    const env = `SCALEGUARD_CLIENT_ID=${client.clientid}\nSCALEGUARD_CLIENT_SECRET=${secret}\nSCALEGUARD_TOKEN_URL=${tokenUrl}\n${client.scopes.length ? `SCALEGUARD_SCOPE="${client.scopes.join(" ")}"\n` : ""}`;
    const file = client.name.replace(/[^A-Za-z0-9_-]+/g, "-").toLowerCase() || "client";
    return (
        <Overlay title={issued.reason === "created" ? `Client “${client.name}” created` : `New secret for “${client.name}”`} onClose={onClose} wide>
            <div className="mb-4 flex gap-2.5 rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-[12.5px] text-amber-800 dark:text-amber-200">
                <FiAlertTriangle className="mt-0.5 shrink-0" aria-hidden />
                <span>Copy or download the secret now. ScaleGuard stores only a hash of it and can't show it again — if it's lost, rotate the secret.{issued.reason === "rotated" ? " The old secret and every token issued with it have stopped working." : ""}</span>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Client ID">
                    <div className="flex items-center gap-1"><input readOnly value={client.clientid} className={`${inputCls} font-mono text-[12.5px]`} aria-label="Client ID" /><CopyButton text={client.clientid} label="client ID" /></div>
                </Field>
                <Field label="Client secret">
                    <div className="flex items-center gap-1"><input readOnly value={secret} className={`${inputCls} font-mono text-[12.5px]`} aria-label="Client secret" onFocus={e => e.currentTarget.select()} /><CopyButton text={secret} label="client secret" /></div>
                </Field>
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
                <button type="button" className={btnPrimary} onClick={() => download(`${file}.credentials.json`, json, "application/json")}><FiDownload /> Download JSON</button>
                <button type="button" className={btnGhost} onClick={() => download(`${file}.env`, env, "text/plain")}><FiDownload /> Download .env</button>
            </div>
            <div className="mt-5">
                <p className="mb-2 text-[12.5px] font-semibold text-slate-700 dark:text-slate-300">Try it</p>
                <SamplePanel tokenUrl={tokenUrl} apiUrl={apiUrl} client={client} secret={secret} />
            </div>
            <div className="mt-5 flex justify-end"><button type="button" className={btnGhost} onClick={onClose}>I've saved it</button></div>
        </Overlay>
    );
}

/* ------------------------------------------------------------------ client form */

interface ClientDraft { name: string; scopes: string; expiryDays: number; keepExpiry: boolean; access: number; refreshTokens: boolean; refresh: number }

function ClientForm({ initial, existing, onCancel, onSave, busy }: { initial?: Client; existing: boolean; onCancel: () => void; onSave: (d: ClientDraft) => void; busy: boolean }) {
    const [d, setD] = useState<ClientDraft>({
        name: initial?.name ?? "", scopes: initial?.scopes.join(" ") ?? "", expiryDays: 0, keepExpiry: existing,
        access: initial?.accessTokenTtlSeconds ?? -1, refreshTokens: initial?.refreshTokens ?? true, refresh: initial?.refreshTokenTtlSeconds ?? -1,
    });
    const [err, setErr] = useState<string | null>(null);
    const set = (p: Partial<ClientDraft>) => setD(x => ({ ...x, ...p }));
    const submit = () => {
        if (!d.name.trim()) return setErr("Give the client a name, e.g. the service that will use it.");
        const bad = d.scopes.split(/[\s,]+/).filter(Boolean).find(s => !/^[\x21\x23-\x5B\x5D-\x7E]+$/.test(s));
        if (bad) return setErr(`“${bad}” isn't a valid scope (no quotes or backslashes).`);
        setErr(null); onSave(d);
    };
    const opts = (o: { v: number; l: string }[], cur: number) => o.some(x => x.v === cur) ? o : [...o, { v: cur, l: duration(cur) }];
    return (
        <div className="space-y-3 rounded-xl border border-teal-400/30 bg-teal-400/[0.04] p-3.5">
            <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Name" htmlFor="c-name" error={err ?? undefined}>
                    <input id="c-name" autoFocus value={d.name} onChange={e => set({ name: e.target.value })} placeholder="billing-service" className={inputCls} />
                </Field>
                <Field label="Allowed scopes" htmlFor="c-scopes" hint="Space-separated. Tokens can carry these; routes can require them.">
                    <input id="c-scopes" value={d.scopes} onChange={e => set({ scopes: e.target.value })} placeholder="orders:read orders:write" className={`${inputCls} font-mono text-[12.5px]`} />
                </Field>
                <Field label="Credentials expire" htmlFor="c-exp" hint={existing ? undefined : "After this the client can't get new tokens."}>
                    <select id="c-exp" value={d.keepExpiry ? "keep" : d.expiryDays} onChange={e => e.target.value === "keep" ? set({ keepExpiry: true }) : set({ keepExpiry: false, expiryDays: Number(e.target.value) })} className={inputCls}>
                        {existing && <option value="keep">Keep current</option>}
                        {EXPIRY_OPTIONS.map(o => <option key={o.v} value={o.v}>{o.v ? `In ${o.l}` : o.l}</option>)}
                    </select>
                </Field>
                <Field label="Access token lifetime" htmlFor="c-at">
                    <select id="c-at" value={d.access} onChange={e => set({ access: Number(e.target.value) })} className={inputCls}>
                        {opts(ACCESS_TTLS, d.access).map(o => <option key={o.v} value={o.v}>{o.l}</option>)}
                    </select>
                </Field>
            </div>
            <ToggleRow id="c-rt" label="Issue refresh tokens" checked={d.refreshTokens} onChange={v => set({ refreshTokens: v })}
                description="Lets the client renew its access token without sending its secret again. Each refresh token works once and is replaced on use." />
            {d.refreshTokens && (
                <div className="sm:w-1/2">
                    <Field label="Refresh token lifetime" htmlFor="c-rtl">
                        <select id="c-rtl" value={d.refresh} onChange={e => set({ refresh: Number(e.target.value) })} className={inputCls}>
                            {opts(REFRESH_TTLS, d.refresh).map(o => <option key={o.v} value={o.v}>{o.l}</option>)}
                        </select>
                    </Field>
                </div>
            )}
            <div className="flex justify-end gap-2">
                <button type="button" className={btnGhost} onClick={onCancel}>Cancel</button>
                <button type="button" className={btnPrimary} disabled={busy} onClick={submit}>{existing ? "Save client" : "Create client"}</button>
            </div>
        </div>
    );
}

/* ------------------------------------------------------------------ client row */

function ClientRow({ c, onAction, editing, form }: { c: Client; onAction: (a: string, c: Client) => void; editing: boolean; form: React.ReactNode }) {
    const expired = c.expiry > 0 && c.expiry < Date.now();
    if (editing) return <li className="p-3">{form}</li>;
    return (
        <li className="px-3 py-3">
            <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                        <span className="text-[13.5px] font-semibold text-slate-900 dark:text-white">{c.name}</span>
                        {!c.enabled ? <Pill tone="muted">disabled</Pill> : expired ? <Pill tone="bad">expired</Pill>
                            : c.expiry > 0 ? <Pill tone={inDays(c.expiry) <= 14 ? "warn" : "muted"}>expires in {inDays(c.expiry)}d</Pill> : <Pill tone="good">active</Pill>}
                        {c.refreshTokens ? <Pill tone="info">refresh tokens</Pill> : null}
                    </div>
                    <div className="mt-1 flex min-w-0 items-center gap-1">
                        <code className="truncate font-mono text-[12px] text-slate-600 dark:text-slate-300">{c.clientid}</code>
                        <CopyButton text={c.clientid} label="client ID" />
                    </div>
                    <div className="mt-1 flex flex-wrap gap-1">
                        {c.scopes.length ? c.scopes.map(s => <span key={s} className="rounded-md bg-slate-100 px-1.5 py-0.5 font-mono text-[11px] text-slate-600 dark:bg-white/[0.06] dark:text-slate-300">{s}</span>)
                            : <span className="text-[11.5px] text-slate-400">no scopes</span>}
                    </div>
                    <p className="mt-1.5 text-[11.5px] text-slate-500 dark:text-slate-400">
                        Secret {c.secretHint || "…"} · set {c.secretUpdatedAt ? when(c.secretUpdatedAt) : "earlier"}
                        {" · "}access tokens {c.accessTokenTtlSeconds ? `last ${duration(c.accessTokenTtlSeconds)}` : "default lifetime"}
                        {c.refreshTokens && c.activeRefreshTokens ? ` · ${c.activeRefreshTokens} live refresh token${c.activeRefreshTokens === 1 ? "" : "s"}` : ""}
                    </p>
                </div>
                <div className="flex shrink-0 flex-wrap items-center gap-0.5">
                    <button type="button" className={iconBtn} title="Sample code" aria-label={`Sample code for ${c.name}`} onClick={() => onAction("samples", c)}><FiCode /></button>
                    <button type="button" className={iconBtn} title="Edit" aria-label={`Edit ${c.name}`} onClick={() => onAction("edit", c)}><FiEdit2 /></button>
                    <button type="button" className={iconBtn} title="Rotate secret" aria-label={`Rotate secret of ${c.name}`} onClick={() => onAction("rotate", c)}><FiKey /></button>
                    <button type="button" className={iconBtn} title="Revoke all tokens" aria-label={`Revoke tokens of ${c.name}`} onClick={() => onAction("revoke", c)}><FiSlash /></button>
                    <button type="button" className={iconBtn} title={c.enabled ? "Disable" : "Enable"} aria-label={`${c.enabled ? "Disable" : "Enable"} ${c.name}`} onClick={() => onAction("toggle", c)}>
                        {c.enabled ? <FiX /> : <FiCheck />}
                    </button>
                    <button type="button" className={`${iconBtn} hover:!bg-rose-500/10 hover:!text-rose-500`} title="Delete" aria-label={`Delete ${c.name}`} onClick={() => onAction("delete", c)}><FiTrash2 /></button>
                </div>
            </div>
        </li>
    );
}

/* ------------------------------------------------------------------ page */

export default function AppSecurity() {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const host: string = auth.data.host;
    const headers = useMemo(() => ({ Authorization: auth.data.token }), [auth]);
    const api = useCallback((path: string) => `${host}${path}${path.includes("?") ? "&" : "?"}scaleguard=true`, [host]);

    const [apps, setApps] = useState<App[]>([]);
    const [oauth, setOauth] = useState<OAuthView | null>(null);
    const [routes, setRoutes] = useState<Route[]>([]);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [newApp, setNewApp] = useState({ name: "", description: "" });
    const [appErr, setAppErr] = useState<string | null>(null);
    const [adding, setAdding] = useState<string | null>(null);     // app id with an open "add client" form
    const [editing, setEditing] = useState<string | null>(null);   // client id being edited
    const [issued, setIssued] = useState<Issued | null>(null);
    const [sampleFor, setSampleFor] = useState<{ c: Client; app: App } | null>(null);
    const [confirm, setConfirm] = useState<{ title: string; body: React.ReactNode; action: string; danger?: boolean; run: () => void } | null>(null);
    const [settings, setSettings] = useState<OAuthView["settings"] | null>(null);
    const [settingsErr, setSettingsErr] = useState<string | null>(null);

    const fail = (e: any, what: string) => toast({ status: "error", title: what, description: e?.response?.data?.message || e?.response?.data?.error_description || e?.message, duration: 6000, isClosable: true });

    const load = useCallback(async () => {
        try {
            const [a, c, o, r] = await Promise.all([
                axios.get(api("/apps"), { headers }),
                axios.get(api("/clients"), { headers }),
                axios.get(api("/oauth"), { headers }).catch(() => null),
                axios.get(api("/config/sourcesystems"), { headers }).catch(() => null),
            ]);
            const clients: Client[] = Array.isArray(c.data) ? c.data : [];
            setApps((Array.isArray(a.data) ? a.data : []).map((x: App) => ({ ...x, clients: clients.filter(k => k.appid === x.id).sort((p, q) => p.createdAt - q.createdAt) })));
            if (o) { setOauth(o.data); setSettings(s => s ?? o.data.settings); }
            const rs = r ? (Array.isArray(r.data) ? r.data : r.data?.sourcesystems) : null;
            if (Array.isArray(rs)) setRoutes(rs);
            setLoadError(o ? null : "This server doesn't have the OAuth service yet. Update the server to the latest build.");
        } catch (e: any) {
            setLoadError("Couldn't load apps and clients.");
        }
    }, [api, headers]);

    useEffect(() => { load(); }, [load]);

    const prefix = oauth?.settings.pathPrefix ?? "/oauth2";
    const protectedRoutes = (appId: string) => routes.filter(r => r.secappid === appId);
    const baseFor = (appId: string) => { const r = protectedRoutes(appId)[0]; return r ? routeUrl(r) : host; };
    const tokenUrlFor = (appId: string) => `${baseFor(appId)}${prefix}/token`;

    const createApp = async () => {
        if (!newApp.name.trim()) return setAppErr("Name the app — usually the API it protects, e.g. orders-api.");
        if (apps.some(a => a.name.toLowerCase() === newApp.name.trim().toLowerCase())) return setAppErr("An app with this name already exists.");
        setAppErr(null); setBusy(true);
        try { await axios.post(api("/apps"), { name: newApp.name.trim(), description: newApp.description.trim() }, { headers }); setNewApp({ name: "", description: "" }); await load(); }
        catch (e) { fail(e, "Couldn't create the app"); } finally { setBusy(false); }
    };

    const body = (d: ClientDraft, appid?: string) => ({
        ...(appid ? { appid } : {}), name: d.name.trim(), scopes: d.scopes.split(/[\s,]+/).filter(Boolean),
        ...(d.keepExpiry ? {} : { expiry: d.expiryDays ? Date.now() + d.expiryDays * 86_400_000 : 0 }),
        accessTokenTtlSeconds: d.access, refreshTokens: d.refreshTokens, refreshTokenTtlSeconds: d.refresh,
    });

    const createClient = async (app: App, d: ClientDraft) => {
        setBusy(true);
        try {
            const r = await axios.post(api("/clients"), body(d, app.id), { headers });
            setAdding(null);
            setIssued({ client: r.data.client, secret: r.data.client_secret, app: app.name, reason: "created" });
            await load();
        } catch (e) { fail(e, "Couldn't create the client"); } finally { setBusy(false); }
    };

    const saveClient = async (c: Client, d: ClientDraft) => {
        setBusy(true);
        try { await axios.post(api(`/clients/${c.id}`), body(d), { headers }); setEditing(null); await load(); toast({ status: "success", title: "Client saved", duration: 2500 }); }
        catch (e) { fail(e, "Couldn't save the client"); } finally { setBusy(false); }
    };

    const onAction = (app: App) => (a: string, c: Client) => {
        if (a === "samples") return setSampleFor({ c, app });
        if (a === "edit") { setAdding(null); return setEditing(c.id); }
        if (a === "rotate") return setConfirm({
            title: `Rotate the secret of “${c.name}”?`, action: "Rotate secret", danger: true,
            body: <>A new secret is generated and shown once. The current secret stops working immediately, and every access and refresh token issued to this client is revoked.</>,
            run: async () => {
                try { const r = await axios.post(api(`/clients/${c.id}/secret`), {}, { headers }); setIssued({ client: r.data.client, secret: r.data.client_secret, app: app.name, reason: "rotated" }); await load(); }
                catch (e) { fail(e, "Couldn't rotate the secret"); }
            },
        });
        if (a === "revoke") return setConfirm({
            title: `Revoke all tokens of “${c.name}”?`, action: "Revoke tokens", danger: true,
            body: <>Every access and refresh token issued so far stops working. The client keeps its secret and can request new tokens right away.</>,
            run: async () => { try { await axios.post(api(`/clients/${c.id}/revoke`), {}, { headers }); toast({ status: "success", title: "Tokens revoked", duration: 2500 }); await load(); } catch (e) { fail(e, "Couldn't revoke tokens"); } },
        });
        if (a === "toggle") {
            const run = async () => { try { await axios.post(api(`/clients/${c.id}`), { enabled: !c.enabled }, { headers }); await load(); } catch (e) { fail(e, "Couldn't update the client"); } };
            return c.enabled ? setConfirm({ title: `Disable “${c.name}”?`, action: "Disable", danger: true, body: <>Its tokens are refused until you enable it again, and its refresh tokens are revoked.</>, run }) : run();
        }
        if (a === "delete") return setConfirm({
            title: `Delete “${c.name}”?`, action: "Delete client", danger: true,
            body: <>The client and all its tokens stop working immediately. This can't be undone.</>,
            run: async () => { try { await axios.delete(api(`/clients/${c.id}`), { headers }); await load(); } catch (e) { fail(e, "Couldn't delete the client"); } },
        });
    };

    const deleteApp = (app: App) => setConfirm({
        title: `Delete app “${app.name}”?`, action: "Delete app", danger: true,
        body: <>Its {app.clients.length} client{app.clients.length === 1 ? "" : "s"} and their tokens are deleted too.{protectedRoutes(app.id).length ? <> {protectedRoutes(app.id).length} route{protectedRoutes(app.id).length === 1 ? " uses" : "s use"} this app — they will refuse every request until you pick another app or none.</> : null}</>,
        run: async () => { try { await axios.delete(api(`/apps/${app.id}`), { headers }); await load(); } catch (e) { fail(e, "Couldn't delete the app"); } },
    });

    const saveSettings = async () => {
        if (!settings) return;
        setSettingsErr(null); setBusy(true);
        try { const r = await axios.post(api("/oauth/settings"), settings, { headers }); setOauth(r.data); setSettings(r.data.settings); toast({ status: "success", title: "OAuth settings saved", duration: 2500 }); }
        catch (e: any) { setSettingsErr(e?.response?.data?.message || "Couldn't save the settings."); } finally { setBusy(false); }
    };

    const rotateKey = () => setConfirm({
        title: "Rotate the signing key?", action: "Rotate key",
        body: <>New tokens are signed with a new key. Tokens signed with the current key keep working until they expire, so no client is interrupted. Backends that verify tokens themselves should fetch <code className="font-mono">{prefix}/jwks</code> again.</>,
        run: async () => { try { const r = await axios.post(api("/oauth/keys/rotate"), {}, { headers }); setOauth(r.data); toast({ status: "success", title: "Signing key rotated", duration: 2500 }); } catch (e) { fail(e, "Couldn't rotate the key"); } },
    });

    const settingsDirty = !!settings && !!oauth && JSON.stringify(settings) !== JSON.stringify(oauth.settings);
    const legacyInUse = !!oauth?.settings.legacyHeaders;

    return (
        <div className="mx-auto max-w-6xl pb-24">
            <PageHeader icon={FiShield} eyebrow="Protect" title="App security"
                description="Protect routes with OAuth 2.0. Each app has clients; a client trades its ID and secret for a short-lived access token (a signed JWT) at the token endpoint and sends it as “Authorization: Bearer …”. Attach an app to a route in the route editor." />

            {loadError && <div className="mb-4 flex items-center gap-2 rounded-xl border border-amber-500/30 bg-amber-500/10 px-3.5 py-2.5 text-[13px] text-amber-800 dark:text-amber-200"><FiAlertTriangle aria-hidden /> {loadError}</div>}

            <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
                <div className="min-w-0 space-y-5">
                    <Card title="New app" description="An app stands for one API you protect. Its clients get tokens that only that API accepts.">
                        <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)_auto] sm:items-start">
                            <Field label="Name" htmlFor="a-name" error={appErr ?? undefined}>
                                <input id="a-name" value={newApp.name} onChange={e => setNewApp(x => ({ ...x, name: e.target.value }))} onKeyDown={e => e.key === "Enter" && createApp()} placeholder="orders-api" className={inputCls} />
                            </Field>
                            <Field label="Description" htmlFor="a-desc">
                                <input id="a-desc" value={newApp.description} onChange={e => setNewApp(x => ({ ...x, description: e.target.value }))} onKeyDown={e => e.key === "Enter" && createApp()} placeholder="Order management API" className={inputCls} />
                            </Field>
                            <div className="sm:pt-[26px]"><button type="button" className={`${btnPrimary} h-10 w-full justify-center sm:w-auto`} disabled={busy} onClick={createApp}><FiPlus /> Create app</button></div>
                        </div>
                    </Card>

                    {apps.length === 0 && !loadError && (
                        <p className="rounded-2xl border border-dashed border-slate-300 px-4 py-8 text-center text-[13px] text-slate-500 dark:border-white/10">No apps yet. Create one above, add a client, then attach the app to a route.</p>
                    )}

                    {apps.map(app => {
                        const pr = protectedRoutes(app.id);
                        return (
                            <Card key={app.id} title={app.name} description={app.description || undefined} icon={FiKey}
                                right={<div className="flex items-center gap-1.5">
                                    <button type="button" className={btnGhost} onClick={() => { setEditing(null); setAdding(app.id); }}><FiPlus /> Add client</button>
                                    <button type="button" className={`${iconBtn} hover:!bg-rose-500/10 hover:!text-rose-500`} aria-label={`Delete app ${app.name}`} title="Delete app" onClick={() => deleteApp(app)}><FiTrash2 /></button>
                                </div>}>
                                <div className="mb-3 flex flex-wrap items-center gap-1.5 text-[12px]">
                                    <FiLink className="text-slate-400" aria-hidden />
                                    {pr.length ? pr.map(r => (
                                        <span key={r.id} className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-2 py-0.5 font-mono text-[11.5px] text-slate-600 dark:border-white/10 dark:text-slate-300">
                                            {routeUrl(r)}{r.oauthScopes ? <span className="text-slate-400"> · needs {r.oauthScopes}</span> : null}
                                        </span>
                                    )) : <span className="text-amber-700 dark:text-amber-300">Not protecting any route yet — open a route and choose “{app.name}” under App security.</span>}
                                </div>

                                {adding === app.id && <div className="mb-3"><ClientForm existing={false} busy={busy} onCancel={() => setAdding(null)} onSave={d => createClient(app, d)} /></div>}

                                {app.clients.length === 0 ? (
                                    adding !== app.id && <p className="rounded-xl border border-dashed border-slate-300 px-3 py-4 text-center text-[12.5px] text-slate-500 dark:border-white/10">No clients. Add one to get a client ID and secret.</p>
                                ) : (
                                    <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 dark:divide-white/[0.05] dark:border-white/[0.07]">
                                        {app.clients.map(c => (
                                            <ClientRow key={c.id} c={c} onAction={onAction(app)} editing={editing === c.id}
                                                form={<ClientForm initial={c} existing busy={busy} onCancel={() => setEditing(null)} onSave={d => saveClient(c, d)} />} />
                                        ))}
                                    </ul>
                                )}
                            </Card>
                        );
                    })}
                </div>

                <div className="min-w-0 space-y-5">
                    <Card title="Endpoints" description="Served on this admin address and on every route that has an app attached.">
                        {oauth ? (
                            <ul className="space-y-2">
                                {([["Token", oauth.endpoints.token], ["Revoke", oauth.endpoints.revoke], ["Introspect", oauth.endpoints.introspect], ["Public keys (JWKS)", oauth.endpoints.jwks], ["Discovery", oauth.endpoints.metadata]] as [string, string][]).map(([l, p]) => (
                                    <li key={l}>
                                        <p className="text-[11.5px] font-medium text-slate-500 dark:text-slate-400">{l}</p>
                                        <div className="flex min-w-0 items-center gap-1"><code className="truncate font-mono text-[12px] text-slate-700 dark:text-slate-200">{host}{p}</code><CopyButton text={`${host}${p}`} label={`${l} URL`} /></div>
                                    </li>
                                ))}
                            </ul>
                        ) : <p className="text-[12.5px] text-slate-500">—</p>}
                    </Card>

                    {settings && (
                        <Card title="Token settings" description="Defaults for every client; a client can override the lifetimes.">
                            <div className="space-y-3">
                                <Field label="Access token lifetime (seconds)" htmlFor="s-at" hint={`${duration(settings.accessTokenTtlSeconds)}. Short is safer; clients renew automatically.`}>
                                    <input id="s-at" type="number" min={60} max={86400} value={settings.accessTokenTtlSeconds} onChange={e => setSettings({ ...settings, accessTokenTtlSeconds: Number(e.target.value) })} className={inputCls} />
                                </Field>
                                <Field label="Refresh token lifetime (seconds)" htmlFor="s-rt" hint={duration(settings.refreshTokenTtlSeconds)}>
                                    <input id="s-rt" type="number" min={300} value={settings.refreshTokenTtlSeconds} onChange={e => setSettings({ ...settings, refreshTokenTtlSeconds: Number(e.target.value) })} className={inputCls} />
                                </Field>
                                <Field label="Issuer (iss)" htmlFor="s-iss" hint="Leave blank to use the address the token was requested on. Set it when backends check the issuer.">
                                    <input id="s-iss" value={settings.issuer} onChange={e => setSettings({ ...settings, issuer: e.target.value })} placeholder="https://api.example.com" className={inputCls} />
                                </Field>
                                <Field label="Endpoint path" htmlFor="s-path" hint="Where the token endpoints live on protected routes.">
                                    <input id="s-path" value={settings.pathPrefix} onChange={e => setSettings({ ...settings, pathPrefix: e.target.value })} className={`${inputCls} font-mono text-[12.5px]`} />
                                </Field>
                                <ToggleRow id="s-legacy" label="Accept client_id / client_secret headers" checked={settings.legacyHeaders} onChange={v => setSettings({ ...settings, legacyHeaders: v })}
                                    description="The old scheme: clients send their secret on every request. Turn off once all clients use access tokens." />
                                {settingsErr && <p className="text-[12px] text-rose-500">{settingsErr}</p>}
                                {settingsDirty && <div className="flex justify-end gap-2">
                                    <button type="button" className={btnGhost} onClick={() => { setSettings(oauth!.settings); setSettingsErr(null); }}>Discard</button>
                                    <button type="button" className={btnPrimary} disabled={busy} onClick={saveSettings}>Save settings</button>
                                </div>}
                            </div>
                        </Card>
                    )}

                    {oauth && (
                        <Card title="Signing keys" description="RS256. Backends can verify tokens offline with the public keys."
                            right={<button type="button" className={btnGhost} onClick={rotateKey}><FiRefreshCw /> Rotate</button>}>
                            <ul className="space-y-2">
                                {oauth.keys.map(k => (
                                    <li key={k.kid} className="flex items-center justify-between gap-2">
                                        <div className="min-w-0">
                                            <code className="block truncate font-mono text-[12px] text-slate-700 dark:text-slate-200">{k.kid}</code>
                                            <p className="text-[11.5px] text-slate-500 dark:text-slate-400">created {when(k.created)}{k.retired ? ` · retired ${when(k.retired)}` : ""}</p>
                                        </div>
                                        {k.active ? <Pill tone="good">signing</Pill> : <Pill tone="muted">verify only</Pill>}
                                    </li>
                                ))}
                            </ul>
                        </Card>
                    )}

                    {legacyInUse && (
                        <p className="flex gap-2 rounded-xl border border-slate-200 p-3 text-[12px] leading-relaxed text-slate-500 dark:border-white/10 dark:text-slate-400">
                            <FiAlertTriangle className="mt-0.5 shrink-0 text-amber-500" aria-hidden />
                            Old-style client_id / client_secret headers are still accepted. Move clients to access tokens, then turn this off in Token settings.
                        </p>
                    )}
                </div>
            </div>

            {issued && <CredentialsModal issued={issued} tokenUrl={tokenUrlFor(issued.client.appid)} apiUrl={baseFor(issued.client.appid)} onClose={() => setIssued(null)} />}
            {sampleFor && (
                <Overlay title={`Use “${sampleFor.c.name}”`} onClose={() => setSampleFor(null)} wide>
                    <p className="mb-3 text-[12.5px] text-slate-600 dark:text-slate-300">
                        Token endpoint <code className="font-mono">{tokenUrlFor(sampleFor.app.id)}</code>. The secret isn't stored in readable form, so the samples read it from <code className="font-mono">SCALEGUARD_CLIENT_SECRET</code>. Lost it? Rotate the secret.
                    </p>
                    <SamplePanel tokenUrl={tokenUrlFor(sampleFor.app.id)} apiUrl={baseFor(sampleFor.app.id)} client={sampleFor.c} />
                </Overlay>
            )}
            {confirm && <Confirm title={confirm.title} body={confirm.body} action={confirm.action} danger={confirm.danger} onConfirm={confirm.run} onClose={() => setConfirm(null)} />}
        </div>
    );
}
