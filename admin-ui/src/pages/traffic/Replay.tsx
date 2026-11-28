import { useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import { useToast } from "@chakra-ui/react";
import { FiCircle, FiDatabase, FiPlay, FiRepeat, FiSquare, FiTrash2, FiX } from "react-icons/fi";
import { useAuth } from "../../contexts/AuthContext";
import { Card, Field, PageHeader, Pill, Segmented, Stat, ago, fmt, inputCls } from "../../components/ui/controls";

interface Recording {
    id: string; name: string; route_id: string | null; pattern: string | null; methods: string;
    max_body: number; max_requests: number; started_ms: number; stopped_ms: number | null; captures: number;
}
interface ReplayRun {
    id: string; recording_id: string; target: string; started_ms: number; finished_ms: number | null; state: string;
    total: number; matched: number; mismatched: number; errors: number;
}
interface View { recorder: { active: number; queuedBytes: number; dropped: number }; recordings: Recording[]; replays: ReplayRun[] }
interface RouteRow { id: string; name?: string; host?: string; port?: string }
type Body = string | { base64: string } | null;
interface Result {
    seq: number; method: string; uri: string; expected_status: number; actual_status: number;
    result: "match" | "mismatch" | "error"; reason: string | null; actual_body: Body; duration_ms: number;
}
interface Capture { seq: number; method: string; uri: string; status: number; req_body: Body; resp_body: Body; duration_ms: number; ts_ms: number }

const btn = "inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 text-[12px] font-semibold text-slate-700 hover:border-teal-400/50 disabled:opacity-50 dark:border-white/10 dark:text-slate-200";
const primary = "inline-flex h-10 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-4 text-[13px] font-semibold text-slate-950 shadow-glow hover:brightness-110 disabled:opacity-60";
const textOf = (b: Body) => (b == null ? "" : typeof b === "string" ? b : `(binary, ${Math.round(b.base64.length * 0.75)} bytes)`);
const errMsg = (e: any, d: string) => e?.response?.data?.message || d;

function BodyBlock({ label, body }: { label: string; body: Body }) {
    const t = textOf(body);
    return (
        <div className="min-w-0">
            <p className="sg-eyebrow mb-1">{label}</p>
            <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-100 p-2.5 font-mono text-[11.5px] text-slate-700 dark:bg-white/[0.04] dark:text-slate-300">{t || "(empty)"}</pre>
        </div>
    );
}

function ResultRow({ r }: { r: Result }) {
    const [open, setOpen] = useState(false);
    const tone = r.result === "match" ? "good" : r.result === "mismatch" ? "warn" : "bad";
    return (
        <li className="border-b border-slate-100 py-2 last:border-0 dark:border-white/[0.05]">
            <button type="button" onClick={() => setOpen(o => !o)} className="flex w-full items-center gap-3 text-left">
                <Pill tone={tone}>{r.result}</Pill>
                <span className="w-14 shrink-0 font-mono text-[11.5px] font-semibold text-slate-500">{r.method}</span>
                <span className="min-w-0 flex-1 truncate font-mono text-[12px] text-slate-800 dark:text-slate-200">{r.uri}</span>
                <span className="shrink-0 font-mono text-[12px] tabular-nums text-slate-500">{r.expected_status} → {r.actual_status || "—"}</span>
            </button>
            {(r.reason || open) && <p className="ml-[4.5rem] mt-1 text-[12px] text-slate-600 dark:text-slate-400">{r.reason}</p>}
            {open && r.actual_body != null && <div className="ml-[4.5rem] mt-2"><BodyBlock label="New server's response" body={r.actual_body} /></div>}
        </li>
    );
}

function CaptureRow({ c }: { c: Capture }) {
    const [open, setOpen] = useState(false);
    return (
        <li className="border-b border-slate-100 py-2 last:border-0 dark:border-white/[0.05]">
            <button type="button" onClick={() => setOpen(o => !o)} className="flex w-full items-center gap-3 text-left">
                <span className="w-10 shrink-0 font-mono text-[11.5px] tabular-nums text-slate-400">#{c.seq}</span>
                <span className="w-14 shrink-0 font-mono text-[11.5px] font-semibold text-slate-500">{c.method}</span>
                <span className="min-w-0 flex-1 truncate font-mono text-[12px] text-slate-800 dark:text-slate-200">{c.uri}</span>
                <span className="shrink-0 font-mono text-[12px] tabular-nums text-slate-500">{c.status} · {Math.round(c.duration_ms)} ms</span>
            </button>
            {open && (
                <div className="mt-2 grid gap-3 md:grid-cols-2">
                    <BodyBlock label="Request body" body={c.req_body} />
                    <BodyBlock label="Recorded response" body={c.resp_body} />
                </div>
            )}
        </li>
    );
}

export default function Replay() {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const headers = useMemo(() => ({ Authorization: auth.data.token }), [auth]);
    const host = auth.data.host;
    const api = useCallback((p: string, q = "") => `${host}/traffic${p}?scaleguard=true${q}`, [host]);

    const [view, setView] = useState<View | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [routes, setRoutes] = useState<RouteRow[]>([]);
    const [rec, setRec] = useState({ name: "", routeId: "", pattern: "", methods: "", maxBodyKb: 1024, maxRequests: 100000 });
    const [replayFor, setReplayFor] = useState<Recording | null>(null);
    const [rp, setRp] = useState({ target: "", methods: "GET", ignoreFields: "", setHeaders: "", concurrency: 4 });
    const [busy, setBusy] = useState(false);
    const [detail, setDetail] = useState<{ kind: "replay" | "captures"; id: string } | null>(null);
    const [filter, setFilter] = useState<"mismatch" | "error" | "match" | "all">("mismatch");
    const [results, setResults] = useState<Result[]>([]);
    const [captures, setCaptures] = useState<Capture[]>([]);

    const load = useCallback(async () => {
        try {
            const r = await axios.get(api(""), { headers });
            setView(r.data);
            setLoadError(null);
        } catch (e: any) {
            setLoadError(e?.response?.status === 404 ? "This ScaleGuard server doesn't support traffic recording yet. Update the server to the latest build." : errMsg(e, "Couldn't load recordings."));
        }
    }, [api, headers]);

    useEffect(() => { load(); const t = setInterval(load, 2000); return () => clearInterval(t); }, [load]);
    useEffect(() => {
        axios.get(`${host}/config/sourcesystems?scaleguard=true`, { headers })
            .then(r => setRoutes(Array.isArray(r.data?.sourcesystems) ? r.data.sourcesystems : []))
            .catch(() => setRoutes([]));
    }, [host, headers]);

    const routeName = (id: string | null) => {
        if (!id) return "All routes";
        const r = routes.find(x => x.id === id);
        return r ? r.name || `${r.host}:${r.port}` : id;
    };

    // detail panel: replay results (refreshed while the run is going) or a recording's captures
    const running = detail?.kind === "replay" && view?.replays.find(x => x.id === detail.id)?.state === "running";
    useEffect(() => {
        if (!detail) return;
        let cancelled = false;
        const fetchDetail = async () => {
            try {
                if (detail.kind === "replay") {
                    const r = await axios.get(api(`/replays/${detail.id}`, `&limit=500${filter === "all" ? "" : `&result=${filter}`}`), { headers });
                    if (!cancelled) setResults(r.data.results || []);
                } else {
                    const r = await axios.get(api(`/recordings/${detail.id}/captures`, "&limit=200"), { headers });
                    if (!cancelled) setCaptures(r.data || []);
                }
            } catch { /* the overview poll shows connectivity problems */ }
        };
        fetchDetail();
        const t = running ? setInterval(fetchDetail, 2000) : undefined;
        return () => { cancelled = true; if (t) clearInterval(t); };
    }, [detail, filter, running, api, headers]);

    const act = async (fn: () => Promise<any>, ok: string) => {
        setBusy(true);
        try {
            await fn();
            toast({ title: ok, status: "success", duration: 2500, position: "top-right" });
            await load();
        } catch (e: any) {
            toast({ title: errMsg(e, "That didn't work."), status: "error", duration: 5000, position: "top-right" });
        } finally { setBusy(false); }
    };

    const startRecording = () => act(() => axios.post(api("/recordings"), {
        name: rec.name.trim(), routeId: rec.routeId || undefined, pattern: rec.pattern.trim() || undefined,
        methods: rec.methods.trim() || undefined, maxBodyBytes: Math.round(rec.maxBodyKb * 1024), maxRequests: rec.maxRequests,
    }, { headers }).then(() => setRec(r => ({ ...r, name: "" }))), "Recording started");

    const startReplay = () => {
        if (!replayFor) return;
        const set: Record<string, string> = {};
        for (const line of rp.setHeaders.split("\n")) {
            const i = line.indexOf(":");
            if (i > 0) set[line.slice(0, i).trim()] = line.slice(i + 1).trim();
        }
        if (!rp.methods.trim() && !window.confirm("No method filter: POST, PUT and DELETE requests will be sent to the target too. Continue?")) return;
        act(async () => {
            const r = await axios.post(api("/replays"), {
                recordingId: replayFor.id, target: rp.target.trim(), concurrency: rp.concurrency, methods: rp.methods.trim() || undefined,
                ignoreFields: rp.ignoreFields.split(",").map(s => s.trim()).filter(Boolean), setHeaders: set,
            }, { headers });
            setReplayFor(null);
            setFilter("all");
            setDetail({ kind: "replay", id: r.data.id });
        }, "Replay started");
    };

    const recName = (id: string) => view?.recordings.find(r => r.id === id)?.name || id;
    const captured = view?.recordings.reduce((n, r) => n + (r.captures || 0), 0) ?? 0;

    return (
        <div className="mx-auto max-w-6xl">
            <PageHeader icon={FiRepeat} eyebrow="Traffic" title="Record & replay"
                description="Record the real API calls a route receives, with the backend's responses, then replay them against a new system and see every response that differs. Built for migrations." />

            {loadError && <Card className="mb-5"><p className="text-[13px] text-rose-600 dark:text-rose-300">{loadError}</p></Card>}

            <div className="mb-5 grid grid-cols-2 gap-3 md:grid-cols-4">
                <Stat label="Recording now" value={fmt(view?.recorder.active ?? 0)} tone={view?.recorder.active ? "good" : undefined} />
                <Stat label="Recordings" value={fmt(view?.recordings.length ?? 0)} />
                <Stat label="Captured requests" value={fmt(captured)} />
                <Stat label="Dropped" value={fmt(view?.recorder.dropped ?? 0)} tone={view?.recorder.dropped ? "warn" : undefined} />
            </div>

            <Card title="New recording" icon={FiCircle} description="Matching requests are stored in the analytics database with their full bodies until you delete the recording." className="mb-5">
                <div className="grid gap-4 md:grid-cols-3">
                    <Field label="Name" htmlFor="rec-name"><input id="rec-name" className={inputCls} placeholder="orders api before migration" value={rec.name} onChange={e => setRec({ ...rec, name: e.target.value })} /></Field>
                    <Field label="Route" htmlFor="rec-route">
                        <select id="rec-route" className={inputCls} value={rec.routeId} onChange={e => setRec({ ...rec, routeId: e.target.value })}>
                            <option value="">All routes</option>
                            {routes.map(r => <option key={r.id} value={r.id}>{r.name || `${r.host}:${r.port}`}</option>)}
                        </select>
                    </Field>
                    <Field label="Path pattern" htmlFor="rec-pattern" hint="Regular expression, e.g. ^/api/. Empty records every path.">
                        <input id="rec-pattern" className={`${inputCls} font-mono`} placeholder="^/api/" value={rec.pattern} onChange={e => setRec({ ...rec, pattern: e.target.value })} />
                    </Field>
                    <Field label="Methods" htmlFor="rec-methods" hint="Comma separated. Empty records all methods.">
                        <input id="rec-methods" className={inputCls} placeholder="GET,POST" value={rec.methods} onChange={e => setRec({ ...rec, methods: e.target.value })} />
                    </Field>
                    <Field label="Max body size (KB)" htmlFor="rec-body" hint="Larger bodies are cut; up to 16384 KB.">
                        <input id="rec-body" type="number" min={0} max={16384} className={inputCls} value={rec.maxBodyKb} onChange={e => setRec({ ...rec, maxBodyKb: Number(e.target.value) })} />
                    </Field>
                    <Field label="Stop after (requests)" htmlFor="rec-max" hint="0 = no limit.">
                        <input id="rec-max" type="number" min={0} className={inputCls} value={rec.maxRequests} onChange={e => setRec({ ...rec, maxRequests: Number(e.target.value) })} />
                    </Field>
                </div>
                <div className="mt-5 flex justify-end">
                    <button type="button" className={primary} disabled={busy || !rec.name.trim()} onClick={startRecording}><FiCircle /> Start recording</button>
                </div>
            </Card>

            <Card title="Recordings" icon={FiDatabase} className="mb-5">
                {!view?.recordings.length ? <p className="text-[13px] text-slate-500">No recordings yet.</p> : (
                    <div className="overflow-x-auto">
                        <table className="w-full min-w-[760px] text-[12.5px]">
                            <thead>
                                <tr className="text-left text-[11.5px] text-slate-500 dark:text-slate-400">
                                    {["Name", "Route / pattern", "Captured", "Started", "Status", ""].map(h => <th key={h} className="px-2 pb-2 font-medium">{h}</th>)}
                                </tr>
                            </thead>
                            <tbody>
                                {view.recordings.map(r => (
                                    <tr key={r.id} className="border-t border-slate-100 dark:border-white/[0.05]">
                                        <td className="px-2 py-2.5 font-semibold text-slate-800 dark:text-slate-100">{r.name}</td>
                                        <td className="px-2 py-2.5 text-slate-600 dark:text-slate-400">{routeName(r.route_id)}{r.pattern && <span className="ml-1.5 font-mono text-[11.5px]">{r.pattern}</span>}{r.methods && <span className="ml-1.5 text-[11.5px]">({r.methods})</span>}</td>
                                        <td className="px-2 py-2.5 tabular-nums">{fmt(r.captures)}</td>
                                        <td className="px-2 py-2.5 text-slate-500">{ago(r.started_ms)}</td>
                                        <td className="px-2 py-2.5">{r.stopped_ms ? <Pill tone="muted">Stopped</Pill> : <Pill tone="good">● Recording</Pill>}</td>
                                        <td className="px-2 py-2.5">
                                            <div className="flex justify-end gap-1.5">
                                                {!r.stopped_ms && <button type="button" className={btn} disabled={busy} onClick={() => act(() => axios.post(api(`/recordings/${r.id}/stop`), {}, { headers }), "Recording stopped")}><FiSquare /> Stop</button>}
                                                <button type="button" className={btn} disabled={!r.captures} onClick={() => setDetail({ kind: "captures", id: r.id })}>View</button>
                                                <button type="button" className={btn} disabled={busy || !r.captures} onClick={() => setReplayFor(r)}><FiPlay /> Replay</button>
                                                <button type="button" aria-label={`Delete ${r.name}`} className={btn} disabled={busy} onClick={() => {
                                                    if (window.confirm(`Delete "${r.name}" with its ${fmt(r.captures)} captured requests and its replays? This can't be undone.`)) {
                                                        if (detail?.id === r.id) setDetail(null);
                                                        act(() => axios.delete(api(`/recordings/${r.id}`), { headers }), "Recording deleted");
                                                    }
                                                }}><FiTrash2 /></button>
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </Card>

            {replayFor && (
                <Card title={`Replay "${replayFor.name}"`} icon={FiPlay} className="mb-5"
                    description={`Sends the ${fmt(replayFor.captures)} recorded requests to the new server, in order, and compares each response with the recorded one.`}
                    right={<button type="button" aria-label="Close" className={btn} onClick={() => setReplayFor(null)}><FiX /></button>}>
                    <div className="grid gap-4 md:grid-cols-2">
                        <Field label="New server (target)" htmlFor="rp-target" hint="Base URL; each request keeps its own path and query.">
                            <input id="rp-target" className={`${inputCls} font-mono`} placeholder="http://new-server:8080" value={rp.target} onChange={e => setRp({ ...rp, target: e.target.value })} />
                        </Field>
                        <Field label="Methods to replay" htmlFor="rp-methods" hint="GET only is safe. Leave empty to also send POST/PUT/DELETE — only against a test copy.">
                            <input id="rp-methods" className={inputCls} placeholder="GET" value={rp.methods} onChange={e => setRp({ ...rp, methods: e.target.value })} />
                        </Field>
                        <Field label="Ignore JSON fields" htmlFor="rp-ignore" hint="Comma separated, at any depth: values expected to differ, like ids and timestamps.">
                            <input id="rp-ignore" className={`${inputCls} font-mono`} placeholder="id, createdAt, updatedAt" value={rp.ignoreFields} onChange={e => setRp({ ...rp, ignoreFields: e.target.value })} />
                        </Field>
                        <Field label="Parallel requests" htmlFor="rp-conc" hint="1 to 64.">
                            <input id="rp-conc" type="number" min={1} max={64} className={inputCls} value={rp.concurrency} onChange={e => setRp({ ...rp, concurrency: Number(e.target.value) })} />
                        </Field>
                        <div className="md:col-span-2">
                            <Field label="Replace headers" htmlFor="rp-headers" hint="One per line, Name: value — e.g. credentials for the new system.">
                                <textarea id="rp-headers" rows={2} className={`${inputCls} h-auto py-2 font-mono`} placeholder="Authorization: Bearer new-token" value={rp.setHeaders} onChange={e => setRp({ ...rp, setHeaders: e.target.value })} />
                            </Field>
                        </div>
                    </div>
                    <div className="mt-5 flex justify-end">
                        <button type="button" className={primary} disabled={busy || !rp.target.trim()} onClick={startReplay}><FiPlay /> Start replay</button>
                    </div>
                </Card>
            )}

            <Card title="Replays" icon={FiRepeat} className="mb-5">
                {!view?.replays.length ? <p className="text-[13px] text-slate-500">No replays yet. Press Replay on a recording.</p> : (
                    <ul className="space-y-2">
                        {view.replays.map(x => {
                            const done = x.matched + x.mismatched + x.errors;
                            const pct = (n: number) => (done ? (n / done) * 100 : 0);
                            const selected = detail?.kind === "replay" && detail.id === x.id;
                            return (
                                <li key={x.id}>
                                    <button type="button" onClick={() => { setFilter(x.mismatched || x.errors ? "mismatch" : "all"); setDetail({ kind: "replay", id: x.id }); }}
                                        className={`w-full rounded-xl border p-3 text-left transition-colors ${selected ? "border-teal-400/60 bg-teal-400/[0.04]" : "border-slate-200 hover:border-teal-400/40 dark:border-white/10"}`}>
                                        <div className="flex flex-wrap items-center gap-2">
                                            <span className="font-semibold text-slate-800 dark:text-slate-100">{recName(x.recording_id)}</span>
                                            <span className="text-slate-400">→</span>
                                            <span className="font-mono text-[12px] text-slate-600 dark:text-slate-300">{x.target}</span>
                                            <span className="ml-auto flex items-center gap-2">
                                                <Pill tone={x.state === "running" ? "info" : x.state === "done" ? "good" : "warn"}>{x.state}</Pill>
                                                <span className="text-[12px] text-slate-500">{ago(x.started_ms)}</span>
                                            </span>
                                        </div>
                                        <div className="mt-2 flex h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-white/[0.05]">
                                            <span className="bg-emerald-500" style={{ width: `${pct(x.matched)}%` }} />
                                            <span className="bg-amber-400" style={{ width: `${pct(x.mismatched)}%` }} />
                                            <span className="bg-rose-500" style={{ width: `${pct(x.errors)}%` }} />
                                        </div>
                                        <p className="mt-1.5 text-[12px] tabular-nums text-slate-500">
                                            <span className="text-emerald-600 dark:text-emerald-300">{fmt(x.matched)} match</span> · <span className="text-amber-600 dark:text-amber-300">{fmt(x.mismatched)} differ</span> · <span className="text-rose-600 dark:text-rose-300">{fmt(x.errors)} errors</span> · {fmt(x.total)} sent
                                        </p>
                                    </button>
                                </li>
                            );
                        })}
                    </ul>
                )}
            </Card>

            {detail?.kind === "replay" && (
                <Card title="Replay results" icon={FiRepeat} description="Status first, then body: equal bytes or equal JSON once ignored fields are removed. Click a row for the new server's response."
                    right={<div className="flex items-center gap-2">
                        <Segmented id="rp-filter" size="sm" value={filter} onChange={setFilter}
                            options={[{ value: "mismatch", label: "Differs", tone: "warn" }, { value: "error", label: "Errors", tone: "danger" }, { value: "match", label: "Matches" }, { value: "all", label: "All" }]} />
                        {running && <button type="button" className={btn} onClick={() => act(() => axios.post(api(`/replays/${detail.id}/cancel`), {}, { headers }), "Cancelling replay")}><FiSquare /> Cancel</button>}
                    </div>}>
                    {!results.length ? <p className="text-[13px] text-slate-500">Nothing here{running ? " yet" : ""}.</p> : <ul>{results.map(r => <ResultRow key={r.seq} r={r} />)}</ul>}
                </Card>
            )}

            {detail?.kind === "captures" && (
                <Card title={`Captured requests — ${recName(detail.id)}`} icon={FiDatabase} description="The first 200, in recorded order. Click a row for its bodies."
                    right={<button type="button" aria-label="Close" className={btn} onClick={() => setDetail(null)}><FiX /></button>}>
                    {!captures.length ? <p className="text-[13px] text-slate-500">Nothing captured yet.</p> : <ul>{captures.map(c => <CaptureRow key={c.seq} c={c} />)}</ul>}
                </Card>
            )}
        </div>
    );
}
