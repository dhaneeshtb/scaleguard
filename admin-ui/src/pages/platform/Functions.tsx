import { useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import CodeMirror from "@uiw/react-codemirror";
import { useToast } from "@chakra-ui/react";
import { useSearchParams } from "react-router-dom";
import { FiAlertTriangle, FiCheckCircle, FiCode, FiLink, FiLock, FiPlay, FiPlus, FiShield, FiTrash2, FiZap } from "react-icons/fi";
import { useAuth } from "../../contexts/AuthContext";
import { Card, Field, PageHeader, Pill, SaveBar, Stat, ToggleRow, ago, fmt, inputCls } from "../../components/ui/controls";

interface Def {
    id?: string; name: string; description?: string; code: string; enabled: boolean; methods: string[];
    timeoutMs: number; maxRequestBytes: number; maxResponseBytes: number; maxConcurrent: number; maxMemoryMb: number;
    networkEnabled: boolean; allowedHosts: string[]; allowPrivateNetworks: boolean; maxHttpCalls: number; maxHttpBytes: number;
    secrets: string[]; config: Record<string, string>;
    routes?: { sourceId: string; name: string; host: string; path: string }[]; stats?: { inflight: number; total: number; failed: number };
}
interface Summary { id: string; name: string; description?: string; enabled: boolean; networkEnabled: boolean; methods: string[]; routes: number; total: number; failed: number; inflight: number; updated: number }
interface Overview { enabled: boolean; functions: Summary[]; secrets: string[]; limits: { maxTimeoutMs: number; maxBodyBytes: number }; defaults: Def }
interface Run { runId: string; at: number; method: string; path: string; status: number; ms: number; error?: string; detail?: string; logs: string[] }
interface TestResult { status: number; headers: Record<string, string>; body: string; error?: string; detail?: string; logs: string[]; ms: number; runId: string }

const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"];
const clone = <T,>(x: T): T => JSON.parse(JSON.stringify(x));
const lines = (s: string) => s.split("\n").map(x => x.trim()).filter(Boolean);
const errText = (e: any, fb: string) => e?.response?.data?.message || fb;
const isDark = () => document.documentElement.classList.contains("dark");

export default function Functions() {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const headers = useMemo(() => ({ Authorization: auth.data.token }), [auth]);
    const host = auth.data.host;
    const [params, setParams] = useSearchParams();
    const id = params.get("id");

    const [overview, setOverview] = useState<Overview | null>(null);
    const [def, setDef] = useState<Def | null>(null);
    const [saved, setSaved] = useState<string>("");
    const [saving, setSaving] = useState(false);
    const [hostsText, setHostsText] = useState("");
    const [configText, setConfigText] = useState("");
    const [check, setCheck] = useState<{ ok: boolean; error: string } | null>(null);
    const [sources, setSources] = useState<any[]>([]);
    const [attachTo, setAttachTo] = useState("");
    const [runs, setRuns] = useState<Run[]>([]);
    const [secretName, setSecretName] = useState("");
    const [secretValue, setSecretValue] = useState("");

    const api = useCallback((method: string, path: string, data?: any) =>
        axios.request({ method, url: `${host}/functions${path}?scaleguard=true`, data, headers }), [host, headers]);

    const loadOverview = useCallback(async () => {
        const r = await api("get", "");
        setOverview(r.data);
        return r.data as Overview;
    }, [api]);

    const open = useCallback((d: Def) => {
        setDef(clone(d));
        setSaved(JSON.stringify(d));
        setHostsText((d.allowedHosts || []).join("\n"));
        setConfigText(Object.entries(d.config || {}).map(([k, v]) => `${k}=${v}`).join("\n"));
        setCheck(null);
    }, []);

    useEffect(() => { loadOverview().catch(e => toast({ title: errText(e, "Couldn't load functions"), status: "error" })); }, []);
    useEffect(() => {
        axios.get(`${host}/config/sourcesystems?scaleguard=true`, { headers }).then(r => setSources(r.data.sourcesystems || [])).catch(() => { });
    }, [host, headers]);
    useEffect(() => {
        if (!id) return;
        api("get", `/${id}`).then(r => open(r.data)).catch(e => toast({ title: errText(e, "Function not found"), status: "error" }));
    }, [id]);
    useEffect(() => {
        if (!id) return;
        const t = setInterval(() => api("get", `/${id}/runs`).then(r => setRuns(r.data.runs)).catch(() => { }), 4000);
        api("get", `/${id}/runs`).then(r => setRuns(r.data.runs)).catch(() => { });
        return () => clearInterval(t);
    }, [id, api]);

    const current = useMemo<Def | null>(() => {
        if (!def) return null;
        const config: Record<string, string> = {};
        lines(configText).forEach(l => { const i = l.indexOf("="); if (i > 0) config[l.slice(0, i).trim()] = l.slice(i + 1).trim(); });
        return { ...def, allowedHosts: lines(hostsText), config };
    }, [def, hostsText, configText]);
    const dirty = !!current && JSON.stringify({ ...current, routes: undefined, stats: undefined }) !== JSON.stringify({ ...JSON.parse(saved || "{}"), routes: undefined, stats: undefined });

    const set = (patch: Partial<Def>) => setDef(d => (d ? { ...d, ...patch } : d));
    const num = (k: keyof Def) => (e: React.ChangeEvent<HTMLInputElement>) => set({ [k]: Number(e.target.value) } as any);

    const save = async () => {
        if (!current) return;
        setSaving(true);
        try {
            const r = current.id ? await api("post", `/${current.id}`, current) : await api("post", "", current);
            open(r.data);
            setParams({ id: r.data.id });
            loadOverview();
            toast({ title: "Function saved — live on every node", status: "success", duration: 2500 });
        } catch (e: any) {
            toast({ title: errText(e, "Couldn't save"), status: "error", duration: 8000 });
        } finally { setSaving(false); }
    };

    const create = () => {
        if (!overview) return;
        const d = { ...clone(overview.defaults), name: "", enabled: true } as Def;
        setParams({});
        open(d);
        setSaved("");
    };

    const remove = async () => {
        if (!current?.id || !window.confirm(`Delete function "${current.name}"?`)) return;
        try { await api("delete", `/${current.id}`); setParams({}); setDef(null); loadOverview(); }
        catch (e: any) { toast({ title: errText(e, "Couldn't delete"), status: "error" }); }
    };

    const runCheck = async () => {
        const r = await api("post", "/check", { code: current?.code });
        setCheck(r.data);
    };

    const attach = async () => {
        if (!current?.id || !attachTo) return;
        try {
            await api("post", `/${current.id}/attach`, { sourceId: attachTo });
            const r = await api("get", `/${current.id}`);
            setDef(d => (d ? { ...d, routes: r.data.routes } : d));
            loadOverview();
            toast({ title: "Route now answers with this function", status: "success" });
        } catch (e: any) { toast({ title: errText(e, "Couldn't attach"), status: "error" }); }
    };

    const addSecret = async () => {
        try {
            await api("put", `/secrets/${secretName}`, { value: secretValue });
            setSecretName(""); setSecretValue("");
            loadOverview();
        } catch (e: any) { toast({ title: errText(e, "Couldn't save the secret"), status: "error" }); }
    };

    // ---- test panel
    const [tm, setTm] = useState("GET");
    const [tpath, setTpath] = useState("/");
    const [tquery, setTquery] = useState("");
    const [theaders, setTheaders] = useState("");
    const [tbody, setTbody] = useState("");
    const [tres, setTres] = useState<TestResult | null>(null);
    const [testing, setTesting] = useState(false);
    const runTest = async () => {
        if (!current?.id) return;
        setTesting(true);
        const h: Record<string, string> = {};
        lines(theaders).forEach(l => { const i = l.indexOf(":"); if (i > 0) h[l.slice(0, i).trim()] = l.slice(i + 1).trim(); });
        try {
            const r = await api("post", `/${current.id}/test`, { method: tm, path: tpath, query: tquery, headers: h, body: tbody });
            setTres(r.data);
            api("get", `/${current.id}/runs`).then(x => setRuns(x.data.runs));
        } catch (e: any) { toast({ title: errText(e, "Test failed"), status: "error" }); }
        finally { setTesting(false); }
    };

    if (!overview) return <div className="p-6 text-slate-500">Loading…</div>;

    return (
        <div className="mx-auto max-w-5xl pb-24">
            <PageHeader icon={FiZap} eyebrow="Platform" title="Cloud functions"
                description="Small Groovy programs that answer a route directly — no backend needed. They run in a sandbox and can do only what you allow below."
                actions={<button type="button" onClick={create} className="inline-flex h-10 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-4 text-[13px] font-semibold text-slate-950"><FiPlus /> New function</button>} />

            {!overview.enabled && (
                <div className="mb-5 rounded-xl border border-amber-500/30 bg-amber-500/10 p-4 text-[13px] text-amber-800 dark:text-amber-200">
                    Cloud functions are switched off on this server (<code>scaleguard.functions=false</code>). Routes that use them answer 503.
                </div>
            )}

            {!current && (
                <Card title="Functions" description="Pick one to edit, or create a new one.">
                    {overview.functions.length === 0 && <p className="text-[13px] text-slate-500">No functions yet.</p>}
                    <div className="divide-y divide-slate-200 dark:divide-white/10">
                        {overview.functions.map(f => (
                            <button key={f.id} type="button" onClick={() => setParams({ id: f.id })} className="flex w-full items-center justify-between gap-3 py-3 text-left">
                                <span className="min-w-0">
                                    <span className="block truncate text-[14px] font-semibold text-slate-900 dark:text-white">{f.name}</span>
                                    <span className="block truncate text-[12px] text-slate-500">{f.description || "No description"} · {f.methods.length ? f.methods.join(", ") : "any method"}</span>
                                </span>
                                <span className="flex shrink-0 items-center gap-2">
                                    <Pill tone={f.enabled ? "good" : "muted"}>{f.enabled ? "enabled" : "disabled"}</Pill>
                                    {f.networkEnabled && <Pill tone="warn">network</Pill>}
                                    <Pill tone={f.routes ? "info" : "muted"}>{f.routes} route{f.routes === 1 ? "" : "s"}</Pill>
                                    <span className="text-[12px] tabular-nums text-slate-500">{fmt(f.total)} runs{f.failed ? `, ${fmt(f.failed)} failed` : ""}</span>
                                </span>
                            </button>
                        ))}
                    </div>
                </Card>
            )}

            {current && (
                <div className="space-y-5">
                    <Card title="Function" icon={FiCode} right={current.id && <button type="button" onClick={remove} className="inline-flex items-center gap-1.5 text-[12.5px] font-semibold text-rose-600"><FiTrash2 /> Delete</button>}>
                        <div className="grid gap-4 sm:grid-cols-2">
                            <Field label="Name" htmlFor="fn-name"><input id="fn-name" className={inputCls} value={current.name} onChange={e => set({ name: e.target.value })} /></Field>
                            <Field label="Description" htmlFor="fn-desc"><input id="fn-desc" className={inputCls} value={current.description || ""} onChange={e => set({ description: e.target.value })} /></Field>
                        </div>
                        <div className="mt-4"><ToggleRow id="fn-enabled" label="Enabled" description="A disabled function answers 503 on every route that uses it." checked={current.enabled} onChange={v => set({ enabled: v })} /></div>
                        <div className="mt-4">
                            <Field label="Code (Groovy)" hint={<>Available: <code>req</code>, <code>res</code>, <code>ctx</code>, <code>log</code>, <code>http</code>, <code>config</code>. Return a Map/List (JSON) or text, or use <code>res.status(201).json(…)</code>. Imports, classes, annotations, reflection, files, threads and System are not allowed.</>}>
                                <div className="overflow-hidden rounded-xl border border-slate-200 text-[13px] dark:border-white/10">
                                    <CodeMirror value={current.code} height="320px" theme={isDark() ? "dark" : "light"} onChange={v => set({ code: v })} />
                                </div>
                            </Field>
                            <div className="mt-2 flex items-center gap-3">
                                <button type="button" onClick={runCheck} className="h-9 rounded-xl border border-slate-200 px-3 text-[12.5px] font-semibold dark:border-white/10">Check code</button>
                                {check && (check.ok ? <Pill tone="good"><FiCheckCircle /> compiles</Pill> : <span className="text-[12.5px] text-rose-600">{check.error}</span>)}
                            </div>
                        </div>
                    </Card>

                    <Card title="Request & limits" icon={FiShield} description="Hard limits the sandbox enforces on every run.">
                        <Field label="HTTP methods" hint="Leave all unchecked to answer every method. Other methods get 405 with an Allow header; the script also sees req.method.">
                            <div className="flex flex-wrap gap-2">
                                {METHODS.map(m => (
                                    <label key={m} className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 py-1 text-[12.5px] dark:border-white/10">
                                        <input type="checkbox" checked={current.methods.includes(m)} onChange={e => set({ methods: e.target.checked ? [...current.methods, m] : current.methods.filter(x => x !== m) })} /> {m}
                                    </label>
                                ))}
                            </div>
                        </Field>
                        <div className="mt-4 grid gap-4 sm:grid-cols-3">
                            <Field label="Time limit (ms)" hint={`Max ${fmt(overview.limits.maxTimeoutMs)}`}><input type="number" className={inputCls} value={current.timeoutMs} onChange={num("timeoutMs")} /></Field>
                            <Field label="Max concurrent runs" hint="More are answered 503."><input type="number" className={inputCls} value={current.maxConcurrent} onChange={num("maxConcurrent")} /></Field>
                            <Field label="Allocation budget (MB)" hint="Stops runaway memory use."><input type="number" className={inputCls} value={current.maxMemoryMb} onChange={num("maxMemoryMb")} /></Field>
                            <Field label="Max request body (bytes)" hint="Larger requests get 413."><input type="number" className={inputCls} value={current.maxRequestBytes} onChange={num("maxRequestBytes")} /></Field>
                            <Field label="Max response body (bytes)" hint="Larger responses get 502."><input type="number" className={inputCls} value={current.maxResponseBytes} onChange={num("maxResponseBytes")} /></Field>
                        </div>
                    </Card>

                    <Card title="Permissions" icon={FiLock} description="Everything here is off by default. Review the code before you turn anything on.">
                        <div className="mb-4 flex gap-3 rounded-xl border border-amber-500/30 bg-amber-500/10 p-3.5 text-[12.5px] leading-relaxed text-amber-900 dark:text-amber-100">
                            <FiAlertTriangle className="mt-0.5 shrink-0 text-[16px]" />
                            <span>The sandbox keeps functions away from the gateway's files, processes and settings, but it runs inside the gateway's JVM and is not a hard security boundary. Only let people you trust edit functions, read the code before enabling network access, and never give a function more than it needs. Outbound calls leave from this server's network position.</span>
                        </div>
                        <ToggleRow id="fn-net" label="Allow outbound network calls" description="Lets the function call other servers through the http client, only to the hosts listed below." checked={current.networkEnabled} onChange={v => set({ networkEnabled: v })} />
                        {current.networkEnabled && (
                            <div className="mt-4 space-y-4">
                                <Field label="Allowed hosts (one per line)" hint={<>Leave empty to allow any public host. To restrict: <code>[http://|https://]host[:port]</code>, no scheme means https on port 443, <code>*.example.com</code> matches subdomains; anything else is refused, including redirects. Private and metadata addresses are blocked either way.</>}>
                                    <textarea className={`${inputCls} h-28 py-2 font-mono`} value={hostsText} onChange={e => setHostsText(e.target.value)} placeholder={"api.example.com\nhttps://*.partner.io:8443"} />
                                </Field>
                                <ToggleRow id="fn-priv" label="Also allow private networks (danger)" description="Permits loopback, 10/8, 172.16/12, 192.168/16, CGNAT and IPv6 ULA targets — i.e. servers inside your network, or this gateway itself. Cloud-metadata and link-local addresses stay blocked." checked={current.allowPrivateNetworks} onChange={v => set({ allowPrivateNetworks: v })} />
                                <div className="grid gap-4 sm:grid-cols-2">
                                    <Field label="Max outbound calls per run"><input type="number" className={inputCls} value={current.maxHttpCalls} onChange={num("maxHttpCalls")} /></Field>
                                    <Field label="Max outbound response (bytes)"><input type="number" className={inputCls} value={current.maxHttpBytes} onChange={num("maxHttpBytes")} /></Field>
                                </div>
                            </div>
                        )}
                        <div className="mt-5">
                            <Field label="Secrets this function may read" hint="ctx.secret('NAME') returns the value only for the names ticked here. Values are write-only and shared across the cluster.">
                                <div className="flex flex-wrap gap-2">
                                    {overview.secrets.length === 0 && <span className="text-[12.5px] text-slate-500">No secrets defined yet.</span>}
                                    {overview.secrets.map(s => (
                                        <span key={s} className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 py-1 text-[12.5px] dark:border-white/10">
                                            <label className="inline-flex items-center gap-1.5"><input type="checkbox" checked={current.secrets.includes(s)} onChange={e => set({ secrets: e.target.checked ? [...current.secrets, s] : current.secrets.filter(x => x !== s) })} /> {s}</label>
                                            <button type="button" title="Delete secret" aria-label={`Delete secret ${s}`} className="text-rose-500" onClick={async () => { if (window.confirm(`Delete secret ${s} for all functions?`)) { await api("delete", `/secrets/${s}`); loadOverview(); } }}><FiTrash2 /></button>
                                        </span>
                                    ))}
                                </div>
                            </Field>
                            <div className="mt-2 flex flex-wrap gap-2">
                                <input aria-label="Secret name" className={`${inputCls} max-w-[200px]`} placeholder="NAME" value={secretName} onChange={e => setSecretName(e.target.value)} />
                                <input aria-label="Secret value" type="password" className={`${inputCls} max-w-[260px]`} placeholder="value (write-only)" value={secretValue} onChange={e => setSecretValue(e.target.value)} />
                                <button type="button" onClick={addSecret} disabled={!secretName || !secretValue} className="h-10 rounded-xl border border-slate-200 px-3 text-[12.5px] font-semibold disabled:opacity-50 dark:border-white/10">Save secret</button>
                            </div>
                        </div>
                        <div className="mt-5">
                            <Field label="Config values (KEY=value per line)" hint="Not secret. Read as config.KEY.">
                                <textarea className={`${inputCls} h-20 py-2 font-mono`} value={configText} onChange={e => setConfigText(e.target.value)} />
                            </Field>
                        </div>
                    </Card>

                    {current.id && (
                        <Card title="Routes" icon={FiLink} description="A route answered by this function never contacts a host group; auth, rate limits, WAF, IP rules, CORS and logging still apply.">
                            {(current.routes || []).map(r => <p key={r.sourceId} className="mb-1 text-[13px]"><Pill tone="info">route</Pill> {r.name || r.host} — {r.host}{r.path}</p>)}
                            {(current.routes || []).length === 0 && <p className="mb-2 text-[13px] text-slate-500">Not attached to a route yet.</p>}
                            <div className="mt-3 flex flex-wrap gap-2">
                                <select aria-label="Route" className={`${inputCls} max-w-sm`} value={attachTo} onChange={e => setAttachTo(e.target.value)}>
                                    <option value="">Choose a route…</option>
                                    {sources.map(s => <option key={s.id} value={s.id}>{s.name || s.host} — {s.host}{s.basePath || ""}</option>)}
                                </select>
                                <button type="button" onClick={attach} disabled={!attachTo || dirty} className="h-10 rounded-xl bg-slate-900 px-4 text-[12.5px] font-semibold text-white disabled:opacity-50 dark:bg-white dark:text-slate-900">Use for this route</button>
                            </div>
                            <p className="mt-2 text-[12px] text-slate-500">This replaces that route's current target. The URL and methods are the route's host and path plus the methods ticked above; to serve several URLs make one route per path.</p>
                        </Card>
                    )}

                    {current.id && (
                        <Card title="Test" icon={FiPlay} description="Runs the saved function on this node exactly as a real request would (same limits and permissions).">
                            <div className="grid gap-3 sm:grid-cols-[110px_1fr_1fr]">
                                <select aria-label="Method" className={inputCls} value={tm} onChange={e => setTm(e.target.value)}>{METHODS.map(m => <option key={m}>{m}</option>)}</select>
                                <input aria-label="Path" className={inputCls} value={tpath} onChange={e => setTpath(e.target.value)} placeholder="/path" />
                                <input aria-label="Query" className={inputCls} value={tquery} onChange={e => setTquery(e.target.value)} placeholder="a=1&b=2" />
                            </div>
                            <div className="mt-3 grid gap-3 sm:grid-cols-2">
                                <textarea aria-label="Headers" className={`${inputCls} h-24 py-2 font-mono`} value={theaders} onChange={e => setTheaders(e.target.value)} placeholder={"Header: value"} />
                                <textarea aria-label="Body" className={`${inputCls} h-24 py-2 font-mono`} value={tbody} onChange={e => setTbody(e.target.value)} placeholder='{"name": "test"}' />
                            </div>
                            <button type="button" onClick={runTest} disabled={testing || dirty} className="mt-3 inline-flex h-10 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-4 text-[13px] font-semibold text-slate-950 disabled:opacity-50"><FiPlay /> {dirty ? "Save first" : "Run"}</button>
                            {tres && (
                                <div className="mt-4 space-y-2">
                                    <div className="flex items-center gap-2"><Pill tone={tres.status < 400 ? "good" : "bad"}>{tres.status}</Pill><span className="text-[12px] text-slate-500">{tres.ms} ms</span>{tres.error && <Pill tone="bad">{tres.error}</Pill>}</div>
                                    {tres.detail && <p className="text-[12.5px] text-rose-600">{tres.detail}</p>}
                                    <pre className="max-h-64 overflow-auto rounded-xl border border-slate-200 p-3 text-[12px] dark:border-white/10">{tres.body}</pre>
                                    {tres.logs.length > 0 && <pre className="max-h-40 overflow-auto rounded-xl bg-slate-100 p-3 text-[12px] dark:bg-white/5">{tres.logs.join("\n")}</pre>}
                                </div>
                            )}
                        </Card>
                    )}

                    {current.id && (
                        <Card title="Recent runs" description="The last 50 runs on this node (cluster nodes keep their own).">
                            <div className="mb-3 grid grid-cols-3 gap-3">
                                <Stat label="Total" value={fmt(current.stats?.total ?? 0)} /><Stat label="Failed" value={fmt(current.stats?.failed ?? 0)} tone={(current.stats?.failed ?? 0) > 0 ? "warn" : undefined} /><Stat label="Running" value={fmt(current.stats?.inflight ?? 0)} />
                            </div>
                            {runs.length === 0 && <p className="text-[13px] text-slate-500">No runs yet.</p>}
                            <div className="divide-y divide-slate-200 dark:divide-white/10">
                                {runs.map(r => (
                                    <details key={r.runId} className="py-2">
                                        <summary className="flex cursor-pointer items-center gap-2 text-[12.5px]">
                                            <Pill tone={r.status < 400 ? "good" : "bad"}>{r.status}</Pill><span className="font-mono">{r.method} {r.path}</span>
                                            <span className="text-slate-500">{r.ms} ms · {ago(r.at)}</span>{r.error && <Pill tone="bad">{r.error}</Pill>}
                                        </summary>
                                        {r.detail && <p className="mt-1 text-[12px] text-rose-600">{r.detail}</p>}
                                        {r.logs.length > 0 && <pre className="mt-1 overflow-auto rounded-lg bg-slate-100 p-2 text-[12px] dark:bg-white/5">{r.logs.join("\n")}</pre>}
                                    </details>
                                ))}
                            </div>
                        </Card>
                    )}
                    <button type="button" onClick={() => { setParams({}); setDef(null); }} className="text-[12.5px] font-semibold text-slate-500">← All functions</button>
                </div>
            )}
            <SaveBar dirty={dirty || (!!current && !current.id)} saving={saving} onSave={save} onDiscard={() => { if (id) { api("get", `/${id}`).then(r => open(r.data)); } else { setDef(null); } }} label={current?.id ? "Save changes" : "Create function"} />
        </div>
    );
}
