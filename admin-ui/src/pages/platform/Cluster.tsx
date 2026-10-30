import { useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import { Link } from "react-router-dom";
import { useToast } from "@chakra-ui/react";
import { FiAlertTriangle, FiDatabase, FiGitMerge, FiRefreshCw, FiServer, FiSliders, FiStar } from "react-icons/fi";
import { useAuth } from "../../contexts/AuthContext";
import { Card, PageHeader, Pill, Stat, ago, fmt } from "../../components/ui/controls";
import { InlineBar } from "../../components/charts/TimeCharts";

interface NodeInfo {
    rps?: number; requests?: number; rateLimited?: number; wafBlocked?: number; routes?: number;
    backends?: number; backendsHealthy?: number; activeConnections?: number; analyticsRows?: number;
    uptimeMs?: number; heapUsedMb?: number; heapMaxMb?: number; cpus?: number; version?: string | null; configReloads?: number;
}
interface Member {
    node: string; address: string; startedAt: number; lastSeenMs: number | null; alive: boolean; leader: boolean; self: boolean; info: NodeInfo;
}
interface RateSync { active: boolean; publishedRows: number; appliedRemoteTokens: number; trackedRemoteKeys: number; lastSyncMs: number; lastSyncAt: number }
interface ClusterView {
    enabled: boolean; reason?: string; error?: string; liveNodes: number;
    self: { node: string; address: string; leader: boolean; leaderSince?: number; startedAt: number };
    nodes: Member[];
    sync: {
        pollMs: number; heartbeatMs: number; rateSyncMs: number; nodeTimeoutMs: number; changesPublished: number; reloads: number;
        lastReloadAt?: number; lastReloadScopes?: string; lastReloadFrom?: string; lastError?: string; lastErrorAt?: number; rateLimit: RateSync;
    };
    config: { scope: string; version: number; updatedBy: string; updatedAt: number }[];
}

/** Friendly names for configuration scopes. */
const SCOPE: Record<string, string> = {
    source_systems: "Routes",
    target_systems: "Services",
    host_groups: "Backends",
    "system_properties/waf": "Firewall (WAF)",
    "system_properties/ratelimit": "Rate limits",
    "system_properties/health": "Health checks",
    "system_properties/analytics": "Analytics settings",
    "system_properties/misc": "System settings",
    dns_entries: "DNS records",
    certificate_orders: "Certificate orders",
    cert_files: "Certificates",
    applications: "Applications",
    clientinfos: "API clients",
    asyncengines: "Async engines",
};
const scopeLabel = (s: string) => SCOPE[s] || (s.startsWith("system_properties/") ? `Settings · ${s.slice(18)}` : s);

function duration(ms?: number | null) {
    if (ms == null) return "—";
    const s = Math.round(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.round(s / 60);
    if (m < 60) return `${m}m`;
    const h = Math.floor(m / 60);
    if (h < 48) return `${h}h ${m % 60}m`;
    return `${Math.floor(h / 24)}d ${h % 24}h`;
}

const rate = (n?: number) => (n ?? 0) >= 100 ? fmt(Math.round(n ?? 0)) : (n ?? 0).toFixed(1);

export default function Cluster() {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const headers = useMemo(() => ({ Authorization: auth.data.token }), [auth]);
    const host = auth.data.host;
    const [view, setView] = useState<ClusterView | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [syncing, setSyncing] = useState(false);

    const load = useCallback(async () => {
        try {
            const r = await axios.get(`${host}/cluster?scaleguard=true`, { headers });
            setView(r.data);
            setLoadError(null);
        } catch (e: any) {
            setLoadError(e?.response?.status === 404 ? "This ScaleGuard server doesn't support clustering yet. Update the server to the latest build." : "Couldn't load the cluster status.");
        }
    }, [host, headers]);

    useEffect(() => { load(); }, [load]);
    useEffect(() => {
        const t = setInterval(load, 3000);
        return () => clearInterval(t);
    }, [load]);

    const resync = async () => {
        setSyncing(true);
        try {
            await axios.post(`${host}/cluster/resync?scaleguard=true`, {}, { headers });
            toast({ title: "Reloading configuration", description: "This node is re-reading everything from the shared database.", status: "success", duration: 2500, position: "top-right" });
            setTimeout(load, 800);
        } catch (e: any) {
            toast({ title: "Couldn't resync", description: e?.response?.data?.message, status: "error", duration: 4000, position: "top-right" });
        } finally { setSyncing(false); }
    };

    if (loadError) return <div className="sg-card mx-auto max-w-xl p-8 text-center"><FiAlertTriangle className="mx-auto mb-3 text-2xl text-amber-500" /><p className="text-[14px] text-slate-700 dark:text-slate-200">{loadError}</p></div>;
    if (!view) return <div className="space-y-4">{[0, 1, 2].map(i => <div key={i} className="sg-card h-40 animate-pulse" />)}</div>;

    const alive = view.nodes.filter(n => n.alive);
    const clusterRps = alive.reduce((s, n) => s + (n.info.rps ?? 0), 0);
    const maxRps = Math.max(0, ...alive.map(n => n.info.rps ?? 0));
    const leader = view.nodes.find(n => n.leader);
    const down = view.nodes.filter(n => !n.alive);

    return (
        <div className="mx-auto max-w-6xl pb-24">
            <PageHeader icon={FiServer} eyebrow="Platform" title="Cluster"
                description="Every ScaleGuard node that points at the same PostgreSQL database forms one cluster: a change saved on any node reaches the others in under a second, rate limits are counted across all of them, and one leader runs the background jobs."
                actions={view.enabled ? (
                    <button type="button" onClick={resync} disabled={syncing}
                        className="inline-flex h-9 items-center gap-2 rounded-xl border border-slate-200 bg-white px-3.5 text-[13px] font-semibold text-slate-700 hover:border-teal-400/50 disabled:opacity-60 dark:border-white/10 dark:bg-white/[0.03] dark:text-slate-200">
                        <FiRefreshCw className={syncing ? "animate-spin" : ""} aria-hidden /> Resync this node
                    </button>
                ) : undefined} />

            {!view.enabled && (
                <div className="mb-5 flex items-start gap-3 rounded-2xl border border-sky-500/25 bg-sky-500/[0.07] p-4 text-[13px] text-slate-700 dark:text-slate-200">
                    <FiDatabase className="mt-0.5 shrink-0 text-sky-500" aria-hidden />
                    <div className="min-w-0">
                        <p className="font-semibold">Running as a single node</p>
                        <p className="mt-0.5 text-slate-600 dark:text-slate-300">{view.reason}</p>
                        <pre className="mt-2 overflow-x-auto rounded-lg bg-slate-900/90 p-3 font-mono text-[12px] leading-relaxed text-slate-100">{`# ~/.scaleguard/postgres.properties — the same file on every node
driver=org.postgresql.Driver
url=jdbc:postgresql://db.internal:5432/scaleguard
username=scaleguard
password=…`}</pre>
                    </div>
                </div>
            )}
            {view.error && <div className="mb-5 rounded-xl border border-rose-500/25 bg-rose-500/10 p-3 text-[13px] text-rose-700 dark:text-rose-300">{view.error}</div>}

            <div className="mb-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
                <Stat label="Live nodes" value={`${alive.length}${view.nodes.length > alive.length ? ` / ${view.nodes.length}` : ""}`} tone={down.length ? "warn" : "good"} />
                <Stat label="Cluster req/s" value={rate(clusterRps)} />
                <Stat label="Leader" value={<span className="block truncate text-[17px] leading-[30px]">{leader ? leader.node : view.enabled ? "electing…" : view.self.node}</span>} />
                <Stat label="Changes applied here" value={fmt(view.sync.reloads)} />
            </div>

            <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
                <div className="min-w-0 space-y-5">
                    <Card icon={FiServer} title="Nodes" description={`Heartbeat every ${view.sync.heartbeatMs / 1000}s · a node is marked down after ${view.sync.nodeTimeoutMs / 1000}s of silence`}>
                        <ul className="divide-y divide-slate-100 dark:divide-white/[0.05]">
                            {view.nodes.map(n => {
                                const i = n.info || {};
                                const heap = i.heapMaxMb ? Math.round(100 * (i.heapUsedMb ?? 0) / i.heapMaxMb) : null;
                                return (
                                    <li key={n.node} className={`py-3.5 ${n.alive ? "" : "opacity-60"}`}>
                                        <div className="flex flex-wrap items-center justify-between gap-3">
                                            <div className="flex min-w-0 items-center gap-2.5">
                                                <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${n.alive ? "bg-emerald-500 shadow-[0_0_0_3px_rgba(16,185,129,0.18)]" : "bg-rose-500"}`} aria-hidden />
                                                <div className="min-w-0">
                                                    <p className="flex flex-wrap items-center gap-1.5 text-[13.5px] font-semibold text-slate-800 dark:text-slate-100">
                                                        <span className="truncate font-mono">{n.node}</span>
                                                        {n.leader && <Pill tone="info"><FiStar aria-hidden /> Leader</Pill>}
                                                        {n.self && <Pill tone="muted">This node</Pill>}
                                                        {!n.alive && <Pill tone="bad">Down · last seen {n.lastSeenMs == null ? "at shutdown" : `${duration(n.lastSeenMs)} ago`}</Pill>}
                                                    </p>
                                                    <p className="truncate font-mono text-[11.5px] text-slate-500 dark:text-slate-400">{n.address}{i.version ? ` · v${i.version}` : ""}</p>
                                                </div>
                                            </div>
                                            {n.alive && (
                                                <div className="w-40 text-right">
                                                    <p className="text-[15px] font-semibold tabular-nums text-slate-900 dark:text-white">{rate(i.rps)} <span className="text-[11.5px] font-medium text-slate-500">req/s</span></p>
                                                    <InlineBar value={i.rps ?? 0} max={maxRps} />
                                                </div>
                                            )}
                                        </div>
                                        <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 pl-5 text-[11.5px] text-slate-500 dark:text-slate-400">
                                            <span>{fmt(i.requests ?? 0)} requests</span>
                                            <span className={i.rateLimited ? "text-amber-600 dark:text-amber-300" : ""}>{fmt(i.rateLimited ?? 0)} rate-limited</span>
                                            <span className={i.wafBlocked ? "text-rose-600 dark:text-rose-300" : ""}>{fmt(i.wafBlocked ?? 0)} blocked by WAF</span>
                                            <span className={(i.backendsHealthy ?? 0) < (i.backends ?? 0) ? "text-amber-600 dark:text-amber-300" : ""}>{fmt(i.backendsHealthy ?? 0)}/{fmt(i.backends ?? 0)} backends healthy</span>
                                            <span>{fmt(i.activeConnections ?? 0)} open connections</span>
                                            {heap != null && <span>heap {fmt(i.heapUsedMb ?? 0)} / {fmt(i.heapMaxMb ?? 0)} MB ({heap}%)</span>}
                                            {i.cpus && <span>{i.cpus} CPUs</span>}
                                            <span>up {duration(i.uptimeMs ?? (Date.now() - n.startedAt))}</span>
                                        </div>
                                    </li>
                                );
                            })}
                        </ul>
                    </Card>

                    {view.enabled && (
                        <Card icon={FiDatabase} title="Shared configuration" description="Each kind of setting has a version in the shared database. Saving on any node bumps it; the others reload just that part.">
                            {view.config.length === 0 ? <p className="text-[13px] text-slate-500">Nothing has been changed since the cluster was formed.</p> : (
                                <div className="overflow-x-auto">
                                    <table className="w-full text-left text-[12.5px]">
                                        <thead className="text-[11px] uppercase tracking-wide text-slate-400">
                                            <tr><th className="pb-2 font-semibold">Setting</th><th className="pb-2 text-right font-semibold">Version</th><th className="pb-2 pl-4 font-semibold">Last changed on</th><th className="pb-2 text-right font-semibold">When</th></tr>
                                        </thead>
                                        <tbody className="divide-y divide-slate-100 dark:divide-white/[0.05]">
                                            {[...view.config].sort((a, b) => b.updatedAt - a.updatedAt).map(c => (
                                                <tr key={c.scope}>
                                                    <td className="py-2 text-slate-700 dark:text-slate-200">{scopeLabel(c.scope)}</td>
                                                    <td className="py-2 text-right font-mono tabular-nums text-slate-500">{fmt(c.version)}</td>
                                                    <td className="py-2 pl-4 font-mono text-slate-500 dark:text-slate-400">{c.updatedBy}</td>
                                                    <td className="py-2 text-right text-slate-500 dark:text-slate-400">{ago(c.updatedAt)}</td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </Card>
                    )}
                </div>

                <aside className="space-y-4 lg:sticky lg:top-24">
                    {view.enabled && (
                        <Card icon={FiGitMerge} title="Sync on this node">
                            <dl className="space-y-2 text-[12.5px]">
                                <div className="flex justify-between gap-3"><dt className="text-slate-500">Last change applied</dt><dd className="text-right text-slate-800 dark:text-slate-100">{view.sync.lastReloadAt ? ago(view.sync.lastReloadAt) : "none yet"}</dd></div>
                                {view.sync.lastReloadScopes && <div className="flex justify-between gap-3"><dt className="text-slate-500">What</dt><dd className="text-right text-slate-800 dark:text-slate-100">{view.sync.lastReloadScopes.split(", ").map(scopeLabel).join(", ")}</dd></div>}
                                {view.sync.lastReloadFrom && <div className="flex justify-between gap-3"><dt className="text-slate-500">From</dt><dd className="truncate text-right font-mono text-slate-800 dark:text-slate-100">{view.sync.lastReloadFrom}</dd></div>}
                                <div className="flex justify-between gap-3"><dt className="text-slate-500">Changes published here</dt><dd className="tabular-nums text-slate-800 dark:text-slate-100">{fmt(view.sync.changesPublished)}</dd></div>
                            </dl>
                            {view.sync.lastError && (
                                <p className="mt-3 rounded-lg border border-rose-500/25 bg-rose-500/10 p-2.5 text-[12px] text-rose-700 dark:text-rose-300">
                                    {view.sync.lastError}{view.sync.lastErrorAt ? ` (${ago(view.sync.lastErrorAt)})` : ""}
                                </p>
                            )}
                        </Card>
                    )}
                    {view.enabled && (
                        <Card icon={FiSliders} title="Shared rate limits">
                            <p className="mb-3 text-[12.5px] leading-relaxed text-slate-600 dark:text-slate-300">
                                {view.sync.rateLimit.active
                                    ? <>Nodes exchange what each client spent every {view.sync.rateSyncMs} ms, so a limit holds for the whole cluster.</>
                                    : <>Rate limits are counted per node. Choose <b>Shared</b> or <b>Divided</b> under <Link className="font-semibold text-teal-600 hover:underline dark:text-teal-300" to="/ratelimit">Rate limiting</Link>.</>}
                            </p>
                            {view.sync.rateLimit.active && (
                                <div className="grid grid-cols-2 gap-2">
                                    <Stat label="Keys shared" value={fmt(view.sync.rateLimit.trackedRemoteKeys)} />
                                    <Stat label="Last sync" value={`${view.sync.rateLimit.lastSyncMs} ms`} />
                                </div>
                            )}
                        </Card>
                    )}
                    <Card title="How it works">
                        <ul className="list-disc space-y-1.5 pl-4 text-[12.5px] text-slate-600 dark:text-slate-300">
                            <li>Put any load balancer, DNS round robin or anycast in front of the nodes; they're interchangeable.</li>
                            <li>Configuration lives in PostgreSQL. Saving on one node notifies the others, which reload only what changed. Backend health and open connections are kept.</li>
                            <li>The leader holds a database lock and renews certificates. If it stops, another node takes over within {view.sync.heartbeatMs / 1000}s.</li>
                            <li>Each node keeps its own analytics database; pick a node on the Analytics page to query it.</li>
                        </ul>
                    </Card>
                </aside>
            </div>
        </div>
    );
}
