import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import axios from "axios";
import { Switch, useToast } from "@chakra-ui/react";
import { AnimatePresence, motion } from "framer-motion";
import {
    FiAlertCircle, FiArrowLeft, FiArrowRight, FiCheck, FiChevronDown, FiCornerDownLeft, FiLayers,
    FiLock, FiPlus, FiRotateCcw, FiServer, FiShield, FiTrash2, FiZap, FiGitBranch, FiRadio, FiClock,
} from "react-icons/fi";
import type { IconType } from "react-icons";
import { formatData, getBaseSchema, updateSource } from "./sourceupdate";
import { useAuth } from "../contexts/AuthContext";

export type EntityType = "sourcesystems" | "targetsystems" | "hostgroups";

const META: Record<EntityType, { noun: string; icon: IconType; tint: string; blurb: string }> = {
    sourcesystems: { noun: "Source system", icon: FiServer, tint: "from-cyan-400 to-teal-400", blurb: "Where traffic arrives: the hostname, port and path ScaleGuard listens on." },
    targetsystems: { noun: "Target system", icon: FiLayers, tint: "from-violet-400 to-indigo-400", blurb: "Where traffic goes: an upstream route backed by a host group." },
    hostgroups: { noun: "Host group member", icon: FiGitBranch, tint: "from-amber-400 to-orange-400", blurb: "One upstream server inside a host group, with its health check and weight." },
};

// ---------- value helpers ----------
const isTrue = (v: any) => v === true || v === "true";
/** Protocol switches default to on: only an explicit false turns them off. */
const isOn = (v: any) => v !== false && v !== "false";
/** Optional numbers: blank and negative mean "use the default" (sent as -1 so a saved override can be cleared). */
const NUMERIC_DEFAULTS = ["connectTimeoutMs", "readTimeoutSeconds", "retries", "maxConns", "maxFails", "failTimeoutSeconds"];
const numText = (v: any) => (v === null || v === undefined || v === "" || Number(v) < 0 ? "" : String(v));
const asText = (v: any) => (v === null || v === undefined ? "" : String(v));
const parseMaybeJSON = (v: any, fallback: any) => {
    if (v === null || v === undefined || v === "") return fallback;
    if (typeof v === "object") return v;
    try { return JSON.parse(v); } catch { return fallback; }
};
const defaultPort = (scheme: string) => (scheme === "https" ? "443" : scheme === "http" ? "80" : "");

// ---------- small UI atoms ----------
const inputCls =
    "w-full h-11 rounded-xl border bg-white/80 px-3.5 text-[14px] text-slate-900 outline-none transition " +
    "placeholder:text-slate-400 focus:border-teal-400 focus:ring-4 focus:ring-teal-400/15 " +
    "dark:bg-slate-950/40 dark:text-white";
const okBorder = "border-slate-200 dark:border-white/10";
const errBorder = "border-rose-400/70 dark:border-rose-400/60";

function Field({ label, required, hint, error, children, htmlFor, span = 1 }: {
    label: string; required?: boolean; hint?: React.ReactNode; error?: string; children: React.ReactNode; htmlFor?: string; span?: 1 | 2;
}) {
    return (
        <div className={`min-w-0 ${span === 2 ? "sm:col-span-2" : ""}`}>
            <label htmlFor={htmlFor} className="mb-1.5 flex items-center gap-1 text-[12.5px] font-medium text-slate-700 dark:text-slate-300">
                {label}{required && <span className="text-rose-400">*</span>}
            </label>
            {children}
            {error
                ? <p className="mt-1.5 flex items-center gap-1 text-[11.5px] text-rose-500 dark:text-rose-300"><FiAlertCircle className="shrink-0" />{error}</p>
                : hint ? <p className="mt-1.5 text-[11.5px] leading-relaxed text-slate-500 dark:text-slate-400">{hint}</p> : null}
        </div>
    );
}

function TextInput({ id, value, onChange, placeholder, error, mono, list, type = "text", inputMode }: {
    id: string; value: any; onChange: (v: string) => void; placeholder?: string; error?: boolean; mono?: boolean; list?: string; type?: string; inputMode?: any;
}) {
    return (
        <input id={id} type={type} inputMode={inputMode} list={list} value={asText(value)} placeholder={placeholder}
            onChange={e => onChange(e.target.value)} autoComplete="off" spellCheck={false}
            className={`${inputCls} ${error ? errBorder : okBorder} ${mono ? "font-mono text-[13px]" : ""}`} />
    );
}

function Segmented<T extends string>({ value, options, onChange, id }: { value: T; options: { value: T; label: string; sub?: string }[]; onChange: (v: T) => void; id: string }) {
    return (
        <div id={id} role="radiogroup" className="flex flex-wrap gap-1 rounded-xl border border-slate-200 bg-slate-100/70 p-1 dark:border-white/10 dark:bg-white/[0.03]">
            {options.map(o => {
                const active = o.value === value;
                return (
                    <button key={o.value} type="button" role="radio" aria-checked={active} onClick={() => onChange(o.value)}
                        className={`relative flex-1 rounded-lg px-3 py-2 text-[13px] font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400 ${active ? "text-slate-900 dark:text-white" : "text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200"}`}>
                        {active && <motion.span layoutId={`seg-${id}`} className="absolute inset-0 rounded-lg bg-white shadow-sm ring-1 ring-slate-200 dark:bg-slate-800 dark:ring-white/10" transition={{ type: "spring", stiffness: 500, damping: 38 }} />}
                        <span className="relative uppercase tracking-wide">{o.label}</span>
                        {o.sub && <span className="relative mt-0.5 block text-[10.5px] font-normal normal-case tracking-normal text-slate-400">{o.sub}</span>}
                    </button>
                );
            })}
        </div>
    );
}

function Toggle({ id, label, description, checked, onChange, disabled, badge }: { id: string; label: string; description?: React.ReactNode; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean; badge?: React.ReactNode }) {
    return (
        <label htmlFor={id} className={`flex items-start justify-between gap-4 rounded-xl border border-slate-200 bg-white/60 p-3.5 transition-colors dark:border-white/10 dark:bg-white/[0.02] sm:col-span-2 ${disabled ? "cursor-not-allowed opacity-55" : "cursor-pointer hover:border-teal-400/40"}`}>
            <span className="min-w-0">
                <span className="flex flex-wrap items-center gap-2 text-[13.5px] font-medium text-slate-800 dark:text-slate-100">{label}{badge}</span>
                {description && <span className="mt-0.5 block text-[12px] leading-relaxed text-slate-500 dark:text-slate-400">{description}</span>}
            </span>
            <Switch id={id} isChecked={checked} isDisabled={disabled} onChange={e => onChange(e.target.checked)} colorScheme="teal" size="md" mt={0.5} />
        </label>
    );
}

/** Small monospace tag, e.g. the nginx directive a setting corresponds to. */
function Tag({ children }: { children: React.ReactNode }) {
    return <span className="rounded-md border border-slate-200 bg-slate-50 px-1.5 py-px font-mono text-[10.5px] font-medium text-slate-500 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-400">{children}</span>;
}

/** Optional number with a default shown as the placeholder; blank = default. */
function OptionalNumber({ id, value, onChange, placeholder, unit }: { id: string; value: any; onChange: (v: number | null) => void; placeholder: string; unit?: string }) {
    return (
        <div className="flex items-center gap-2">
            <input id={id} inputMode="numeric" value={numText(value)} placeholder={placeholder} autoComplete="off"
                onChange={e => { const t = e.target.value.replace(/[^\d]/g, ""); onChange(t === "" ? null : parseInt(t, 10)); }}
                className={`${inputCls} ${okBorder} font-mono text-[13px]`} />
            {unit && <span className="shrink-0 text-[12px] text-slate-500">{unit}</span>}
        </div>
    );
}

function SelectInput({ id, value, onChange, children, error }: { id: string; value: any; onChange: (v: string) => void; children: React.ReactNode; error?: boolean }) {
    return (
        <div className="relative">
            <select id={id} value={asText(value)} onChange={e => onChange(e.target.value)}
                className={`${inputCls} ${error ? errBorder : okBorder} appearance-none pr-10`}>
                {children}
            </select>
            <FiChevronDown className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-slate-400" />
        </div>
    );
}

function Section({ id, icon: Icon, title, description, children, collapsible, defaultOpen = true, badge }: {
    id: string; icon: IconType; title: string; description?: string; children: React.ReactNode; collapsible?: boolean; defaultOpen?: boolean; badge?: React.ReactNode;
}) {
    const [open, setOpen] = useState(defaultOpen);
    const header = (
        <div className="flex items-start gap-3">
            <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 dark:border-white/10 dark:bg-white/[0.03] dark:text-slate-300"><Icon className="text-[14px]" /></span>
            <div className="min-w-0 flex-1 text-left">
                <h2 className="flex items-center gap-2 text-[14.5px] font-semibold text-slate-900 dark:text-white">{title}{badge}</h2>
                {description && <p className="mt-0.5 text-[12.5px] text-slate-500 dark:text-slate-400">{description}</p>}
            </div>
            {collapsible && <FiChevronDown className={`mt-2 text-slate-400 transition-transform ${open ? "rotate-180" : ""}`} />}
        </div>
    );
    return (
        <section id={id} className="sg-card scroll-mt-24 p-5 sm:p-6">
            {collapsible
                ? <button type="button" className="w-full rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400" onClick={() => setOpen(o => !o)} aria-expanded={open}>{header}</button>
                : header}
            <AnimatePresence initial={false}>
                {open && (
                    <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.22 }} className="overflow-hidden">
                        <div className="grid gap-4 pt-5 sm:grid-cols-2">{children}</div>
                    </motion.div>
                )}
            </AnimatePresence>
        </section>
    );
}

/** Editable list of header name → value pairs, stored as a plain object. */
function KeyValueEditor({ id, value, onChange, valuePlaceholder = "value", keyOnly }: { id: string; value: any; onChange: (v: Record<string, string>) => void; valuePlaceholder?: string; keyOnly?: boolean }) {
    const obj = parseMaybeJSON(value, {}) || {};
    const [rows, setRows] = useState<{ k: string; v: string }[]>(() => Object.entries(obj).map(([k, v]) => ({ k, v: asText(v) })));
    const push = (next: { k: string; v: string }[]) => {
        setRows(next);
        const out: Record<string, string> = {};
        next.forEach(r => { if (r.k.trim()) out[r.k.trim()] = r.v; });
        onChange(out);
    };
    return (
        <div className="space-y-2">
            {rows.length === 0 && <p className="rounded-xl border border-dashed border-slate-300 px-3 py-3 text-[12.5px] text-slate-500 dark:border-white/10">None yet.</p>}
            {rows.map((r, i) => (
                <div key={i} className="flex gap-2">
                    <input id={`${id}-k${i}`} aria-label="Header name" value={r.k} placeholder="X-Header-Name" spellCheck={false}
                        onChange={e => push(rows.map((x, j) => j === i ? { ...x, k: e.target.value } : x))}
                        className={`${inputCls} ${okBorder} h-10 min-w-0 flex-1 font-mono text-[12.5px]`} />
                    {!keyOnly && <input id={`${id}-v${i}`} aria-label="Header value" value={r.v} placeholder={valuePlaceholder} spellCheck={false}
                        onChange={e => push(rows.map((x, j) => j === i ? { ...x, v: e.target.value } : x))}
                        className={`${inputCls} ${okBorder} h-10 min-w-0 flex-1 font-mono text-[12.5px]`} />}
                    <button type="button" onClick={() => push(rows.filter((_, j) => j !== i))} aria-label="Remove header"
                        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-slate-200 text-slate-400 hover:border-rose-400/50 hover:text-rose-500 dark:border-white/10"><FiTrash2 /></button>
                </div>
            ))}
            <button type="button" onClick={() => setRows([...rows, { k: "", v: "" }])}
                className="inline-flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-[12.5px] font-semibold text-teal-600 hover:bg-teal-500/10 dark:text-teal-300">
                <FiPlus /> Add header
            </button>
        </div>
    );
}

/** Cache rules (List<CachedResource>): pattern, method, TTL; keeps any other fields untouched. */
function CacheRulesEditor({ id, value, onChange }: { id: string; value: any; onChange: (v: any[]) => void }) {
    const rules: any[] = Array.isArray(parseMaybeJSON(value, [])) ? parseMaybeJSON(value, []) : [];
    const set = (next: any[]) => onChange(next);
    return (
        <div className="space-y-2">
            {rules.length === 0 && <p className="rounded-xl border border-dashed border-slate-300 px-3 py-3 text-[12.5px] text-slate-500 dark:border-white/10">No cache rules. Add a URL pattern to cache its responses at the edge.</p>}
            {rules.map((r, i) => (
                <div key={i} className="grid grid-cols-[minmax(0,1fr)_88px_84px_40px] gap-2">
                    <input id={`${id}-p${i}`} aria-label="URL pattern" value={asText(r.pattern)} placeholder="/api/catalog/*" spellCheck={false}
                        onChange={e => set(rules.map((x, j) => j === i ? { ...x, pattern: e.target.value } : x))}
                        className={`${inputCls} ${okBorder} h-10 font-mono text-[12.5px]`} />
                    <select id={`${id}-m${i}`} aria-label="Method" value={asText(r.method) || "GET"}
                        onChange={e => set(rules.map((x, j) => j === i ? { ...x, method: e.target.value } : x))}
                        className={`${inputCls} ${okBorder} h-10 px-2 text-[12.5px]`}>
                        {["GET", "POST", "PUT", "DELETE", "*"].map(m => <option key={m} value={m}>{m}</option>)}
                    </select>
                    <div className="relative">
                        <input id={`${id}-t${i}`} aria-label="TTL in seconds" inputMode="numeric" value={asText(r.ttl ?? 300)}
                            onChange={e => set(rules.map((x, j) => j === i ? { ...x, ttl: parseInt(e.target.value || "0", 10) || 0 } : x))}
                            className={`${inputCls} ${okBorder} h-10 pr-6 text-right font-mono text-[12.5px]`} />
                        <span className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-[10px] text-slate-400">s</span>
                    </div>
                    <button type="button" onClick={() => set(rules.filter((_, j) => j !== i))} aria-label="Remove rule"
                        className="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 text-slate-400 hover:border-rose-400/50 hover:text-rose-500 dark:border-white/10"><FiTrash2 /></button>
                </div>
            ))}
            <button type="button" onClick={() => set([...rules, { pattern: "", method: "GET", ttl: 300, cached: true }])}
                className="inline-flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-[12.5px] font-semibold text-teal-600 hover:bg-teal-500/10 dark:text-teal-300">
                <FiPlus /> Add cache rule
            </button>
        </div>
    );
}

// ---------- validation ----------
function validate(type: EntityType, o: any): Record<string, string> {
    const e: Record<string, string> = {};
    const scheme = o.scheme;
    const need = (k: string, msg: string) => { if (!asText(o[k]).trim()) e[k] = msg; };
    const port = (k: string) => {
        const p = asText(o[k]).trim();
        if (!p) e[k] = "Enter a port.";
        else if (!/^\d+$/.test(p) || +p < 1 || +p > 65535) e[k] = "Use a number between 1 and 65535.";
    };
    if (type === "sourcesystems") {
        need("name", "Give this source a name.");
        if (scheme !== "tcp") need("host", "Enter the hostname clients will call.");
        port("port");
        if (scheme === "kafka") need("basePath", "Enter at least one topic.");
        need("target", "Choose where traffic should go.");
        if (isTrue(o.async) && !asText(o.asyncEngine)) e.asyncEngine = "Pick an async engine.";
    } else if (type === "targetsystems") {
        need("name", "Give this target a name.");
        need("hostGroupId", "Choose the host group that serves this target.");
    } else {
        need("host", "Enter the server hostname or IP.");
        port("port");
        need("groupId", "Choose or name a host group.");
        const lf = parseFloat(asText(o.loadFactor));
        if (asText(o.loadFactor) !== "" && (isNaN(lf) || lf < 0 || lf > 1)) e.loadFactor = "Use a value from 0 to 1.";
        if (asText(o.priority) !== "" && !/^-?\d+$/.test(asText(o.priority))) e.priority = "Use a whole number.";
    }
    return e;
}

// ---------- the editor ----------
export default function EntityEditor({ type, id }: { type: EntityType; id?: string }) {
    const navigate = useNavigate();
    const toast = useToast();
    const { auth } = useAuth() as any;
    const isNew = !id || id === "new";
    const meta = META[type] || META.hostgroups;
    const Icon = meta.icon;

    const [original, setOriginal] = useState<any>(null);
    const [draft, setDraft] = useState<any>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);
    const [showErrors, setShowErrors] = useState(false);
    const [formVersion, setFormVersion] = useState(0);

    const [certificates, setCertificates] = useState<any[]>([]);
    const [engines, setEngines] = useState<any[]>([]);
    const [apps, setApps] = useState<any[]>([]);
    const [targets, setTargets] = useState<any[]>([]);
    const [hostGroups, setHostGroups] = useState<any[]>([]);

    const headers = useMemo(() => ({ Authorization: auth?.data?.token }), [auth]);
    const host = auth?.data?.host;

    // Load the record being edited.
    useEffect(() => {
        let cancelled = false;
        setLoadError(null);
        if (isNew) {
            const base = { ...getBaseSchema(type) };
            // Start new records blank instead of with placeholder values.
            if (type === "sourcesystems") { base.host = ""; base.port = defaultPort(base.scheme); }
            if (type === "hostgroups") { base.host = ""; base.port = ""; base.groupId = ""; }
            setOriginal(base); setDraft(base);
            return;
        }
        axios.get(`${host}/config/${type}/${id}?scaleguard=true`, { headers })
            .then(r => {
                const rec = (r.data?.[type] || [])[0];
                if (cancelled) return;
                if (!rec) { setLoadError("This record no longer exists. It may have been deleted."); return; }
                const merged = { ...getBaseSchema(type), ...rec };
                setOriginal(merged); setDraft(merged);
            })
            .catch(() => { if (!cancelled) setLoadError("Couldn't load this record. Check that the control plane is reachable."); });
        return () => { cancelled = true; };
    }, [type, id, isNew, host, headers]);

    // Reference data for pickers. Failures just leave the pickers empty.
    useEffect(() => {
        const get = (path: string) => axios.get(`${host}${path}${path.includes("?") ? "&" : "?"}scaleguard=true`, { headers }).then(r => r.data).catch(() => null);
        if (type === "sourcesystems") {
            get("/certificates").then(d => Array.isArray(d) && setCertificates(formatData(d)));
            get("/asyncengines").then(d => d && setEngines(Object.values(d)));
            get("/apps").then(d => Array.isArray(d) && setApps(d));
            get("/config/targetsystems").then(d => setTargets(d?.targetsystems || []));
        }
        if (type === "targetsystems" || type === "hostgroups") {
            get("/config/hostgroups").then(d => setHostGroups(d?.hostgroups || []));
        }
    }, [type, host, headers]);

    const set = useCallback((k: string, v: any) => setDraft((d: any) => ({ ...d, [k]: v })), []);
    const setScheme = (s: string) => setDraft((d: any) => {
        const next = { ...d, scheme: s };
        // Keep the port sensible when switching between http and https.
        if ("port" in d && (asText(d.port) === "" || asText(d.port) === defaultPort(d.scheme))) next.port = defaultPort(s) || d.port;
        return next;
    });

    const errors = useMemo(() => (draft ? validate(type, draft) : {}), [type, draft]);
    const errorCount = Object.keys(errors).length;
    const dirty = useMemo(() => !!draft && JSON.stringify(draft) !== JSON.stringify(original), [draft, original]);
    const err = (k: string) => (showErrors ? errors[k] : undefined);

    const save = useCallback(async () => {
        if (!draft) return;
        if (Object.keys(validate(type, draft)).length) {
            setShowErrors(true);
            toast({ title: "Some fields need attention", description: "Fix the highlighted fields, then save again.", status: "warning", duration: 3500, isClosable: true, position: "top-right" });
            return;
        }
        setSaving(true);
        try {
            const out = { ...draft };
            ["includeHeaders", "excludeHeaders"].forEach(k => { if (k in out) out[k] = parseMaybeJSON(out[k], {}); });
            if ("cachedResources" in out) out.cachedResources = (parseMaybeJSON(out.cachedResources, []) || []).filter((r: any) => asText(r?.pattern).trim());
            if (type === "sourcesystems") {
                out.async = isTrue(out.async);
                ["http2", "http3", "grpc"].forEach(k => { out[k] = isOn(out[k]); });
            }
            // Blank optional numbers go out as -1 so the server falls back to its default (null wouldn't clear a saved value).
            NUMERIC_DEFAULTS.forEach(k => { if (k in out) out[k] = out[k] === null || out[k] === "" || out[k] === undefined ? -1 : Number(out[k]); });
            if (type === "targetsystems") {
                out.tlsVerify = isTrue(out.tlsVerify);
                out.tlsServerName = asText(out.tlsServerName).trim();
            }
            if (type === "hostgroups") {
                out.priority = parseInt(asText(out.priority) || "0", 10) || 0;
                out.loadFactor = parseFloat(asText(out.loadFactor) || "0") || 0;
            }
            await updateSource(out, type, auth);
            setOriginal(draft);
            toast({ title: isNew ? `${meta.noun} created` : "Changes saved", status: "success", duration: 2200, position: "top-right" });
            navigate("/home");
        } catch (e: any) {
            const msg = e?.response?.data?.message || e?.response?.statusText || e?.message || "The control plane rejected the request.";
            toast({ title: "Couldn't save", description: String(msg), status: "error", duration: 6000, isClosable: true, position: "top-right" });
        } finally {
            setSaving(false);
        }
    }, [draft, type, auth, isNew, meta.noun, navigate, toast]);

    const discard = () => { setDraft(original); setShowErrors(false); setFormVersion(v => v + 1); };

    // ⌘S / Ctrl+S saves.
    const saveRef = useRef(save);
    saveRef.current = save;
    useEffect(() => {
        const onKey = (e: KeyboardEvent) => {
            if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "s") { e.preventDefault(); saveRef.current(); }
        };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, []);

    // Warn before closing the tab with unsaved edits.
    useEffect(() => {
        if (!dirty) return;
        const h = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ""; };
        window.addEventListener("beforeunload", h);
        return () => window.removeEventListener("beforeunload", h);
    }, [dirty]);

    // ---------- states ----------
    if (loadError) {
        return (
            <div className="mx-auto max-w-lg py-20 text-center">
                <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-rose-500/10 text-rose-500"><FiAlertCircle /></div>
                <p className="text-[15px] font-semibold text-slate-900 dark:text-white">{loadError}</p>
                <RouterLink to="/home" className="mt-5 inline-flex items-center gap-2 text-[13px] font-semibold text-teal-600 dark:text-teal-300"><FiArrowLeft /> Back to overview</RouterLink>
            </div>
        );
    }
    if (!draft) {
        return (
            <div className="mx-auto max-w-6xl space-y-4">
                <div className="h-16 w-72 animate-pulse rounded-xl bg-slate-200/70 dark:bg-white/[0.04]" />
                <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
                    <div className="space-y-4">{[0, 1, 2].map(i => <div key={i} className="sg-card h-48 animate-pulse" />)}</div>
                    <div className="sg-card h-64 animate-pulse" />
                </div>
            </div>
        );
    }

    const scheme = asText(draft.scheme) || "https";
    const rlNum = draft.rateLimit === null || draft.rateLimit === undefined || draft.rateLimit === "" ? -1 : Number(draft.rateLimit);
    const rlMode: "default" | "custom" | "unlimited" = rlNum < 0 ? "default" : rlNum === 0 ? "unlimited" : "custom";
    const isHttp = scheme === "http" || scheme === "https";
    const h2On = isOn(draft.http2);
    const h3On = scheme === "https" && isOn(draft.http3);
    const grpcOn = h2On && isOn(draft.grpc);
    const protocolChips = [
        { label: "HTTP/1.1", on: true }, { label: "HTTP/2", on: h2On }, { label: "HTTP/3", on: h3On }, { label: "gRPC", on: grpcOn },
    ];
    const title = asText(draft.name) || (type === "hostgroups" && draft.host ? `${draft.host}${draft.port ? ":" + draft.port : ""}` : "") || `New ${meta.noun.toLowerCase()}`;

    // ---------- section content per type ----------
    const sourceSections = (
        <>
            <Section id="sec-identity" icon={FiServer} title="Listener" description="The address clients call. ScaleGuard answers requests that match it.">
                <Field label="Name" required htmlFor="f-name" error={err("name")} hint="Shown in the dashboard and logs." span={2}>
                    <TextInput id="f-name" value={draft.name} onChange={v => set("name", v)} placeholder="Public API" error={!!err("name")} />
                </Field>
                <Field label="Protocol" required htmlFor="f-scheme" span={2}>
                    <Segmented id="f-scheme" value={scheme} onChange={setScheme} options={[
                        { value: "https", label: "HTTPS" }, { value: "http", label: "HTTP" }, { value: "tcp", label: "TCP" }, { value: "kafka", label: "Kafka" },
                    ]} />
                </Field>
                {scheme !== "tcp" && (
                    <Field label="Hostname" required htmlFor="f-host" error={err("host")} hint="The domain clients use, for example api.example.com.">
                        <TextInput id="f-host" value={draft.host} onChange={v => set("host", v)} placeholder="api.example.com" mono error={!!err("host")} />
                    </Field>
                )}
                <Field label="Port" required htmlFor="f-port" error={err("port")} hint={scheme === "https" ? "443 is standard for HTTPS." : scheme === "http" ? "80 is standard for HTTP." : undefined}>
                    <TextInput id="f-port" value={draft.port} onChange={v => set("port", v.replace(/[^\d]/g, ""))} placeholder={defaultPort(scheme) || "9000"} inputMode="numeric" mono error={!!err("port")} />
                </Field>
                {scheme !== "tcp" && (
                    <Field label={scheme === "kafka" ? "Topics" : "Path prefix"} required={scheme === "kafka"} htmlFor="f-base" error={err("basePath")} span={2}
                        hint={scheme === "kafka" ? "Comma-separated topic names, for example orders,payments." : "Only requests under this path match. Use / to match everything."}>
                        <TextInput id="f-base" value={scheme === "kafka" && draft.basePath === "/" ? "" : draft.basePath} onChange={v => set("basePath", v)} placeholder={scheme === "kafka" ? "orders,payments" : "/"} mono error={!!err("basePath")} />
                    </Field>
                )}
            </Section>

            <Section id="sec-routing" icon={FiArrowRight} title="Routing" description="Where matched traffic is forwarded.">
                <Field label="Target group" required htmlFor="f-target" error={err("target")} span={2}
                    hint={targets.length ? "Pick a target system's group ID, or type one." : "The group ID of the target system that should receive this traffic."}>
                    <TextInput id="f-target" list="dl-targets" value={draft.target} onChange={v => set("target", v)} placeholder="t-my-service" mono error={!!err("target")} />
                    <datalist id="dl-targets">
                        {Array.from(new Set(targets.map((t: any) => t.groupId || t.id).filter(Boolean))).map((g: any) => {
                            const t = targets.find((x: any) => (x.groupId || x.id) === g);
                            return <option key={g} value={g}>{t?.name || g}</option>;
                        })}
                    </datalist>
                </Field>
                <Field label="Group ID" htmlFor="f-group" hint="Optional label to group related sources.">
                    <TextInput id="f-group" value={draft.groupId} onChange={v => set("groupId", v)} placeholder="s-public" mono />
                </Field>
            </Section>

            {isHttp && (
                <Section id="sec-protocols" icon={FiRadio} title="Protocols" description="What clients can speak to this route. HTTP/1.1 always works; the rest follow nginx's per-server switches.">
                    <div className="flex flex-wrap gap-1.5 sm:col-span-2" aria-label="Enabled protocols">
                        {protocolChips.map(c => (
                            <span key={c.label} className={`rounded-full border px-2.5 py-0.5 text-[11.5px] font-semibold ${c.on ? "border-teal-400/40 bg-teal-400/10 text-teal-700 dark:text-teal-300" : "border-slate-200 text-slate-400 line-through dark:border-white/10"}`}>{c.label}</span>
                        ))}
                    </div>
                    <Toggle id="f-http2" label="HTTP/2" badge={<Tag>http2 {h2On ? "on" : "off"}</Tag>} checked={h2On} onChange={v => set("http2", v)}
                        description={scheme === "https"
                            ? "Negotiated during the TLS handshake (ALPN h2). Browsers get multiplexed requests on one connection; required for gRPC."
                            : "h2c with prior knowledge on plain HTTP, as used by gRPC clients without TLS. Browsers only use HTTP/2 over HTTPS."} />
                    <Toggle id="f-http3" label="HTTP/3 (QUIC)" badge={<Tag>{h3On ? `listen ${asText(draft.port) || "443"} quic · http3 on` : "http3 off"}</Tag>} checked={h3On} disabled={scheme !== "https"} onChange={v => set("http3", v)}
                        description={scheme !== "https"
                            ? "HTTP/3 always runs over TLS: switch the listener to HTTPS to offer it."
                            : <>Served over UDP on port {asText(draft.port) || "443"} with the same certificate, and advertised with <span className="font-mono">Alt-Svc: h3=":{asText(draft.port) || "443"}"</span> so browsers switch on their next request. Faster on lossy and mobile networks. Open UDP {asText(draft.port) || "443"} in your firewall.</>} />
                    <Toggle id="f-grpc" label="gRPC" badge={<Tag>{grpcOn ? "grpc_pass" : "no grpc_pass"}</Tag>} checked={grpcOn} disabled={!h2On} onChange={v => set("grpc", v)}
                        description={!h2On
                            ? "gRPC needs HTTP/2. Turn HTTP/2 on to proxy gRPC calls."
                            : "Requests with content-type application/grpc are relayed message by message to the target over HTTP/2 (h2c, or h2 over TLS for an HTTPS target): unary and streaming calls, trailers intact. When off, gRPC clients get UNIMPLEMENTED."} />
                </Section>
            )}

            {isHttp && (
                <Section id="sec-tls" icon={FiLock} title="TLS certificate" description="How ScaleGuard proves this hostname to clients.">
                    <Toggle id="f-auto" label="Get and renew a certificate automatically" description="ScaleGuard requests a free certificate for the hostname and renews it before it expires."
                        checked={isTrue(draft.autoProcure)} onChange={v => set("autoProcure", v)} />
                    {!isTrue(draft.autoProcure) && (
                        <Field label="Use an existing certificate" htmlFor="f-cert" span={2} hint={certificates.length ? undefined : "No certificates are installed yet. Add one on the Certificates page."}>
                            <SelectInput id="f-cert" value={draft.certificateId} onChange={v => set("certificateId", v)}>
                                <option value="">No certificate</option>
                                {certificates.map((c: any) => (
                                    <option key={c.id} value={c.id}>{(c.json?.identifiers || []).map((s: any) => s.value).join(", ") || c.id}</option>
                                ))}
                            </SelectInput>
                        </Field>
                    )}
                </Section>
            )}

            <Section id="sec-security" icon={FiShield} title="App security" description="Attach a security policy that checks requests before they're forwarded.">
                <Field label="Security app" htmlFor="f-sec" span={2} hint={apps.length ? "Set these up on the App Security page." : "No security apps yet. Create one on the App Security page."}>
                    <SelectInput id="f-sec" value={draft.secappid} onChange={v => set("secappid", v)}>
                        <option value="">None</option>
                        {apps.map((a: any) => <option key={a.id} value={a.id}>{a.name || a.id}</option>)}
                    </SelectInput>
                </Field>
            </Section>

            <Section id="sec-protect" icon={FiShield} title="Rate limit & firewall" description="Protect this route from floods and common attacks.">
                <Field label="Requests per client" htmlFor="f-rl" span={2}
                    hint={rlMode === "default" ? "Follows the default on the Rate limiting page." : rlMode === "unlimited" ? "No limit for this route." : `About ${(Math.max(1, Number(draft.rateLimit) || 0) / 60).toFixed(1)} requests per second per client.`}>
                    <div className="flex flex-wrap items-center gap-2">
                        <Segmented id="f-rl" value={rlMode} onChange={v => set("rateLimit", v === "default" ? -1 : v === "unlimited" ? 0 : (Number(draft.rateLimit) > 0 ? draft.rateLimit : 6000))} options={[
                            { value: "default", label: "Default" }, { value: "custom", label: "Custom" }, { value: "unlimited", label: "Unlimited" },
                        ]} />
                        {rlMode === "custom" && (
                            <span className="flex items-center gap-2">
                                <input id="f-rl-value" inputMode="numeric" value={asText(draft.rateLimit)} onChange={e => set("rateLimit", parseInt(e.target.value.replace(/[^\d]/g, "") || "1", 10))}
                                    className={`${inputCls} ${okBorder} w-32 font-mono text-[13px]`} />
                                <span className="text-[12px] text-slate-500">per minute</span>
                            </span>
                        )}
                    </div>
                </Field>
                {!isNew && (
                    <RouterLink to={`/firewall?route=${id}`} className="flex items-center justify-between rounded-xl border border-slate-200 bg-white/60 p-3.5 text-[13.5px] font-medium text-slate-800 transition-colors hover:border-teal-400/40 dark:border-white/10 dark:bg-white/[0.02] dark:text-slate-100 sm:col-span-2">
                        <span>Web application firewall rules for this route<span className="mt-0.5 block text-[12px] font-normal text-slate-500 dark:text-slate-400">SQL injection, XSS, path traversal and more — configured on the Firewall page.</span></span>
                        <FiArrowRight className="shrink-0 text-slate-400" />
                    </RouterLink>
                )}
            </Section>

            {isHttp && (
                <Section id="sec-advanced" icon={FiZap} title="Async processing & auth" description="Queue requests through an async engine and look up JWT keys." collapsible
                    defaultOpen={isTrue(draft.async) || !!draft.callbackId || !!draft.jwtKeylookup}>
                    <Toggle id="f-async" label="Process requests asynchronously" description="Requests are accepted right away and delivered to the upstream through an async engine."
                        checked={isTrue(draft.async)} onChange={v => set("async", v)} />
                    {isTrue(draft.async) && (
                        <Field label="Async engine" required htmlFor="f-engine" error={err("asyncEngine")} span={2} hint={engines.length ? undefined : "No async engines yet. Create one on the Async Engines page."}>
                            <SelectInput id="f-engine" value={draft.asyncEngine} onChange={v => set("asyncEngine", v)} error={!!err("asyncEngine")}>
                                <option value="">Choose an engine…</option>
                                {engines.map((e: any) => <option key={e.name} value={e.name}>{e.name} · {e.type}</option>)}
                            </SelectInput>
                        </Field>
                    )}
                    <Field label="Callback ID" htmlFor="f-cb" hint="Identifies the callback for async responses.">
                        <TextInput id="f-cb" value={draft.callbackId} onChange={v => set("callbackId", v)} placeholder="cb-orders" mono />
                    </Field>
                    <Field label="JWT key lookup" htmlFor="f-jwt" hint="Reference to the key used to verify tokens.">
                        <TextInput id="f-jwt" value={draft.jwtKeylookup} onChange={v => set("jwtKeylookup", v || null)} placeholder="jwks-main" mono />
                    </Field>
                </Section>
            )}
        </>
    );

    const groupIds: string[] = Array.from(new Set(hostGroups.map((h: any) => h.groupId).filter(Boolean))) as string[];

    const targetSections = (
        <>
            <Section id="sec-identity" icon={FiLayers} title="Upstream route" description="Which host group serves this route, and over which protocol.">
                <Field label="Name" required htmlFor="f-name" error={err("name")} span={2}>
                    <TextInput id="f-name" value={draft.name} onChange={v => set("name", v)} placeholder="Orders API backend" error={!!err("name")} />
                </Field>
                <Field label="Protocol to upstream" required htmlFor="f-scheme" span={2}>
                    <Segmented id="f-scheme" value={scheme} onChange={setScheme} options={[
                        { value: "https", label: "HTTPS" }, { value: "http", label: "HTTP" }, { value: "tcp", label: "TCP" }, { value: "kafka", label: "Kafka" },
                    ]} />
                </Field>
                <Field label="Host group" required htmlFor="f-hg" error={err("hostGroupId")}
                    hint={groupIds.length ? `${groupIds.length} host group${groupIds.length > 1 ? "s" : ""} available. Pick one or type a new ID.` : "The group of servers that handle this route."}>
                    <TextInput id="f-hg" list="dl-hg" value={draft.hostGroupId} onChange={v => set("hostGroupId", v)} placeholder="h-orders" mono error={!!err("hostGroupId")} />
                    <datalist id="dl-hg">{groupIds.map(g => <option key={g} value={g} />)}</datalist>
                </Field>
                <Field label="Group ID" htmlFor="f-group" hint="Sources route here using this ID.">
                    <TextInput id="f-group" value={draft.groupId} onChange={v => set("groupId", v)} placeholder="t-orders" mono />
                </Field>
                {isHttp && (
                    <Field label="Upstream path prefix" htmlFor="f-base" span={2} hint="Prepended to the request path when forwarding. Use / to keep paths as they are.">
                        <TextInput id="f-base" value={draft.basePath} onChange={v => set("basePath", v)} placeholder="/" mono />
                    </Field>
                )}
            </Section>

            {isHttp && (
                <Section id="sec-upstream" icon={FiClock} title="Upstream connection" description="How ScaleGuard talks to the servers in the host group — the nginx proxy_* and grpc_* settings.">
                    <div className="rounded-xl border border-slate-200 bg-slate-50/70 p-3.5 text-[12.5px] leading-relaxed text-slate-600 dark:border-white/10 dark:bg-white/[0.02] dark:text-slate-300 sm:col-span-2">
                        Requests reach the servers over <b>HTTP/1.1 with keep-alive</b>, whatever the client used (HTTP/1.1, HTTP/2 or HTTP/3) — nginx's <span className="font-mono">proxy_http_version 1.1</span>.
                        {" "}gRPC calls go over <b>HTTP/2</b>: {scheme === "https" ? <><span className="font-mono">h2</span> over TLS</> : <>cleartext <span className="font-mono">h2c</span></>}, like <span className="font-mono">grpc_pass {scheme === "https" ? "grpcs" : "grpc"}://</span>.
                    </div>
                    <Field label="Connect timeout" htmlFor="f-cto" hint={<>Give up on a server that doesn't accept the connection. <Tag>proxy_connect_timeout</Tag></>}>
                        <OptionalNumber id="f-cto" value={draft.connectTimeoutMs} onChange={v => set("connectTimeoutMs", v)} placeholder="5000 (default)" unit="ms" />
                    </Field>
                    <Field label="Response timeout" htmlFor="f-rto" hint={<>Answer 504 when a server goes quiet mid-request. <Tag>proxy_read_timeout</Tag></>}>
                        <OptionalNumber id="f-rto" value={draft.readTimeoutSeconds} onChange={v => set("readTimeoutSeconds", v)} placeholder="60 (default)" unit="seconds" />
                    </Field>
                    <Field label="Retries on another server" htmlFor="f-retries" hint={<>When a server refuses the connection, try the next one. 0 turns it off. <Tag>proxy_next_upstream</Tag></>}>
                        <OptionalNumber id="f-retries" value={draft.retries} onChange={v => set("retries", v)} placeholder="1 (default)" />
                    </Field>
                    {scheme === "https" && (
                        <>
                            <Toggle id="f-tlsverify" label="Verify the server's certificate" badge={<Tag>proxy_ssl_verify</Tag>} checked={isTrue(draft.tlsVerify)} onChange={v => set("tlsVerify", v)}
                                description="Reject servers whose certificate isn't trusted or doesn't match the name below. Off by default (as in nginx) so self-signed internal certificates work." />
                            <Field label="TLS server name (SNI)" htmlFor="f-sni" span={2} hint={<>Sent in the TLS handshake and checked against the certificate. Leave blank to use each server's host. Set it when servers are addressed by IP. <Tag>proxy_ssl_name</Tag></>}>
                                <TextInput id="f-sni" value={draft.tlsServerName} onChange={v => set("tlsServerName", v)} placeholder="api.internal.example.com" mono />
                            </Field>
                        </>
                    )}
                </Section>
            )}

            {isHttp && (
                <Section id="sec-headers" icon={FiCornerDownLeft} title="Request headers" description="Add or strip headers on requests forwarded to the upstream.">
                    <Field label="Add these headers" htmlFor="f-inc-k0" span={2}>
                        <KeyValueEditor key={`inc-${formVersion}`} id="f-inc" value={draft.includeHeaders} onChange={v => set("includeHeaders", v)} />
                    </Field>
                    <Field label="Remove these headers" htmlFor="f-exc-k0" span={2}>
                        <KeyValueEditor key={`exc-${formVersion}`} id="f-exc" value={draft.excludeHeaders} onChange={v => set("excludeHeaders", v)} valuePlaceholder="(optional)" />
                    </Field>
                </Section>
            )}

            {isHttp && (
                <Section id="sec-cache" icon={FiZap} title="Edge caching" description="Serve repeat responses from ScaleGuard instead of the upstream.">
                    <Toggle id="f-cache" label="Enable caching" description="Only URLs that match a rule below are cached." checked={isTrue(draft.enableCache)} onChange={v => set("enableCache", v)} />
                    {isTrue(draft.enableCache) && (
                        <Field label="Cache rules" htmlFor="f-rules-p0" span={2} hint="Pattern, method and how long to keep the response.">
                            <CacheRulesEditor id="f-rules" value={draft.cachedResources} onChange={v => set("cachedResources", v)} />
                        </Field>
                    )}
                </Section>
            )}
        </>
    );

    const lf = Math.max(0, Math.min(1, parseFloat(asText(draft.loadFactor)) || 0));
    const hostGroupSections = (
        <>
            <Section id="sec-identity" icon={FiServer} title="Server" description="The upstream server and how ScaleGuard checks its health.">
                <Field label="Protocol" required htmlFor="f-scheme" span={2}>
                    <Segmented id="f-scheme" value={scheme} onChange={setScheme} options={[
                        { value: "https", label: "HTTPS" }, { value: "http", label: "HTTP" }, { value: "tcp", label: "TCP" },
                    ]} />
                </Field>
                <Field label="Host" required htmlFor="f-host" error={err("host")} hint="Hostname or IP address.">
                    <TextInput id="f-host" value={draft.host} onChange={v => set("host", v)} placeholder="10.0.1.12" mono error={!!err("host")} />
                </Field>
                <Field label="Port" required htmlFor="f-port" error={err("port")}>
                    <TextInput id="f-port" value={draft.port} onChange={v => set("port", v.replace(/[^\d]/g, ""))} placeholder="8080" inputMode="numeric" mono error={!!err("port")} />
                </Field>
                <Field label="Health check path" htmlFor="f-health" span={2} hint="ScaleGuard calls this path to decide whether the server can take traffic. Leave empty to skip checks.">
                    <TextInput id="f-health" value={draft.health} onChange={v => set("health", v)} placeholder="/healthz" mono />
                </Field>
            </Section>

            <Section id="sec-group" icon={FiGitBranch} title="Group & role" description="Which group this server joins and whether it takes traffic now.">
                <Field label="Host group" required htmlFor="f-group" error={err("groupId")} span={2}
                    hint={groupIds.length ? "Pick an existing group or type a new ID to start one." : "Servers with the same group ID are balanced together."}>
                    <TextInput id="f-group" list="dl-hg" value={draft.groupId} onChange={v => set("groupId", v)} placeholder="h-orders" mono error={!!err("groupId")} />
                    <datalist id="dl-hg">{groupIds.map(g => <option key={g} value={g} />)}</datalist>
                </Field>
                <Field label="Role" required htmlFor="f-type" span={2}>
                    <Segmented id="f-type" value={(asText(draft.type) || "active").toLowerCase()} onChange={v => set("type", v)} options={[
                        { value: "active", label: "Active", sub: "Takes traffic" }, { value: "standby", label: "Backup", sub: "Only when actives can't" },
                    ]} />
                </Field>
                <Toggle id="f-active" label="Enabled" badge={draft.active === false || draft.active === "false" ? <Tag>down</Tag> : undefined}
                    description="Turn off to drain this server without deleting it (nginx down). Backup servers take traffic only when no active server can (nginx backup)."
                    checked={draft.active !== false && draft.active !== "false"} onChange={v => set("active", v)} />
            </Section>

            {scheme !== "tcp" && (
                <Section id="sec-limits" icon={FiShield} title="Limits & failure handling" description="Protect this server from overload and decide when it's taken out of rotation — nginx's server parameters.">
                    <Field label="Max connections" htmlFor="f-maxconns" hint={<>Open connections from ScaleGuard to this server. When reached, requests go to other servers. Blank = unlimited. <Tag>max_conns</Tag></>}>
                        <OptionalNumber id="f-maxconns" value={draft.maxConns} onChange={v => set("maxConns", v)} placeholder="unlimited" />
                    </Field>
                    <Field label="Failures before ejecting" htmlFor="f-maxfails" hint={<>Failed requests in a row (errors, 502/503/504, timeouts). Blank = the Backend health setting. <Tag>max_fails</Tag></>}>
                        <OptionalNumber id="f-maxfails" value={draft.maxFails} onChange={v => set("maxFails", v)} placeholder="5 (default)" />
                    </Field>
                    <Field label="Ejection time" htmlFor="f-failtimeout" span={2} hint={<>How long the server stays out after being ejected; doubles if it fails again right away. Blank = the Backend health setting. <Tag>fail_timeout</Tag></>}>
                        <OptionalNumber id="f-failtimeout" value={draft.failTimeoutSeconds} onChange={v => set("failTimeoutSeconds", v)} placeholder="10 (default)" unit="seconds" />
                    </Field>
                </Section>
            )}

            <Section id="sec-balance" icon={FiZap} title="Load balancing" description="How much traffic this server gets compared with the rest of its group.">
                <Field label="Load factor" htmlFor="f-lf" error={err("loadFactor")} span={2} hint="Share of the group's traffic, from 0 to 1.">
                    <div className="flex items-center gap-4">
                        <input id="f-lf" type="range" min={0} max={1} step={0.05} value={lf} onChange={e => set("loadFactor", e.target.value)}
                            className="h-2 min-w-0 flex-1 cursor-pointer appearance-none rounded-full bg-slate-200 accent-teal-500 dark:bg-white/10" />
                        <span className="w-16 rounded-lg border border-slate-200 py-1.5 text-center font-mono text-[13px] tabular-nums text-slate-800 dark:border-white/10 dark:text-slate-100">{lf.toFixed(2)}</span>
                    </div>
                </Field>
                <Field label="Priority" htmlFor="f-prio" error={err("priority")} hint="Lower numbers are tried first within a role (active/standby strategy).">
                    <TextInput id="f-prio" value={draft.priority} onChange={v => set("priority", v.replace(/[^\d-]/g, ""))} placeholder="0" inputMode="numeric" mono error={!!err("priority")} />
                </Field>
            </Section>
        </>
    );

    // ---------- summary rail ----------
    const listenUrl = type === "sourcesystems"
        ? (scheme === "tcp" ? `tcp://*:${asText(draft.port) || "?"}` : `${scheme}://${asText(draft.host) || "host"}${draft.port && draft.port !== defaultPort(scheme) ? ":" + draft.port : ""}${scheme === "kafka" ? "  ·  " + (asText(draft.basePath) || "topics") : asText(draft.basePath) || "/"}`)
        : type === "hostgroups"
            ? `${scheme}://${asText(draft.host) || "host"}:${asText(draft.port) || "?"}${asText(draft.health)}`
            : `${scheme}://<${asText(draft.hostGroupId) || "host group"}>${isHttp ? asText(draft.basePath) || "/" : ""}`;

    const flow = type === "sourcesystems"
        ? [{ k: "Clients", v: isHttp ? `${listenUrl}  ·  ${protocolChips.filter(c => c.on).map(c => c.label).join(", ")}` : listenUrl }, { k: "ScaleGuard", v: isTrue(draft.async) ? `queued via ${asText(draft.asyncEngine) || "engine"}` : "route & balance" }, { k: "Target", v: asText(draft.target) || "not set" }]
        : type === "targetsystems"
            ? [{ k: "Sources", v: asText(draft.groupId) ? `route to ${draft.groupId}` : "group ID not set" }, { k: "ScaleGuard", v: isTrue(draft.enableCache) ? "cache + balance" : "balance" }, { k: "Host group", v: `${asText(draft.hostGroupId) || "not set"}${isHttp ? `  ·  HTTP/1.1, gRPC over ${scheme === "https" ? "h2" : "h2c"}${isTrue(draft.tlsVerify) && scheme === "https" ? ", TLS verified" : ""}` : ""}` }]
            : [{ k: "Host group", v: asText(draft.groupId) || "not set" }, { k: "This server", v: listenUrl }, { k: "Role", v: `${(asText(draft.type) || "active").toLowerCase() === "standby" ? "backup" : "active"}${draft.active === false ? " · down" : ""}${Number(draft.maxConns) > 0 ? ` · max ${draft.maxConns} conns` : ""}` }];

    return (
        <div className="mx-auto max-w-6xl pb-24">
            {/* Header */}
            <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
                <div className="min-w-0">
                    <RouterLink to="/home" className="mb-3 inline-flex items-center gap-1.5 text-[12.5px] font-medium text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white">
                        <FiArrowLeft /> Overview
                    </RouterLink>
                    <div className="flex items-center gap-3">
                        <span className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br ${meta.tint} text-slate-950 shadow-glow`}><Icon className="text-[18px]" /></span>
                        <div className="min-w-0">
                            <p className="sg-eyebrow">{isNew ? `New ${meta.noun.toLowerCase()}` : meta.noun}</p>
                            <h1 className="truncate text-[24px] font-bold tracking-tight text-slate-900 dark:text-white sm:text-[28px]">{title}</h1>
                        </div>
                    </div>
                    <p className="mt-2 max-w-xl text-[13px] text-slate-500 dark:text-slate-400">{meta.blurb}</p>
                </div>
                {!isNew && <span className="max-w-full self-start truncate rounded-lg sm:self-auto border border-slate-200 px-2.5 py-1 font-mono text-[11px] text-slate-500 dark:border-white/10 dark:text-slate-400" title={id}>ID {id}</span>}
            </div>

            <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
                <form key={formVersion} className="min-w-0 space-y-4" onSubmit={e => { e.preventDefault(); save(); }} noValidate>
                    {type === "sourcesystems" ? sourceSections : type === "targetsystems" ? targetSections : hostGroupSections}
                    <button type="submit" hidden />
                </form>

                {/* Summary rail */}
                <aside className="space-y-4 lg:sticky lg:top-24">
                    <div className="sg-card sg-ring-gradient overflow-hidden p-5">
                        <p className="sg-eyebrow">Live preview</p>
                        <ol className="relative mt-4 space-y-4">
                            <span className="absolute bottom-3 left-[9px] top-3 w-px bg-gradient-to-b from-teal-400 via-cyan-400 to-violet-400 opacity-60" />
                            {flow.map((s, i) => (
                                <li key={i} className="relative flex gap-3">
                                    <span className={`relative z-10 mt-0.5 h-[19px] w-[19px] shrink-0 rounded-full border-2 ${i === 1 ? "border-teal-400 bg-teal-400/20" : "border-slate-300 bg-white dark:border-white/20 dark:bg-slate-900"}`} />
                                    <div className="min-w-0">
                                        <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-slate-400">{s.k}</p>
                                        <p className="break-all font-mono text-[12.5px] text-slate-800 dark:text-slate-100">{s.v}</p>
                                    </div>
                                </li>
                            ))}
                        </ol>
                    </div>

                    <div className="sg-card p-5">
                        <p className="sg-eyebrow">Ready to save</p>
                        {errorCount === 0 ? (
                            <p className="mt-3 flex items-center gap-2 text-[13px] font-medium text-emerald-600 dark:text-emerald-300"><FiCheck /> All required fields are filled.</p>
                        ) : (
                            <ul className="mt-3 space-y-2">
                                {Object.entries(errors).map(([k, m]) => (
                                    <li key={k}>
                                        <button type="button" onClick={() => { setShowErrors(true); document.getElementById(`f-${k === "basePath" ? "base" : k === "hostGroupId" ? "hg" : k === "groupId" ? "group" : k === "asyncEngine" ? "engine" : k === "loadFactor" ? "lf" : k === "priority" ? "prio" : k}`)?.focus(); }}
                                            className="flex w-full items-start gap-2 rounded-lg text-left text-[12.5px] text-amber-700 hover:underline dark:text-amber-300">
                                            <FiAlertCircle className="mt-0.5 shrink-0" /> {m}
                                        </button>
                                    </li>
                                ))}
                            </ul>
                        )}
                        <p className="mt-4 border-t border-slate-200 pt-3 text-[11.5px] text-slate-500 dark:border-white/[0.06] dark:text-slate-400">
                            Press <span className="sg-kbd">⌘</span> <span className="sg-kbd">S</span> to save from anywhere on the page.
                        </p>
                    </div>
                </aside>
            </div>

            {/* Sticky action bar */}
            <div className="fixed inset-x-0 bottom-0 z-30 border-t border-slate-200/80 bg-white/80 backdrop-blur-xl dark:border-white/[0.06] dark:bg-slate-950/70 md:left-[var(--sg-sidebar,0px)]"
                style={{ paddingBottom: "env(safe-area-inset-bottom, 0px)" }}>
                <div className="mx-auto flex h-16 max-w-6xl items-center gap-3 px-4 sm:px-6 lg:px-10">
                    <AnimatePresence mode="wait" initial={false}>
                        {dirty ? (
                            <motion.span key="dirty" initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }}
                                className="flex items-center gap-2 text-[13px] font-medium text-amber-600 dark:text-amber-300">
                                <span className="h-2 w-2 rounded-full bg-amber-400" /> Unsaved changes
                            </motion.span>
                        ) : (
                            <motion.span key="clean" initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }}
                                className="text-[13px] text-slate-500 dark:text-slate-400">
                                {isNew ? "Fill in the required fields to create it." : "No changes yet."}
                            </motion.span>
                        )}
                    </AnimatePresence>
                    <div className="ml-auto flex items-center gap-2">
                        {dirty && !isNew && (
                            <button type="button" onClick={discard} className="inline-flex h-10 items-center gap-1.5 rounded-xl px-3.5 text-[13px] font-semibold text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/5">
                                <FiRotateCcw /> Discard
                            </button>
                        )}
                        <RouterLink to="/home" className="hidden h-10 items-center rounded-xl px-3.5 text-[13px] font-semibold text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/5 sm:inline-flex">Cancel</RouterLink>
                        <button type="button" onClick={save} disabled={saving || (!dirty && !isNew)}
                            className="inline-flex h-10 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-5 text-[13.5px] font-semibold text-slate-950 shadow-glow transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40 disabled:shadow-none">
                            {saving ? <span className="h-4 w-4 animate-spin rounded-full border-2 border-slate-950/30 border-t-slate-950" /> : <FiCheck />}
                            {isNew ? `Create ${meta.noun.toLowerCase()}` : "Save changes"}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
