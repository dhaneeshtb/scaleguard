import React, { useEffect, useState } from 'react';
import Stats from '../components/Stats';
import RootComponents from '../components/RootComponents';
import QuickMapping from '../components/QuickMapping';
import useSystemContext from '../contexts/SystemContext';
import { useAuth } from '../contexts/AuthContext';
import axios from 'axios';
import { FaShieldAlt, FaServer, FaNetworkWired, FaCertificate, FaPlug, FaCheckCircle } from 'react-icons/fa';
import { motion, AnimatePresence } from 'framer-motion';

function Home() {
    const { properties } = useSystemContext() as any;
    const { auth } = useAuth() as any;
    const [counts, setCounts] = useState({ sources: 0, targets: 0, certs: 0, hostGroups: 0 });
    const [loading, setLoading] = useState(true);
    const [loadPhase, setLoadPhase] = useState(0); // 0: infra, 1: stats, 2: systems, 3: done

    useEffect(() => {
        const loadCounts = async () => {
            setLoading(true);
            setLoadPhase(0);
            try {
                const headers = { Authorization: auth.data.token };
                const host = auth.data.host;

                // Phase 0: Load infrastructure counts
                const [srcRes, tgtRes, certRes, hgRes] = await Promise.allSettled([
                    axios.get(`${host}/config/sourcesystems?scaleguard=true`, { headers }),
                    axios.get(`${host}/config/targetsystems?scaleguard=true`, { headers }),
                    axios.get(`${host}/certificates?scaleguard=true`, { headers }),
                    axios.get(`${host}/config/hostgroups?scaleguard=true`, { headers }),
                ]);
                setCounts({
                    sources: srcRes.status === 'fulfilled' ? (srcRes.value.data.sourcesystems?.length || 0) : 0,
                    targets: tgtRes.status === 'fulfilled' ? (tgtRes.value.data.targetsystems?.length || 0) : 0,
                    certs: certRes.status === 'fulfilled' ? (certRes.value.data?.length || 0) : 0,
                    hostGroups: hgRes.status === 'fulfilled' ? (hgRes.value.data.hostgroups?.length || 0) : 0,
                });
                setLoadPhase(1);

                // Small delay for visual stagger
                await new Promise(r => setTimeout(r, 300));
                setLoadPhase(2);

                await new Promise(r => setTimeout(r, 200));
                setLoadPhase(3);
            } catch (e) {
                console.error('Failed to load counts:', e);
                setLoadPhase(3);
            }
            setLoading(false);
        };
        if (auth.data) loadCounts();
    }, [auth.data]);

    const hostname = properties?.hostName?.value || 'Scaleguard';
    const isFullyLoaded = loadPhase >= 3;

    return (
        <div className='min-h-screen'>
            {/* Hero Section */}
            <div className="relative overflow-hidden rounded-2xl mx-2 mt-2 mb-6">
                <div className="absolute inset-0 bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900"></div>
                <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_top_right,_var(--tw-gradient-stops))] from-teal-500/10 via-transparent to-blue-500/10"></div>

                {/* Grid pattern overlay */}
                <div className="absolute inset-0 opacity-[0.03]" style={{
                    backgroundImage: 'linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)',
                    backgroundSize: '32px 32px'
                }}></div>

                <div className="relative px-6 py-8 sm:px-8 sm:py-10">
                    <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
                        <div className="space-y-2">
                            <div className="flex items-center gap-3">
                                <div className="p-2 bg-teal-500/10 rounded-lg border border-teal-500/20">
                                    <FaShieldAlt className="text-teal-400 text-lg" />
                                </div>
                                <div>
                                    <p className="text-xs font-medium text-teal-400 uppercase tracking-widest">Load Balancer & Reverse Proxy</p>
                                    <h1 className="text-2xl sm:text-3xl font-bold text-white tracking-tight">{hostname}</h1>
                                </div>
                            </div>
                            <p className="text-slate-400 text-sm max-w-lg">
                                Manage source routing, target upstreams, SSL certificates, and DNS records from a unified control plane.
                            </p>
                        </div>
                        <div className="flex gap-2 items-center">
                            {loading && (
                                <motion.div
                                    initial={{ opacity: 0, scale: 0.8 }}
                                    animate={{ opacity: 1, scale: 1 }}
                                    exit={{ opacity: 0, scale: 0.8 }}
                                    className="flex items-center gap-2 px-3 py-1.5 bg-teal-500/10 border border-teal-500/20 rounded-full"
                                >
                                    <div className="relative w-3.5 h-3.5">
                                        <div className="absolute inset-0 rounded-full border-2 border-teal-500/30"></div>
                                        <div className="absolute inset-0 rounded-full border-2 border-transparent border-t-teal-400 animate-spin"></div>
                                    </div>
                                    <span className="text-[11px] font-medium text-teal-300">
                                        {loadPhase === 0 ? 'Loading infrastructure…' : loadPhase === 1 ? 'Loading metrics…' : 'Initializing…'}
                                    </span>
                                </motion.div>
                            )}
                            {isFullyLoaded && (
                                <motion.div
                                    initial={{ opacity: 0, scale: 0.8 }}
                                    animate={{ opacity: 1, scale: 1 }}
                                    className="flex items-center gap-1.5 px-3 py-1.5 bg-emerald-500/10 border border-emerald-500/20 rounded-full"
                                >
                                    <FaCheckCircle className="text-emerald-400 text-[10px]" />
                                    <span className="text-[11px] font-medium text-emerald-300">All systems operational</span>
                                </motion.div>
                            )}
                            <QuickMapping onMappingComplete={() => { window.location.reload() }} />
                        </div>
                    </div>

                    {/* Infrastructure Counter Cards */}
                    <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mt-6">
                        {[
                            { icon: <FaServer />, label: "Source Systems", count: counts.sources, color: "blue", delay: 0 },
                            { icon: <FaPlug />, label: "Target Upstreams", count: counts.targets, color: "violet", delay: 0.08 },
                            { icon: <FaCertificate />, label: "SSL Certificates", count: counts.certs, color: "emerald", delay: 0.16 },
                            { icon: <FaNetworkWired />, label: "Host Groups", count: counts.hostGroups, color: "amber", delay: 0.24 },
                        ].map((card, i) => (
                            <motion.div
                                key={card.label}
                                initial={{ opacity: 0, y: 12 }}
                                animate={{ opacity: loadPhase >= 1 ? 1 : 0.4, y: 0 }}
                                transition={{ delay: card.delay, duration: 0.4, ease: "easeOut" }}
                            >
                                <InfraCard
                                    icon={card.icon}
                                    label={card.label}
                                    count={card.count}
                                    color={card.color}
                                    loading={loadPhase < 1}
                                />
                            </motion.div>
                        ))}
                    </div>
                </div>
            </div>

            {/* Performance Section */}
            <motion.div
                className="px-2 mb-6"
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: loadPhase >= 2 ? 1 : 0, y: loadPhase >= 2 ? 0 : 20 }}
                transition={{ duration: 0.5, ease: "easeOut" }}
            >
                <div className="flex items-center gap-2 mb-4 px-2">
                    <div className="h-1 w-6 bg-gradient-to-r from-teal-400 to-cyan-400 rounded-full"></div>
                    <h2 className="text-lg font-semibold text-slate-700 dark:text-slate-200">Performance Overview</h2>
                </div>
                <Stats />
            </motion.div>

            {/* Systems Management */}
            <motion.div
                className="px-2"
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: loadPhase >= 3 ? 1 : 0, y: loadPhase >= 3 ? 0 : 20 }}
                transition={{ duration: 0.5, ease: "easeOut" }}
            >
                <div className="flex items-center gap-2 mb-2 px-2">
                    <div className="h-1 w-6 bg-gradient-to-r from-violet-400 to-purple-400 rounded-full"></div>
                    <h2 className="text-lg font-semibold text-slate-700 dark:text-slate-200">System Management</h2>
                </div>
                <RootComponents />
            </motion.div>
        </div>
    );
}

function InfraCard({ icon, label, count, color, loading }: { icon: React.ReactNode; label: string; count: number; color: string; loading?: boolean }) {
    const colorMap: Record<string, { bg: string; text: string; border: string; shimmer: string }> = {
        blue: { bg: 'bg-blue-500/10', text: 'text-blue-400', border: 'border-blue-500/20', shimmer: 'from-blue-500/20' },
        violet: { bg: 'bg-violet-500/10', text: 'text-violet-400', border: 'border-violet-500/20', shimmer: 'from-violet-500/20' },
        emerald: { bg: 'bg-emerald-500/10', text: 'text-emerald-400', border: 'border-emerald-500/20', shimmer: 'from-emerald-500/20' },
        amber: { bg: 'bg-amber-500/10', text: 'text-amber-400', border: 'border-amber-500/20', shimmer: 'from-amber-500/20' },
    };
    const c = colorMap[color] || colorMap.blue;

    return (
        <div className={`${c.bg} border ${c.border} rounded-xl p-4 backdrop-blur-sm transition-all duration-300 hover:scale-[1.02] relative overflow-hidden`}>
            {/* Shimmer overlay when loading */}
            {loading && (
                <div className="absolute inset-0 overflow-hidden">
                    <div
                        className={`absolute inset-0 -translate-x-full animate-[shimmer_1.5s_ease-in-out_infinite] bg-gradient-to-r from-transparent ${c.shimmer} to-transparent`}
                    ></div>
                </div>
            )}

            <div className="flex items-center gap-2 mb-2 relative">
                <span className={`${c.text} text-sm`}>{icon}</span>
                <span className="text-xs font-medium text-slate-400 uppercase tracking-wider">{label}</span>
            </div>

            <div className="relative">
                {loading ? (
                    <div className="h-8 flex items-center">
                        <div className="flex gap-1 items-end">
                            <div className="w-1.5 h-4 bg-slate-600 rounded-full animate-pulse"></div>
                            <div className="w-1.5 h-6 bg-slate-500 rounded-full animate-pulse" style={{ animationDelay: '0.15s' }}></div>
                            <div className="w-1.5 h-3 bg-slate-600 rounded-full animate-pulse" style={{ animationDelay: '0.3s' }}></div>
                        </div>
                    </div>
                ) : (
                    <AnimatedNumber value={count} />
                )}
            </div>
        </div>
    );
}

function AnimatedNumber({ value }: { value: number }) {
    const [displayed, setDisplayed] = useState(0);

    useEffect(() => {
        if (value === 0) { setDisplayed(0); return; }
        const duration = 600;
        const steps = 20;
        const increment = value / steps;
        let current = 0;
        const timer = setInterval(() => {
            current += increment;
            if (current >= value) {
                setDisplayed(value);
                clearInterval(timer);
            } else {
                setDisplayed(Math.floor(current));
            }
        }, duration / steps);
        return () => clearInterval(timer);
    }, [value]);

    return (
        <motion.p
            className="text-2xl font-bold text-white"
            initial={{ opacity: 0, scale: 0.5 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ type: "spring", stiffness: 200, damping: 15 }}
        >
            {displayed.toLocaleString()}
        </motion.p>
    );
}

export default Home;
