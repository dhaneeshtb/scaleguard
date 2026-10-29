import React, { useEffect, useMemo, useState } from 'react';
import RootComponents from '../components/RootComponents';
import QuickMapping from '../components/QuickMapping';
import useSystemContext from '../contexts/SystemContext';
import { useAuth } from '../contexts/AuthContext';
import axios from 'axios';
import { FiCpu, FiGitBranch, FiLayers, FiLock, FiServer } from 'react-icons/fi';
import { motion } from 'framer-motion';
import TrafficFlow from '../components/TrafficFlow';
import Insights, { deriveInsights, StatEntry } from '../components/Insights';
import { RouteTable, StatsKPIs } from '../components/Stats';

function greeting() {
    const h = new Date().getHours();
    return h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
}

function Home() {
    const { properties } = useSystemContext() as any;
    const { auth } = useAuth() as any;
    const [counts, setCounts] = useState({ sources: 0, targets: 0, certs: 0, hostGroups: 0 });
    const [initialData, setInitialData] = useState<any>({ sourcesystems: null, targetsystems: null, hostgroups: null });
    const [stats, setStats] = useState<StatEntry[] | null>(null);
    const [loading, setLoading] = useState(true);
    const fetchedRef = React.useRef(false);

    useEffect(() => {
        if (!auth.data || fetchedRef.current) return;
        fetchedRef.current = true;

        const load = async () => {
            setLoading(true);
            try {
                const headers = { Authorization: auth.data.token };
                const host = auth.data.host;
                const [srcRes, tgtRes, certRes, hgRes, statRes] = await Promise.allSettled([
                    axios.get(`${host}/config/sourcesystems?scaleguard=true`, { headers }),
                    axios.get(`${host}/config/targetsystems?scaleguard=true`, { headers }),
                    axios.get(`${host}/certificates?scaleguard=true`, { headers }),
                    axios.get(`${host}/config/hostgroups?scaleguard=true`, { headers }),
                    axios.get(`${host}/stats?scaleguard=true`, { headers }),
                ]);

                const srcData = srcRes.status === 'fulfilled' ? srcRes.value.data.sourcesystems || [] : [];
                const tgtData = tgtRes.status === 'fulfilled' ? tgtRes.value.data.targetsystems || [] : [];
                const hgData = hgRes.status === 'fulfilled' ? hgRes.value.data.hostgroups || [] : [];

                setCounts({
                    sources: srcData.length,
                    targets: tgtData.length,
                    certs: certRes.status === 'fulfilled' ? (certRes.value.data?.length || 0) : 0,
                    hostGroups: hgData.length,
                });
                setInitialData({ sourcesystems: srcData, targetsystems: tgtData, hostgroups: hgData });
                setStats(statRes.status === 'fulfilled' && Array.isArray(statRes.value.data) ? statRes.value.data : []);
            } catch (e) {
                console.error('Failed to load overview:', e);
                setStats([]);
            }
            setLoading(false);
        };
        load();
    }, [auth.data]);

    const hostname = properties?.hostName?.value || 'ScaleGuard';
    const insights = useMemo(() => deriveInsights(stats, counts), [stats, counts]);
    const warnings = insights.filter(i => i.level === 'warn').length;

    const infra = [
        { icon: FiServer, label: 'Sources', count: counts.sources, tint: 'text-cyan-500 dark:text-cyan-300' },
        { icon: FiLayers, label: 'Upstreams', count: counts.targets, tint: 'text-violet-500 dark:text-violet-300' },
        { icon: FiGitBranch, label: 'Host groups', count: counts.hostGroups, tint: 'text-amber-500 dark:text-amber-300' },
        { icon: FiLock, label: 'Certificates', count: counts.certs, tint: 'text-emerald-500 dark:text-emerald-300' },
    ];

    return (
        <div className="space-y-6">
            {/* Hero */}
            <section className="sg-card sg-ring-gradient overflow-hidden">
                <div className="pointer-events-none absolute -left-24 -top-24 h-72 w-72 rounded-full bg-teal-400/20 blur-3xl dark:bg-teal-400/10" />
                <div className="pointer-events-none absolute -bottom-32 right-10 h-80 w-80 rounded-full bg-violet-500/20 blur-3xl dark:bg-violet-500/10" />

                <div className="relative grid gap-2 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)]">
                    <div className="flex flex-col justify-between p-6 sm:p-8">
                        <div>
                            <div className="flex flex-wrap items-center gap-2">
                                <span className="inline-flex items-center gap-1.5 rounded-full border border-teal-500/25 bg-teal-500/10 px-2.5 py-1 text-[11px] font-semibold text-teal-700 dark:text-teal-300">
                                    <FiCpu className="text-[11px]" /> Intelligent load balancer
                                </span>
                                {loading ? (
                                    <span className="inline-flex items-center gap-1.5 rounded-full border border-slate-300/60 px-2.5 py-1 text-[11px] font-medium text-slate-500 dark:border-white/10">
                                        <span className="h-2.5 w-2.5 animate-spin rounded-full border-2 border-slate-400/40 border-t-teal-400" /> Syncing
                                    </span>
                                ) : warnings === 0 ? (
                                    <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-500/25 bg-emerald-500/10 px-2.5 py-1 text-[11px] font-semibold text-emerald-700 dark:text-emerald-300">
                                        <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" /> All systems nominal
                                    </span>
                                ) : (
                                    <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-500/25 bg-amber-500/10 px-2.5 py-1 text-[11px] font-semibold text-amber-700 dark:text-amber-300">
                                        <span className="h-1.5 w-1.5 rounded-full bg-amber-400" /> {warnings} {warnings === 1 ? 'item needs' : 'items need'} attention
                                    </span>
                                )}
                            </div>

                            <p className="mt-6 text-sm text-slate-500 dark:text-slate-400">{greeting()}, {auth?.data?.username || 'admin'}</p>
                            <h1 className="mt-1 break-words text-3xl font-bold tracking-tight text-slate-900 dark:text-white sm:text-[40px] sm:leading-[1.1]">
                                <span className="inline-block pr-1 pb-1 dark:bg-gradient-to-r dark:from-white dark:via-teal-100 dark:to-cyan-300 dark:bg-clip-text dark:text-transparent">{hostname}</span>
                            </h1>
                            <p className="mt-3 max-w-md text-[14px] leading-relaxed text-slate-500 dark:text-slate-400">
                                One control plane for routing, upstream balancing, TLS and DNS — watching every route in real time.
                            </p>

                            <div className="mt-6">
                                <QuickMapping onMappingComplete={() => { window.location.reload(); }} />
                            </div>
                        </div>

                        <div className="mt-8 grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-slate-200/80 bg-slate-200/80 dark:border-white/[0.06] dark:bg-white/[0.06] sm:grid-cols-4">
                            {infra.map((c, i) => {
                                const Icon = c.icon;
                                return (
                                    <motion.div
                                        key={c.label}
                                        initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.06 * i }}
                                        className="bg-white/80 px-4 py-3.5 dark:bg-slate-900/70"
                                    >
                                        <div className="flex items-center gap-1.5">
                                            <Icon className={`text-[12px] ${c.tint}`} />
                                            <span className="text-[11px] font-medium text-slate-500 dark:text-slate-400">{c.label}</span>
                                        </div>
                                        {loading
                                            ? <div className="mt-2 h-6 w-10 animate-pulse rounded bg-slate-200 dark:bg-white/[0.06]" />
                                            : <AnimatedNumber value={c.count} />}
                                    </motion.div>
                                );
                            })}
                        </div>
                    </div>

                    <div className="relative min-h-[300px] border-t border-slate-200/70 p-4 dark:border-white/[0.05] lg:border-l lg:border-t-0 sm:p-6">
                        <div className="absolute left-6 top-5 flex items-center gap-2 sm:left-8">
                            <span className="relative flex h-2 w-2">
                                <span className="absolute inline-flex h-full w-full animate-pulse-ring rounded-full bg-teal-400" />
                                <span className="relative inline-flex h-2 w-2 rounded-full bg-teal-400" />
                            </span>
                            <span className="sg-eyebrow">Live topology</span>
                        </div>
                        <div className="h-full pt-6">
                            <TrafficFlow sources={initialData.sourcesystems} targets={initialData.targetsystems} loading={loading} />
                        </div>
                    </div>
                </div>
            </section>

            {/* Performance */}
            <section className="space-y-4">
                <SectionHeader title="Performance" subtitle="Aggregated across every route since the last restart" />
                <StatsKPIs stats={stats || []} loading={loading} />
                <div className="grid gap-4 xl:grid-cols-3">
                    <div className="xl:col-span-2"><RouteTable stats={stats || []} loading={loading} /></div>
                    <Insights insights={insights} loading={loading} />
                </div>
            </section>

            {/* Systems */}
            <section className="space-y-4">
                <SectionHeader title="Routing configuration" subtitle="Sources, upstream targets and host groups" />
                {!loading && <RootComponents initialData={initialData} />}
                {loading && <div className="sg-card h-64 animate-pulse" />}
            </section>
        </div>
    );
}

function SectionHeader({ title, subtitle }: { title: string; subtitle?: string }) {
    return (
        <div className="flex items-end justify-between gap-4 px-1">
            <div>
                <h2 className="text-[17px] font-semibold tracking-tight text-slate-900 dark:text-white">{title}</h2>
                {subtitle && <p className="mt-0.5 text-[12.5px] text-slate-500 dark:text-slate-400">{subtitle}</p>}
            </div>
        </div>
    );
}

function AnimatedNumber({ value }: { value: number }) {
    const [displayed, setDisplayed] = useState(0);

    useEffect(() => {
        if (value === 0) { setDisplayed(0); return; }
        const duration = 700;
        const start = performance.now();
        let raf = 0;
        const tick = (t: number) => {
            const p = Math.min(1, (t - start) / duration);
            const eased = 1 - Math.pow(1 - p, 3);
            setDisplayed(Math.round(value * eased));
            if (p < 1) raf = requestAnimationFrame(tick);
        };
        raf = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(raf);
    }, [value]);

    return <p className="mt-1 text-2xl font-semibold tracking-tight text-slate-900 tabular-nums dark:text-white">{displayed.toLocaleString()}</p>;
}

export default Home;
