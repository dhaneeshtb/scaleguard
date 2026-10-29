import { useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import { useToast } from "@chakra-ui/react";
import { FiActivity, FiAlertTriangle, FiHeart, FiRotateCcw, FiServer, FiSliders } from "react-icons/fi";
import { useAuth } from "../../contexts/AuthContext";
import { Card, Field, PageHeader, Pill, SaveBar, Stat, ToggleRow, ago, fmt, inputCls } from "../../components/ui/controls";

interface Settings {
    enabled: boolean;
    consecutiveFailures: number;
    baseEjectionSeconds: number;
    maxEjectionSeconds: number;
    failureStatuses: string;
    activeIntervalSeconds?: number;
}
interface Host {
    id: string; groupId: string; address: string; healthUrl: string | null; targets: string[];
    state: "healthy" | "probing" | "ejected" | "down"; activeCheck: "none" | "passing" | "failing";
    ejectedForSeconds: number; ejections: number; totalEjections: number; consecutiveFailures: number;
    requests: number; failures: number; lastFailure: string | null; lastFailureAt: number | null; activeConnections: number;
}
interface View { settings: Settings; summary: { hosts: number; ejected: number; down: number }; hosts: Host[] }

const editable = (s: Settings) => ({
    enabled: s.enabled, consecutiveFailures: s.consecutiveFailures, baseEjectionSeconds: s.baseEjectionSeconds,
    maxEjectionSeconds: s.maxEjectionSeconds, failureStatuses: s.failureStatuses,
});

const STATE: Record<Host["state"], { tone: "good" | "bad" | "warn" | "info"; label: string; help: string }> = {
    healthy: { tone: "good", label: "Healthy", help: "In rotation" },
    probing: { tone: "info", label: "Recovering", help: "Back in rotation after an ejection; one more failure ejects it again for longer" },
    ejected: { tone: "warn", label: "Ejected", help: "Out of rotation after failing real requests" },
    down: { tone: "bad", label: "Down", help: "Failing its health-check URL" },
};

export default function BackendHealth() {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const headers = useMemo(() => ({ Authorization: auth.data.token }), [auth]);
    const host = auth.data.host;

    const [view, setView] = useState<View | null>(null);
    const [draft, setDraft] = useState<ReturnType<typeof editable> | null>(null);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);

    const load = useCallback(async (resetDraft: boolean) => {
        try {
            const r = await axios.get(`${host}/backends?scaleguard=true`, { headers });
            setView(r.data);
            setLoadError(null);
            if (resetDraft) setDraft(editable(r.data.settings));
        } catch (e: any) {
            setLoadError(e?.response?.status === 404 ? "This ScaleGuard server doesn't support backend health settings yet. Update the server to the latest build." : "Couldn't load backend health.");
        }
    }, [host, headers]);

    useEffect(() => { load(true); }, [load]);
    useEffect(() => {
        const t = setInterval(() => load(false), 3000);
        return () => clearInterval(t);
    }, [load]);

    const dirty = !!draft && !!view && JSON.stringify(draft) !== JSON.stringify(editable(view.settings));

    const save = async () => {
        if (!draft) return;
        setSaving(true); setError(null);
        try {
            const r = await axios.post(`${host}/backends/settings?scaleguard=true`, draft, { headers });
            setView(r.data); setDraft(editable(r.data.settings));
            toast({ title: "Health settings saved", description: "Applied immediately.", status: "success", duration: 2500, position: "top-right" });
        } catch (e: any) {
            setError(e?.response?.data?.message || "Couldn't save the settings.");
        } finally { setSaving(false); }
    };

    const reset = async (h: Host) => {
        try {
            const r = await axios.post(`${host}/backends/${encodeURIComponent(h.id)}/reset?scaleguard=true`, {}, { headers });
            setView(r.data);
            toast({ title: `${h.address} is back in rotation`, status: "success", duration: 2200, position: "top-right" });
        } catch (e: any) {
            toast({ title: "Couldn't restore the host", description: e?.response?.data?.message, status: "error", duration: 4000, position: "top-right" });
        }
    };

    if (loadError) return <div className="sg-card mx-auto max-w-xl p-8 text-center"><FiAlertTriangle className="mx-auto mb-3 text-2xl text-amber-500" /><p className="text-[14px] text-slate-700 dark:text-slate-200">{loadError}</p></div>;
    if (!view || !draft) return <div className="space-y-4">{[0, 1, 2].map(i => <div key={i} className="sg-card h-40 animate-pulse" />)}</div>;

    const maxBackoff = Math.min(draft.maxEjectionSeconds, draft.baseEjectionSeconds * 8);

    return (
        <div className="mx-auto max-w-6xl pb-24">
            <PageHeader icon={FiHeart} eyebrow="Traffic" title="Backend health"
                description="ScaleGuard polls each backend's health URL and also watches real requests. A backend that keeps failing is taken out of rotation within seconds and tried again later, so clients don't wait for the next poll." />

            <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
                <div className="min-w-0 space-y-5">
                    <Card icon={FiServer} title="Backends" description="Live state · refreshes every 3 s">
                        {view.hosts.length === 0 ? (
                            <p className="text-[13px] text-slate-500">No backend hosts configured yet.</p>
                        ) : (
                            <ul className="divide-y divide-slate-100 dark:divide-white/[0.05]">
                                {view.hosts.map(h => {
                                    const st = STATE[h.state];
                                    const failRate = h.requests ? (100 * h.failures / h.requests) : 0;
                                    return (
                                        <li key={h.id} className="py-3">
                                            <div className="flex flex-wrap items-center justify-between gap-3">
                                                <div className="min-w-0">
                                                    <p className="truncate font-mono text-[13px] font-semibold text-slate-800 dark:text-slate-100">{h.address}</p>
                                                    <p className="truncate text-[11.5px] text-slate-500 dark:text-slate-400">
                                                        {h.targets.length ? h.targets.join(", ") : h.groupId}
                                                        {" · "}health URL {h.activeCheck === "none" ? "not set" : h.activeCheck}
                                                    </p>
                                                </div>
                                                <div className="flex items-center gap-2">
                                                    <span title={st.help}><Pill tone={st.tone}>{st.label}{h.state === "ejected" ? ` · ${h.ejectedForSeconds}s` : ""}</Pill></span>
                                                    {(h.state === "ejected" || h.state === "probing") && (
                                                        <button onClick={() => reset(h)} className="inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-[12.5px] font-semibold text-teal-600 hover:bg-teal-500/10 dark:text-teal-300">
                                                            <FiRotateCcw aria-hidden /> Restore now
                                                        </button>
                                                    )}
                                                </div>
                                            </div>
                                            <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[11.5px] text-slate-500 dark:text-slate-400">
                                                <span>{fmt(h.requests)} requests</span>
                                                <span className={h.failures ? "text-rose-600 dark:text-rose-300" : ""}>{fmt(h.failures)} failed{h.requests ? ` (${failRate.toFixed(failRate < 1 ? 2 : 1)}%)` : ""}</span>
                                                <span>{fmt(h.totalEjections)} ejection{h.totalEjections === 1 ? "" : "s"}</span>
                                                <span>{fmt(h.activeConnections)} open connection{h.activeConnections === 1 ? "" : "s"}</span>
                                                {h.lastFailure && <span className="min-w-0 truncate">Last failure {h.lastFailureAt ? ago(h.lastFailureAt) : ""}: <span className="font-mono">{h.lastFailure}</span></span>}
                                            </div>
                                        </li>
                                    );
                                })}
                            </ul>
                        )}
                    </Card>

                    <Card icon={FiSliders} title="Passive health checks" description="Decide when a backend is taken out of rotation based on the requests it serves.">
                        <div className="space-y-4">
                            <ToggleRow id="ph-enabled" label="Eject backends that fail requests"
                                description="Failures are connection errors, resets, the status codes below and gRPC UNAVAILABLE. If every backend of a service is ejected, ScaleGuard keeps using them rather than failing all traffic."
                                checked={draft.enabled} onChange={v => setDraft({ ...draft, enabled: v })} />
                            <div className={`grid gap-4 sm:grid-cols-2 ${draft.enabled ? "" : "pointer-events-none opacity-50"}`}>
                                <Field label="Failures in a row before ejecting" htmlFor="ph-fails" hint="Lower reacts faster; higher tolerates the odd error.">
                                    <input id="ph-fails" inputMode="numeric" className={`${inputCls} font-mono`} value={draft.consecutiveFailures}
                                        onChange={e => setDraft({ ...draft, consecutiveFailures: parseInt(e.target.value.replace(/\D/g, "") || "0", 10) })} />
                                </Field>
                                <Field label="Status codes that count as failures" htmlFor="ph-status" hint="Comma-separated, e.g. 502,503,504.">
                                    <input id="ph-status" className={`${inputCls} font-mono`} value={draft.failureStatuses}
                                        onChange={e => setDraft({ ...draft, failureStatuses: e.target.value })} />
                                </Field>
                                <Field label="First ejection lasts (seconds)" htmlFor="ph-base" hint={<>Doubles each time it fails again: {draft.baseEjectionSeconds}s, {Math.min(draft.maxEjectionSeconds, draft.baseEjectionSeconds * 2)}s, {Math.min(draft.maxEjectionSeconds, draft.baseEjectionSeconds * 4)}s, {maxBackoff}s…</>}>
                                    <input id="ph-base" inputMode="numeric" className={`${inputCls} font-mono`} value={draft.baseEjectionSeconds}
                                        onChange={e => setDraft({ ...draft, baseEjectionSeconds: parseInt(e.target.value.replace(/\D/g, "") || "0", 10) })} />
                                </Field>
                                <Field label="Longest ejection (seconds)" htmlFor="ph-max" error={error || undefined}>
                                    <input id="ph-max" inputMode="numeric" className={`${inputCls} font-mono`} value={draft.maxEjectionSeconds}
                                        onChange={e => setDraft({ ...draft, maxEjectionSeconds: parseInt(e.target.value.replace(/\D/g, "") || "0", 10) })} />
                                </Field>
                            </div>
                        </div>
                    </Card>
                </div>

                <aside className="space-y-4 lg:sticky lg:top-24">
                    <Card icon={FiActivity} title="Right now">
                        <div className="grid grid-cols-3 gap-2">
                            <Stat label="Backends" value={fmt(view.summary.hosts)} />
                            <Stat label="Ejected" value={fmt(view.summary.ejected)} tone={view.summary.ejected ? "warn" : undefined} />
                            <Stat label="Down" value={fmt(view.summary.down)} tone={view.summary.down ? "bad" : undefined} />
                        </div>
                    </Card>
                    <Card title="How it works">
                        <ul className="list-disc space-y-1.5 pl-4 text-[12.5px] text-slate-600 dark:text-slate-300">
                            <li>Health URLs are polled every {view.settings.activeIntervalSeconds ?? 10}s, all backends in parallel.</li>
                            <li>Between polls, real requests are watched. A backend that fails {draft.consecutiveFailures} in a row is ejected.</li>
                            <li>If ScaleGuard can't connect to a backend, the request is retried once on another backend before the client sees an error.</li>
                            <li>When an ejection ends, the backend gets real traffic again. A success restores it fully.</li>
                        </ul>
                    </Card>
                </aside>
            </div>

            <SaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => { setDraft(editable(view.settings)); setError(null); }} />
        </div>
    );
}
