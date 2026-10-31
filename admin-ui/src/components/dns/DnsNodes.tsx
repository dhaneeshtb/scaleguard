import React, { useCallback, useEffect, useState } from "react";
import { useAuth } from "../../contexts/AuthContext";
import { Card, Pill } from "../ui/controls";
import { dnsCall } from "./dnsApi";

/** Per-node view of the cluster-wide setting: the desired state comes from the setting, each node reports what it runs. */
export default function DnsNodes({ status }: { status: any }) {
    const { auth } = useAuth() as any;
    const [cluster, setCluster] = useState<any>();

    const load = useCallback(async () => {
        try { setCluster((await dnsCall(auth, "get", "/cluster")).data); } catch { setCluster(undefined); }
    }, [auth]);
    useEffect(() => { load(); const t = setInterval(load, 10000); return () => clearInterval(t); }, [load]);

    const nodes: any[] = cluster?.enabled ? cluster.nodes : [];
    const want = !!status?.enabled;

    return (
        <Card title="Nodes" description={cluster?.enabled
            ? "Start, stop and settings changes are replicated; each node follows within seconds."
            : "This node is not part of a cluster, so changes apply here only."}>
            {nodes.length === 0 ? (
                <div className="flex items-center justify-between text-[13px]">
                    <span className="text-slate-700 dark:text-slate-300">This node</span>
                    <Pill tone={status?.running ? "good" : "muted"}>{status?.running ? `Running on :${status.port}` : "Stopped"}</Pill>
                </div>
            ) : (
                <table className="min-w-full text-left text-[13px]">
                    <thead><tr className="text-[11px] uppercase tracking-wider text-slate-400">
                        <th className="py-2 pr-4 font-medium">Node</th><th className="py-2 pr-4 font-medium">Address</th>
                        <th className="py-2 pr-4 font-medium">Seen</th><th className="py-2 font-medium">DNS server</th>
                    </tr></thead>
                    <tbody className="divide-y divide-slate-100 dark:divide-white/5">
                        {nodes.map(n => {
                            const running = n.self ? !!status?.running : n.info?.dnsRunning;
                            const inSync = running === want;
                            return (
                                <tr key={n.node} className="text-slate-700 dark:text-slate-300">
                                    <td className="py-2.5 pr-4 font-medium text-slate-900 dark:text-white">{n.node}{n.self && <span className="ml-2 text-[11px] text-slate-400">this node</span>}</td>
                                    <td className="py-2.5 pr-4 font-mono text-[12px]">{n.address}</td>
                                    <td className="py-2.5 pr-4 text-slate-500">{n.alive ? (n.lastSeenMs != null ? `${Math.round(n.lastSeenMs / 1000)}s ago` : "now") : "down"}</td>
                                    <td className="py-2.5">
                                        {!n.alive ? <Pill tone="bad">Offline</Pill>
                                            : running === undefined ? <Pill tone="muted">Unknown</Pill>
                                                : <Pill tone={inSync ? (running ? "good" : "muted") : "warn"}>{running ? "Running" : "Stopped"}{inSync ? "" : " (out of sync)"}</Pill>}
                                    </td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            )}
        </Card>
    );
}
