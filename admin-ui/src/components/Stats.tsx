import axios from "axios";
import React, { useEffect, useRef, useState } from "react";
import { useAuth } from "../contexts/AuthContext";
import { FiActivity, FiClock, FiRepeat, FiZap } from "react-icons/fi";
import { motion } from "framer-motion";
import type { StatEntry } from "./Insights";

export type { StatEntry };

export function summarize(stats: StatEntry[]) {
  const totalRequests = stats.reduce((acc, s) => acc + s.total, 0);
  const avgLatency = stats.length > 0 ? stats.reduce((acc, s) => acc + s.avg, 0) / stats.length : 0;
  const minLatency = stats.length > 0 ? Math.min(...stats.map((s) => s.min)) : 0;
  const maxLatency = stats.length > 0 ? Math.max(...stats.map((s) => s.max)) : 0;
  return { totalRequests, avgLatency, minLatency, maxLatency };
}

function compact(n: number) {
  if (n >= 1e9) return (n / 1e9).toFixed(1) + "B";
  if (n >= 1e6) return (n / 1e6).toFixed(1) + "M";
  if (n >= 1e4) return (n / 1e3).toFixed(1) + "K";
  return n.toLocaleString();
}

export function StatsKPIs({ stats, loading }: { stats: StatEntry[]; loading?: boolean }) {
  const s = summarize(stats);
  const cards = [
    { icon: FiRepeat, label: "Total requests", value: compact(s.totalRequests), unit: "", accent: "from-cyan-400 to-sky-500", tint: "text-cyan-500 dark:text-cyan-300", bar: Math.min(1, s.totalRequests / 1e5) },
    { icon: FiActivity, label: "Avg latency", value: s.avgLatency.toFixed(1), unit: "ms", accent: "from-teal-400 to-emerald-500", tint: "text-teal-500 dark:text-teal-300", bar: Math.min(1, s.avgLatency / 500) },
    { icon: FiZap, label: "Fastest response", value: String(s.minLatency), unit: "ms", accent: "from-violet-400 to-indigo-500", tint: "text-violet-500 dark:text-violet-300", bar: Math.min(1, s.minLatency / 500) },
    { icon: FiClock, label: "Slowest response", value: String(s.maxLatency), unit: "ms", accent: "from-amber-400 to-orange-500", tint: "text-amber-500 dark:text-amber-300", bar: Math.min(1, s.maxLatency / 2000) },
  ];
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4 lg:gap-4">
      {cards.map((c, i) => {
        const Icon = c.icon;
        return (
          <motion.div
            key={c.label}
            initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.05 * i, duration: 0.4 }}
            className="sg-card group overflow-hidden p-4 transition-transform duration-300 hover:-translate-y-0.5 sm:p-5"
          >
            <div className={`pointer-events-none absolute -right-10 -top-10 h-28 w-28 rounded-full bg-gradient-to-br ${c.accent} opacity-[0.08] blur-2xl transition-opacity group-hover:opacity-20`} />
            <div className="flex items-center justify-between">
              <span className="sg-eyebrow">{c.label}</span>
              <Icon className={`text-[15px] ${c.tint}`} />
            </div>
            {loading ? (
              <div className="mt-3 h-8 w-24 animate-pulse rounded-md bg-slate-200/70 dark:bg-white/[0.05]" />
            ) : (
              <p className="mt-3 flex items-baseline gap-1 text-[28px] font-semibold leading-none tracking-tight text-slate-900 dark:text-white tabular-nums">
                {c.value}
                {c.unit && <span className="text-sm font-medium text-slate-400">{c.unit}</span>}
              </p>
            )}
            <div className="mt-4 h-1 overflow-hidden rounded-full bg-slate-200/70 dark:bg-white/[0.05]">
              <motion.div
                className={`h-full rounded-full bg-gradient-to-r ${c.accent}`}
                initial={{ width: 0 }} animate={{ width: loading ? "0%" : `${Math.max(6, c.bar * 100)}%` }}
                transition={{ duration: 0.9, ease: "easeOut", delay: 0.1 + i * 0.05 }}
              />
            </div>
          </motion.div>
        );
      })}
    </div>
  );
}

export function RouteTable({ stats, loading }: { stats: StatEntry[]; loading?: boolean }) {
  const { maxLatency } = summarize(stats);
  const sorted = [...stats].sort((a, b) => b.total - a.total);
  return (
    <div className="sg-card h-full overflow-hidden">
      <div className="flex items-center justify-between px-5 pb-3 pt-5">
        <div>
          <h3 className="text-sm font-semibold text-slate-900 dark:text-white">Route performance</h3>
          <p className="text-[11px] text-slate-500 dark:text-slate-400">Sorted by request volume</p>
        </div>
        <div className="hidden items-center gap-3 text-[11px] text-slate-500 sm:flex">
          <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-emerald-400" /> &lt; 200 ms</span>
          <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-amber-400" /> ≥ 200 ms</span>
        </div>
      </div>

      {loading ? (
        <div className="space-y-2 px-5 pb-5">
          {[0, 1, 2, 3].map(i => <div key={i} className="h-10 animate-pulse rounded-lg bg-slate-200/60 dark:bg-white/[0.04]" />)}
        </div>
      ) : stats.length === 0 ? (
        <div className="flex flex-col items-center justify-center px-5 pb-10 pt-6 text-center">
          <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-2xl border border-dashed border-slate-300 text-slate-400 dark:border-white/10">
            <FiActivity />
          </div>
          <p className="text-sm font-medium text-slate-700 dark:text-slate-200">No traffic recorded yet</p>
          <p className="mt-1 max-w-xs text-xs text-slate-500">Send a request through any source system and per-route latency will show up here.</p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full text-left text-sm">
            <thead>
              <tr className="border-y border-slate-200/70 text-[10.5px] uppercase tracking-[0.12em] text-slate-500 dark:border-white/[0.05] dark:text-slate-400">
                <th className="px-5 py-2.5 font-semibold">Route</th>
                <th className="px-4 py-2.5 text-right font-semibold">Requests</th>
                <th className="px-4 py-2.5 text-right font-semibold">Min</th>
                <th className="px-4 py-2.5 text-right font-semibold">Avg</th>
                <th className="px-4 py-2.5 text-right font-semibold">Max</th>
                <th className="w-[28%] px-5 py-2.5 font-semibold">Latency</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-white/[0.04]">
              {sorted.map((stat, index) => {
                const route = stat.key.split(":").slice(2).join(":") || "/";
                const avgPct = maxLatency > 0 ? (stat.avg / maxLatency) * 100 : 0;
                const minPct = maxLatency > 0 ? (stat.min / maxLatency) * 100 : 0;
                const maxPct = maxLatency > 0 ? (stat.max / maxLatency) * 100 : 0;
                const healthy = stat.avg < 200;
                return (
                  <tr key={stat.key || index} className="text-slate-700 transition-colors hover:bg-slate-50/70 dark:text-slate-300 dark:hover:bg-white/[0.02]">
                    <td className="px-5 py-3">
                      <div className="flex items-center gap-2.5">
                        <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${healthy ? "bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,.8)]" : "bg-amber-400 shadow-[0_0_8px_rgba(251,191,36,.8)]"}`} />
                        <span className="truncate font-mono text-[12px] font-medium text-slate-800 dark:text-slate-100" title={route}>{route}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-[12px] tabular-nums">{stat.total.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right font-mono text-[12px] tabular-nums text-emerald-600 dark:text-emerald-400">{stat.min}</td>
                    <td className="px-4 py-3 text-right font-mono text-[12px] font-semibold tabular-nums text-slate-900 dark:text-white">{stat.avg}</td>
                    <td className="px-4 py-3 text-right font-mono text-[12px] tabular-nums text-amber-600 dark:text-amber-400">{stat.max}</td>
                    <td className="px-5 py-3">
                      {/* min–max range with avg marker */}
                      <div className="relative h-2 rounded-full bg-slate-200/70 dark:bg-white/[0.05]" title={`min ${stat.min} · avg ${stat.avg} · max ${stat.max} ms`}>
                        <div
                          className={`absolute inset-y-0 rounded-full opacity-40 ${healthy ? "bg-emerald-400" : "bg-amber-400"}`}
                          style={{ left: `${minPct}%`, width: `${Math.max(2, maxPct - minPct)}%` }}
                        />
                        <div
                          className={`absolute -top-[3px] h-[14px] w-[3px] rounded-full ${healthy ? "bg-emerald-300" : "bg-amber-300"}`}
                          style={{ left: `calc(${Math.min(99, avgPct)}% - 1px)` }}
                        />
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/** Self-fetching composite, kept for any caller that doesn't supply stats. */
export default function Stats({ stats: provided }: { stats?: StatEntry[] | null }) {
  const [stats, setStats] = useState<StatEntry[] | null>(provided ?? null);
  const { auth } = useAuth() as any;
  const fetchedRef = useRef(false);

  useEffect(() => {
    if (provided !== undefined) { setStats(provided); return; }
    if (fetchedRef.current) return;
    fetchedRef.current = true;
    axios
      .get(auth.data.host + "/stats?scaleguard=true", { headers: { Authorization: auth.data.token } })
      .then((r) => setStats(r.data || []))
      .catch((err) => { console.error("Failed to load stats:", err); setStats([]); });
  }, [provided, auth]);

  const loading = stats === null;
  return (
    <div className="space-y-4">
      <StatsKPIs stats={stats || []} loading={loading} />
      <RouteTable stats={stats || []} loading={loading} />
    </div>
  );
}
