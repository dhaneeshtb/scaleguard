import { useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import { Link } from "react-router-dom";
import { useToast } from "@chakra-ui/react";
import { FiAlertTriangle, FiCheckCircle, FiDatabase, FiGitMerge, FiLink, FiRefreshCw, FiServer, FiShield, FiSliders, FiStar } from "react-icons/fi";
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
    dr?: boolean; ledgerEntries?: number; error?: string;
}
interface Chain { origin: string; node: string; seq: number; hash: string; self: boolean }
interface Election { role: string; term: number; master: string | null; votedFor: string | null; quorum: number; voters: number; elections: number; masterSince?: number; lastMasterContactMs?: number; lastElection?: string }
interface DrStatus { standby: boolean; promotedAt?: number; primaryNodes: string[]; standbyNodes: string[]; lagEntries: number; lastReplicatedAt?: number }
interface LedgerView {
    open: boolean; path: string; origin: string; genesis: string | null; entries: number; rows: number; appended: number; imported: number; applied: number; superseded: number;
    lastImportAt?: number; chains: Chain[]; rejected?: Record<string, string>; seeds: string[]; unreachable?: Record<string, string>;
    lastPullError?: string; diverged: string[]; inSync: boolean; dr: DrStatus;
}
interface Verify { ok: boolean; tookMs: number; chains: { origin: string; node: string; entries: number; ok: boolean; problem?: string }[] }
interface RateSync { active: boolean; publishedRows: number; appliedRemoteTokens: number; trackedRemoteKeys: number; lastSyncMs: number; lastSyncAt: number }
interface ClusterView {
    mode?: "ledger" | "postgres" | "off"; master?: string | null; election?: Election; ledger?: LedgerView;
    enabled: boolean; reason?: string; error?: string; liveNodes: number;
    self: { node: string; address: string; leader: boolean; leaderSince?: number; startedAt: number };
    nodes: Member[];
    sync: {
        pollMs?: number; heartbeatMs: number; rateSyncMs: number; nodeTimeoutMs: number; changesPublished?: number; reloads: number;
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
    const [verify, setVerify] = useState<Verify | null>(null);
    const [verifying, setVerifying] = useState(false);
    const [promoting, setPromoting] = useState(false);

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
            toast({ title: "Reloading configuration", description: view?.mode === "ledger" ? "This node is reloading its configuration and pulling any missing ledger entries." : "This node is re-reading everything from the shared database.", status: "success", duration: 2500, position: "top-right" });
            setTimeout(load, 800);
        } catch (e: any) {
            toast({ title: "Couldn't resync", description: e?.response?.data?.message, status: "error", duration: 4000, position: "top-right" });
        } finally { setSyncing(false); }
    };

    const runVerify = async () => {
        setVerifying(true);
        try {
            const r = await axios.get(`${host}/cluster/ledger/verify?scaleguard=true`, { headers });
            setVerify(r.data);
        } catch (e: any) {
            toast({ title: "Couldn't verify", description: e?.response?.data?.message, status: "error", duration: 4000, position: "top-right" });
        } finally { setVerifying(false); }
    };

    const promote = async (force: boolean) => {
        const msg = force
            ? "The primary site's master still answers. Promoting anyway leaves two sites that both accept changes until the old one is stopped. Continue?"
            : "Promote this DR site? Its nodes become the cluster's primary nodes and elect a master. Do this only when the primary site is lost.";
        if (!window.confirm(msg)) return;
        setPromoting(true);
        try {
            const r = await axios.post(`${host}/cluster/dr/promote?scaleguard=true`, { force }, { headers });
            toast({ title: "DR site promoted", description: r.data?.message, status: "success", duration: 5000, position: "top-right" });
            setTimeout(load, 1500);
        } catch (e: any) {
            const m = e?.response?.data?.message;
            if (e?.response?.status === 409 && m && m.includes("still reachable") && !force) {
                setPromoting(false);
                return promote(true);
            }
            toast({ title: "Couldn't promote", description: m, status: "error", duration: 6000, position: "top-right" });
        } finally { setPromoting(false); }
    };

    if (loadError) return <div className="sg-card mx-auto max-w-xl p-8 text-center"><FiAlertTriangle className="mx-auto mb-3 text-2xl text-amber-500" /><p className="text-[14px] text-slate-700 dark:text-slate-200">{loadError}</p></div>;
    if (!view) return <div className="space-y-4">{[0, 1, 2].map(i => <div key={i} className="sg-card h-40 animate-pulse" />)}</div>;

    const alive = view.nodes.filter(n => n.alive);
    const clusterRps = alive.reduce((s, n) => s + (n.info.rps ?? 0), 0);
    const maxRps = Math.max(0, ...alive.map(n => n.info.rps ?? 0));
    const leader = view.nodes.find(n => n.leader);
    const down = view.nodes.filter(n => !n.alive);
    const ledger = view.mode === "ledger";
    const L = view.ledger;
    const el = view.election;
    const standby = !!L?.dr?.standby;

    return (
        <div className="mx-auto max-w-6xl pb-24">
            <PageHeader icon={FiServer} eyebrow="Platform" title="Cluster"
                description={ledger
                    ? "Nodes find each other through their peers, elect a master by majority vote, and keep identical copies of the configuration in a tamper-evident DuckDB ledger. No shared database. Changes are made on the master and reach every node within milliseconds; analytics flow through the master to every node."
                    : "Every ScaleGuard node that points at the same PostgreSQL database forms one cluster: a change saved on any node reaches the others in under a second, rate limits are counted across all of them, and one leader runs the background jobs."}
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
                        <pre className="mt-2 overflow-x-auto rounded-lg bg-slate-900/90 p-3 font-mono text-[12px] leading-relaxed text-slate-100">{ledger ? `# on every node: list the other nodes (3 or more, so a majority survives a failure)
-Dscaleguard.cluster.peers=10.0.0.11:80,10.0.0.12:80
-Dscaleguard.cluster.advertise=http://10.0.0.10:80
# a disaster-recovery standby in another site
-Dscaleguard.cluster.dr=true -Dscaleguard.cluster.peers=10.0.0.10:80,10.0.0.11:80,10.0.0.12:80` : `# ~/.scaleguard/postgres.properties — the same file on every node
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
                <Stat label={ledger ? "Master" : "Leader"} tone={ledger && view.enabled && !view.master ? "bad" : undefined}
                    value={<span className="block truncate text-[17px] leading-[30px]">{ledger ? (view.master ?? (view.enabled ? "none elected" : view.self.node)) : leader ? leader.node : view.enabled ? "electing…" : view.self.node}</span>} />
                {ledger && L
                    ? <Stat label={standby ? "DR lag" : "Ledger"} tone={standby ? (L.dr.lagEntries ? "warn" : "good") : L.inSync ? "good" : "warn"}
                        value={standby ? `${fmt(L.dr.lagEntries)} entries` : L.inSync ? "in sync" : "catching up"} />
                    : <Stat label="Changes applied here" value={fmt(view.sync.reloads)} />}
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
                                                        {n.leader && <Pill tone="info"><FiStar aria-hidden /> {ledger ? "Master" : "Leader"}</Pill>}
                                                        {n.dr && <Pill tone="warn"><FiShield aria-hidden /> DR standby</Pill>}
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
                                            {n.ledgerEntries != null && <span>{fmt(n.ledgerEntries)} ledger entries</span>}
                                            {n.error && !n.alive && <span className="text-rose-600 dark:text-rose-300">{n.error}</span>}
                                        </div>
                                    </li>
                                );
                            })}
                        </ul>
                    </Card>

                    {ledger && L && (
                        <Card icon={FiLink} title="Configuration ledger"
                            description="Every change is an entry chained to the one before it by SHA-256, one chain per writer. Nodes check each link before storing a copy, so an edited or missing entry is refused."
                            right={
                                <button type="button" onClick={runVerify} disabled={verifying}
                                    className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-200 px-3 text-[12px] font-semibold text-slate-700 hover:border-teal-400/50 disabled:opacity-60 dark:border-white/10 dark:text-slate-200">
                                    <FiCheckCircle className={verifying ? "animate-pulse" : ""} aria-hidden /> Verify chains
                                </button>}>
                            {verify && (
                                <div className={`mb-3 rounded-lg border p-2.5 text-[12.5px] ${verify.ok ? "border-emerald-500/25 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300" : "border-rose-500/25 bg-rose-500/10 text-rose-700 dark:text-rose-300"}`}>
                                    {verify.ok ? `All ${verify.chains.length} chains verified (${fmt(verify.chains.reduce((a, c) => a + c.entries, 0))} entries, ${verify.tookMs} ms).`
                                        : verify.chains.filter(c => !c.ok).map(c => `${c.node}: ${c.problem}`).join(" · ")}
                                </div>
                            )}
                            {(L.diverged.length > 0 || L.rejected) && (
                                <div className="mb-3 rounded-lg border border-rose-500/25 bg-rose-500/10 p-2.5 text-[12px] text-rose-700 dark:text-rose-300">
                                    {[...L.diverged, ...Object.values(L.rejected ?? {})].map((d, k) => <p key={k}>{d}</p>)}
                                </div>
                            )}
                            <div className="overflow-x-auto">
                                <table className="w-full text-left text-[12.5px]">
                                    <thead className="text-[11px] uppercase tracking-wide text-slate-400">
                                        <tr><th className="pb-2 font-semibold">Written by</th><th className="pb-2 text-right font-semibold">Entries</th><th className="pb-2 pl-4 font-semibold">Head hash</th></tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100 dark:divide-white/[0.05]">
                                        {[...L.chains].sort((a, b) => b.seq - a.seq).map(c => (
                                            <tr key={c.origin}>
                                                <td className="py-2 font-mono text-slate-700 dark:text-slate-200">{c.node}{c.self && <span className="ml-1.5 text-[11px] text-slate-400">(this node)</span>}</td>
                                                <td className="py-2 text-right font-mono tabular-nums text-slate-500">{fmt(c.seq)}</td>
                                                <td className="py-2 pl-4 font-mono text-slate-500 dark:text-slate-400" title={c.hash}>{c.hash.slice(0, 16)}…</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                            <p className="mt-3 text-[11.5px] text-slate-500 dark:text-slate-400">{fmt(L.rows)} rows · {fmt(L.applied)} changes applied from other nodes · stored in <span className="font-mono">{L.path}</span></p>
                        </Card>
                    )}

                    {view.enabled && !ledger && (
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
                    {ledger && el && view.enabled && (
                        <Card icon={FiStar} title="Master election">
                            <dl className="space-y-2 text-[12.5px]">
                                <div className="flex justify-between gap-3"><dt className="text-slate-500">This node</dt><dd className="capitalize text-slate-800 dark:text-slate-100">{standby ? "DR standby" : el.role}</dd></div>
                                <div className="flex justify-between gap-3"><dt className="text-slate-500">Term</dt><dd className="font-mono tabular-nums text-slate-800 dark:text-slate-100">{fmt(el.term)}</dd></div>
                                <div className="flex justify-between gap-3"><dt className="text-slate-500">Votes to win</dt><dd className="tabular-nums text-slate-800 dark:text-slate-100">{el.quorum} of {el.voters}</dd></div>
                                {el.masterSince && <div className="flex justify-between gap-3"><dt className="text-slate-500">Master since</dt><dd className="text-slate-800 dark:text-slate-100">{ago(el.masterSince)}</dd></div>}
                            </dl>
                            {el.lastElection && <p className="mt-3 text-[11.5px] text-slate-500 dark:text-slate-400">{el.lastElection}</p>}
                            {!view.master && <p className="mt-3 rounded-lg border border-amber-500/25 bg-amber-500/10 p-2.5 text-[12px] text-amber-700 dark:text-amber-300">No master: fewer than {el.quorum} voting nodes are reachable. Traffic is served, but configuration can't change until a majority is back.</p>}
                        </Card>
                    )}
                    {ledger && L && view.enabled && (L.dr.standby || L.dr.standbyNodes.length > 0 || L.dr.promotedAt) && (
                        <Card icon={FiShield} title="Disaster recovery">
                            <dl className="space-y-2 text-[12.5px]">
                                <div className="flex justify-between gap-3"><dt className="text-slate-500">Primary site</dt><dd className="truncate text-right font-mono text-slate-800 dark:text-slate-100">{L.dr.primaryNodes.join(", ") || "unreachable"}</dd></div>
                                <div className="flex justify-between gap-3"><dt className="text-slate-500">DR standbys</dt><dd className="truncate text-right font-mono text-slate-800 dark:text-slate-100">{L.dr.standbyNodes.join(", ") || "none"}</dd></div>
                                {standby && <div className="flex justify-between gap-3"><dt className="text-slate-500">Behind by</dt><dd className="tabular-nums text-slate-800 dark:text-slate-100">{fmt(L.dr.lagEntries)} entries</dd></div>}
                                {standby && L.dr.lastReplicatedAt && <div className="flex justify-between gap-3"><dt className="text-slate-500">Last copied</dt><dd className="text-slate-800 dark:text-slate-100">{ago(L.dr.lastReplicatedAt)}</dd></div>}
                                {L.dr.promotedAt && <div className="flex justify-between gap-3"><dt className="text-slate-500">Promoted</dt><dd className="text-slate-800 dark:text-slate-100">{ago(L.dr.promotedAt)}</dd></div>}
                            </dl>
                            {standby && (
                                <>
                                    <p className="mt-3 text-[12px] leading-relaxed text-slate-600 dark:text-slate-300">This site copies the primary's configuration and analytics and serves traffic, but doesn't vote. If the primary site is lost, promote it.</p>
                                    <button type="button" onClick={() => promote(false)} disabled={promoting}
                                        className="mt-3 inline-flex h-9 w-full items-center justify-center gap-2 rounded-xl bg-rose-600 px-3.5 text-[13px] font-semibold text-white hover:bg-rose-500 disabled:opacity-60">
                                        <FiShield aria-hidden /> Promote DR site to primary
                                    </button>
                                </>
                            )}
                        </Card>
                    )}
                    {view.enabled && (
                        <Card icon={FiGitMerge} title="Sync on this node">
                            {ledger && L?.lastPullError && <p className="mb-3 rounded-lg border border-amber-500/25 bg-amber-500/10 p-2.5 text-[12px] text-amber-700 dark:text-amber-300">{L.lastPullError}</p>}
                            <dl className="space-y-2 text-[12.5px]">
                                <div className="flex justify-between gap-3"><dt className="text-slate-500">Last change applied</dt><dd className="text-right text-slate-800 dark:text-slate-100">{view.sync.lastReloadAt ? ago(view.sync.lastReloadAt) : "none yet"}</dd></div>
                                {view.sync.lastReloadScopes && <div className="flex justify-between gap-3"><dt className="text-slate-500">What</dt><dd className="text-right text-slate-800 dark:text-slate-100">{view.sync.lastReloadScopes.split(", ").map(scopeLabel).join(", ")}</dd></div>}
                                {view.sync.lastReloadFrom && <div className="flex justify-between gap-3"><dt className="text-slate-500">From</dt><dd className="truncate text-right font-mono text-slate-800 dark:text-slate-100">{view.sync.lastReloadFrom}</dd></div>}
                                {ledger && L
                                    ? <div className="flex justify-between gap-3"><dt className="text-slate-500">Entries written here</dt><dd className="tabular-nums text-slate-800 dark:text-slate-100">{fmt(L.appended)}</dd></div>
                                    : <div className="flex justify-between gap-3"><dt className="text-slate-500">Changes published here</dt><dd className="tabular-nums text-slate-800 dark:text-slate-100">{fmt(view.sync.changesPublished ?? 0)}</dd></div>}
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
                        {ledger ? (
                            <ul className="list-disc space-y-1.5 pl-4 text-[12.5px] text-slate-600 dark:text-slate-300">
                                <li>Put any load balancer, DNS round robin or anycast in front of the nodes; they're interchangeable for traffic.</li>
                                <li>The nodes elect a master by majority vote. Changes saved on any node are made on the master and copied to the others within milliseconds. If the master stops, a new one is elected in a few seconds.</li>
                                <li>Each node keeps the configuration in its own database plus a DuckDB ledger of every change; nothing is shared.</li>
                                <li>Analytics go to the master first, which passes every node's requests on to the rest, so any node shows the whole cluster.</li>
                                <li>Use 3 or more nodes: with 2, losing one leaves no majority and changes pause (traffic doesn't).</li>
                            </ul>
                        ) : (
                        <ul className="list-disc space-y-1.5 pl-4 text-[12.5px] text-slate-600 dark:text-slate-300">
                            <li>Put any load balancer, DNS round robin or anycast in front of the nodes; they're interchangeable.</li>
                            <li>Configuration lives in PostgreSQL. Saving on one node notifies the others, which reload only what changed. Backend health and open connections are kept.</li>
                            <li>The leader holds a database lock and renews certificates. If it stops, another node takes over within {view.sync.heartbeatMs / 1000}s.</li>
                            <li>Each node keeps its own analytics database; pick a node on the Analytics page to query it.</li>
                        </ul>
                        )}
                    </Card>
                </aside>
            </div>
        </div>
    );
}
