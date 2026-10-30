import { useEffect, useMemo, useRef, useState } from "react";

/** One time bucket from /analytics/summary (`t` = bucket start, epoch ms). */
export type Bucket = { t: number; [k: string]: number };
export type SeriesDef = { key: string; label: string; color: string };

/** Fills empty buckets with zeros so gaps in traffic show as gaps, not as interpolated lines. */
export function fillBuckets(rows: Bucket[], from: number, to: number, bucketMs: number, keys: string[]): Bucket[] {
    const byT = new Map(rows.map(r => [r.t, r]));
    const out: Bucket[] = [];
    for (let t = Math.floor(from / bucketMs) * bucketMs; t <= to; t += bucketMs) {
        const r = byT.get(t);
        const b: Bucket = { t };
        keys.forEach(k => { b[k] = r && r[k] != null ? Number(r[k]) : 0; });
        b.__empty = r ? 0 : 1;
        out.push(b);
    }
    return out;
}

function useWidth<T extends HTMLElement>() {
    const ref = useRef<T>(null);
    const [w, setW] = useState(600);
    useEffect(() => {
        if (!ref.current) return;
        const ro = new ResizeObserver(e => setW(Math.max(240, Math.floor(e[0].contentRect.width))));
        ro.observe(ref.current);
        return () => ro.disconnect();
    }, []);
    return [ref, w] as const;
}

/** 0, 1, 2, 2.5, 5 × 10^n steps → about 4 gridlines. */
function niceTicks(max: number, count = 4): number[] {
    if (max <= 0) return [0];
    const raw = max / count;
    const mag = Math.pow(10, Math.floor(Math.log10(raw)));
    const step = [1, 2, 2.5, 5, 10].map(m => m * mag).find(s => s >= raw) || raw;
    const ticks: number[] = [];
    for (let v = 0; v <= max + step * 0.001; v += step) ticks.push(v);
    if (ticks[ticks.length - 1] < max) ticks.push(ticks[ticks.length - 1] + step);
    return ticks;
}

const shortNum = (v: number) => v >= 1e6 ? `${+(v / 1e6).toFixed(1)}M` : v >= 1e3 ? `${+(v / 1e3).toFixed(1)}k` : `${+v.toFixed(v < 10 && v % 1 ? 1 : 0)}`;

function timeLabel(t: number, bucketMs: number) {
    const d = new Date(t);
    if (bucketMs >= 3 * 3600_000) return d.toLocaleDateString(undefined, { month: "short", day: "numeric" }) + " " + d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
    return d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", ...(bucketMs < 60_000 ? { second: "2-digit" } : {}) });
}

const PAD = { l: 44, r: 16, t: 10, b: 26 };

function Legend({ series }: { series: SeriesDef[] }) {
    return (
        <ul className="mb-3 flex flex-wrap gap-x-4 gap-y-1 text-[12px] text-slate-600 dark:text-slate-300">
            {series.map(s => (
                <li key={s.key} className="flex items-center gap-1.5">
                    <span className="inline-block h-2.5 w-2.5 rounded-sm" style={{ background: s.color }} aria-hidden />{s.label}
                </li>
            ))}
        </ul>
    );
}

function Tooltip({ x, width, title, rows }: { x: number; width: number; title: string; rows: { label: string; value: string; color?: string }[] }) {
    const left = Math.min(Math.max(x + 12, 0), width - 236);
    return (
        <div className="pointer-events-none absolute top-2 z-10 w-[224px] rounded-lg border border-slate-200 bg-white/95 p-2.5 text-[12px] shadow-lg backdrop-blur dark:border-white/10 dark:bg-[#1c1c1c]/95"
            style={{ left: x + 246 > width ? x - 236 : left }}>
            <p className="mb-1.5 font-semibold text-slate-900 dark:text-white">{title}</p>
            {rows.map(r => (
                <p key={r.label} className="flex items-center justify-between gap-3 text-slate-600 dark:text-slate-300">
                    <span className="flex items-center gap-1.5">{r.color && <span className="inline-block h-2 w-2 rounded-sm" style={{ background: r.color }} />}{r.label}</span>
                    <span className="font-mono tabular-nums text-slate-900 dark:text-white">{r.value}</span>
                </p>
            ))}
        </div>
    );
}

function Axes({ ticks, y, w, h, fmtY, buckets, x, bucketMs }: {
    ticks: number[]; y: (v: number) => number; w: number; h: number; fmtY: (v: number) => string;
    buckets: Bucket[]; x: (i: number) => number; bucketMs: number;
}) {
    const every = Math.max(1, Math.ceil(buckets.length / Math.max(2, Math.floor((w - PAD.l - PAD.r) / 90))));
    return (
        <g className="text-[10.5px]" fill="currentColor">
            {ticks.map(v => (
                <g key={v}>
                    <line x1={PAD.l} x2={w - PAD.r} y1={y(v)} y2={y(v)} className={v === 0 ? "stroke-slate-300 dark:stroke-white/15" : "stroke-slate-200/80 dark:stroke-white/[0.06]"} strokeWidth={1} />
                    <text x={PAD.l - 6} y={y(v) + 3.5} textAnchor="end" className="fill-slate-400 dark:fill-slate-500 tabular-nums">{fmtY(v)}</text>
                </g>
            ))}
            {buckets.map((b, i) => i % every === 0 && (
                <text key={b.t} x={x(i)} y={h - 8} textAnchor="middle" className="fill-slate-400 dark:fill-slate-500">{timeLabel(b.t, bucketMs)}</text>
            ))}
        </g>
    );
}

/** Stacked bars (2 px surface gap between segments), one bar per bucket, with a per-bar tooltip. */
export function StackedBars({ buckets, series, bucketMs, height = 220, unit = "requests" }: {
    buckets: Bucket[]; series: SeriesDef[]; bucketMs: number; height?: number; unit?: string;
}) {
    const [ref, w] = useWidth<HTMLDivElement>();
    const [hover, setHover] = useState<number | null>(null);
    const totals = buckets.map(b => series.reduce((s, d) => s + (b[d.key] || 0), 0));
    const ticks = niceTicks(Math.max(1, ...totals));
    const max = ticks[ticks.length - 1];
    const innerW = w - PAD.l - PAD.r, innerH = height - PAD.t - PAD.b;
    const slot = innerW / Math.max(1, buckets.length);
    const barW = Math.max(2, Math.min(28, slot - Math.max(1, slot * 0.25)));
    const x = (i: number) => PAD.l + slot * i + slot / 2;
    const y = (v: number) => PAD.t + innerH - (v / max) * innerH;
    return (
        <div>
            <Legend series={series} />
            <div ref={ref} className="relative text-slate-500" onMouseLeave={() => setHover(null)}>
                <svg width={w} height={height} role="img" aria-label={`${unit} over time by status`}>
                    <Axes ticks={ticks} y={y} w={w} h={height} fmtY={shortNum} buckets={buckets} x={x} bucketMs={bucketMs} />
                    {buckets.map((b, i) => {
                        let acc = 0;
                        const segs = series.map(s => {
                            const v = b[s.key] || 0;
                            const y0 = y(acc), y1 = y(acc + v);
                            acc += v;
                            return { s, v, top: y1, h: Math.max(0, y0 - y1) };
                        }).filter(g => g.v > 0);
                        return (
                            <g key={b.t} opacity={hover == null || hover === i ? 1 : 0.55}>
                                {segs.map((g, j) => {
                                    const isTop = j === segs.length - 1;
                                    const hh = Math.max(0, g.h - (j > 0 ? 2 : 0));   // 2px gap between stacked segments
                                    return isTop
                                        ? <path key={g.s.key} d={roundedTop(x(i) - barW / 2, g.top, barW, hh, Math.min(4, barW / 2, hh))} fill={g.s.color} />
                                        : <rect key={g.s.key} x={x(i) - barW / 2} y={g.top + (j > 0 ? 0 : 0)} width={barW} height={hh} fill={g.s.color} />;
                                })}
                                <rect x={x(i) - slot / 2} y={PAD.t} width={slot} height={innerH} fill="transparent" onMouseEnter={() => setHover(i)} />
                            </g>
                        );
                    })}
                </svg>
                {hover != null && buckets[hover] && (
                    <Tooltip x={x(hover)} width={w} title={timeLabel(buckets[hover].t, bucketMs)}
                        rows={[...series.map(s => ({ label: s.label, value: (buckets[hover][s.key] || 0).toLocaleString(), color: s.color })),
                            { label: "Total", value: totals[hover].toLocaleString() }]} />
                )}
            </div>
        </div>
    );
}

function roundedTop(x: number, y: number, w: number, h: number, r: number) {
    if (h <= 0) return "";
    r = Math.max(0, Math.min(r, h));
    return `M${x},${y + h} V${y + r} Q${x},${y} ${x + r},${y} H${x + w - r} Q${x + w},${y} ${x + w},${y + r} V${y + h} Z`;
}

/** 2 px lines on one axis, direct-labelled at the right end, crosshair tooltip. Empty buckets break the line. */
export function LineChart({ buckets, series, bucketMs, height = 220, unit = "ms" }: {
    buckets: Bucket[]; series: SeriesDef[]; bucketMs: number; height?: number; unit?: string;
}) {
    const [ref, w] = useWidth<HTMLDivElement>();
    const [hover, setHover] = useState<number | null>(null);
    const labelRoom = 40;
    const vals = buckets.flatMap(b => b.__empty ? [] : series.map(s => b[s.key] || 0));
    const ticks = niceTicks(Math.max(1, ...vals));
    const max = ticks[ticks.length - 1];
    const innerW = w - PAD.l - PAD.r - labelRoom, innerH = height - PAD.t - PAD.b;
    const step = innerW / Math.max(1, buckets.length - 1);
    const x = (i: number) => PAD.l + step * i;
    const y = (v: number) => PAD.t + innerH - (v / max) * innerH;
    const fmtV = (v: number) => `${v >= 100 ? Math.round(v) : +v.toFixed(1)} ${unit}`;
    const paths = useMemo(() => series.map(s => {
        let d = "", pen = false;
        buckets.forEach((b, i) => {
            if (b.__empty) { pen = false; return; }
            d += `${pen ? "L" : "M"}${x(i).toFixed(1)},${y(b[s.key] || 0).toFixed(1)} `;
            pen = true;
        });
        return d;
        // eslint-disable-next-line
    }), [buckets, series, w, max]);
    const lastIdx = (() => { for (let i = buckets.length - 1; i >= 0; i--) if (!buckets[i].__empty) return i; return -1; })();
    return (
        <div>
            <Legend series={series} />
            <div ref={ref} className="relative text-slate-500" onMouseLeave={() => setHover(null)}
                onMouseMove={e => {
                    const r = (e.currentTarget as HTMLDivElement).getBoundingClientRect();
                    const i = Math.round((e.clientX - r.left - PAD.l) / step);
                    setHover(i >= 0 && i < buckets.length ? i : null);
                }}>
                <svg width={w} height={height} role="img" aria-label={`latency percentiles over time`}>
                    <Axes ticks={ticks} y={y} w={w - labelRoom} h={height} fmtY={v => shortNum(v)} buckets={buckets} x={x} bucketMs={bucketMs} />
                    {hover != null && <line x1={x(hover)} x2={x(hover)} y1={PAD.t} y2={PAD.t + innerH} className="stroke-slate-300 dark:stroke-white/20" strokeWidth={1} />}
                    {series.map((s, k) => <path key={s.key} d={paths[k]} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />)}
                    {lastIdx >= 0 && series.map(s => (
                        <text key={s.key} x={x(lastIdx) + 6} y={y(buckets[lastIdx][s.key] || 0) + 3.5} className="fill-slate-600 text-[10.5px] font-semibold dark:fill-slate-300">{s.label}</text>
                    ))}
                    {hover != null && !buckets[hover]?.__empty && series.map(s => (
                        <circle key={s.key} cx={x(hover)} cy={y(buckets[hover][s.key] || 0)} r={4} fill={s.color} className="stroke-white dark:stroke-[#141414]" strokeWidth={2} />
                    ))}
                </svg>
                {hover != null && buckets[hover] && (
                    <Tooltip x={x(hover)} width={w} title={timeLabel(buckets[hover].t, bucketMs)}
                        rows={buckets[hover].__empty ? [{ label: "No traffic", value: "" }] : series.map(s => ({ label: s.label, value: fmtV(buckets[hover][s.key] || 0), color: s.color }))} />
                )}
            </div>
        </div>
    );
}

/** Horizontal magnitude bar for table rows (single sequential hue). */
export function InlineBar({ value, max }: { value: number; max: number }) {
    const pct = max > 0 ? Math.max(2, (value / max) * 100) : 0;
    return (
        <div className="h-1.5 w-full rounded-full bg-slate-200/70 dark:bg-white/[0.06]">
            <div className="h-1.5 rounded-full bg-[#2a78d6] dark:bg-[#3987e5]" style={{ width: `${pct}%` }} />
        </div>
    );
}
