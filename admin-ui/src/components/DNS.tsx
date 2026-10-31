import React, { useCallback, useEffect, useState } from "react";
import { FiGlobe } from "react-icons/fi";
import { useAuth } from "../contexts/AuthContext";
import DnsGuide from "./dns/DnsGuide";
import DnsNodes from "./dns/DnsNodes";
import DnsRecords from "./dns/DnsRecords";
import DnsSettings from "./dns/DnsSettings";
import DnsZones from "./dns/DnsZones";
import { dnsCall, errorText } from "./dns/dnsApi";
import { Card, PageHeader, Pill, inputCls } from "./ui/controls";

const TABS = [["records", "Records"], ["settings", "Server settings"], ["nodes", "Nodes"], ["guide", "Setup guide"]] as const;
type Tab = typeof TABS[number][0];

export default function DNS() {
    const { auth } = useAuth() as any;
    const [tab, setTab] = useState<Tab>("records");
    const [zones, setZones] = useState<Record<string, any[]>>({});
    const [status, setStatus] = useState<any>();
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string>();

    const [testDomain, setTestDomain] = useState("");
    const [testing, setTesting] = useState(false);
    const [testResult, setTestResult] = useState<any>();

    const load = useCallback(async () => {
        const [z, s] = await Promise.all([dnsCall(auth, "get", "/dns"), dnsCall(auth, "get", "/dns/status")]);
        setZones(z.data); setStatus(s.data);
    }, [auth]);
    useEffect(() => { load(); }, [load]);

    const all = Object.values(zones).flat();
    const records = all.filter(r => r.type !== "base" && r.type !== "zone");
    const domains = all.filter(r => r.type === "zone");

    const run = async (fn: () => Promise<any>, fallback: string): Promise<boolean> => {
        setBusy(true); setError(undefined);
        try { await fn(); await load(); setBusy(false); return true; }
        catch (e) { setError(errorText(e, fallback)); setBusy(false); return false; }
    };

    const toggleServer = () => run(() => dnsCall(auth, "post", `/dns/${status?.enabled ? "stop" : "start"}`, {}), "Could not change the DNS server state");
    const addRecord = (body: any) => run(() => dnsCall(auth, "post", "/dns", body), "Could not save the record");
    const addDomain = (name: string) => run(() => dnsCall(auth, "post", "/dns", { name, type: "zone" }), "Could not add the domain");
    const deleteRecord = async (id: string) => { await run(() => dnsCall(auth, "delete", `/dns/${id}`), "Could not delete the record"); };

    /** domain given: test that one; none: test the whole configuration (server, name servers, forwarders, every domain). */
    const runTest = async (domain?: string) => {
        setTesting(true); setTestResult(undefined); setError(undefined);
        try { setTestResult((await dnsCall(auth, "get", "/dns/test", undefined, domain ? "&domain=" + encodeURIComponent(domain) : "")).data); }
        catch (e) { setError(errorText(e, "Could not run the test")); }
        setTesting(false);
    };

    // one shape for both answers: sections of checks, plus the setup steps of the first domain that needs them
    const sections: { title: string; ok: boolean; checks: any[] }[] = !testResult ? [] : testResult.domains
        ? [{ title: "Server", ok: testResult.checks.every((c: any) => c.ok), checks: testResult.checks },
        ...testResult.domains.map((d: any) => ({ title: d.domain, ok: d.ok, checks: d.checks }))]
        : [{ title: testResult.domain, ok: testResult.ok, checks: testResult.checks }];
    const steps: string[] | undefined = testResult?.instructions ?? (testResult?.domains?.find((d: any) => !d.ok) ?? testResult?.domains?.[0])?.instructions;

    const state = !status ? null : status.running ? <Pill tone="good">Running on :{status.port}</Pill>
        : status.enabled ? <Pill tone="warn">Enabled, not running here</Pill> : <Pill tone="muted">Stopped</Pill>;

    return (
        <div className="mx-auto max-w-5xl p-6 sm:p-8">
            <PageHeader icon={FiGlobe} eyebrow="Platform" title="DNS"
                description="Authoritative DNS server with forwarding. Records, settings and start/stop are replicated to every connected node."
                actions={status && <>
                    {state}
                    <button type="button" onClick={toggleServer} disabled={busy}
                        className={`h-10 rounded-xl px-4 text-[13px] font-semibold text-white disabled:opacity-60 ${status.enabled ? "bg-rose-500 hover:bg-rose-600" : "bg-emerald-500 hover:bg-emerald-600"}`}>
                        {status.enabled ? "Stop DNS server" : "Start DNS server"}
                    </button>
                </>} />

            <div role="tablist" className="mb-5 flex gap-1 overflow-x-auto border-b border-slate-200 dark:border-white/10">
                {TABS.map(([id, label]) => (
                    <button key={id} role="tab" aria-selected={tab === id} type="button" onClick={() => setTab(id)}
                        className={`-mb-px whitespace-nowrap border-b-2 px-4 py-2.5 text-[13.5px] font-medium ${tab === id ? "border-teal-400 text-slate-900 dark:text-white" : "border-transparent text-slate-500 hover:text-slate-800 dark:text-slate-400"}`}>
                        {label}{id === "records" && <span className="ml-1.5 text-[11px] opacity-60">{records.length}</span>}
                    </button>
                ))}
            </div>

            {error && tab !== "records" && <p className="mb-4 text-[12.5px] text-rose-500 dark:text-rose-300">{error}</p>}

            {tab === "records" && <DnsRecords records={records} busy={busy} error={error} setupHint={status && !status.setup ? status.setupHint : undefined} onSetup={() => setTab("settings")} onAdd={addRecord} onDelete={deleteRecord} />}

            {tab === "settings" && (
                <div className="space-y-5 pb-16">
                    <DnsSettings onSaved={load} />
                    <DnsZones domains={domains} busy={busy} setupHint={status && !status.setup ? status.setupHint : undefined}
                        onAdd={addDomain} onDelete={deleteRecord} onTest={d => { setTestDomain(d); runTest(d); }} />
                    <Card title="Test your setup" description="Test everything checks the server, name servers, forwarders, cluster nodes and every domain above. Or test one domain.">
                        <div className="flex flex-wrap gap-2">
                            <button type="button" onClick={() => runTest()} disabled={testing}
                                className="h-10 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-5 text-[13px] font-semibold text-slate-950 disabled:opacity-50">{testing ? "Testing…" : "Test everything"}</button>
                            <input value={testDomain} onChange={e => setTestDomain(e.target.value)} className={`${inputCls} max-w-xs`} placeholder="one domain, e.g. app.example.com" aria-label="Domain to test" />
                            <button type="button" onClick={() => runTest(testDomain.trim())} disabled={testing || !testDomain.trim()}
                                className="h-10 rounded-xl border border-slate-200 px-4 text-[13px] font-semibold text-slate-700 disabled:opacity-50 dark:border-white/10 dark:text-slate-200">Test domain</button>
                        </div>
                        {testResult && (
                            <div className="mt-4 space-y-4">
                                <Pill tone={testResult.ok ? "good" : "bad"}>{testResult.ok ? "Everything is configured" : "Needs attention"}</Pill>
                                {sections.map(sec => (
                                    <div key={sec.title} className="space-y-2">
                                        <div className="flex items-center gap-2 text-[13px] font-semibold text-slate-800 dark:text-slate-100">{sec.title}<Pill tone={sec.ok ? "good" : "bad"}>{sec.ok ? "OK" : "Failing"}</Pill></div>
                                        {sec.checks.map((c: any, i: number) => (
                                            <div key={i} className="flex gap-2 text-[13px]">
                                                <span className={c.ok ? "text-emerald-500" : "text-rose-500"}>{c.ok ? "✓" : "✗"}</span>
                                                <span className="text-slate-700 dark:text-slate-300">{c.name}<span className="block text-[12px] text-slate-400">{c.detail}</span></span>
                                            </div>
                                        ))}
                                    </div>
                                ))}
                            </div>
                        )}
                    </Card>
                    {steps && (
                        <Card title="Next steps" description="For the first domain that needs attention.">
                            <ol className="space-y-1.5 text-[13px] text-slate-600 dark:text-slate-300">
                                {steps.map((t: string, i: number) => <li key={i}>{t}</li>)}
                            </ol>
                            <button type="button" onClick={() => setTab("guide")} className="mt-3 text-[13px] font-semibold text-teal-600 hover:underline dark:text-teal-300">Open the full setup guide</button>
                        </Card>
                    )}
                </div>
            )}
            {tab === "nodes" && <DnsNodes status={status} />}
            {tab === "guide" && <DnsGuide status={status} domains={domains} onOpenSettings={() => setTab("settings")} />}
        </div>
    );
}
