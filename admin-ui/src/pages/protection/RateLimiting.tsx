import { useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import { useToast } from "@chakra-ui/react";
import { Link as RouterLink } from "react-router-dom";
import { FiActivity, FiAlertTriangle, FiGlobe, FiLayers, FiSliders } from "react-icons/fi";
import { useAuth } from "../../contexts/AuthContext";
import { updateSource } from "../../components/sourceupdate";
import { Card, Field, PageHeader, Pill, SaveBar, Stat, ToggleRow, fmt, inputCls } from "../../components/ui/controls";

interface Settings { enabled: boolean; perMinute: number; burstSeconds: number; trustedProxies: string }
interface LimitedClient { client: string; route: string; rejected: number; limitPerMinute: number; lastRejectedSecondsAgo: number }
interface RouteRow { id: string; name: string; host: string; rateLimit: number | null }
interface View { settings: Settings; stats: { allowed: number; rejected: number; trackedClients: number; limitedClients: LimitedClient[] }; routes: RouteRow[] }

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

                    <RouteLimits view={view} defaults={view.settings} onChanged={() => load(false)} />
                </div>

                <aside className="space-y-4 lg:sticky lg:top-24">
                    <Card icon={FiActivity} title="Live activity" description="Since the server started · refreshes every 5s">
                        <div className="grid grid-cols-2 gap-2">
                            <Stat label="Allowed" value={fmt(view.stats.allowed)} tone="good" />
                            <Stat label="Limited" value={fmt(view.stats.rejected)} tone={view.stats.rejected ? "bad" : undefined} />
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
                                            <Pill tone="bad">{fmt(c.rejected)} limited</Pill>
                                        </div>
                                        <p className="mt-1 truncate text-[11.5px] text-slate-500 dark:text-slate-400">{c.route} · {fmt(c.limitPerMinute)}/min · {c.lastRejectedSecondsAgo}s ago</p>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </Card>
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
                                <div className="flex items-center gap-2">
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
