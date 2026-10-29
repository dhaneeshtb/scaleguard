import React from "react";
import { Switch } from "@chakra-ui/react";
import { motion } from "framer-motion";
import type { IconType } from "react-icons";

/** Shared form/layout atoms for the protection pages. */

export const inputCls =
    "w-full h-10 rounded-xl border border-slate-200 bg-white/80 px-3.5 text-[13.5px] text-slate-900 outline-none transition " +
    "placeholder:text-slate-400 focus:border-teal-400 focus:ring-4 focus:ring-teal-400/15 dark:border-white/10 dark:bg-slate-950/40 dark:text-white";

export function PageHeader({ icon: Icon, eyebrow, title, description, actions }: {
    icon: IconType; eyebrow: string; title: string; description: string; actions?: React.ReactNode;
}) {
    return (
        <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
            <div className="flex items-start gap-3">
                <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-teal-400 to-violet-500 text-slate-950 shadow-glow"><Icon className="text-[18px]" /></span>
                <div className="min-w-0">
                    <p className="sg-eyebrow">{eyebrow}</p>
                    <h1 className="text-[24px] font-bold tracking-tight text-slate-900 dark:text-white sm:text-[28px]">{title}</h1>
                    <p className="mt-1 max-w-2xl text-[13px] leading-relaxed text-slate-500 dark:text-slate-400">{description}</p>
                </div>
            </div>
            {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </div>
    );
}

export function Card({ title, description, icon: Icon, right, children, className = "" }: {
    title?: string; description?: string; icon?: IconType; right?: React.ReactNode; children: React.ReactNode; className?: string;
}) {
    return (
        <section className={`sg-card p-5 sm:p-6 ${className}`}>
            {(title || right) && (
                <div className="mb-5 flex items-start justify-between gap-4">
                    <div className="flex items-start gap-3">
                        {Icon && <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 dark:border-white/10 dark:bg-white/[0.03] dark:text-slate-300"><Icon className="text-[14px]" /></span>}
                        <div className="min-w-0">
                            {title && <h2 className="text-[14.5px] font-semibold text-slate-900 dark:text-white">{title}</h2>}
                            {description && <p className="mt-0.5 text-[12.5px] leading-relaxed text-slate-500 dark:text-slate-400">{description}</p>}
                        </div>
                    </div>
                    {right}
                </div>
            )}
            {children}
        </section>
    );
}

export function Field({ label, hint, error, htmlFor, children }: { label: string; hint?: React.ReactNode; error?: string; htmlFor?: string; children: React.ReactNode }) {
    return (
        <div className="min-w-0">
            <label htmlFor={htmlFor} className="mb-1.5 block text-[12.5px] font-medium text-slate-700 dark:text-slate-300">{label}</label>
            {children}
            {error ? <p className="mt-1.5 text-[11.5px] text-rose-500 dark:text-rose-300">{error}</p>
                : hint ? <p className="mt-1.5 text-[11.5px] leading-relaxed text-slate-500 dark:text-slate-400">{hint}</p> : null}
        </div>
    );
}

export function ToggleRow({ id, label, description, checked, onChange, disabled }: {
    id: string; label: string; description?: React.ReactNode; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean;
}) {
    return (
        <label htmlFor={id} className={`flex items-start justify-between gap-4 rounded-xl border border-slate-200 bg-white/60 p-3.5 transition-colors dark:border-white/10 dark:bg-white/[0.02] ${disabled ? "opacity-60" : "cursor-pointer hover:border-teal-400/40"}`}>
            <span className="min-w-0">
                <span className="block text-[13.5px] font-medium text-slate-800 dark:text-slate-100">{label}</span>
                {description && <span className="mt-0.5 block text-[12px] leading-relaxed text-slate-500 dark:text-slate-400">{description}</span>}
            </span>
            <Switch id={id} isChecked={checked} isDisabled={disabled} onChange={e => onChange(e.target.checked)} colorScheme="teal" mt={0.5} />
        </label>
    );
}

export function Segmented<T extends string>({ id, value, options, onChange, size = "md" }: {
    id: string; value: T; options: { value: T; label: string; tone?: "danger" | "warn" }[]; onChange: (v: T) => void; size?: "sm" | "md";
}) {
    return (
        <div role="radiogroup" className="inline-flex gap-1 rounded-xl border border-slate-200 bg-slate-100/70 p-1 dark:border-white/10 dark:bg-white/[0.03]">
            {options.map(o => {
                const active = o.value === value;
                const tone = active && o.tone === "danger" ? "text-rose-600 dark:text-rose-300" : active && o.tone === "warn" ? "text-amber-600 dark:text-amber-300" : active ? "text-slate-900 dark:text-white" : "text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200";
                return (
                    <button key={o.value} type="button" role="radio" aria-checked={active} onClick={() => onChange(o.value)}
                        className={`relative rounded-lg font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400 ${size === "sm" ? "px-2.5 py-1 text-[11.5px]" : "px-3.5 py-1.5 text-[12.5px]"} ${tone}`}>
                        {active && <motion.span layoutId={`seg-${id}`} className="absolute inset-0 rounded-lg bg-white shadow-sm ring-1 ring-slate-200 dark:bg-slate-800 dark:ring-white/10" transition={{ type: "spring", stiffness: 500, damping: 38 }} />}
                        <span className="relative">{o.label}</span>
                    </button>
                );
            })}
        </div>
    );
}

export function Stat({ label, value, tone }: { label: string; value: React.ReactNode; tone?: "good" | "bad" | "warn" }) {
    const c = tone === "good" ? "text-emerald-600 dark:text-emerald-300" : tone === "bad" ? "text-rose-600 dark:text-rose-300" : tone === "warn" ? "text-amber-600 dark:text-amber-300" : "text-slate-900 dark:text-white";
    return (
        <div className="rounded-xl border border-slate-200 bg-white/60 px-4 py-3 dark:border-white/[0.07] dark:bg-white/[0.02]">
            <p className="sg-eyebrow">{label}</p>
            <p className={`mt-1 text-[22px] font-semibold tabular-nums tracking-tight ${c}`}>{value}</p>
        </div>
    );
}

export function Pill({ tone, children }: { tone: "good" | "bad" | "warn" | "muted" | "info"; children: React.ReactNode }) {
    const cls = {
        good: "border-emerald-500/25 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300",
        bad: "border-rose-500/25 bg-rose-500/10 text-rose-700 dark:text-rose-300",
        warn: "border-amber-500/25 bg-amber-500/10 text-amber-700 dark:text-amber-300",
        info: "border-sky-500/25 bg-sky-500/10 text-sky-700 dark:text-sky-300",
        muted: "border-slate-300/70 bg-slate-100 text-slate-500 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-400",
    }[tone];
    return <span className={`inline-flex items-center gap-1 whitespace-nowrap rounded-full border px-2 py-0.5 text-[11px] font-semibold ${cls}`}>{children}</span>;
}

export function SaveBar({ dirty, saving, onSave, onDiscard, label = "Save changes" }: { dirty: boolean; saving: boolean; onSave: () => void; onDiscard: () => void; label?: string }) {
    if (!dirty) return null;
    return (
        <motion.div initial={{ y: 20, opacity: 0 }} animate={{ y: 0, opacity: 1 }}
            className="fixed bottom-5 left-1/2 z-40 flex -translate-x-1/2 items-center gap-3 rounded-2xl border border-slate-200 bg-white/90 px-4 py-2.5 shadow-2xl backdrop-blur-xl dark:border-white/10 dark:bg-slate-900/90 md:ml-[calc(var(--sg-sidebar,0px)/2)]">
            <span className="flex items-center gap-2 text-[13px] font-medium text-amber-600 dark:text-amber-300"><span className="h-2 w-2 rounded-full bg-amber-400" /> Unsaved changes</span>
            <button type="button" onClick={onDiscard} className="h-9 rounded-xl px-3 text-[13px] font-semibold text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/5">Discard</button>
            <button type="button" onClick={onSave} disabled={saving} className="inline-flex h-9 items-center gap-2 rounded-xl bg-gradient-to-r from-teal-400 to-cyan-400 px-4 text-[13px] font-semibold text-slate-950 shadow-glow hover:brightness-110 disabled:opacity-60">
                {saving && <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-slate-950/30 border-t-slate-950" />}{label}
            </button>
        </motion.div>
    );
}

export const fmt = (n: number) => (n ?? 0).toLocaleString();

export function ago(ms: number) {
    const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 60) return `${s}s ago`;
    if (s < 3600) return `${Math.round(s / 60)}m ago`;
    return `${Math.round(s / 3600)}h ago`;
}
