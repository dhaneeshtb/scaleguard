import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { FiCornerDownLeft, FiSearch } from "react-icons/fi";
import type { IconType } from "react-icons";

export interface Command {
    id: string;
    label: string;
    hint?: string;
    group: string;
    icon: IconType;
    run: () => void;
}

export default function CommandPalette({ open, onClose, commands }: { open: boolean; onClose: () => void; commands: Command[] }) {
    const [query, setQuery] = useState("");
    const [active, setActive] = useState(0);
    const inputRef = useRef<HTMLInputElement>(null);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return commands;
        return commands.filter(c => (c.label + " " + (c.hint || "") + " " + c.group).toLowerCase().includes(q));
    }, [query, commands]);

    useEffect(() => {
        if (open) {
            setQuery("");
            setActive(0);
            setTimeout(() => inputRef.current?.focus(), 10);
        }
    }, [open]);

    useEffect(() => { setActive(0); }, [query]);

    const execute = (c?: Command) => {
        if (!c) return;
        onClose();
        c.run();
    };

    const onKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === "ArrowDown") { e.preventDefault(); setActive(a => Math.min(a + 1, filtered.length - 1)); }
        else if (e.key === "ArrowUp") { e.preventDefault(); setActive(a => Math.max(a - 1, 0)); }
        else if (e.key === "Enter") { e.preventDefault(); execute(filtered[active]); }
        else if (e.key === "Escape") { e.preventDefault(); onClose(); }
    };

    let lastGroup = "";

    return (
        <AnimatePresence>
            {open && (
                <motion.div
                    className="fixed inset-0 z-[200] flex items-start justify-center px-4 pt-[14vh]"
                    initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
                    transition={{ duration: 0.15 }}
                >
                    <div className="absolute inset-0 bg-slate-950/60 backdrop-blur-sm" onClick={onClose} />
                    <motion.div
                        role="dialog"
                        aria-label="Command palette"
                        className="relative w-full max-w-xl overflow-hidden rounded-2xl border border-slate-200 bg-white/95 shadow-2xl backdrop-blur-xl dark:border-white/10 dark:bg-slate-900/95"
                        initial={{ y: -12, scale: 0.98 }} animate={{ y: 0, scale: 1 }} exit={{ y: -8, scale: 0.98 }}
                        transition={{ type: "spring", stiffness: 420, damping: 32 }}
                        onKeyDown={onKeyDown}
                    >
                        <div className="flex items-center gap-3 border-b border-slate-200 px-4 dark:border-white/[0.07]">
                            <FiSearch className="text-slate-400" />
                            <input
                                ref={inputRef}
                                value={query}
                                onChange={e => setQuery(e.target.value)}
                                placeholder="Jump to a page or run an action…"
                                className="h-14 w-full bg-transparent text-[15px] text-slate-900 outline-none placeholder:text-slate-400 dark:text-white"
                            />
                            <span className="sg-kbd">esc</span>
                        </div>
                        <div className="max-h-[360px] overflow-y-auto p-2">
                            {filtered.length === 0 && (
                                <p className="px-3 py-8 text-center text-sm text-slate-500">No matches for “{query}”</p>
                            )}
                            {filtered.map((c, i) => {
                                const header = c.group !== lastGroup ? c.group : null;
                                lastGroup = c.group;
                                const Icon = c.icon;
                                const isActive = i === active;
                                return (
                                    <div key={c.id}>
                                        {header && <p className="sg-eyebrow px-3 pb-1 pt-3">{header}</p>}
                                        <button
                                            onMouseEnter={() => setActive(i)}
                                            onClick={() => execute(c)}
                                            className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm transition-colors ${isActive
                                                ? "bg-teal-500/10 text-slate-900 dark:text-white"
                                                : "text-slate-600 dark:text-slate-300"}`}
                                        >
                                            <span className={`flex h-7 w-7 items-center justify-center rounded-lg border ${isActive
                                                ? "border-teal-400/40 bg-teal-400/10 text-teal-500 dark:text-teal-300"
                                                : "border-slate-200 text-slate-400 dark:border-white/[0.07]"}`}>
                                                <Icon className="text-[13px]" />
                                            </span>
                                            <span className="flex-1 font-medium">{c.label}</span>
                                            {c.hint && <span className="text-xs text-slate-400">{c.hint}</span>}
                                            {isActive && <FiCornerDownLeft className="text-slate-400" />}
                                        </button>
                                    </div>
                                );
                            })}
                        </div>
                        <div className="flex items-center gap-4 border-t border-slate-200 px-4 py-2.5 text-[11px] text-slate-400 dark:border-white/[0.07]">
                            <span className="flex items-center gap-1.5"><span className="sg-kbd">↑</span><span className="sg-kbd">↓</span> navigate</span>
                            <span className="flex items-center gap-1.5"><span className="sg-kbd">↵</span> open</span>
                        </div>
                    </motion.div>
                </motion.div>
            )}
        </AnimatePresence>
    );
}
