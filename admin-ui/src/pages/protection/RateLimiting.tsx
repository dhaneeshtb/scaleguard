import { useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import { useToast } from "@chakra-ui/react";
import { Link as RouterLink } from "react-router-dom";
import { FiActivity, FiAlertTriangle, FiGlobe, FiLayers, FiServer, FiSliders, FiSlash, FiTrash2, FiUserCheck, FiSearch } from "react-icons/fi";
import { useAuth } from "../../contexts/AuthContext";
import { updateSource } from "../../components/sourceupdate";
import { Card, Field, PageHeader, Pill, SaveBar, Segmented, Stat, ToggleRow, ago, fmt, inputCls } from "../../components/ui/controls";

type ClusterMode = "local" | "shared" | "divided" | "strict";
interface Settings { enabled: boolean; perMinute: number; burstSeconds: number; trustedProxies: string; clusterMode?: ClusterMode }
interface LimitedClient { client: string; route: string; rejected: number; limitPerMinute: number; lastRejectedSecondsAgo: number }
interface RouteRow { id: string; name: string; host: string; rateLimit: number | null; ipAllow?: string | null; ipDeny?: string | null }
interface AclEntry { value: string; note: string | null; addedAt: number; expiresAt: number | null }
interface Access { blocked: AclEntry[]; exempt: AclEntry[]; blockedRequests: number; recentlyBlocked: { client: string; requests: number; lastSecondsAgo: number }[] }
interface View { settings: Settings; stats: { allowed: number; rejected: number; trackedClients: number; limitedClients: LimitedClient[]; cluster?: boolean; clusterNodes?: number }; routes: RouteRow[]; access?: Access }

const CLUSTER_HELP: Record<ClusterMode, string> = {
    shared: "One budget for the whole cluster: nodes tell each other what every client spent, so a client gets the limit once no matter which node it reaches.",
    strict: "One budget that is never exceeded: every 100 ms the nodes split each client's remaining allowance by where its requests are arriving. Steadiest per-client throughput; a client that moves to another node may be refused for up to ~100 ms.",
    divided: "Each node allows its share (limit ÷ live nodes). No coordination traffic; accurate when your load balancer spreads clients evenly.",
    local: "Each node counts on its own, so a client can get up to the limit from every node.",
};

const perSecond = (perMinute: number) => {
    const v = perMinute / 60;
    return v >= 10 ? Math.round(v).toLocaleString() : v.toFixed(1);
};

export default function RateLimiting() {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const headers = useMemo(() => ({ Authorization: auth.data.token }), [auth]);
    const host = auth.data.host;

    const [view, setView] = useState<View | null>(null);
    const [draft, setDraft] = useState<Settings | null>(null);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);

    const load = useCallback(async (resetDraft: boolean) => {
        try {
            const r = await axios.get(`${host}/ratelimit?scaleguard=true`, { headers });
            setView(r.data);
            setLoadError(null);
            if (resetDraft) setDraft(r.data.settings);
        } catch (e: any) {
            setLoadError(e?.response?.status === 404 ? "This ScaleGuard server doesn't support rate-limit settings yet. Update the server to the latest build." : "Couldn't load rate-limit settings.");
        }
    }, [host, headers]);

    useEffect(() => { load(true); }, [load]);
    useEffect(() => { // live stats
        const t = setInterval(() => load(false), 5000);
        return () => clearInterval(t);
    }, [load]);

    const dirty = !!draft && !!view && JSON.stringify(draft) !== JSON.stringify(view.settings);

    const save = async () => {
        if (!draft) return;
        setSaving(true); setError(null);
        try {
            const r = await axios.post(`${host}/ratelimit?scaleguard=true`, draft, { headers });
            setView(r.data); setDraft(r.data.settings);
            toast({ title: "Rate limits saved", description: "New limits apply immediately.", status: "success", duration: 2500, position: "top-right" });
        } catch (e: any) {
            setError(e?.response?.data?.message || "Couldn't save the settings.");
        } finally { setSaving(false); }
    };

    const block = async (client: string, ttlSeconds: number) => {
        try {
            const r = await axios.post(`${host}/ratelimit/access?scaleguard=true`, { list: "blocked", values: client, note: "Blocked from Clients being limited", ttlSeconds }, { headers });
            setView(r.data);
            toast({ title: `${client} blocked${ttlSeconds ? ` for ${ttlSeconds >= 86400 ? `${ttlSeconds / 86400} day` : `${ttlSeconds / 3600} h`}` : ""}`, status: "success", duration: 2500, position: "top-right" });
        } catch (e: any) {
            toast({ title: "Couldn't block", description: e?.response?.data?.message, status: "error", duration: 4000, position: "top-right" });
        }
    };

    if (loadError) return <div className="sg-card mx-auto max-w-xl p-8 text-center"><FiAlertTriangle className="mx-auto mb-3 text-2xl text-amber-500" /><p className="text-[14px] text-slate-700 dark:text-slate-200">{loadError}</p></div>;
    if (!view || !draft) return <div className="space-y-4">{[0, 1, 2].map(i => <div key={i} className="sg-card h-40 animate-pulse" />)}</div>;

    const burstRequests = Math.max(10, Math.ceil(draft.perMinute / 60 * draft.burstSeconds));
    const proxies = draft.trustedProxies.split(",").map(s => s.trim()).filter(Boolean);

    return (
        <div className="mx-auto max-w-6xl pb-24">
            <PageHeader icon={FiSliders} eyebrow="Protect" title="Rate limiting"
                description="Caps how many requests each client can send to each route. Clients over the limit get HTTP 429 with a Retry-After header. Changes apply immediately — no restart." />

            <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
                <div className="min-w-0 space-y-5">
                    <Card icon={FiSliders} title="Default limit" description="Applies to every route that doesn't set its own limit.">
                        <div className="space-y-4">
                            <ToggleRow id="rl-enabled" label="Limit requests per client" description="Turn off to stop limiting every route that uses the default. Routes with their own limit keep it."
                                checked={draft.enabled} onChange={v => setDraft({ ...draft, enabled: v })} />
                            <div className={`grid gap-4 sm:grid-cols-2 ${draft.enabled ? "" : "pointer-events-none opacity-50"}`}>
                                <Field label="Requests per minute, per client, per route" htmlFor="rl-rpm" hint={<>About <b className="text-slate-700 dark:text-slate-200">{perSecond(draft.perMinute)}</b> requests per second sustained.</>}>
                                    <input id="rl-rpm" inputMode="numeric" className={`${inputCls} font-mono`} value={draft.perMinute}
                                        onChange={e => setDraft({ ...draft, perMinute: parseInt(e.target.value.replace(/\D/g, "") || "0", 10) })} />
                                </Field>
                                <Field label={`Burst allowance: ${draft.burstSeconds}s`} htmlFor="rl-burst" hint={<>A client can briefly send up to <b className="text-slate-700 dark:text-slate-200">{fmt(burstRequests)}</b> requests at once before limiting starts.</>}>
                                    <input id="rl-burst" type="range" min={1} max={60} value={draft.burstSeconds} onChange={e => setDraft({ ...draft, burstSeconds: +e.target.value })}
                                        className="mt-3 h-2 w-full cursor-pointer appearance-none rounded-full bg-slate-200 accent-teal-500 dark:bg-white/10" />
                                </Field>
                            </div>
                        </div>
                    </Card>

                    <Card icon={FiGlobe} title="Trusted proxies" description="Only connections from these addresses may tell ScaleGuard the real client IP through X-Forwarded-For. Anyone else's header is ignored, so clients can't fake their address to dodge limits.">
                        <Field label="IP addresses or CIDR ranges, comma-separated" htmlFor="rl-proxies" error={error || undefined}
                            hint="Add your CDN or upstream load balancer here (for Cloudflare, its published IP ranges). Leave loopback in place for local tools.">
                            <textarea id="rl-proxies" rows={3} className={`${inputCls} h-auto py-2.5 font-mono text-[12.5px]`} value={draft.trustedProxies}
                                onChange={e => { setDraft({ ...draft, trustedProxies: e.target.value }); setError(null); }} />
                        </Field>
                        <div className="mt-3 flex flex-wrap gap-1.5">{proxies.map(p => <Pill key={p} tone="muted">{p}</Pill>)}</div>
                    </Card>

                    {view.access && <AccessListsCard access={view.access} onChanged={v => setView(v)} />}

                    {view.stats.cluster && (
                        <Card icon={FiServer} title="Across the cluster" description={`${view.stats.clusterNodes ?? 1} live node${(view.stats.clusterNodes ?? 1) === 1 ? "" : "s"} share this configuration.`}>
                            <Segmented id="rl-cluster" value={(draft.clusterMode ?? "shared") as ClusterMode} onChange={v => setDraft({ ...draft, clusterMode: v })}
                                options={[{ value: "shared", label: "Shared" }, { value: "strict", label: "Strict" }, { value: "divided", label: "Divided" }, { value: "local", label: "Per node" }]} />
                            <p className="mt-3 text-[12.5px] leading-relaxed text-slate-600 dark:text-slate-300">{CLUSTER_HELP[(draft.clusterMode ?? "shared") as ClusterMode]}</p>
                        </Card>
                    )}

                    <RouteLimits view={view} defaults={view.settings} onChanged={() => load(false)} />
                </div>

                <aside className="space-y-4 lg:sticky lg:top-24">
                    <Card icon={FiActivity} title="Live activity" description="Since the server started · refreshes every 5s">
                        <div className="grid grid-cols-2 gap-2">
                            <Stat label="Allowed" value={fmt(view.stats.allowed)} tone="good" />
                            <Stat label="Limited" value={fmt(view.stats.rejected)} tone={view.stats.rejected ? "warn" : undefined} />
                        </div>
                        <div className="mt-2 flex items-center justify-between rounded-xl border border-slate-200 bg-white/60 px-4 py-2.5 dark:border-white/[0.07] dark:bg-white/[0.02]">
                            <span className="flex items-center gap-2 text-[12.5px] font-medium text-slate-600 dark:text-slate-300"><FiSlash className="text-rose-500" aria-hidden /> Blocked by IP</span>
                            <span className={`text-[18px] font-semibold tabular-nums ${view.access?.blockedRequests ? "text-rose-600 dark:text-rose-300" : "text-slate-900 dark:text-white"}`}>{fmt(view.access?.blockedRequests ?? 0)}</span>
                        </div>
                        <p className="mt-3 text-[11.5px] text-slate-500 dark:text-slate-400">{fmt(view.stats.trackedClients)} client/route pairs tracked right now.</p>
                    </Card>
                    <Card title="Clients being limited" description="Last 10 minutes">
                        {view.stats.limitedClients.length === 0 ? (
                            <p className="rounded-xl border border-dashed border-slate-300 px-3 py-4 text-center text-[12.5px] text-slate-500 dark:border-white/10">Nobody has hit a limit recently.</p>
                        ) : (
                            <ul className="space-y-2">
                                {view.stats.limitedClients.slice(0, 8).map((c, i) => (
                                    <li key={i} className="rounded-xl border border-slate-200 p-2.5 dark:border-white/[0.07]">
                                        <div className="flex items-center justify-between gap-2">
                                            <span className="truncate font-mono text-[12.5px] font-semibold text-slate-800 dark:text-slate-100">{c.client}</span>
                                            <Pill tone="warn">{fmt(c.rejected)} limited</Pill>
                                        </div>
                                        <p className="mt-1 truncate text-[11.5px] text-slate-500 dark:text-slate-400">{c.route} · {fmt(c.limitPerMinute)}/min · {c.lastRejectedSecondsAgo}s ago</p>
                                        {!(view.access?.blocked || []).some(b => b.value === c.client) && (
                                            <div className="mt-2 flex items-center gap-1.5 text-[11.5px]">
                                                <FiSlash className="text-rose-500" aria-hidden />
                                                <span className="text-slate-500 dark:text-slate-400">Block</span>
                                                {[[3600, "1 h"], [86400, "24 h"], [0, "always"]].map(([t, l]) => (
                                                    <button key={String(t)} onClick={() => block(c.client, t as number)} className="rounded-md px-1.5 py-0.5 font-semibold text-rose-600 hover:bg-rose-500/10 dark:text-rose-300">{l}</button>
                                                ))}
                                            </div>
                                        )}
                                    </li>
                                ))}
                            </ul>
                        )}
                    </Card>
                    {view.access && view.access.recentlyBlocked.length > 0 && (
                        <Card title="Recently blocked" description="Requests refused by the blocklist · last hour">
                            <ul className="space-y-1.5">
                                {view.access.recentlyBlocked.slice(0, 8).map(r => (
                                    <li key={r.client} className="flex items-center justify-between gap-2 text-[12.5px]">
                                        <span className="truncate font-mono text-slate-800 dark:text-slate-100">{r.client}</span>
                                        <span className="shrink-0 text-[11.5px] text-slate-500 dark:text-slate-400">{fmt(r.requests)} · {r.lastSecondsAgo}s ago</span>
                                    </li>
                                ))}
                            </ul>
                        </Card>
                    )}
                </aside>
            </div>

            <SaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => { setDraft(view.settings); setError(null); }} />
        </div>
    );
}

type Mode = "default" | "custom" | "unlimited";

function RouteLimits({ view, defaults, onChanged }: { view: View; defaults: Settings; onChanged: () => void }) {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const [editing, setEditing] = useState<string | null>(null);
    const [mode, setMode] = useState<Mode>("default");
    const [value, setValue] = useState(1000);
    const [busy, setBusy] = useState(false);

    const start = (r: RouteRow) => {
        setEditing(r.id);
        setMode(r.rateLimit == null ? "default" : r.rateLimit === 0 ? "unlimited" : "custom");
        setValue(r.rateLimit && r.rateLimit > 0 ? r.rateLimit : defaults.perMinute);
    };

    const save = async (r: RouteRow) => {
        setBusy(true);
        try {
            const res = await axios.get(`${auth.data.host}/config/sourcesystems?scaleguard=true`, { headers: { Authorization: auth.data.token } });
            const src = (res.data.sourcesystems || []).find((s: any) => s.id === r.id);
            if (!src) throw new Error("Route not found");
            const rateLimit = mode === "default" ? -1 : mode === "unlimited" ? 0 : Math.max(1, value);
            await updateSource({ ...src, rateLimit }, "sourcesystems", auth);
            toast({ title: `Limit for ${r.name || r.host} saved`, status: "success", duration: 2200, position: "top-right" });
            setEditing(null);
            onChanged();
        } catch (e: any) {
            toast({ title: "Couldn't save the route limit", description: e?.response?.data?.message || e?.message, status: "error", duration: 5000, position: "top-right" });
        } finally { setBusy(false); }
    };

    const label = (r: RouteRow) => r.rateLimit == null
        ? <Pill tone="muted">Default · {defaults.enabled ? `${fmt(defaults.perMinute)}/min` : "off"}</Pill>
        : r.rateLimit === 0 ? <Pill tone="warn">Unlimited</Pill> : <Pill tone="info">{fmt(r.rateLimit)}/min</Pill>;

    return (
        <Card icon={FiLayers} title="Per-route limits" description="Give a busy API more headroom, or tighten a login page. Routes on Default follow the setting above.">
            {view.routes.length === 0 ? (
                <p className="text-[13px] text-slate-500">No routes yet. <RouterLink className="font-semibold text-teal-600 dark:text-teal-300" to="/managehost/sourcesystems/new">Add a source system</RouterLink>.</p>
            ) : (
                <ul className="divide-y divide-slate-100 dark:divide-white/[0.05]">
                    {view.routes.map(r => (
                        <li key={r.id} className="py-3">
                            <div className="flex flex-wrap items-center justify-between gap-3">
                                <div className="min-w-0">
                                    <p className="truncate text-[13.5px] font-semibold text-slate-800 dark:text-slate-100">{r.name || r.host}</p>
                                    <p className="truncate font-mono text-[11.5px] text-slate-500">{r.host}</p>
                                </div>
                                <div className="flex flex-wrap items-center gap-2">
                                    {r.ipAllow && <span title={`Only: ${r.ipAllow}`}><Pill tone="info">Allow-list</Pill></span>}
                                    {r.ipDeny && <span title={`Refused: ${r.ipDeny}`}><Pill tone="bad">Deny {r.ipDeny.split(",").filter(x => x.trim()).length}</Pill></span>}
                                    {label(r)}
                                    {editing !== r.id && <button onClick={() => start(r)} className="rounded-lg px-2.5 py-1 text-[12.5px] font-semibold text-teal-600 hover:bg-teal-500/10 dark:text-teal-300">Change</button>}
                                </div>
                            </div>
                            {editing === r.id && (
                                <div className="mt-3 flex flex-wrap items-center gap-2 rounded-xl border border-slate-200 bg-slate-50/70 p-3 dark:border-white/[0.07] dark:bg-white/[0.02]">
                                    <select aria-label="Limit type" value={mode} onChange={e => setMode(e.target.value as Mode)} className={`${inputCls} w-auto`}>
                                        <option value="default">Use default</option>
                                        <option value="custom">Custom limit</option>
                                        <option value="unlimited">Unlimited</option>
                                    </select>
                                    {mode === "custom" && (
                                        <div className="flex items-center gap-2">
                                            <input aria-label="Requests per minute" inputMode="numeric" className={`${inputCls} w-32 font-mono`} value={value} onChange={e => setValue(parseInt(e.target.value.replace(/\D/g, "") || "0", 10))} />
                                            <span className="text-[12px] text-slate-500">per minute · ≈{perSecond(value)}/s</span>
                                        </div>
                                    )}
                                    <div className="ml-auto flex gap-1.5">
                                        <button onClick={() => setEditing(null)} className="h-9 rounded-xl px-3 text-[12.5px] font-semibold text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/5">Cancel</button>
                                        <button disabled={busy || (mode === "custom" && value < 1)} onClick={() => save(r)} className="h-9 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-4 text-[12.5px] font-semibold text-slate-950 disabled:opacity-50">Save</button>
                                    </div>
                                </div>
                            )}
                        </li>
                    ))}
                </ul>
            )}
        </Card>
    );
}

const DURATIONS: { value: string; label: string; seconds: number }[] = [
    { value: "0", label: "Permanently", seconds: 0 },
    { value: "3600", label: "For 1 hour", seconds: 3600 },
    { value: "86400", label: "For 24 hours", seconds: 86400 },
    { value: "604800", label: "For 7 days", seconds: 604800 },
    { value: "2592000", label: "For 30 days", seconds: 2592000 },
];

function until(ms: number) {
    const s = Math.max(0, Math.round((ms - Date.now()) / 1000));
    if (s < 60) return `${s}s`;
    if (s < 3540) return `${Math.round(s / 60)}m`;
    if (s < 172800) return `${Math.round(s / 3600)}h`;
    return `${Math.round(s / 86400)}d`;
}

/** Gateway-wide blocked and rate-limit-exempt IPs / networks. */
function AccessListsCard({ access, onChanged }: { access: Access; onChanged: (v: View) => void }) {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const headers = { Authorization: auth.data.token };
    const [list, setList] = useState<"blocked" | "exempt">("blocked");
    const [values, setValues] = useState("");
    const [note, setNote] = useState("");
    const [duration, setDuration] = useState("0");
    const [filter, setFilter] = useState("");
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const entries = access[list] || [];
    const shown = filter.trim() ? entries.filter(e => e.value.includes(filter.trim()) || (e.note || "").toLowerCase().includes(filter.trim().toLowerCase())) : entries;

    const add = async () => {
        if (!values.trim()) return;
        setBusy(true); setError(null);
        try {
            const r = await axios.post(`${auth.data.host}/ratelimit/access?scaleguard=true`,
                { list, values, note: note || null, ttlSeconds: Number(duration) }, { headers });
            onChanged(r.data); setValues(""); setNote("");
            toast({ title: list === "blocked" ? "Blocked" : "Exempted", description: "Applies immediately on every route.", status: "success", duration: 2200, position: "top-right" });
        } catch (e: any) {
            setError(e?.response?.data?.message || "Couldn't add that.");
        } finally { setBusy(false); }
    };
    const remove = async (value: string) => {
        try {
            const r = await axios.post(`${auth.data.host}/ratelimit/access/remove?scaleguard=true`, { list, value }, { headers });
            onChanged(r.data);
        } catch (e: any) {
            toast({ title: "Couldn't remove", description: e?.response?.data?.message, status: "error", duration: 4000, position: "top-right" });
        }
    };

    return (
        <Card icon={list === "blocked" ? FiSlash : FiUserCheck} title="Blocked & exempt addresses"
            description="Refuse abusive clients, networks or even a misbehaving proxy outright, and let trusted networks skip rate limits. Changes apply at once on every route.">
            <div className="space-y-4">
                <Segmented id="acl-list" value={list} onChange={v => { setList(v); setError(null); setFilter(""); }} options={[
                    { value: "blocked", label: `Blocked · ${access.blocked.length}`, tone: "danger" },
                    { value: "exempt", label: `Exempt from limits · ${access.exempt.length}` },
                ]} />
                <p className="text-[12.5px] leading-relaxed text-slate-600 dark:text-slate-300">
                    {list === "blocked"
                        ? <>Refused on every connection — HTTP/1.1, HTTP/2, HTTP/3, gRPC and WebSocket. Matches the real client address <i>and</i> the connection's source, so blocking a CDN edge or proxy here cuts off everything it forwards. The admin API is never blocked, so you can't lock yourself out.</>
                        : <>These clients are never rate limited — monitoring, office networks, partners. Matched against the real client address only (X-Forwarded-For is believed only from trusted proxies), so it can't be faked with a header.</>}
                </p>
                <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,0.8fr)]">
                    <Field label="IP addresses or CIDR ranges" htmlFor="acl-values" error={error || undefined} hint="Separate several with commas, spaces or new lines. IPv4 and IPv6.">
                        <textarea id="acl-values" rows={2} value={values} onChange={e => { setValues(e.target.value); setError(null); }}
                            onKeyDown={e => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) add(); }}
                            placeholder={list === "blocked" ? "203.0.113.7, 198.51.100.0/24" : "10.20.0.0/16"} className={`${inputCls} h-auto py-2 font-mono text-[12.5px]`} />
                    </Field>
                    <div className="space-y-3">
                        <Field label="Note" htmlFor="acl-note">
                            <input id="acl-note" value={note} onChange={e => setNote(e.target.value)} placeholder={list === "blocked" ? "Credential stuffing, ticket #123" : "Uptime monitor"} className={inputCls} />
                        </Field>
                        <div className="flex gap-2">
                            <select aria-label="Duration" value={duration} onChange={e => setDuration(e.target.value)} className={`${inputCls} min-w-0 flex-1`}>
                                {DURATIONS.map(d => <option key={d.value} value={d.value}>{d.label}</option>)}
                            </select>
                            <button type="button" disabled={busy || !values.trim()} onClick={add}
                                className={`h-10 shrink-0 rounded-xl px-4 text-[13px] font-semibold disabled:opacity-50 ${list === "blocked" ? "bg-rose-500 text-white hover:bg-rose-600" : "bg-gradient-to-r from-teal-400 to-cyan-400 text-slate-950"}`}>
                                {list === "blocked" ? "Block" : "Exempt"}
                            </button>
                        </div>
                    </div>
                </div>

                {entries.length > 6 && (
                    <div className="relative">
                        <FiSearch className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" aria-hidden />
                        <input aria-label="Filter" value={filter} onChange={e => setFilter(e.target.value)} placeholder="Filter by address or note" className={`${inputCls} pl-9`} />
                    </div>
                )}
                {entries.length === 0 ? (
                    <p className="rounded-xl border border-dashed border-slate-300 px-3 py-4 text-center text-[12.5px] text-slate-500 dark:border-white/10">
                        {list === "blocked" ? "Nothing is blocked." : "No exempt clients — everyone is rate limited."}
                    </p>
                ) : (
                    <ul className="max-h-[360px] divide-y divide-slate-100 overflow-y-auto rounded-xl border border-slate-200 dark:divide-white/[0.05] dark:border-white/[0.07]">
                        {shown.map(e => (
                            <li key={e.value} className="flex items-center justify-between gap-3 px-3 py-2.5">
                                <div className="min-w-0">
                                    <p className="truncate font-mono text-[13px] font-semibold text-slate-800 dark:text-slate-100">{e.value}</p>
                                    <p className="truncate text-[11.5px] text-slate-500 dark:text-slate-400">{e.note ? `${e.note} · ` : ""}added {ago(e.addedAt)}</p>
                                </div>
                                <div className="flex shrink-0 items-center gap-2">
                                    {e.expiresAt ? <Pill tone="warn">expires in {until(e.expiresAt)}</Pill> : <Pill tone="muted">permanent</Pill>}
                                    <button type="button" aria-label={`Remove ${e.value}`} onClick={() => remove(e.value)} className="rounded-lg p-1.5 text-slate-400 hover:bg-rose-500/10 hover:text-rose-500"><FiTrash2 /></button>
                                </div>
                            </li>
                        ))}
                        {shown.length === 0 && <li className="px-3 py-3 text-center text-[12.5px] text-slate-500">No match.</li>}
                    </ul>
                )}
            </div>
        </Card>
    );
}
