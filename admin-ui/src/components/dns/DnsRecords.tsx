import React, { useMemo, useState } from "react";
import { FiPlus, FiSearch, FiTrash2 } from "react-icons/fi";
import { Card, Field, Pill, inputCls } from "../ui/controls";

export const RECORD_TYPES = ["A", "AAAA", "CNAME", "MX", "TXT", "NS", "SRV", "CAA", "PTR"] as const;
type RType = typeof RECORD_TYPES[number];

const HELP: Record<RType, string> = {
    A: "Points a name to an IPv4 address.",
    AAAA: "Points a name to an IPv6 address.",
    CNAME: "Makes a name an alias of another name.",
    MX: "Where mail for the name is delivered; the lowest priority is tried first.",
    TXT: "Free text: SPF, DKIM, domain verification.",
    NS: "Delegates a name to another name server.",
    SRV: "Locates a service: priority, weight, port and target host.",
    CAA: "Which certificate authorities may issue certificates for the name.",
    PTR: "Reverse lookup: an address name pointing to a host.",
};

const TTLS = [[60, "1 minute"], [300, "5 minutes"], [600, "10 minutes (default)"], [3600, "1 hour"], [14400, "4 hours"], [86400, "1 day"]];

/** Builds the stored value from the per-type fields; the server validates it again. */
function compose(type: RType, f: Record<string, string>): string {
    switch (type) {
        case "MX": return `${f.priority || "10"} ${f.host || ""}`.trim();
        case "SRV": return `${f.priority || "10"} ${f.weight || "5"} ${f.port || ""} ${f.host || ""}`.trim();
        case "CAA": return `${f.flags || "0"} ${f.tag || "issue"} ${f.value || ""}`.trim();
        default: return (f.value || "").trim();
    }
}

function ready(type: RType, f: Record<string, string>): boolean {
    switch (type) {
        case "MX": return !!f.host;
        case "SRV": return !!f.port && !!f.host;
        default: return !!f.value;
    }
}

const VALUE_PLACEHOLDER: Partial<Record<RType, string>> = {
    A: "203.0.113.10", AAAA: "2001:db8::1", CNAME: "target.example.com", NS: "ns1.example.com",
    PTR: "host.example.com", TXT: "v=spf1 include:_spf.example.com ~all",
};

export default function DnsRecords({ records, busy, error, onAdd, onDelete }: {
    records: any[]; busy: boolean; error?: string;
    onAdd: (body: { name: string; type: string; ip: string; ttl: number }) => Promise<boolean>;
    onDelete: (id: string) => Promise<void>;
}) {
    const [type, setType] = useState<RType>("A");
    const [name, setName] = useState("");
    const [ttl, setTtl] = useState(600);
    const [f, setF] = useState<Record<string, string>>({});
    const [query, setQuery] = useState("");
    const [filter, setFilter] = useState<string>("ALL");
    const set = (k: string, v: string) => setF(p => ({ ...p, [k]: v }));

    const counts = useMemo(() => {
        const c: Record<string, number> = {};
        records.forEach(r => { const t = String(r.type).toUpperCase(); c[t] = (c[t] || 0) + 1; });
        return c;
    }, [records]);

    const shown = records.filter(r =>
        (filter === "ALL" || String(r.type).toUpperCase() === filter) &&
        (!query || `${r.name} ${r.ip}`.toLowerCase().includes(query.toLowerCase())));

    const save = async () => {
        const ok = await onAdd({ name, type: type.toLowerCase(), ip: compose(type, f), ttl });
        if (ok) { setName(""); setF({}); }
    };

    const input = (k: string, placeholder: string, extra = "") =>
        <input value={f[k] || ""} onChange={e => set(k, e.target.value)} placeholder={placeholder} className={`${inputCls} ${extra}`} />;

    return (
        <div className="space-y-5">
            <Card title="Add record" description={HELP[type]}>
                <div className="grid gap-4 sm:grid-cols-6">
                    <div className="sm:col-span-1">
                        <Field label="Type" htmlFor="dns-type">
                            <select id="dns-type" value={type} onChange={e => { setType(e.target.value as RType); setF({}); }} className={inputCls}>
                                {RECORD_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                            </select>
                        </Field>
                    </div>
                    <div className="sm:col-span-3">
                        <Field label="Name" hint="The full host name, e.g. www.example.com" htmlFor="dns-name">
                            <input id="dns-name" value={name} onChange={e => setName(e.target.value.trim())} placeholder="www.example.com" className={inputCls} />
                        </Field>
                    </div>
                    <div className="sm:col-span-2">
                        <Field label="TTL" htmlFor="dns-ttl">
                            <select id="dns-ttl" value={ttl} onChange={e => setTtl(+e.target.value)} className={inputCls}>
                                {TTLS.map(([s, l]) => <option key={s} value={s}>{l}</option>)}
                            </select>
                        </Field>
                    </div>

                    {type === "MX" && <>
                        <div className="sm:col-span-1"><Field label="Priority">{input("priority", "10")}</Field></div>
                        <div className="sm:col-span-5"><Field label="Mail server">{input("host", "mail.example.com")}</Field></div>
                    </>}
                    {type === "SRV" && <>
                        <div className="sm:col-span-1"><Field label="Priority">{input("priority", "10")}</Field></div>
                        <div className="sm:col-span-1"><Field label="Weight">{input("weight", "5")}</Field></div>
                        <div className="sm:col-span-1"><Field label="Port">{input("port", "5060")}</Field></div>
                        <div className="sm:col-span-3"><Field label="Target">{input("host", "sip.example.com")}</Field></div>
                    </>}
                    {type === "CAA" && <>
                        <div className="sm:col-span-1"><Field label="Flags">{input("flags", "0")}</Field></div>
                        <div className="sm:col-span-2">
                            <Field label="Tag">
                                <select value={f.tag || "issue"} onChange={e => set("tag", e.target.value)} className={inputCls}>
                                    <option value="issue">issue</option><option value="issuewild">issuewild</option><option value="iodef">iodef</option>
                                </select>
                            </Field>
                        </div>
                        <div className="sm:col-span-3"><Field label="Value">{input("value", "letsencrypt.org")}</Field></div>
                    </>}
                    {!["MX", "SRV", "CAA"].includes(type) &&
                        <div className="sm:col-span-6"><Field label="Value">{input("value", VALUE_PLACEHOLDER[type] || "")}</Field></div>}
                </div>
                {error && <p className="mt-3 text-[12.5px] text-rose-500 dark:text-rose-300">{error}</p>}
                <div className="mt-4 flex justify-end">
                    <button type="button" onClick={save} disabled={busy || !name || !ready(type, f)}
                        className="inline-flex h-10 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-4 text-[13px] font-semibold text-slate-950 shadow-glow hover:brightness-110 disabled:opacity-50">
                        <FiPlus /> Add record
                    </button>
                </div>
            </Card>

            <Card title="Records" description={`${records.length} record${records.length === 1 ? "" : "s"}, replicated to every node.`}
                right={<div className="relative"><FiSearch className="pointer-events-none absolute left-3 top-3 text-slate-400" />
                    <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Search" className={`${inputCls} pl-9`} /></div>}>
                <div className="mb-4 flex flex-wrap gap-1.5">
                    {["ALL", ...RECORD_TYPES.filter(t => counts[t])].map(t => (
                        <button key={t} type="button" onClick={() => setFilter(t)}
                            className={`rounded-full border px-3 py-1 text-[12px] font-semibold ${filter === t ? "border-teal-400 bg-teal-400/15 text-teal-700 dark:text-teal-300" : "border-slate-200 text-slate-500 dark:border-white/10 dark:text-slate-400"}`}>
                            {t}{t !== "ALL" && <span className="ml-1 opacity-60">{counts[t]}</span>}
                        </button>
                    ))}
                </div>
                {shown.length === 0 ? (
                    <p className="py-8 text-center text-[13px] text-slate-400">{records.length ? "No records match." : "No records yet. Add one above."}</p>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="min-w-full text-left text-[13px]">
                            <thead>
                                <tr className="text-[11px] uppercase tracking-wider text-slate-400">
                                    <th className="py-2 pr-4 font-medium">Type</th><th className="py-2 pr-4 font-medium">Name</th>
                                    <th className="py-2 pr-4 font-medium">Value</th><th className="py-2 pr-4 font-medium">TTL</th><th />
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 dark:divide-white/5">
                                {shown.map(r => (
                                    <tr key={r.id} className="text-slate-700 dark:text-slate-300">
                                        <td className="py-2.5 pr-4"><Pill tone="info">{String(r.type).toUpperCase()}</Pill></td>
                                        <td className="py-2.5 pr-4 font-medium text-slate-900 dark:text-white">{r.name}</td>
                                        <td className="max-w-[28rem] truncate py-2.5 pr-4 font-mono text-[12px]" title={r.ip}>{r.ip}</td>
                                        <td className="py-2.5 pr-4 font-mono text-[12px] text-slate-500">{r.ttl}s</td>
                                        <td className="py-2.5 text-right">
                                            <button type="button" aria-label={`Delete ${r.name}`} onClick={() => onDelete(r.id)} disabled={busy}
                                                className="rounded-lg p-2 text-slate-400 hover:bg-rose-500/10 hover:text-rose-500"><FiTrash2 /></button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </Card>
        </div>
    );
}
