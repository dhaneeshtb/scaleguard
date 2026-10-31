import React, { useCallback, useEffect, useState } from "react";
import { useAuth } from "../../contexts/AuthContext";
import { Card, Field, SaveBar, ToggleRow, inputCls } from "../ui/controls";
import { dnsCall, errorText } from "./dnsApi";

type Values = Record<string, string>;

export default function DnsSettings({ onSaved }: { onSaved: () => void }) {
    const { auth } = useAuth() as any;
    const [saved, setSaved] = useState<Values>();
    const [draft, setDraft] = useState<Values>({});
    const [defaults, setDefaults] = useState<Values>({});
    const [detected, setDetected] = useState<string[]>([]);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string>();

    const load = useCallback(async () => {
        const r = await dnsCall(auth, "get", "/dns/settings");
        setSaved(r.data.settings); setDraft(r.data.settings); setDefaults(r.data.defaults); setDetected(r.data.detectedIps || []);
    }, [auth]);
    useEffect(() => { load(); }, [load]);

    const dirty = !!saved && Object.keys(draft).some(k => draft[k] !== saved[k]);
    const set = (k: string, v: string) => setDraft(p => ({ ...p, [k]: v }));

    const save = async () => {
        setSaving(true); setError(undefined);
        try {
            const r = await dnsCall(auth, "post", "/dns/settings", draft);
            setSaved(r.data.settings); setDraft(r.data.settings); setDetected(r.data.detectedIps || []);
            onSaved();
        } catch (e) {
            setError(errorText(e, "Could not save the settings"));
        }
        setSaving(false);
    };

    if (!saved) return <p className="py-8 text-center text-[13px] text-slate-400">Loading…</p>;

    const text = (k: string, label: string, hint: string, type = "text") => (
        <Field label={label} hint={hint} htmlFor={k}>
            <input id={k} type={type} value={draft[k] ?? ""} onChange={e => set(k, e.target.value)} placeholder={defaults[k]} className={inputCls} />
        </Field>
    );

    return (
        <div className="space-y-5">
            <p className="text-[12.5px] text-slate-500 dark:text-slate-400">
                These settings are stored once and replicated: every connected node applies them and restarts its DNS server briefly. Leave a field blank to use its default.
            </p>
            {error && <p className="text-[12.5px] text-rose-500 dark:text-rose-300">{error}</p>}

            <Card title="Server" description="Where this DNS server listens and how it presents itself.">
                <div className="grid gap-4 sm:grid-cols-2">
                    {text("dnsPort", "Port", "UDP and TCP. 53 is the standard; below 1024 needs privileges.", "number")}
                    <Field label="Public IPs" hint={detected.length
                        ? "Detected automatically. In a cluster this lists every running node; nodes that join or stop are added or removed."
                        : "No public IP found on this host's interfaces."}>
                        <div className="flex min-h-10 flex-wrap items-center gap-1.5 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 dark:border-white/10 dark:bg-white/[0.03]">
                            {detected.length ? detected.map(ip => <span key={ip} className="rounded-md bg-slate-200/70 px-2 py-0.5 font-mono text-[12px] text-slate-700 dark:bg-white/10 dark:text-slate-200">{ip}</span>)
                                : <span className="text-[13px] text-slate-400">None detected</span>}
                        </div>
                    </Field>
                    {text("dnsNameServers", "Name servers", "Host names to give your registrar, comma separated, e.g. ns1.example.com, ns2.example.com. Each resolves to this server's public IPs.")}
                    {text("dnsBlockedDomains", "Blocked domains", "Suffixes answered with REFUSED, comma separated.")}
                </div>
            </Card>

            <Card title="Load balancing" description="How answers with several servers are spread.">
                <ToggleRow id="dns-lb" label="Rotate answers (round-robin)"
                    description="Each lookup lists the addresses in a different order, so clients spread across all servers. In a cluster, base records hold every running node. Use a low TTL (60 to 300 s) on records so a stopped node drops out quickly."
                    checked={(draft.dnsLoadBalance || "true") !== "false"} onChange={v => set("dnsLoadBalance", String(v))} />
            </Card>

            <Card title="Forwarding and recursion" description="Answers for names outside your zones.">
                <div className="space-y-4">
                    <ToggleRow id="dns-recursion" label="Resolve other names for allowed clients"
                        description="Off: the server only answers for your own zones."
                        checked={(draft.dnsRecursion || "true") !== "false"} onChange={v => set("dnsRecursion", String(v))} />
                    <div className="grid gap-4 sm:grid-cols-2">
                        {text("dnsProxy", "Forwarders", "Upstream resolvers, comma separated: 1.1.1.1, 8.8.8.8:53")}
                        {text("dnsUpstreamTimeoutMs", "Forwarder timeout (ms)", "Per attempt.", "number")}
                    </div>
                    {text("dnsRecursionAllow", "Allowed clients", "IPs or CIDR ranges that may use recursion. Everyone else gets REFUSED, which prevents DNS amplification.")}
                </div>
            </Card>

            <Card title="Cache and limits" description="Protects the server and keeps answers fast.">
                <div className="grid gap-4 sm:grid-cols-2">
                    {text("dnsCacheSize", "Cache size (answers)", "Forwarded answers kept in memory.", "number")}
                    {text("dnsTcpMaxConnections", "Max TCP connections", "Concurrent TCP clients.", "number")}
                    {text("dnsRateLimit", "Rate limit (queries/s per client)", "0 turns it off.", "number")}
                    {text("dnsRateBurst", "Rate burst", "Short bursts allowed above the limit.", "number")}
                </div>
            </Card>

            <SaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => setDraft(saved)} label="Save and apply to all nodes" />
        </div>
    );
}
