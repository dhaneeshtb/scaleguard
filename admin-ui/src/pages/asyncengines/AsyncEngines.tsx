import { useEffect, useState } from "react";
import { useToast } from "@chakra-ui/react";
import { useAuth } from "../../contexts/AuthContext";
import axios from "axios";
import {
    FiAlertTriangle, FiCheckCircle, FiChevronRight, FiCpu, FiEdit2,
    FiInfo, FiPlus, FiServer, FiTrash2, FiX, FiZap,
} from "react-icons/fi";
import { Card, Field, PageHeader, Pill, inputCls } from "../../components/ui/controls";

// ── Types ─────────────────────────────────────────────────────────────────────

interface Engine {
    id: string;
    name: string;
    type: "embedded" | "kafka" | string;
    topic: string;
    brokers?: string;
    description?: string;
}

const EMPTY: Omit<Engine, "id"> = {
    name: "", type: "embedded", topic: "", brokers: "", description: "",
};

// ── Helpers ───────────────────────────────────────────────────────────────────

function connUrl(e: Engine) {
    return e.type === "embedded"
        ? `embedded://${e.topic}`
        : `kafka://${e.brokers || "…"}/${e.topic}`;
}

// ── Type selector card ────────────────────────────────────────────────────────

const ENGINE_TYPES = [
    {
        value: "embedded",
        label: "Embedded",
        icon: FiCpu,
        description: "In-process queue. No external dependencies. Best for single-node setups or low-volume async delivery.",
        badge: "teal",
    },
    {
        value: "kafka",
        label: "Kafka",
        icon: FiServer,
        description: "Distributed event streaming via Apache Kafka. Scales to millions of events/sec across multiple nodes.",
        badge: "amber",
    },
] as const;

function TypePicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
    return (
        <div className="grid gap-3 sm:grid-cols-2">
            {ENGINE_TYPES.map(t => {
                const Icon = t.icon;
                const active = value === t.value;
                return (
                    <button
                        key={t.value}
                        type="button"
                        onClick={() => onChange(t.value)}
                        className={`relative flex items-start gap-3 rounded-2xl border p-4 text-left transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400
                            ${active
                                ? "border-teal-500/40 bg-teal-500/[0.07] dark:bg-teal-500/[0.09] ring-1 ring-teal-500/25"
                                : "border-slate-200 bg-white/60 hover:border-slate-300 dark:border-white/[0.07] dark:bg-white/[0.02] dark:hover:border-white/[0.12]"}`}
                    >
                        <span className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl
                            ${active ? "bg-teal-500/15 text-teal-600 dark:text-teal-300" : "bg-slate-100 text-slate-400 dark:bg-white/[0.05] dark:text-slate-500"}`}>
                            <Icon className="text-[15px]" />
                        </span>
                        <span className="min-w-0">
                            <span className="flex items-center gap-2">
                                <span className={`text-[13.5px] font-semibold ${active ? "text-teal-700 dark:text-teal-200" : "text-slate-800 dark:text-slate-100"}`}>
                                    {t.label}
                                </span>
                                {active && <FiCheckCircle className="text-teal-500 text-[13px]" />}
                            </span>
                            <span className="mt-0.5 block text-[11.5px] leading-relaxed text-slate-500 dark:text-slate-400">
                                {t.description}
                            </span>
                        </span>
                    </button>
                );
            })}
        </div>
    );
}

// ── Engine row ────────────────────────────────────────────────────────────────

function EngineRow({
    engine, onEdit, onDelete,
}: { engine: Engine; onEdit: () => void; onDelete: () => void }) {
    const isKafka = engine.type === "kafka";
    return (
        <div className="flex flex-wrap items-center gap-4 rounded-2xl border border-slate-200/70 bg-white/70 p-4 dark:border-white/[0.06] dark:bg-white/[0.03]">
            {/* Icon */}
            <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl
                ${isKafka ? "bg-amber-500/10 text-amber-600 dark:text-amber-400" : "bg-teal-500/10 text-teal-600 dark:text-teal-400"}`}>
                {isKafka ? <FiServer className="text-[16px]" /> : <FiCpu className="text-[16px]" />}
            </span>

            {/* Name + type */}
            <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                    <span className="text-[14px] font-semibold text-slate-800 dark:text-slate-100">{engine.name}</span>
                    <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide
                        ${isKafka
                            ? "border-amber-400/30 bg-amber-400/10 text-amber-700 dark:text-amber-300"
                            : "border-teal-400/30 bg-teal-400/10 text-teal-700 dark:text-teal-300"}`}>
                        {engine.type}
                    </span>
                </div>
                {engine.description && (
                    <p className="mt-0.5 text-[12px] text-slate-500 dark:text-slate-400">{engine.description}</p>
                )}
                <code className="mt-1.5 inline-block rounded-lg bg-slate-100 px-2.5 py-1 font-mono text-[11.5px] text-slate-500 dark:bg-white/[0.05] dark:text-slate-400">
                    {connUrl(engine)}
                </code>
            </div>

            {/* Actions */}
            <div className="flex shrink-0 gap-1.5">
                <button onClick={onEdit}
                    className="inline-flex h-8 items-center gap-1.5 rounded-xl border border-slate-200 px-3 text-[12px] font-semibold text-slate-600 hover:border-teal-400/40 hover:text-teal-600 dark:border-white/[0.08] dark:text-slate-300 dark:hover:text-teal-300 transition-colors">
                    <FiEdit2 className="text-[12px]" /> Edit
                </button>
                <button onClick={onDelete}
                    className="flex h-8 w-8 items-center justify-center rounded-xl border border-slate-200 text-slate-400 hover:border-rose-400/40 hover:bg-rose-500/[0.07] hover:text-rose-500 dark:border-white/[0.08] dark:hover:text-rose-400 transition-colors"
                    aria-label="Delete engine">
                    <FiTrash2 className="text-[13px]" />
                </button>
            </div>
        </div>
    );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function AsyncEngines() {
    const { auth } = useAuth() as any;
    const toast = useToast();
    const headers = { Authorization: auth.data.token };

    const [engines, setEngines] = useState<Engine[]>([]);
    const [loadErr, setLoadErr] = useState<string | null>(null);
    const [showForm, setShowForm] = useState(false);
    const [editId, setEditId] = useState<string | null>(null);
    const [form, setForm] = useState<Omit<Engine, "id">>(EMPTY);
    const [saving, setSaving] = useState(false);
    const [deleting, setDeleting] = useState<string | null>(null);
    const [formErr, setFormErr] = useState<string | null>(null);

    // ── Load ────────────────────────────────────────────────────────────────

    const load = async () => {
        try {
            const r = await axios.get(`${auth.data.host}/asyncengines?scaleguard=true`, { headers });
            const res = r.data as Record<string, any>;
            setEngines(Object.values(res).map(o => ({
                id: o.id,
                name: o.name,
                type: o.type,
                topic: o.payload?.topic || "event-stream",
                brokers: o.payload?.brokers || "",
                description: o.description || "",
            })));
            setLoadErr(null);
        } catch {
            setLoadErr("Could not load async engines from the server.");
        }
    };

    useEffect(() => { load(); }, []);

    // ── Save ────────────────────────────────────────────────────────────────

    const openAdd = () => { setForm(EMPTY); setEditId(null); setFormErr(null); setShowForm(true); };
    const openEdit = (e: Engine) => {
        setForm({ name: e.name, type: e.type, topic: e.topic, brokers: e.brokers || "", description: e.description || "" });
        setEditId(e.id);
        setFormErr(null);
        setShowForm(true);
    };
    const closeForm = () => { setShowForm(false); setEditId(null); setForm(EMPTY); setFormErr(null); };

    const submit = async () => {
        if (!form.name.trim()) { setFormErr("Engine name is required."); return; }
        if (!form.topic.trim()) { setFormErr("Topic is required."); return; }
        if (form.type === "kafka" && !form.brokers?.trim()) { setFormErr("Broker addresses are required for Kafka."); return; }
        setSaving(true);
        setFormErr(null);
        try {
            await axios.post(`${auth.data.host}/asyncengines?scaleguard=true`, {
                name: form.name.trim(),
                id: editId || form.name.trim(),
                type: form.type,
                description: form.description?.trim() || "",
                payload: { topic: form.topic.trim(), brokers: form.brokers?.trim() || "" },
            }, { headers });
            await load();
            closeForm();
            toast({ title: editId ? "Engine updated" : "Engine added", description: `"${form.name}" is now active.`, status: "success", duration: 3000, position: "top-right" });
        } catch (e: any) {
            setFormErr(e?.response?.data?.message || "Failed to save. Check the server logs.");
        } finally { setSaving(false); }
    };

    const remove = async (e: Engine) => {
        if (!window.confirm(`Delete "${e.name}"? Requests currently routed through it will fall back to synchronous delivery.`)) return;
        setDeleting(e.id);
        try {
            await axios.delete(`${auth.data.host}/asyncengines/${e.id}?scaleguard=true`, { headers });
            await load();
            toast({ title: "Engine deleted", status: "info", duration: 2500, position: "top-right" });
        } catch {
            toast({ title: "Delete failed", status: "error", duration: 3000, position: "top-right" });
        } finally { setDeleting(null); }
    };

    // ── Render ──────────────────────────────────────────────────────────────

    const isKafkaForm = form.type === "kafka";
    const canSave = form.name.trim() && form.topic.trim() && (!isKafkaForm || !!form.brokers?.trim());

    return (
        <div className="mx-auto max-w-4xl pb-24">
            <PageHeader
                icon={FiZap}
                eyebrow="Delivery"
                title="Async engines"
                description="Async engines decouple request delivery from upstream responses. When a route uses async delivery, ScaleGuard enqueues the request and immediately returns 202 Accepted — your backend processes it at its own pace."
                actions={
                    !showForm && (
                        <button onClick={openAdd}
                            className="inline-flex h-9 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-500 to-cyan-500 px-4 text-[13px] font-semibold text-white shadow-sm hover:brightness-110 transition-all">
                            <FiPlus /> New engine
                        </button>
                    )
                }
            />

            {/* ── How it works callout ─────────────────────────────────── */}
            <div className="mb-5 flex gap-3 rounded-2xl border border-slate-200/70 bg-white/50 p-4 dark:border-white/[0.06] dark:bg-white/[0.02]">
                <FiInfo className="mt-0.5 shrink-0 text-[16px] text-teal-500" />
                <div className="text-[12.5px] leading-relaxed text-slate-500 dark:text-slate-400">
                    <span className="font-semibold text-slate-700 dark:text-slate-200">How async delivery works: </span>
                    ScaleGuard receives a request → puts it on the engine's queue → replies <code className="rounded bg-slate-100 px-1 font-mono dark:bg-white/[0.05]">202 Accepted</code> → the engine delivers it to the backend asynchronously.
                    Use <span className="font-medium text-slate-600 dark:text-slate-300">Embedded</span> for simple in-process queuing (single node) or <span className="font-medium text-slate-600 dark:text-slate-300">Kafka</span> for distributed, durable, high-throughput delivery.
                </div>
            </div>

            {/* ── Load error ───────────────────────────────────────────── */}
            {loadErr && (
                <div className="mb-5 flex items-center gap-3 rounded-2xl border border-rose-400/30 bg-rose-500/[0.07] p-4">
                    <FiAlertTriangle className="shrink-0 text-rose-400" />
                    <p className="text-[13px] text-slate-700 dark:text-slate-200">{loadErr}</p>
                </div>
            )}

            {/* ── Engine list ──────────────────────────────────────────── */}
            {engines.length > 0 ? (
                <Card icon={FiZap} title={`${engines.length} engine${engines.length !== 1 ? "s" : ""} configured`}
                    description="Click Edit to change settings. Deleting an engine causes routes using it to fall back to synchronous delivery.">
                    <div className="space-y-3">
                        {engines.map(e => (
                            <div key={e.id} className={deleting === e.id ? "opacity-50 pointer-events-none" : ""}>
                                <EngineRow engine={e} onEdit={() => openEdit(e)} onDelete={() => remove(e)} />
                            </div>
                        ))}
                    </div>
                </Card>
            ) : !showForm && !loadErr ? (
                <div className="rounded-2xl border border-dashed border-slate-300 p-12 text-center dark:border-white/[0.08]">
                    <FiZap className="mx-auto mb-3 text-[28px] text-slate-300 dark:text-slate-600" />
                    <p className="text-[14px] font-semibold text-slate-500 dark:text-slate-400">No async engines yet</p>
                    <p className="mt-1 text-[12.5px] text-slate-400 dark:text-slate-500">
                        Add your first engine to enable asynchronous request delivery.
                    </p>
                    <button onClick={openAdd}
                        className="mt-5 inline-flex items-center gap-2 rounded-xl border border-slate-200 px-4 py-2 text-[13px] font-semibold text-slate-600 hover:border-teal-400/40 dark:border-white/[0.08] dark:text-slate-300">
                        <FiPlus /> Add engine
                    </button>
                </div>
            ) : null}

            {/* ── Add / Edit form ──────────────────────────────────────── */}
            {showForm && (
                <Card
                    icon={editId ? FiEdit2 : FiPlus}
                    title={editId ? "Edit engine" : "Add a new engine"}
                    description={editId
                        ? "Update the settings below. Changes take effect immediately for new requests."
                        : "Choose a type and fill in the details. You can add as many engines as you need."}
                    right={
                        <button onClick={closeForm}
                            className="flex h-8 w-8 items-center justify-center rounded-xl border border-slate-200 text-slate-400 hover:text-slate-700 dark:border-white/[0.08] dark:hover:text-white transition-colors"
                            aria-label="Close form">
                            <FiX />
                        </button>
                    }
                    className="mt-5"
                >
                    <div className="space-y-5">

                        {/* Step 1 — Engine type */}
                        <div>
                            <div className="mb-3 flex items-center gap-2">
                                <span className="flex h-5 w-5 items-center justify-center rounded-full bg-teal-500/15 text-[10px] font-bold text-teal-600 dark:text-teal-400">1</span>
                                <span className="text-[12.5px] font-semibold text-slate-700 dark:text-slate-200">Choose engine type</span>
                            </div>
                            <TypePicker value={form.type} onChange={v => setForm(f => ({ ...f, type: v, brokers: "" }))} />
                        </div>

                        <hr className="border-slate-200/60 dark:border-white/[0.06]" />

                        {/* Step 2 — Details */}
                        <div>
                            <div className="mb-3 flex items-center gap-2">
                                <span className="flex h-5 w-5 items-center justify-center rounded-full bg-teal-500/15 text-[10px] font-bold text-teal-600 dark:text-teal-400">2</span>
                                <span className="text-[12.5px] font-semibold text-slate-700 dark:text-slate-200">Engine details</span>
                            </div>
                            <div className="grid gap-4 sm:grid-cols-2">
                                <Field label="Engine name" htmlFor="ae-name" hint="A unique identifier used to reference this engine from routes.">
                                    <input id="ae-name" className={inputCls} placeholder="e.g. order-events"
                                        value={form.name}
                                        disabled={!!editId}
                                        onChange={e => { setForm(f => ({ ...f, name: e.target.value })); setFormErr(null); }} />
                                </Field>
                                <Field label="Topic / channel" htmlFor="ae-topic"
                                    hint={isKafkaForm ? "The Kafka topic name messages are published to." : "Name of the in-process queue channel."}>
                                    <input id="ae-topic" className={inputCls} placeholder="e.g. event-stream"
                                        value={form.topic}
                                        onChange={e => { setForm(f => ({ ...f, topic: e.target.value })); setFormErr(null); }} />
                                </Field>
                                {isKafkaForm && (
                                    <Field label="Broker addresses" htmlFor="ae-brokers"
                                        hint="Comma-separated list of Kafka brokers, e.g. broker1:9092,broker2:9092">
                                        <input id="ae-brokers" className={inputCls} placeholder="localhost:9092"
                                            value={form.brokers}
                                            onChange={e => { setForm(f => ({ ...f, brokers: e.target.value })); setFormErr(null); }} />
                                    </Field>
                                )}
                                <Field label="Description (optional)" htmlFor="ae-desc" hint="Free-form note for your team.">
                                    <input id="ae-desc" className={inputCls} placeholder="e.g. Handles order confirmations"
                                        value={form.description}
                                        onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
                                </Field>
                            </div>
                        </div>

                        {/* Connection preview */}
                        {(form.topic || form.brokers) && (
                            <div className="flex items-center gap-3 rounded-xl border border-slate-200/70 bg-slate-50/60 px-4 py-3 dark:border-white/[0.06] dark:bg-white/[0.02]">
                                <FiChevronRight className="shrink-0 text-teal-500 text-[14px]" />
                                <div className="min-w-0">
                                    <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">Connection URL preview</p>
                                    <code className="mt-0.5 block truncate font-mono text-[12px] text-slate-600 dark:text-slate-300">
                                        {connUrl({ ...form, id: "" })}
                                    </code>
                                </div>
                            </div>
                        )}

                        {/* Error */}
                        {formErr && (
                            <div className="flex items-center gap-2.5 rounded-xl border border-rose-400/30 bg-rose-500/[0.07] px-4 py-3">
                                <FiAlertTriangle className="shrink-0 text-rose-400" />
                                <p className="text-[12.5px] text-rose-600 dark:text-rose-300">{formErr}</p>
                            </div>
                        )}

                        {/* Actions */}
                        <div className="flex items-center justify-between gap-3 pt-1">
                            <button onClick={closeForm}
                                className="h-9 rounded-xl px-4 text-[13px] font-semibold text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/5 transition-colors">
                                Cancel
                            </button>
                            <button onClick={submit} disabled={saving || !canSave}
                                className="inline-flex h-9 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-500 to-cyan-500 px-5 text-[13px] font-semibold text-white disabled:opacity-50 hover:brightness-110 transition-all">
                                {saving
                                    ? <><span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/30 border-t-white" /> Saving…</>
                                    : <>{editId ? "Update engine" : "Add engine"}</>}
                            </button>
                        </div>
                    </div>
                </Card>
            )}
        </div>
    );
}
