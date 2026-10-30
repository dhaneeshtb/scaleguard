import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import { useToast } from "@chakra-ui/react";
import { FiActivity, FiAlertTriangle, FiBarChart2, FiClock, FiDatabase, FiDownload, FiList, FiPlay, FiRefreshCw, FiSearch, FiSettings, FiShield, FiTerminal } from "react-icons/fi";
import { useAuth } from "../../contexts/AuthContext";
import { Card, Field, PageHeader, Pill, Segmented, ToggleRow, fmt, inputCls } from "../../components/ui/controls";
import { Bucket, InlineBar, LineChart, SeriesDef, StackedBars, fillBuckets } from "../../components/charts/TimeCharts";

type Range = "15m" | "1h" | "6h" | "24h" | "7d" | "30d";
type Tab = "overview" | "logs" | "sql" | "settings";
const RANGES: { value: Range; label: string }[] = ["15m", "1h", "6h", "24h", "7d", "30d"].map(r => ({ value: r as Range, label: r }));

// Status palette (fixed roles, always shown with a legend label) and two validated categorical slots for lines.
const STATUS_SERIES: SeriesDef[] = [
    { key: "ok", label: "Success (1xx–3xx)", color: "#0ca30c" },
    { key: "c4", label: "Client errors (4xx)", color: "#fab219" },
    { key: "blocked", label: "Blocked (WAF / rate limit)", color: "#ec835a" },
    { key: "c5xx", label: "Server errors (5xx)", color: "#d03b3b" },
];
const useLineSeries = (): SeriesDef[] => {
    const dark = typeof document !== "undefined" && document.documentElement.classList.contains("dark");
    return [
        { key: "p50", label: "p50", color: dark ? "#3987e5" : "#2a78d6" },
        { key: "p95", label: "p95", color: dark ? "#d95926" : "#eb6834" },
    ];
};

const ms = (v: number | null | undefined) => v == null ? "–" : v >= 1000 ? `${(v / 1000).toFixed(2)} s` : `${v >= 100 ? Math.round(v) : v.toFixed(1)} ms`;
const bytes = (v: number) => v >= 1e9 ? `${(v / 1e9).toFixed(1)} GB` : v >= 1e6 ? `${(v / 1e6).toFixed(1)} MB` : v >= 1e3 ? `${(v / 1e3).toFixed(1)} KB` : `${v} B`;
const pct = (n: number, d: number) => d ? `${(100 * n / d).toFixed(n / d < 0.01 ? 2 : 1)}%` : "0%";
const statusTone = (s: number): "good" | "warn" | "bad" | "info" => s >= 500 ? "bad" : s >= 400 ? "warn" : s >= 300 ? "info" : "good";

export default function Analytics() {
    const { auth } = useAuth() as any;
    const headers = useMemo(() => ({ Authorization: auth.data.token }), [auth]);
    const host = auth.data.host;
    const [tab, setTab] = useState<Tab>("overview");
    const [range, setRange] = useState<Range>("1h");
    const [route, setRoute] = useState("");
    const [node, setNode] = useState("");               // cluster member whose access log to read ("" = this node)
    const [nodes, setNodes] = useState<{ node: string; self: boolean }[]>([]);
    const [summary, setSummary] = useState<any>(null);
    const [error, setError] = useState<string | null>(null);
    const [live, setLive] = useState(true);
    const [loading, setLoading] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const r = await axios.get(`${host}/analytics/summary?scaleguard=true&range=${range}${route ? `&route=${encodeURIComponent(route)}` : ""}${node ? `&node=${encodeURIComponent(node)}` : ""}`, { headers });
            setSummary(r.data);
            setError(null);
        } catch (e: any) {
            setError(e?.response?.status === 404 ? "This ScaleGuard server doesn't have request analytics yet. Update the server to the latest build."
                : e?.response?.data?.message || "Couldn't load analytics.");
        } finally { setLoading(false); }
    }, [host, headers, range, route, node]);

    useEffect(() => { if (tab === "overview") load(); }, [load, tab]);
    useEffect(() => { // in a cluster each node keeps its own access log: offer a node picker
        axios.get(`${host}/cluster?scaleguard=true`, { headers })
            .then(r => setNodes(r.data.enabled ? (r.data.nodes || []).filter((n: any) => n.alive).map((n: any) => ({ node: n.node, self: n.self })) : []))
            .catch(() => setNodes([]));
    }, [host, headers]);
    useEffect(() => {
        if (!live || tab !== "overview") return;
        const t = setInterval(load, 10_000);
        return () => clearInterval(t);
    }, [live, load, tab]);

    const routes: { id: string; name: string }[] = (summary?.routes || []).filter((r: any) => r.id).map((r: any) => ({ id: r.id, name: r.name }));

    return (
        <div className="mx-auto max-w-7xl pb-16">
            <PageHeader icon={FiBarChart2} eyebrow="Traffic" title="Analytics"
                description="Every request through the gateway, stored in ScaleGuard's built-in DuckDB: traffic, errors, latency, blocked attacks and full access logs, queryable with SQL. Nothing leaves the server."
                actions={tab === "overview" ? (
                    <button onClick={() => setLive(!live)} className={`inline-flex h-9 items-center gap-2 rounded-xl border px-3 text-[12.5px] font-semibold transition ${live ? "border-teal-400/50 text-teal-600 dark:text-teal-300" : "border-slate-200 text-slate-500 dark:border-white/10"}`}>
                        <span className={`h-2 w-2 rounded-full ${live ? "animate-pulse bg-teal-400" : "bg-slate-400"}`} />{live ? "Live" : "Paused"}
                    </button>
                ) : undefined} />

            <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
                <Segmented id="an-tab" value={tab} onChange={setTab} options={[
                    { value: "overview", label: "Overview" }, { value: "logs", label: "Access logs" },
                    { value: "sql", label: "SQL" }, { value: "settings", label: "Settings" }]} />
                {(tab === "overview" || tab === "logs" || tab === "sql") && (
                    <div className="flex flex-wrap items-center gap-2">
                        {nodes.length > 1 && (
                            <div className="w-44">
                                <select aria-label="Node" value={node} onChange={e => setNode(e.target.value)} className={`${inputCls} h-9`}>
                                    {nodes.map(n => <option key={n.node} value={n.self ? "" : n.node}>{n.node}{n.self ? " (this node)" : ""}</option>)}
                                </select>
                            </div>
                        )}
                        {tab !== "sql" && <div className="w-48">
                            <select aria-label="Route" value={route} onChange={e => setRoute(e.target.value)} className={`${inputCls} h-9`}>
                                <option value="">All routes</option>
                                {routes.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
                            </select>
                        </div>}
                        {tab !== "sql" && <Segmented id="an-range" size="sm" value={range} onChange={setRange} options={RANGES} />}
                    </div>
                )}
            </div>

            {tab === "overview" && (error ? <ErrorCard message={error} /> : !summary ? <Skeleton /> : <Overview s={summary} loading={loading} onRefresh={load} />)}
            {tab === "logs" && <Logs host={host} headers={headers} range={range} route={route} node={node} />}
            {tab === "sql" && <SqlConsole host={host} headers={headers} node={node} />}
            {tab === "settings" && <Settings host={host} headers={headers} />}
        </div>
    );
}

function ErrorCard({ message }: { message: string }) {
    return <div className="sg-card mx-auto max-w-xl p-8 text-center"><FiAlertTriangle className="mx-auto mb-3 text-2xl text-amber-500" /><p className="text-[14px] text-slate-700 dark:text-slate-200">{message}</p></div>;
}
function Skeleton() {
    return <div className="space-y-4">{[0, 1, 2].map(i => <div key={i} className="sg-card h-48 animate-pulse" />)}</div>;
}

function Kpi({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: "good" | "bad" | "warn" }) {
    const c = tone === "bad" ? "text-rose-600 dark:text-rose-300" : tone === "warn" ? "text-amber-600 dark:text-amber-300" : "text-slate-900 dark:text-white";
    return (
        <div className="sg-card p-4">
            <p className="text-[12px] font-medium text-slate-500 dark:text-slate-400">{label}</p>
            <p className={`mt-1 text-[24px] font-bold tracking-tight ${c}`}>{value}</p>
            {sub && <p className="mt-0.5 text-[11.5px] text-slate-500 dark:text-slate-400">{sub}</p>}
        </div>
    );
}

function Overview({ s, loading, onRefresh }: { s: any; loading: boolean; onRefresh: () => void }) {
    const t = s.totals;
    const lineSeries = useLineSeries();
    const buckets: Bucket[] = useMemo(() => {
        const rows = (s.series || []).map((r: any) => ({ ...r, c4: Math.max(0, (r.c4xx || 0) - (r.blocked || 0)) }));
        return fillBuckets(rows, s.from, s.to, s.bucketMs, ["ok", "c4", "blocked", "c5xx", "p50", "p95", "total"]);
    }, [s]);
    const blocked = (t.wafBlocked || 0) + (t.rateLimited || 0);
    if (!t.requests) {
        return (
            <div className="sg-card p-10 text-center">
                <FiActivity className="mx-auto mb-3 text-2xl text-slate-400" />
                <p className="text-[14px] font-semibold text-slate-800 dark:text-slate-100">No requests in this time range</p>
                <p className="mt-1 text-[13px] text-slate-500">Requests to your routes appear here within a second. Admin and health-check calls aren't counted.</p>
                {s.status && !s.status.available && <p className="mt-3 text-[12.5px] text-amber-600">Analytics storage is unavailable: {s.status.reason}</p>}
            </div>
        );
    }
    return (
        <div className="space-y-5">
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
                <Kpi label="Requests" value={fmt(t.requests)} sub={`${t.rps >= 10 ? Math.round(t.rps).toLocaleString() : t.rps.toFixed(2)} per second`} />
                <Kpi label="Server errors" value={pct(t.errors5xx, t.requests)} sub={`${fmt(t.errors5xx)} × 5xx`} tone={t.errors5xx / t.requests > 0.01 ? "bad" : undefined} />
                <Kpi label="Latency p95" value={ms(t.p95)} sub={`p50 ${ms(t.p50)} · p99 ${ms(t.p99)}`} />
                <Kpi label="Blocked" value={fmt(blocked)} sub={`${fmt(t.wafBlocked)} WAF · ${fmt(t.rateLimited)} rate limit`} tone={blocked ? "warn" : undefined} />
                <Kpi label="Clients" value={fmt(t.clients)} sub="unique IPs" />
                <Kpi label="Data served" value={bytes(t.bytesOut || 0)} sub={`${bytes(t.bytesIn || 0)} received`} />
            </div>

            <div className="grid gap-5 xl:grid-cols-2">
                <Card icon={FiActivity} title="Requests" description={`Per ${humanBucket(s.bucketMs)}, by result`}
                    right={<button aria-label="Refresh" onClick={onRefresh} className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 dark:hover:bg-white/5"><FiRefreshCw className={loading ? "animate-spin" : ""} /></button>}>
                    <StackedBars buckets={buckets} series={STATUS_SERIES} bucketMs={s.bucketMs} />
                </Card>
                <Card icon={FiClock} title="Latency" description={`Gateway response time per ${humanBucket(s.bucketMs)}, including the backend`}>
                    <LineChart buckets={buckets} series={lineSeries} bucketMs={s.bucketMs} />
                </Card>
            </div>

            <div className="grid gap-5 lg:grid-cols-2">
                <TopTable title="Routes" rows={s.routes} cols={[
                    { label: "Route", render: (r: any) => <span className="font-medium">{r.name}</span> },
                    { label: "Requests", num: true, render: (r: any) => fmt(r.requests) },
                    { label: "5xx", num: true, render: (r: any) => <span className={r.errors ? "text-rose-600 dark:text-rose-300" : ""}>{pct(r.errors, r.requests)}</span> },
                    { label: "p95", num: true, render: (r: any) => ms(r.p95) }]} />
                <TopTable title="Paths" rows={s.paths} cols={[
                    { label: "Path", render: (r: any) => <span className="font-mono text-[12px]" title={r.host}>{r.path}</span> },
                    { label: "Requests", num: true, render: (r: any) => fmt(r.requests) },
                    { label: "4xx/5xx", num: true, render: (r: any) => pct(r.errors, r.requests) },
                    { label: "p95", num: true, render: (r: any) => ms(r.p95) }]} />
                <TopTable title="Clients" rows={s.clients} cols={[
                    { label: "Client IP", render: (r: any) => <span className="font-mono text-[12px]" title={r.userAgent || ""}>{r.ip}</span> },
                    { label: "Requests", num: true, render: (r: any) => fmt(r.requests) },
                    { label: "Blocked", num: true, render: (r: any) => r.blocked ? <Pill tone="warn">{fmt(r.blocked)}</Pill> : "0" }]} />
                <TopTable title="Backends" rows={s.backends} empty="No proxied requests yet." cols={[
                    { label: "Backend", render: (r: any) => <span className="font-mono text-[12px]">{r.backend}</span> },
                    { label: "Requests", num: true, render: (r: any) => fmt(r.requests) },
                    { label: "5xx", num: true, render: (r: any) => pct(r.errors, r.requests) },
                    { label: "p95", num: true, render: (r: any) => ms(r.p95) }]} />
            </div>

            <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-4">
                <Breakdown title="Status codes" rows={s.statuses} label={(r: any) => <Pill tone={statusTone(r.status)}>{r.status}</Pill>} />
                <Breakdown title="Outcomes" rows={s.outcomes} label={(r: any) => OUTCOME_LABEL[r.outcome] || r.outcome} />
                <Breakdown title="Protocols" rows={s.protocols} label={(r: any) => r.protocol} />
                <Breakdown title="Firewall rules matched" rows={s.wafRules} label={(r: any) => <span className="font-mono text-[12px]">{r.rule}</span>} empty="No rules matched." />
            </div>
        </div>
    );
}

const OUTCOME_LABEL: Record<string, string> = {
    proxied: "Proxied", rate_limited: "Rate limited", waf_blocked: "Blocked by WAF", auth_denied: "Auth denied",
    no_backend: "No backend", no_route: "No route", error: "Backend error", cancelled: "Cancelled by client",
};

function humanBucket(b: number) {
    return b >= 3600_000 ? `${b / 3600_000} h` : b >= 60_000 ? `${b / 60_000} min` : `${b / 1000} s`;
}

function TopTable({ title, rows, cols, empty = "No data." }: { title: string; rows: any[]; cols: { label: string; num?: boolean; render: (r: any) => React.ReactNode }[]; empty?: string }) {
    const max = Math.max(0, ...(rows || []).map(r => r.requests || 0));
    return (
        <Card title={title}>
            {!rows?.length ? <p className="text-[13px] text-slate-500">{empty}</p> : (
                <table className="w-full table-fixed text-[13px]">
                    <thead>
                        <tr className="text-left text-[11.5px] text-slate-500 dark:text-slate-400">
                            {cols.map((c, i) => <th key={c.label} className={`pb-2 font-medium ${c.num ? "text-right" : ""} ${i === 0 ? "w-[46%]" : ""}`}>{c.label}</th>)}
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((r, i) => (
                            <tr key={i} className="border-t border-slate-100 align-top dark:border-white/[0.05]">
                                <td className="py-2 pr-3">
                                    <div className="truncate text-slate-800 dark:text-slate-100">{cols[0].render(r)}</div>
                                    <div className="mt-1.5"><InlineBar value={r.requests || 0} max={max} /></div>
                                </td>
                                {cols.slice(1).map(c => <td key={c.label} className="py-2 text-right font-mono text-[12px] tabular-nums text-slate-700 dark:text-slate-200">{c.render(r)}</td>)}
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </Card>
    );
}

function Breakdown({ title, rows, label, empty = "No data." }: { title: string; rows: any[]; label: (r: any) => React.ReactNode; empty?: string }) {
    const total = (rows || []).reduce((a, r) => a + (r.requests || 0), 0);
    return (
        <Card title={title}>
            {!rows?.length ? <p className="text-[13px] text-slate-500">{empty}</p> : (
                <ul className="space-y-2.5">
                    {rows.map((r, i) => (
                        <li key={i}>
                            <div className="mb-1 flex items-center justify-between gap-2 text-[12.5px] text-slate-700 dark:text-slate-200">
                                <span className="min-w-0 truncate">{label(r)}</span>
                                <span className="shrink-0 font-mono text-[12px] tabular-nums">{fmt(r.requests)} <span className="text-slate-400">· {pct(r.requests, total)}</span></span>
                            </div>
                            <InlineBar value={r.requests} max={rows[0].requests} />
                        </li>
                    ))}
                </ul>
            )}
        </Card>
    );
}

// ---------------------------------------------------------------------------------------------- logs

function Logs({ host, headers, range, route, node }: { host: string; headers: any; range: Range; route: string; node: string }) {
    const [f, setF] = useState({ status: "", outcome: "", ip: "", q: "", trace: "", method: "" });
    const [rows, setRows] = useState<any[]>([]);
    const [next, setNext] = useState<number | null>(null);
    const [busy, setBusy] = useState(false);
    const [err, setErr] = useState<string | null>(null);
    const [open, setOpen] = useState<string | null>(null);

    const query = useCallback((before?: number) => {
        const p = new URLSearchParams({ scaleguard: "true", range, limit: "100" });
        if (node) p.set("node", node);
        if (route) p.set("route", route);
        Object.entries(f).forEach(([k, v]) => { if (v.trim()) p.set(k, v.trim()); });
        if (before) p.set("before", String(before));
        return p;
    }, [f, range, route, node]);

    const load = useCallback(async (before?: number) => {
        setBusy(true);
        try {
            const r = await axios.get(`${host}/analytics/logs?${query(before)}`, { headers });
            setRows(prev => before ? [...prev, ...r.data.rows] : r.data.rows);
            setNext(r.data.next);
            setErr(null);
        } catch (e: any) {
            setErr(e?.response?.data?.message || "Couldn't load the access log.");
        } finally { setBusy(false); }
    }, [host, headers, query]);

    useEffect(() => { const t = setTimeout(() => load(), 250); return () => clearTimeout(t); }, [load]);

    const download = async (format: "csv" | "ndjson") => {
        const p = query(); p.set("format", format); p.delete("limit");
        const r = await axios.get(`${host}/analytics/export?${p}`, { headers, responseType: "blob" });
        const a = document.createElement("a");
        a.href = URL.createObjectURL(r.data);
        a.download = `scaleguard-access-log-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-")}.${format}`;
        a.click();
        URL.revokeObjectURL(a.href);
    };

    const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });

    return (
        <Card icon={FiList} title="Access log" description="Newest first. Click a row for details. Times are your local time."
            right={<div className="flex gap-2">
                <button onClick={() => download("csv")} className="inline-flex h-9 items-center gap-1.5 rounded-xl border border-slate-200 px-3 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 dark:border-white/10 dark:text-slate-200 dark:hover:bg-white/5"><FiDownload aria-hidden /> CSV</button>
                <button onClick={() => download("ndjson")} className="inline-flex h-9 items-center gap-1.5 rounded-xl border border-slate-200 px-3 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 dark:border-white/10 dark:text-slate-200 dark:hover:bg-white/5"><FiDownload aria-hidden /> JSON lines</button>
            </div>}>
            <div className="mb-4 grid gap-2 sm:grid-cols-3 lg:grid-cols-6">
                <div className="relative sm:col-span-2">
                    <FiSearch className="pointer-events-none absolute left-3 top-3 text-slate-400" aria-hidden />
                    <input aria-label="Path or host contains" placeholder="Path or host contains…" value={f.q} onChange={set("q")} className={`${inputCls} h-9 pl-9`} />
                </div>
                <select aria-label="Status" value={f.status} onChange={set("status")} className={`${inputCls} h-9`}>
                    <option value="">Any status</option>{["2xx", "3xx", "4xx", "5xx", "404", "429", "403", "502"].map(s => <option key={s} value={s}>{s}</option>)}
                </select>
                <select aria-label="Outcome" value={f.outcome} onChange={set("outcome")} className={`${inputCls} h-9`}>
                    <option value="">Any outcome</option>{Object.entries(OUTCOME_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
                <input aria-label="Client IP" placeholder="Client IP" value={f.ip} onChange={set("ip")} className={`${inputCls} h-9 font-mono`} />
                <input aria-label="Trace ID" placeholder="Trace ID" value={f.trace} onChange={set("trace")} className={`${inputCls} h-9 font-mono`} />
            </div>
            {err ? <p className="text-[13px] text-rose-500">{err}</p> : (
                <div className="-mx-2 overflow-x-auto">
                    <table className="w-full min-w-[860px] text-[12.5px]">
                        <thead>
                            <tr className="text-left text-[11.5px] text-slate-500 dark:text-slate-400">
                                {["Time", "Request", "Status", "Duration", "Route", "Backend", "Client"].map(h => <th key={h} className="px-2 pb-2 font-medium">{h}</th>)}
                            </tr>
                        </thead>
                        <tbody>
                            {rows.map(r => {
                                const key = r.trace_id + r.ts;
                                return (
                                    <Fragment key={key}>
                                        <tr onClick={() => setOpen(open === key ? null : key)} className="cursor-pointer border-t border-slate-100 hover:bg-slate-50 dark:border-white/[0.05] dark:hover:bg-white/[0.03]">
                                            <td className="whitespace-nowrap px-2 py-2 font-mono text-[11.5px] text-slate-500">{new Date(r.ts).toLocaleTimeString(undefined, { hour12: false })}.{String(r.ts % 1000).padStart(3, "0")}</td>
                                            <td className="max-w-[360px] truncate px-2 py-2 font-mono text-[12px] text-slate-800 dark:text-slate-100"><span className="mr-1.5 font-semibold">{r.method}</span>{r.path}{r.query ? <span className="text-slate-400">?{r.query}</span> : null}</td>
                                            <td className="px-2 py-2"><span className="inline-flex items-center gap-1.5"><Pill tone={statusTone(r.status)}>{r.status}</Pill>{r.outcome !== "proxied" && <span className="text-[11px] text-slate-500">{OUTCOME_LABEL[r.outcome] || r.outcome}</span>}</span></td>
                                            <td className="px-2 py-2 text-right font-mono tabular-nums">{ms(r.duration_ms)}</td>
                                            <td className="max-w-[140px] truncate px-2 py-2">{r.route_name || "–"}</td>
                                            <td className="max-w-[160px] truncate px-2 py-2 font-mono text-[11.5px]">{r.backend || "–"}</td>
                                            <td className="px-2 py-2 font-mono text-[11.5px]">{r.client_ip}</td>
                                        </tr>
                                        {open === key && (
                                            <tr className="bg-slate-50/70 dark:bg-white/[0.02]">
                                                <td colSpan={7} className="px-3 py-3">
                                                    <dl className="grid gap-x-6 gap-y-1.5 text-[12px] sm:grid-cols-2 lg:grid-cols-3">
                                                        {[["Trace ID", r.trace_id], ["Host", r.host], ["Protocol", r.protocol + (r.tls ? " · TLS" : "")],
                                                        ["gRPC status", r.grpc_status >= 0 ? r.grpc_status : null], ["Bytes in / out", `${fmt(r.bytes_in)} / ${fmt(r.bytes_out)}`],
                                                        ["Firewall rule", r.waf_rule], ["User agent", r.user_agent], ["Referer", r.referer], ["Gateway node", r.node]]
                                                            .filter(([, v]) => v != null && v !== "").map(([k, v]) => (
                                                                <div key={k as string} className="min-w-0"><dt className="text-slate-500">{k}</dt><dd className="truncate font-mono text-slate-800 dark:text-slate-100" title={String(v)}>{String(v)}</dd></div>
                                                            ))}
                                                    </dl>
                                                </td>
                                            </tr>
                                        )}
                                    </Fragment>
                                );
                            })}
                        </tbody>
                    </table>
                    {!rows.length && !busy && <p className="py-8 text-center text-[13px] text-slate-500">No requests match these filters.</p>}
                    {next && <div className="mt-3 text-center"><button disabled={busy} onClick={() => load(next)} className="h-9 rounded-xl border border-slate-200 px-4 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50 dark:border-white/10 dark:text-slate-200">{busy ? "Loading…" : "Load older"}</button></div>}
                </div>
            )}
        </Card>
    );
}

// ---------------------------------------------------------------------------------------------- sql

const EXAMPLES: { label: string; sql: string }[] = [
    { label: "Slowest paths (p95), last hour", sql: "SELECT host, path, count(*) AS requests,\n       round(quantile_cont(duration_ms, 0.95), 1) AS p95_ms\nFROM access_log\nWHERE ts > now() - INTERVAL 1 HOUR\nGROUP BY ALL\nORDER BY p95_ms DESC\nLIMIT 20" },
    { label: "Error rate per route per minute", sql: "SELECT time_bucket(INTERVAL 1 MINUTE, ts) AS minute, route_name,\n       count(*) AS requests,\n       round(100.0 * count_if(status >= 500) / count(*), 2) AS error_pct\nFROM access_log\nWHERE ts > now() - INTERVAL 1 HOUR\nGROUP BY ALL\nORDER BY minute DESC, error_pct DESC" },
    { label: "Top blocked clients", sql: "SELECT client_ip, outcome, waf_rule, count(*) AS hits,\n       any_value(user_agent) AS user_agent\nFROM access_log\nWHERE outcome IN ('waf_blocked', 'rate_limited')\nGROUP BY ALL\nORDER BY hits DESC\nLIMIT 25" },
    { label: "gRPC methods and status codes", sql: "SELECT path AS method, grpc_status, count(*) AS calls,\n       round(quantile_cont(duration_ms, 0.5), 2) AS p50_ms\nFROM access_log\nWHERE protocol = 'gRPC'\nGROUP BY ALL\nORDER BY calls DESC" },
    { label: "Describe the table", sql: "DESCRIBE access_log" },
];

function SqlConsole({ host, headers, node }: { host: string; headers: any; node: string }) {
    const [sql, setSql] = useState(EXAMPLES[0].sql);
    const [res, setRes] = useState<any>(null);
    const [err, setErr] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const run = async () => {
        setBusy(true); setErr(null);
        try {
            const r = await axios.post(`${host}/analytics/query?scaleguard=true${node ? `&node=${encodeURIComponent(node)}` : ""}`, { sql }, { headers });
            setRes(r.data);
        } catch (e: any) {
            setErr(e?.response?.data?.message || "Query failed."); setRes(null);
        } finally { setBusy(false); }
    };
    return (
        <div className="space-y-5">
            <Card icon={FiTerminal} title="Query the access log" description="Read-only DuckDB SQL over the access_log view: one row per request, ts in UTC. Up to 1,000 rows, 15 s per query. Press ⌘/Ctrl + Enter to run.">
                <div className="mb-3 flex flex-wrap gap-1.5">
                    {EXAMPLES.map(e => <button key={e.label} onClick={() => { setSql(e.sql); setErr(null); }} className="rounded-full border border-slate-200 px-3 py-1 text-[12px] text-slate-600 hover:border-teal-400/50 hover:text-teal-600 dark:border-white/10 dark:text-slate-300 dark:hover:text-teal-300">{e.label}</button>)}
                </div>
                <textarea aria-label="SQL query" value={sql} onChange={e => setSql(e.target.value)} spellCheck={false} rows={8}
                    onKeyDown={e => { if ((e.metaKey || e.ctrlKey) && e.key === "Enter") { e.preventDefault(); run(); } }}
                    className={`${inputCls} h-auto py-3 font-mono text-[12.5px] leading-relaxed`} />
                <div className="mt-3 flex items-center justify-between gap-3">
                    <p className="text-[12px] text-slate-500 dark:text-slate-400">{res ? `${fmt(res.rows.length)} row${res.rows.length === 1 ? "" : "s"}${res.truncated ? " (first 1,000)" : ""} · ${res.ms} ms` : "Columns: ts, trace_id, client_ip, method, host, path, query, protocol, route_id, route_name, backend, status, grpc_status, bytes_in, bytes_out, duration_ms, outcome, waf_rule, user_agent, referer, tls, node"}</p>
                    <button onClick={run} disabled={busy || !sql.trim()} className="inline-flex h-9 shrink-0 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-4 text-[12.5px] font-semibold text-slate-950 disabled:opacity-50"><FiPlay aria-hidden />{busy ? "Running…" : "Run"}</button>
                </div>
                {err && <pre className="mt-3 whitespace-pre-wrap rounded-xl border border-rose-300/50 bg-rose-50 p-3 font-mono text-[12px] text-rose-700 dark:border-rose-400/20 dark:bg-rose-500/10 dark:text-rose-300">{err}</pre>}
            </Card>
            {res && (
                <Card title="Results">
                    {!res.rows.length ? <p className="text-[13px] text-slate-500">No rows.</p> : (
                        <div className="-mx-2 max-h-[520px] overflow-auto">
                            <table className="w-full text-[12px]">
                                <thead className="sticky top-0 bg-white dark:bg-[#141414]">
                                    <tr>{res.columns.map((c: string) => <th key={c} className="whitespace-nowrap px-2 pb-2 text-left font-medium text-slate-500 dark:text-slate-400">{c}</th>)}</tr>
                                </thead>
                                <tbody>
                                    {res.rows.map((r: any[], i: number) => (
                                        <tr key={i} className="border-t border-slate-100 dark:border-white/[0.05]">
                                            {r.map((v, j) => <td key={j} className={`max-w-[320px] truncate px-2 py-1.5 font-mono ${typeof v === "number" ? "text-right tabular-nums" : ""} text-slate-700 dark:text-slate-200`} title={v == null ? "" : String(v)}>{v == null ? <span className="text-slate-400">null</span> : typeof v === "number" ? (Number.isInteger(v) ? v.toLocaleString() : +v.toFixed(3)) : String(v)}</td>)}
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </Card>
            )}
        </div>
    );
}

// ---------------------------------------------------------------------------------------------- settings

function Settings({ host, headers }: { host: string; headers: any }) {
    const toast = useToast();
    const [s, setS] = useState<any>(null);
    const [days, setDays] = useState(7);
    const load = useCallback(async () => {
        const r = await axios.get(`${host}/analytics/settings?scaleguard=true`, { headers });
        setS(r.data); setDays(r.data.retentionDays);
    }, [host, headers]);
    useEffect(() => { load(); }, [load]);
    const save = async (patch: any) => {
        try {
            const r = await axios.post(`${host}/analytics/settings?scaleguard=true`, patch, { headers });
            setS(r.data); setDays(r.data.retentionDays);
            toast({ title: "Analytics settings saved", status: "success", duration: 2000, position: "top-right" });
        } catch (e: any) {
            toast({ title: "Couldn't save", description: e?.response?.data?.message, status: "error", duration: 4000, position: "top-right" });
        }
    };
    if (!s) return <Skeleton />;
    const st = s.status || {};
    return (
        <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
            <Card icon={FiSettings} title="Recording" description="Applies immediately, no restart.">
                <div className="space-y-4">
                    <ToggleRow id="an-enabled" label="Record every request" checked={s.enabled} onChange={v => save({ enabled: v })}
                        description="Each routed request (HTTP/1.1, HTTP/2, gRPC, and requests the gateway refused) is written to DuckDB in the background, never on the request path. Admin calls aren't recorded." />
                    <Field label="Keep access logs for (days)" htmlFor="an-days" hint="Older rows are deleted every hour.">
                        <div className="flex gap-2">
                            <input id="an-days" inputMode="numeric" className={`${inputCls} w-32 font-mono`} value={days} onChange={e => setDays(parseInt(e.target.value.replace(/\D/g, "") || "0", 10))} />
                            <button disabled={days === s.retentionDays || days < 1} onClick={() => save({ retentionDays: days })} className="h-10 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-4 text-[12.5px] font-semibold text-slate-950 disabled:opacity-40">Save</button>
                        </div>
                    </Field>
                    <div className="rounded-xl border border-slate-200 p-3.5 text-[12.5px] leading-relaxed text-slate-600 dark:border-white/10 dark:text-slate-300">
                        <p className="mb-1 flex items-center gap-1.5 font-semibold text-slate-800 dark:text-slate-100"><FiShield aria-hidden /> Tracing</p>
                        Every request gets a W3C <span className="font-mono">traceparent</span> header to the backend (keeping the caller's trace ID if it sent one), and the response carries <span className="font-mono">X-Trace-Id</span>. Search for that ID under Access logs to find the request.
                    </div>
                </div>
            </Card>
            <Card icon={FiDatabase} title="Storage">
                {!st.available ? <p className="text-[13px] text-amber-600">Unavailable: {st.reason}</p> : (
                    <dl className="space-y-2 text-[12.5px]">
                        {[["Rows written", fmt(st.written)], ["Dropped (writer behind)", fmt(st.dropped)], ["Waiting to write", fmt(st.queued)], ["File size", bytes(st.fileBytes || 0)]].map(([k, v]) => (
                            <div key={k} className="flex justify-between gap-3"><dt className="text-slate-500 dark:text-slate-400">{k}</dt><dd className="font-mono tabular-nums text-slate-800 dark:text-slate-100">{v}</dd></div>
                        ))}
                        <div className="pt-1"><dt className="text-slate-500 dark:text-slate-400">File</dt><dd className="break-all font-mono text-[11.5px] text-slate-700 dark:text-slate-300">{st.path}</dd></div>
                    </dl>
                )}
            </Card>
        </div>
    );
}
