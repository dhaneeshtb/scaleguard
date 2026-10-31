import React, { useState } from "react";
import { FiPlus, FiTrash2 } from "react-icons/fi";
import { Card, Pill, inputCls } from "../ui/controls";

/** What to do at the DNS host above this name so the world asks ScaleGuard about it. */
function delegationHint(domain: string): string {
    const name = domain.replace(/\.$/, "");
    const dot = name.indexOf(".");
    const parts = name.split(".");
    if (parts.length < 3) return `Registered domain: at your registrar set its name servers to your name servers (with glue records).`;
    return `At the DNS host of ${name.substring(dot + 1)}: add an NS record with host "${name.substring(0, dot)}" pointing to your name servers.`;
}

export default function DnsZones({ domains, busy, setupHint, onAdd, onDelete, onTest }: {
    domains: any[]; busy: boolean; setupHint?: string;
    onAdd: (name: string) => Promise<boolean>; onDelete: (id: string) => Promise<void>; onTest: (name: string) => void;
}) {
    const [name, setName] = useState("");
    const add = async () => { if (await onAdd(name.trim().toLowerCase())) setName(""); };

    return (
        <Card title="Domains served" description="Domains this server is the authority for, e.g. app.example.com. Names inside them answer from your records; unknown names get NXDOMAIN.">
            {setupHint && <p className="mb-3 text-[12.5px] text-amber-700 dark:text-amber-300">{setupHint}</p>}
            <div className="flex gap-2">
                <input value={name} onChange={e => setName(e.target.value)} onKeyDown={e => { if (e.key === "Enter" && name.trim()) add(); }}
                    className={inputCls} placeholder="app.example.com" aria-label="Domain to serve" disabled={!!setupHint} />
                <button type="button" onClick={add} disabled={!!setupHint || busy || !name.trim()}
                    className="inline-flex h-10 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-4 text-[13px] font-semibold text-slate-950 disabled:opacity-50"><FiPlus /> Add</button>
            </div>
            {domains.length > 0 && (
                <ul className="mt-4 divide-y divide-slate-100 dark:divide-white/5">
                    {domains.map(d => (
                        <li key={d.id} className="flex items-start justify-between gap-3 py-3">
                            <div className="min-w-0">
                                <div className="flex items-center gap-2"><span className="font-mono text-[13px] font-medium text-slate-900 dark:text-white">{String(d.name).replace(/\.$/, "")}</span><Pill tone="info">Served</Pill></div>
                                <p className="mt-1 text-[12px] text-slate-500 dark:text-slate-400">{delegationHint(d.name)}</p>
                            </div>
                            <div className="flex shrink-0 items-center gap-1">
                                <button type="button" onClick={() => onTest(String(d.name).replace(/\.$/, ""))} className="rounded-lg px-3 py-1.5 text-[12.5px] font-semibold text-teal-600 hover:bg-teal-400/10 dark:text-teal-300">Test</button>
                                <button type="button" aria-label={`Remove ${d.name}`} onClick={() => onDelete(d.id)} disabled={busy} className="rounded-lg p-2 text-slate-400 hover:bg-rose-500/10 hover:text-rose-500"><FiTrash2 /></button>
                            </div>
                        </li>
                    ))}
                </ul>
            )}
        </Card>
    );
}
