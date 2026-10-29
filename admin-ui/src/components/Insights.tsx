import { FiAlertTriangle, FiCheckCircle, FiInfo, FiTrendingUp } from "react-icons/fi";
import { HiSparkles } from "react-icons/hi2";
import type { IconType } from "react-icons";
import { motion } from "framer-motion";

export interface StatEntry { key: string; total: number; min: number; max: number; avg: number }

type Level = "good" | "info" | "warn";
interface Insight { level: Level; title: string; detail: string; icon: IconType }

const routeName = (key: string) => key.split(":").slice(2).join(":") || "/";

function median(xs: number[]) {
    if (!xs.length) return 0;
    const s = [...xs].sort((a, b) => a - b);
    const m = Math.floor(s.length / 2);
    return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

/** Derives operator-facing insights from live route statistics and configuration counts. */
export function deriveInsights(stats: StatEntry[] | null, counts: { sources: number; targets: number; certs: number; hostGroups: number }): Insight[] {
    const out: Insight[] = [];
    const list = stats || [];

    if (list.length === 0) {
        out.push({ level: "info", icon: FiInfo, title: "Waiting for traffic", detail: "Route statistics will appear here as soon as requests flow through ScaleGuard." });
    } else {
        const total = list.reduce((a, s) => a + s.total, 0);
        const med = median(list.map(s => s.avg));
        const slowest = [...list].sort((a, b) => b.avg - a.avg)[0];
        if (slowest && slowest.avg > 150 && med > 0 && slowest.avg >= med * 2 && list.length > 1) {
            out.push({
                level: "warn", icon: FiAlertTriangle,
                title: `${routeName(slowest.key)} is a latency outlier`,
                detail: `Averages ${Math.round(slowest.avg)} ms — ${(slowest.avg / med).toFixed(1)}× the fleet median. Check upstream health or add capacity.`,
            });
        }
        const busiest = [...list].sort((a, b) => b.total - a.total)[0];
        if (busiest && total > 0 && list.length > 1) {
            const share = Math.round((busiest.total / total) * 100);
            if (share >= 50) {
                out.push({
                    level: "info", icon: FiTrendingUp,
                    title: `${routeName(busiest.key)} carries ${share}% of traffic`,
                    detail: "A single hot route — consider spreading it across more upstream targets.",
                });
            }
        }
        const healthy = list.filter(s => s.avg < 200).length;
        out.push({
            level: healthy === list.length ? "good" : "warn",
            icon: healthy === list.length ? FiCheckCircle : FiAlertTriangle,
            title: `${healthy} of ${list.length} routes under 200 ms`,
            detail: healthy === list.length ? "Every route is responding within target latency." : `${list.length - healthy} route${list.length - healthy > 1 ? "s are" : " is"} above the 200 ms target.`,
        });
    }

    if (counts.sources > 0 && counts.certs === 0) {
        out.push({ level: "warn", icon: FiAlertTriangle, title: "No TLS certificates", detail: "Sources are configured without certificates. Turn on auto-procure under System." });
    }
    if (counts.sources > 0 && counts.targets === 0) {
        out.push({ level: "warn", icon: FiAlertTriangle, title: "No upstream targets", detail: "Traffic has nowhere to go — add a target system or host group." });
    }
    return out.slice(0, 4);
}

const tone: Record<Level, string> = {
    good: "text-emerald-500 dark:text-emerald-300 bg-emerald-500/10 border-emerald-500/20",
    info: "text-sky-500 dark:text-sky-300 bg-sky-500/10 border-sky-500/20",
    warn: "text-amber-500 dark:text-amber-300 bg-amber-500/10 border-amber-500/20",
};

export default function Insights({ insights, loading }: { insights: Insight[]; loading?: boolean }) {
    return (
        <div className="sg-card sg-ring-gradient h-full overflow-hidden">
            <div className="pointer-events-none absolute -right-16 -top-16 h-48 w-48 rounded-full bg-violet-500/20 blur-3xl" />
            <div className="relative flex items-center justify-between px-5 pb-3 pt-5">
                <div className="flex items-center gap-2.5">
                    <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-teal-400 to-violet-500 text-white shadow-glow-violet">
                        <HiSparkles className="text-[15px]" />
                    </span>
                    <div>
                        <h3 className="text-sm font-semibold text-slate-900 dark:text-white">Traffic insights</h3>
                        <p className="text-[11px] text-slate-500 dark:text-slate-400">Analyzed from live route statistics</p>
                    </div>
                </div>
            </div>
            <div className="relative space-y-2 px-3 pb-4">
                {loading
                    ? [0, 1, 2].map(i => <div key={i} className="h-[68px] animate-pulse rounded-xl bg-slate-200/60 dark:bg-white/[0.04]" />)
                    : insights.map((ins, i) => {
                        const Icon = ins.icon;
                        return (
                            <motion.div
                                key={ins.title}
                                initial={{ opacity: 0, x: 8 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.08 * i }}
                                className="flex gap-3 rounded-xl border border-transparent p-2.5 transition-colors hover:border-slate-200 hover:bg-white/60 dark:hover:border-white/[0.06] dark:hover:bg-white/[0.02]"
                            >
                                <span className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border ${tone[ins.level]}`}>
                                    <Icon className="text-[13px]" />
                                </span>
                                <div className="min-w-0">
                                    <p className="truncate text-[13px] font-semibold text-slate-800 dark:text-slate-100">{ins.title}</p>
                                    <p className="mt-0.5 text-[12px] leading-relaxed text-slate-500 dark:text-slate-400">{ins.detail}</p>
                                </div>
                            </motion.div>
                        );
                    })}
            </div>
        </div>
    );
}
