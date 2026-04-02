import axios from "axios";
import { useEffect, useState } from "react";
import { useAuth } from "../contexts/AuthContext";
import { FaArrowDown, FaArrowUp, FaBolt, FaClock, FaExchangeAlt, FaServer, FaTachometerAlt } from "react-icons/fa";

interface StatEntry {
  key: string;
  total: number;
  min: number;
  max: number;
  avg: number;
}

export default function Stats() {
  const [stats, setStats] = useState<StatEntry[]>([]);
  const { auth } = useAuth() as any;

  useEffect(() => {
    axios
      .get(auth.data.host + "/stats?scaleguard=true", {
        headers: {
          Authorization: auth.data.token,
        },
      })
      .then((r) => setStats(r.data))
      .catch((err) => console.error("Failed to load stats:", err));
  }, []);

  const totalRequests = stats.reduce((acc, s) => acc + s.total, 0);
  const avgLatency =
    stats.length > 0
      ? (stats.reduce((acc, s) => acc + s.avg, 0) / stats.length).toFixed(1)
      : "0";
  const minLatency =
    stats.length > 0 ? Math.min(...stats.map((s) => s.min)) : 0;
  const maxLatency =
    stats.length > 0 ? Math.max(...stats.map((s) => s.max)) : 0;

  return (
    <div className="space-y-6">
      {/* Summary KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <KPICard
          icon={<FaExchangeAlt />}
          label="Total Requests"
          value={totalRequests.toLocaleString()}
          accent="from-blue-500 to-cyan-400"
          iconBg="bg-blue-500/10 text-blue-400"
        />
        <KPICard
          icon={<FaTachometerAlt />}
          label="Avg Latency"
          value={`${avgLatency} ms`}
          accent="from-emerald-500 to-teal-400"
          iconBg="bg-emerald-500/10 text-emerald-400"
        />
        <KPICard
          icon={<FaBolt />}
          label="Fastest Response"
          value={`${minLatency} ms`}
          accent="from-violet-500 to-purple-400"
          iconBg="bg-violet-500/10 text-violet-400"
        />
        <KPICard
          icon={<FaClock />}
          label="Slowest Response"
          value={`${maxLatency} ms`}
          accent="from-amber-500 to-orange-400"
          iconBg="bg-amber-500/10 text-amber-400"
        />
      </div>

      {/* Per-Route Performance */}
      {stats.length > 0 && (
        <div className="bg-white/5 dark:bg-slate-800/60 backdrop-blur-sm rounded-2xl border border-white/10 dark:border-slate-700/50 overflow-hidden">
          <div className="px-6 py-4 border-b border-white/5 dark:border-slate-700/40">
            <div className="flex items-center gap-2">
              <FaServer className="text-teal-400" />
              <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 uppercase tracking-wider">
                Route Performance
              </h3>
            </div>
          </div>
          <div className="overflow-x-auto">
            <table className="min-w-full text-left text-sm">
              <thead>
                <tr className="text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400 bg-slate-50/50 dark:bg-slate-800/80">
                  <th className="px-6 py-3 font-medium">Route</th>
                  <th className="px-6 py-3 font-medium text-right">Requests</th>
                  <th className="px-6 py-3 font-medium text-right">Min (ms)</th>
                  <th className="px-6 py-3 font-medium text-right">Avg (ms)</th>
                  <th className="px-6 py-3 font-medium text-right">Max (ms)</th>
                  <th className="px-6 py-3 font-medium">Latency Distribution</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-700/40">
                {stats.map((stat, index) => {
                  const routeName = stat.key.split(":").slice(2).join(":");
                  const barWidth = maxLatency > 0 ? (stat.avg / maxLatency) * 100 : 0;
                  const isHealthy = stat.avg < 200;
                  return (
                    <tr
                      key={stat.key || index}
                      className="text-slate-700 dark:text-slate-300 hover:bg-slate-50/50 dark:hover:bg-slate-700/30 transition-colors"
                    >
                      <td className="px-6 py-3">
                        <div className="flex items-center gap-2">
                          <span
                            className={`w-2 h-2 rounded-full ${
                              isHealthy ? "bg-emerald-400" : "bg-amber-400"
                            }`}
                          ></span>
                          <span className="font-medium text-xs">{routeName || "/"}</span>
                        </div>
                      </td>
                      <td className="px-6 py-3 text-right font-mono text-xs">
                        {stat.total.toLocaleString()}
                      </td>
                      <td className="px-6 py-3 text-right font-mono text-xs text-emerald-500">
                        {stat.min}
                      </td>
                      <td className="px-6 py-3 text-right font-mono text-xs">
                        {stat.avg}
                      </td>
                      <td className="px-6 py-3 text-right font-mono text-xs text-amber-500">
                        {stat.max}
                      </td>
                      <td className="px-6 py-3">
                        <div className="flex items-center gap-2">
                          <div className="flex-1 h-2 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
                            <div
                              className={`h-full rounded-full transition-all duration-500 ${
                                isHealthy
                                  ? "bg-gradient-to-r from-emerald-400 to-teal-400"
                                  : "bg-gradient-to-r from-amber-400 to-orange-400"
                              }`}
                              style={{ width: `${Math.max(barWidth, 5)}%` }}
                            ></div>
                          </div>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

function KPICard({
  icon,
  label,
  value,
  accent,
  iconBg,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  accent: string;
  iconBg: string;
}) {
  return (
    <div className="relative group">
      <div className={`absolute inset-0 bg-gradient-to-br ${accent} opacity-0 group-hover:opacity-5 rounded-2xl transition-opacity duration-300`}></div>
      <div className="relative bg-white/80 dark:bg-slate-800/60 backdrop-blur-sm rounded-2xl border border-slate-200/60 dark:border-slate-700/50 p-5 transition-all duration-300 hover:shadow-lg hover:shadow-slate-200/20 dark:hover:shadow-slate-900/40 hover:-translate-y-0.5">
        <div className="flex items-start justify-between">
          <div className="space-y-2">
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wider">
              {label}
            </p>
            <p className="text-2xl font-bold text-slate-800 dark:text-white tracking-tight">
              {value}
            </p>
          </div>
          <div className={`p-3 rounded-xl ${iconBg}`}>
            <span className="text-lg">{icon}</span>
          </div>
        </div>
      </div>
    </div>
  );
}