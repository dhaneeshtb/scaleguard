import { useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import { useToast } from "@chakra-ui/react";
import { useSearchParams, Link as RouterLink } from "react-router-dom";
import { FiAlertTriangle, FiCheckCircle, FiChevronDown, FiCode, FiEye, FiList, FiPlay, FiPlus, FiShield, FiTrash2, FiXOctagon } from "react-icons/fi";
import { useAuth } from "../../contexts/AuthContext";
import { Card, Field, PageHeader, Pill, SaveBar, Segmented, Stat, ToggleRow, ago, fmt, inputCls } from "../../components/ui/controls";

interface Rule { id: string; name: string; category: string; description: string; pattern: string; targets: string[]; defaultAction: "block" | "log"; builtIn: boolean; hits: number }
interface RuleSetting { enabled: boolean; action?: "block" | "log" | null }
interface Policy { enabled: boolean; mode: "block" | "detect"; inspectBody: boolean; maxBodyBytes: number; rules: Record<string, RuleSetting> }
interface RouteRow { id: string; name: string; host: string; scheme: string; policy: Policy }
interface Match { ruleId: string; ruleName: string; category: string; location: string; snippet: string; action: string }
interface WafEvent { time: number; incidentId: string; action: "blocked" | "logged"; routeId: string; route: string; clientIp: string; method: string; uri: string; matches: Match[] }
interface View { rules: Rule[]; routes: RouteRow[]; stats: { inspected: number; blocked: number; logged: number }; events: WafEvent[] }

const TARGETS = [
    { id: "PATH", label: "Path" }, { id: "QUERY", label: "Query string" }, { id: "HEADERS", label: "Headers" },
    { id: "USER_AGENT", label: "User-Agent" }, { id: "BODY", label: "Body" },
];

const clone = <T,>(x: T): T => JSON.parse(JSON.stringify(x));

export default function Firewall() {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const headers = useMemo(() => ({ Authorization: auth.data.token }), [auth]);
    const host = auth.data.host;
    const [params, setParams] = useSearchParams();

    const [view, setView] = useState<View | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [routeId, setRouteId] = useState<string | null>(params.get("route"));
    const [draft, setDraft] = useState<Policy | null>(null);
    const [saving, setSaving] = useState(false);

    const load = useCallback(async () => {
        try {
            const r = await axios.get(`${host}/waf?scaleguard=true`, { headers });
            setView(r.data);
            setLoadError(null);
            return r.data as View;
        } catch (e: any) {
            setLoadError(e?.response?.status === 404 ? "This ScaleGuard server doesn't include the web application firewall yet. Update the server to the latest build." : "Couldn't load firewall settings.");
            return null;
        }
    }, [host, headers]);

    useEffect(() => {
        load().then(v => {
            if (!v) return;
            const id = routeId && v.routes.some(r => r.id === routeId) ? routeId : v.routes[0]?.id ?? null;
            setRouteId(id);
            const r = v.routes.find(x => x.id === id);
            if (r) setDraft(clone(r.policy));
        });
    }, []);

    useEffect(() => { // events & counters
        const t = setInterval(load, 5000);
        return () => clearInterval(t);
    }, [load]);

    const route = view?.routes.find(r => r.id === routeId);
    const saved = route?.policy;
    const dirty = !!draft && !!saved && JSON.stringify(draft) !== JSON.stringify(saved);

    const selectRoute = (id: string) => {
        if (dirty && !window.confirm("Discard unsaved firewall changes for this route?")) return;
        setRouteId(id);
        setParams({ route: id }, { replace: true });
        const r = view?.routes.find(x => x.id === id);
        if (r) setDraft(clone(r.policy));
    };

    const save = async () => {
        if (!draft || !routeId) return;
        setSaving(true);
        try {
            const r = await axios.post(`${host}/waf/policy/${routeId}?scaleguard=true`, draft, { headers });
            setView(r.data);
            const nr = (r.data as View).routes.find(x => x.id === routeId);
            if (nr) setDraft(clone(nr.policy));
            toast({ title: "Firewall policy saved", description: "Applies to new requests immediately.", status: "success", duration: 2500, position: "top-right" });
        } catch (e: any) {
            toast({ title: "Couldn't save", description: e?.response?.data?.message, status: "error", duration: 5000, position: "top-right" });
        } finally { setSaving(false); }
    };

    if (loadError) return <div className="sg-card mx-auto max-w-xl p-8 text-center"><FiAlertTriangle className="mx-auto mb-3 text-2xl text-amber-500" /><p className="text-[14px] text-slate-700 dark:text-slate-200">{loadError}</p></div>;
    if (!view) return <div className="space-y-4">{[0, 1, 2].map(i => <div key={i} className="sg-card h-40 animate-pulse" />)}</div>;

    return (
        <div className="mx-auto max-w-7xl pb-24">
            <PageHeader icon={FiShield} eyebrow="Protect" title="Web application firewall"
                description="Inspects requests for SQL injection, cross-site scripting, path traversal, command injection and other common attacks before they reach your backends. Each route has its own policy." />

            <div className="mb-5 grid grid-cols-3 gap-3">
                <Stat label="Requests inspected" value={fmt(view.stats.inspected)} />
                <Stat label="Blocked" value={fmt(view.stats.blocked)} tone={view.stats.blocked ? "bad" : undefined} />
                <Stat label="Logged only" value={fmt(view.stats.logged)} tone={view.stats.logged ? "warn" : undefined} />
            </div>

            {view.routes.length === 0 ? (
                <Card><p className="text-[13px] text-slate-500">No routes yet. <RouterLink className="font-semibold text-teal-600 dark:text-teal-300" to="/managehost/sourcesystems/new">Add a source system</RouterLink> to protect it.</p></Card>
            ) : (
                <div className="grid items-start gap-5 lg:grid-cols-[260px_minmax(0,1fr)]">
                    {/* Route list */}
                    <nav aria-label="Routes" className="sg-card p-2 lg:sticky lg:top-24">
                        <p className="sg-eyebrow px-3 pb-2 pt-2">Routes</p>
                        {view.routes.map(r => {
                            const p = r.id === routeId && draft ? draft : r.policy;
                            const active = r.id === routeId;
                            return (
                                <button key={r.id} onClick={() => selectRoute(r.id)}
                                    className={`flex w-full items-center justify-between gap-2 rounded-xl px-3 py-2.5 text-left transition-colors ${active ? "bg-teal-500/10 ring-1 ring-inset ring-teal-500/25" : "hover:bg-slate-100 dark:hover:bg-white/[0.03]"}`}>
                                    <span className="min-w-0">
                                        <span className="block truncate text-[13px] font-semibold text-slate-800 dark:text-slate-100">{r.name || r.host}</span>
                                        <span className="block truncate font-mono text-[11px] text-slate-500">{r.host}</span>
                                    </span>
                                    {!p.enabled ? <Pill tone="muted">Off</Pill> : p.mode === "detect" ? <Pill tone="warn">Detect</Pill> : <Pill tone="good">Blocking</Pill>}
                                </button>
                            );
                        })}
                    </nav>

                    {route && draft && (
                        <div className="min-w-0 space-y-5">
                            <PolicyCard route={route} draft={draft} setDraft={setDraft} />
                            <RulesCard rules={view.rules} draft={draft} setDraft={setDraft} />
                            <CustomRulesCard rules={view.rules.filter(r => !r.builtIn)} onChanged={v => { setView(v); }} />
                            <TestCard routeId={route.id} draft={draft} />
                            <EventsCard events={view.events} routeId={route.id} />
                        </div>
                    )}
                </div>
            )}

            <SaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => saved && setDraft(clone(saved))} label="Save policy" />
        </div>
    );
}

function PolicyCard({ route, draft, setDraft }: { route: RouteRow; draft: Policy; setDraft: (p: Policy) => void }) {
    return (
        <Card icon={FiShield} title={`Policy for ${route.name || route.host}`} description={`${route.scheme || "http"}://${route.host}`}
            right={!draft.enabled ? <Pill tone="muted">Not protected</Pill> : draft.mode === "detect" ? <Pill tone="warn">Detect only</Pill> : <Pill tone="good"><FiCheckCircle /> Protected</Pill>}>
            <div className="space-y-3">
                <ToggleRow id="waf-enabled" label="Inspect this route's traffic" description="When off, requests pass straight through without any firewall checks."
                    checked={draft.enabled} onChange={v => setDraft({ ...draft, enabled: v })} />
                <div className={`flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white/60 p-3.5 dark:border-white/10 dark:bg-white/[0.02] ${draft.enabled ? "" : "pointer-events-none opacity-50"}`}>
                    <span className="min-w-0">
                        <span className="block text-[13.5px] font-medium text-slate-800 dark:text-slate-100">When a rule matches</span>
                        <span className="mt-0.5 block text-[12px] text-slate-500 dark:text-slate-400">
                            {draft.mode === "detect" ? "Nothing is blocked. Matches are recorded so you can check for false positives before blocking." : "Rules set to Block reject the request with 403; rules set to Log only record it."}
                        </span>
                    </span>
                    <Segmented id="waf-mode" value={draft.mode} onChange={v => setDraft({ ...draft, mode: v })}
                        options={[{ value: "block", label: "Block", tone: "danger" }, { value: "detect", label: "Detect only", tone: "warn" }]} />
                </div>
                <div className={draft.enabled ? "" : "pointer-events-none opacity-50"}>
                    <ToggleRow id="waf-body" label="Inspect request bodies"
                        description={`Checks form, JSON, XML and text bodies (first ${Math.round(draft.maxBodyBytes / 1024)} KB). File uploads are not scanned.`}
                        checked={draft.inspectBody} onChange={v => setDraft({ ...draft, inspectBody: v })} />
                </div>
            </div>
        </Card>
    );
}

function RulesCard({ rules, draft, setDraft }: { rules: Rule[]; draft: Policy; setDraft: (p: Policy) => void }) {
    const [open, setOpen] = useState<Record<string, boolean>>({});
    const groups = useMemo(() => {
        const g: Record<string, Rule[]> = {};
        rules.forEach(r => { (g[r.category] = g[r.category] || []).push(r); });
        return Object.entries(g);
    }, [rules]);

    const setting = (r: Rule): { enabled: boolean; action: "block" | "log" } => {
        const s = draft.rules?.[r.id];
        return { enabled: s ? s.enabled : true, action: (s?.action || r.defaultAction) as "block" | "log" };
    };
    const update = (r: Rule, patch: Partial<RuleSetting>) => {
        const cur = setting(r);
        const next = { ...cur, ...patch };
        const rulesMap = { ...(draft.rules || {}) };
        if (next.enabled && next.action === r.defaultAction) delete rulesMap[r.id]; // back to defaults
        else rulesMap[r.id] = { enabled: next.enabled, action: next.action === r.defaultAction ? null : next.action };
        setDraft({ ...draft, rules: rulesMap });
    };
    const setGroup = (list: Rule[], enabled: boolean) => {
        const rulesMap = { ...(draft.rules || {}) };
        list.forEach(r => {
            const cur = setting(r);
            if (enabled && cur.action === r.defaultAction) delete rulesMap[r.id];
            else rulesMap[r.id] = { enabled, action: cur.action === r.defaultAction ? null : cur.action };
        });
        setDraft({ ...draft, rules: rulesMap });
    };

    return (
        <Card icon={FiList} title="Rules" description="Every rule is on by default. Switch off any rule that blocks legitimate traffic on this route, or set it to Log only.">
            <div className={`space-y-3 ${draft.enabled ? "" : "opacity-60"}`}>
                {groups.map(([cat, list]) => {
                    const on = list.filter(r => setting(r).enabled).length;
                    const isOpen = open[cat] ?? false;
                    return (
                        <div key={cat} className="overflow-hidden rounded-xl border border-slate-200 dark:border-white/[0.07]">
                            <div className="flex items-center justify-between gap-3 bg-slate-50/80 px-4 py-3 dark:bg-white/[0.02]">
                                <button className="flex min-w-0 items-center gap-2 text-left" onClick={() => setOpen({ ...open, [cat]: !isOpen })} aria-expanded={isOpen}>
                                    <FiChevronDown className={`shrink-0 text-slate-400 transition-transform ${isOpen ? "rotate-180" : ""}`} />
                                    <span className="truncate text-[13.5px] font-semibold text-slate-800 dark:text-slate-100">{cat}</span>
                                    <span className="text-[12px] text-slate-500">{on}/{list.length} on</span>
                                </button>
                                <div className="flex shrink-0 gap-1">
                                    <button onClick={() => setGroup(list, true)} className="rounded-lg px-2 py-1 text-[11.5px] font-semibold text-teal-600 hover:bg-teal-500/10 dark:text-teal-300">All on</button>
                                    <button onClick={() => setGroup(list, false)} className="rounded-lg px-2 py-1 text-[11.5px] font-semibold text-slate-500 hover:bg-slate-200/60 dark:hover:bg-white/5">All off</button>
                                </div>
                            </div>
                            {isOpen && (
                                <ul className="divide-y divide-slate-100 dark:divide-white/[0.05]">
                                    {list.map(r => {
                                        const s = setting(r);
                                        return (
                                            <li key={r.id} className="flex flex-wrap items-start gap-3 px-4 py-3">
                                                <input type="checkbox" id={`rule-${r.id}`} checked={s.enabled} onChange={e => update(r, { enabled: e.target.checked })}
                                                    className="mt-1 h-4 w-4 shrink-0 cursor-pointer accent-teal-500" />
                                                <label htmlFor={`rule-${r.id}`} className="min-w-0 flex-1 cursor-pointer">
                                                    <span className="flex flex-wrap items-center gap-2 text-[13px] font-semibold text-slate-800 dark:text-slate-100">
                                                        {r.name}
                                                        {r.hits > 0 && <Pill tone="info">{fmt(r.hits)} hits</Pill>}
                                                        {!r.builtIn && <Pill tone="muted">Custom</Pill>}
                                                    </span>
                                                    <span className="mt-0.5 block text-[12px] leading-relaxed text-slate-500 dark:text-slate-400">{r.description}</span>
                                                    <span className="mt-1 block font-mono text-[10.5px] text-slate-400">{r.id} · checks {r.targets.map(t => TARGETS.find(x => x.id === t)?.label || t).join(", ")}</span>
                                                </label>
                                                <Segmented id={`act-${r.id}`} size="sm" value={s.action} onChange={v => update(r, { action: v })}
                                                    options={[{ value: "block", label: "Block", tone: "danger" }, { value: "log", label: "Log only", tone: "warn" }]} />
                                            </li>
                                        );
                                    })}
                                </ul>
                            )}
                        </div>
                    );
                })}
            </div>
        </Card>
    );
}

function CustomRulesCard({ rules, onChanged }: { rules: Rule[]; onChanged: (v: View) => void }) {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const empty = { name: "", description: "", pattern: "", targets: ["PATH", "QUERY", "BODY"], action: "BLOCK" };
    const [form, setForm] = useState<any>(null);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const h = { headers: { Authorization: auth.data.token } };

    const submit = async () => {
        setBusy(true); setError(null);
        try {
            const r = await axios.post(`${auth.data.host}/waf/rules?scaleguard=true`, form, h);
            onChanged(r.data); setForm(null);
            toast({ title: "Custom rule saved", description: "It's switched on for every route with the firewall enabled.", status: "success", duration: 3000, position: "top-right" });
        } catch (e: any) { setError(e?.response?.data?.message || "Couldn't save the rule."); } finally { setBusy(false); }
    };
    const remove = async (r: Rule) => {
        if (!window.confirm(`Delete the custom rule "${r.name}"?`)) return;
        try {
            const res = await axios.delete(`${auth.data.host}/waf/rules/${r.id}?scaleguard=true`, h);
            onChanged(res.data);
        } catch (e: any) { toast({ title: "Couldn't delete", description: e?.response?.data?.message, status: "error", position: "top-right" }); }
    };

    return (
        <Card icon={FiCode} title="Custom rules" description="Your own patterns (Java regular expressions, case-insensitive). They appear in every route's rule list."
            right={!form && <button onClick={() => setForm({ ...empty })} className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:border-teal-400/50 dark:border-white/10 dark:text-slate-200"><FiPlus /> New rule</button>}>
            {rules.length === 0 && !form && <p className="text-[12.5px] text-slate-500">No custom rules. Example: block <code className="font-mono">^/internal/</code> on the path.</p>}
            {rules.length > 0 && (
                <ul className="mb-3 space-y-2">
                    {rules.map(r => (
                        <li key={r.id} className="flex items-start justify-between gap-3 rounded-xl border border-slate-200 p-3 dark:border-white/[0.07]">
                            <div className="min-w-0">
                                <p className="text-[13px] font-semibold text-slate-800 dark:text-slate-100">{r.name} <span className="ml-1 text-[11.5px] font-normal text-slate-500">{r.defaultAction === "block" ? "blocks" : "logs"}</span></p>
                                <p className="mt-0.5 truncate font-mono text-[11.5px] text-slate-500">{r.pattern}</p>
                            </div>
                            <div className="flex shrink-0 gap-1">
                                <button onClick={() => setForm({ id: r.id, name: r.name, description: r.description, pattern: r.pattern, targets: r.targets, action: r.defaultAction.toUpperCase() })} className="rounded-lg px-2 py-1 text-[12px] font-semibold text-teal-600 hover:bg-teal-500/10 dark:text-teal-300">Edit</button>
                                <button onClick={() => remove(r)} aria-label="Delete rule" className="rounded-lg p-1.5 text-slate-400 hover:bg-rose-500/10 hover:text-rose-500"><FiTrash2 /></button>
                            </div>
                        </li>
                    ))}
                </ul>
            )}
            {form && (
                <div className="space-y-3 rounded-xl border border-teal-500/25 bg-teal-500/[0.03] p-4">
                    <div className="grid gap-3 sm:grid-cols-2">
                        <Field label="Name" htmlFor="cr-name"><input id="cr-name" className={inputCls} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="Block internal paths" /></Field>
                        <Field label="When it matches" htmlFor="cr-action">
                            <select id="cr-action" className={inputCls} value={form.action} onChange={e => setForm({ ...form, action: e.target.value })}>
                                <option value="BLOCK">Block the request</option><option value="LOG">Log only</option>
                            </select>
                        </Field>
                    </div>
                    <Field label="Pattern (regular expression)" htmlFor="cr-pattern" error={error || undefined} hint="Matched case-insensitively against decoded input. Example: ^/admin/ or \bdrop\s+table\b">
                        <input id="cr-pattern" className={`${inputCls} font-mono text-[12.5px]`} value={form.pattern} onChange={e => { setForm({ ...form, pattern: e.target.value }); setError(null); }} placeholder="^/internal/" spellCheck={false} />
                    </Field>
                    <Field label="Check these parts of the request">
                        <div className="flex flex-wrap gap-2">
                            {TARGETS.map(t => {
                                const on = form.targets.includes(t.id);
                                return (
                                    <button key={t.id} type="button" onClick={() => setForm({ ...form, targets: on ? form.targets.filter((x: string) => x !== t.id) : [...form.targets, t.id] })}
                                        className={`rounded-lg border px-2.5 py-1 text-[12px] font-semibold ${on ? "border-teal-500/40 bg-teal-500/10 text-teal-700 dark:text-teal-300" : "border-slate-200 text-slate-500 dark:border-white/10"}`}>{t.label}</button>
                                );
                            })}
                        </div>
                    </Field>
                    <Field label="Description (optional)" htmlFor="cr-desc"><input id="cr-desc" className={inputCls} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></Field>
                    <div className="flex justify-end gap-2">
                        <button onClick={() => { setForm(null); setError(null); }} className="h-9 rounded-xl px-3 text-[12.5px] font-semibold text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/5">Cancel</button>
                        <button disabled={busy || !form.name || !form.pattern || form.targets.length === 0} onClick={submit} className="h-9 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-4 text-[12.5px] font-semibold text-slate-950 disabled:opacity-50">Save rule</button>
                    </div>
                </div>
            )}
        </Card>
    );
}

function TestCard({ routeId, draft }: { routeId: string; draft: Policy }) {
    const { auth } = useAuth() as any;
    const [uri, setUri] = useState("/search?q=1' UNION SELECT password FROM users--");
    const [ua, setUa] = useState("");
    const [body, setBody] = useState("");
    const [result, setResult] = useState<{ blocked: boolean; matches: Match[] } | null>(null);
    const [busy, setBusy] = useState(false);

    const run = async () => {
        setBusy(true);
        setResult(null);
        try {
            const headers: Record<string, string> = {};
            if (ua) headers["User-Agent"] = ua;
            if (body) headers["Content-Type"] = body.trim().startsWith("{") ? "application/json" : "application/x-www-form-urlencoded";
            const r = await axios.post(
                `${auth.data.host}/waf/test?scaleguard=true`,
                { sourceId: routeId, policy: { ...draft, enabled: true }, uri: encodeURI(uri), headers, body },
                { headers: { Authorization: auth.data.token } }
            );
            setResult(r.data);
        } finally { setBusy(false); }
    };

    // Determine verdict kind: "blocked" | "logged" | "allowed"
    const verdict = result
        ? result.blocked
            ? "blocked"
            : result.matches.length > 0
                ? "logged"
                : "allowed"
        : null;

    const verdictCfg = {
        blocked: {
            border: "border-rose-500/40",
            bg: "bg-rose-500/[0.08] dark:bg-rose-500/[0.12]",
            icon: <FiXOctagon className="shrink-0 text-[22px] text-rose-500" />,
            badge: "bg-rose-500 text-white",
            label: "BLOCKED",
            title: "This request would be blocked",
            sub: "The firewall would return 403 Forbidden before the request reached your backend.",
        },
        logged: {
            border: "border-amber-400/40",
            bg: "bg-amber-400/[0.08] dark:bg-amber-400/[0.10]",
            icon: <FiEye className="shrink-0 text-[22px] text-amber-400" />,
            badge: "bg-amber-400 text-slate-900",
            label: "LOGGED",
            title: "Request would be allowed — but logged",
            sub: "One or more rules matched but their action is set to Log only. The request reaches your backend; the match is recorded.",
        },
        allowed: {
            border: "border-teal-500/40",
            bg: "bg-teal-500/[0.07] dark:bg-teal-500/[0.10]",
            icon: <FiCheckCircle className="shrink-0 text-[22px] text-teal-500" />,
            badge: "bg-teal-500 text-white",
            label: "ALLOWED",
            title: "Request would pass through",
            sub: "No rules matched. The request would reach your backend normally.",
        },
    } as const;

    return (
        <Card icon={FiPlay} title="Test a request" description="Runs a sample request through this route's current (unsaved) policy. Nothing is sent to your backend or recorded.">
            <div className="space-y-4">
                {/* Inputs */}
                <Field label="Path and query" htmlFor="t-uri">
                    <input id="t-uri" className={`${inputCls} font-mono text-[12.5px]`} value={uri}
                        onChange={e => { setUri(e.target.value); setResult(null); }} spellCheck={false} />
                </Field>
                <div className="grid gap-3 sm:grid-cols-2">
                    <Field label="User-Agent (optional)" htmlFor="t-ua">
                        <input id="t-ua" className={`${inputCls} font-mono text-[12.5px]`} value={ua}
                            onChange={e => { setUa(e.target.value); setResult(null); }} placeholder="sqlmap/1.7" />
                    </Field>
                    <Field label="Body (optional)" htmlFor="t-body">
                        <input id="t-body" className={`${inputCls} font-mono text-[12.5px]`} value={body}
                            onChange={e => { setBody(e.target.value); setResult(null); }} placeholder='{"comment":"<script>…"}' />
                    </Field>
                </div>

                {/* Run button */}
                <button onClick={run} disabled={busy}
                    className="inline-flex h-9 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-500 to-cyan-500 px-5 text-[13px] font-semibold text-white shadow-sm disabled:opacity-50 hover:brightness-110 transition-all">
                    {busy
                        ? <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
                        : <FiPlay />}
                    {busy ? "Testing…" : "Run test"}
                </button>

                {/* ── Verdict banner ─────────────────────────────────────── */}
                {verdict && (() => {
                    const cfg = verdictCfg[verdict];
                    return (
                        <div className={`rounded-2xl border ${cfg.border} ${cfg.bg} p-4`}>
                            {/* Header row */}
                            <div className="flex items-center gap-3">
                                {cfg.icon}
                                <div className="min-w-0 flex-1">
                                    <div className="flex flex-wrap items-center gap-2">
                                        <span className={`inline-flex items-center rounded-md px-2 py-0.5 text-[11px] font-bold tracking-widest ${cfg.badge}`}>
                                            {cfg.label}
                                        </span>
                                        <span className="text-[13.5px] font-semibold text-slate-800 dark:text-slate-100">
                                            {cfg.title}
                                        </span>
                                    </div>
                                    <p className="mt-0.5 text-[12px] text-slate-500 dark:text-slate-400">{cfg.sub}</p>
                                </div>
                            </div>

                            {/* Matched rules */}
                            {result!.matches.length > 0 && (
                                <ul className="mt-3 space-y-2">
                                    {result!.matches.map((m, i) => (
                                        <li key={i} className="rounded-xl border border-slate-200/70 bg-white/70 p-3 dark:border-white/[0.07] dark:bg-white/[0.04]">
                                            <div className="flex flex-wrap items-center gap-2">
                                                <span className={`inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wider ${m.action === "block" ? "bg-rose-100 text-rose-600 dark:bg-rose-500/20 dark:text-rose-400" : "bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-400"}`}>
                                                    {m.action === "block" ? "BLOCK" : "LOG"}
                                                </span>
                                                <span className="text-[13px] font-semibold text-slate-800 dark:text-slate-100">{m.ruleName}</span>
                                                <span className="text-[11.5px] text-slate-400">·</span>
                                                <span className="text-[11.5px] text-slate-500">{m.category}</span>
                                            </div>
                                            <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[11.5px] text-slate-500">
                                                <span>matched in</span>
                                                <code className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[11px] text-slate-600 dark:bg-white/[0.06] dark:text-slate-300">{m.location}</code>
                                            </div>
                                            <code className="mt-2 block w-full overflow-x-auto rounded-lg bg-slate-950/[0.04] px-3 py-2 font-mono text-[11.5px] text-rose-600 dark:bg-black/30 dark:text-rose-300">
                                                {m.snippet}
                                            </code>
                                        </li>
                                    ))}
                                </ul>
                            )}
                        </div>
                    );
                })()}
            </div>
        </Card>
    );
}

function EventsCard({ events, routeId }: { events: WafEvent[]; routeId: string }) {
    const [all, setAll] = useState(false);
    const list = events.filter(e => all || e.routeId === routeId).slice(0, 50);
    return (
        <Card icon={FiAlertTriangle} title="Recent events" description="Blocked and logged requests · refreshes every 5s"
            right={<Segmented id="ev-scope" size="sm" value={all ? "all" : "route"} onChange={v => setAll(v === "all")} options={[{ value: "route", label: "This route" }, { value: "all", label: "All routes" }]} />}>
            {list.length === 0 ? (
                <p className="rounded-xl border border-dashed border-slate-300 px-3 py-6 text-center text-[12.5px] text-slate-500 dark:border-white/10">No firewall events yet.</p>
            ) : (
                <div className="-mx-5 overflow-x-auto sm:-mx-6">
                    <table className="min-w-full text-left text-[12.5px]">
                        <thead>
                            <tr className="border-y border-slate-200/70 text-[10.5px] uppercase tracking-[0.12em] text-slate-500 dark:border-white/[0.05]">
                                <th className="px-5 py-2 font-semibold sm:px-6">When</th><th className="px-3 py-2 font-semibold">Action</th><th className="px-3 py-2 font-semibold">Client</th>
                                <th className="px-3 py-2 font-semibold">Request</th><th className="px-3 py-2 font-semibold">Rule</th><th className="px-3 py-2 pr-6 font-semibold">Incident</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100 dark:divide-white/[0.04]">
                            {list.map(e => (
                                <tr key={e.incidentId} className="align-top text-slate-700 dark:text-slate-300">
                                    <td className="whitespace-nowrap px-5 py-2.5 text-slate-500 sm:px-6">{ago(e.time)}</td>
                                    <td className="px-3 py-2.5">{e.action === "blocked" ? <Pill tone="bad">Blocked</Pill> : <Pill tone="warn">Logged</Pill>}</td>
                                    <td className="whitespace-nowrap px-3 py-2.5 font-mono text-[11.5px]">{e.clientIp}</td>
                                    <td className="max-w-[280px] px-3 py-2.5"><span className="block truncate font-mono text-[11.5px]" title={e.uri}>{e.method} {e.uri}</span>{all && <span className="text-[11px] text-slate-500">{e.route}</span>}</td>
                                    <td className="px-3 py-2.5"><span className="block font-semibold">{e.matches[0]?.ruleName}</span><span className="text-[11px] text-slate-500">{e.matches[0]?.location}</span></td>
                                    <td className="px-3 py-2.5 pr-6 font-mono text-[11px] text-slate-500">{e.incidentId}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </Card>
    );
}
