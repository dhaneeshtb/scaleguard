import { useMemo } from "react";

interface Node { label: string; sub?: string }

const W = 560, H = 300, CX = 280, CY = 150;
const LX = 18, RX = W - 18 - 132, NODE_W = 132, NODE_H = 34;

function spread(n: number): number[] {
    if (n <= 1) return [CY];
    const top = 46, bottom = H - 46;
    return Array.from({ length: n }, (_, i) => top + (i * (bottom - top)) / (n - 1));
}

function trim(s: string, n = 16) {
    return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

function toNodes(list: any[] | undefined, fallback: string, max = 4): { nodes: Node[]; extra: number; empty: boolean } {
    const items = (list || []).map(x => ({
        label: String(x?.name || x?.host || x?.id || fallback),
        sub: x?.host ? `${x.host}${x.port ? ":" + x.port : ""}` : undefined,
    }));
    if (items.length === 0) return { nodes: [{ label: fallback }], extra: 0, empty: true };
    return { nodes: items.slice(0, max), extra: Math.max(0, items.length - max), empty: false };
}

/**
 * Live topology: source systems → ScaleGuard → target upstreams.
 * Nodes come from the real configuration; particles animate the request path.
 */
export default function TrafficFlow({ sources, targets, loading }: { sources?: any[]; targets?: any[]; loading?: boolean }) {
    const left = useMemo(() => toNodes(sources, "Incoming traffic"), [sources]);
    const right = useMemo(() => toNodes(targets, "No upstreams yet"), [targets]);
    const ly = spread(left.nodes.length);
    const ry = spread(right.nodes.length);

    const inPaths = ly.map(y => `M ${LX + NODE_W} ${y} C ${LX + NODE_W + 70} ${y}, ${CX - 100} ${CY}, ${CX - 40} ${CY}`);
    const outPaths = ry.map(y => `M ${CX + 40} ${CY} C ${CX + 100} ${CY}, ${RX - 70} ${y}, ${RX} ${y}`);

    return (
        <div className="relative h-full w-full">
            <svg viewBox={`0 0 ${W} ${H}`} className="h-full w-full" role="img" aria-label="Traffic topology from source systems through ScaleGuard to target upstreams">
                <defs>
                    <linearGradient id="sg-in" x1="0" x2="1">
                        <stop offset="0%" stopColor="#22d3ee" stopOpacity="0.15" />
                        <stop offset="100%" stopColor="#2dd4bf" stopOpacity="0.9" />
                    </linearGradient>
                    <linearGradient id="sg-out" x1="0" x2="1">
                        <stop offset="0%" stopColor="#a78bfa" stopOpacity="0.9" />
                        <stop offset="100%" stopColor="#818cf8" stopOpacity="0.15" />
                    </linearGradient>
                    <radialGradient id="sg-core" cx="50%" cy="40%" r="65%">
                        <stop offset="0%" stopColor="#5eead4" />
                        <stop offset="55%" stopColor="#0891b2" />
                        <stop offset="100%" stopColor="#4c1d95" />
                    </radialGradient>
                    <filter id="sg-glow" x="-50%" y="-50%" width="200%" height="200%">
                        <feGaussianBlur stdDeviation="8" result="b" />
                        <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
                    </filter>
                </defs>

                {/* Connections */}
                {inPaths.map((d, i) => (
                    <g key={"in" + i} opacity={left.empty ? 0.35 : 1}>
                        <path d={d} fill="none" stroke="url(#sg-in)" strokeWidth="1.25" />
                        <path d={d} fill="none" stroke="#5eead4" strokeOpacity="0.5" strokeWidth="1.25" className="sg-flow" />
                        {!loading && !left.empty && [0, 1].map(k => (
                            <circle key={k} r="2.6" fill="#99f6e4" filter="url(#sg-glow)">
                                <animateMotion dur={`${2.2 + i * 0.25}s`} begin={`${k * 1.1 + i * 0.3}s`} repeatCount="indefinite" path={d} />
                            </circle>
                        ))}
                    </g>
                ))}
                {outPaths.map((d, i) => (
                    <g key={"out" + i} opacity={right.empty ? 0.3 : 1}>
                        <path d={d} fill="none" stroke="url(#sg-out)" strokeWidth="1.25" />
                        <path d={d} fill="none" stroke="#c4b5fd" strokeOpacity="0.45" strokeWidth="1.25" className="sg-flow" />
                        {!loading && !right.empty && [0, 1].map(k => (
                            <circle key={k} r="2.6" fill="#ddd6fe" filter="url(#sg-glow)">
                                <animateMotion dur={`${2.4 + i * 0.2}s`} begin={`${0.6 + k * 1.2 + i * 0.25}s`} repeatCount="indefinite" path={d} />
                            </circle>
                        ))}
                    </g>
                ))}

                {/* Core */}
                <g>
                    <circle cx={CX} cy={CY} r="62" fill="#2dd4bf" opacity="0.06" />
                    <circle cx={CX} cy={CY} r="52" fill="none" stroke="#5eead4" strokeOpacity="0.35" strokeDasharray="2 6"
                        className="animate-spin-slow" style={{ transformOrigin: `${CX}px ${CY}px` }} />
                    <circle cx={CX} cy={CY} r="44" fill="none" stroke="#a78bfa" strokeOpacity="0.25" />
                    <circle cx={CX} cy={CY} r="34" fill="url(#sg-core)" filter="url(#sg-glow)" />
                    <image href="/lb.svg" x={CX - 16} y={CY - 16} width="32" height="32" />
                    <text x={CX} y={CY + 76} textAnchor="middle" className="fill-slate-700 dark:fill-slate-200" fontSize="11" fontWeight="700" letterSpacing="0.14em">SCALEGUARD</text>
                    <text x={CX} y={CY + 90} textAnchor="middle" className="fill-slate-400" fontSize="9.5">route · balance · secure</text>
                </g>

                {/* Source nodes */}
                {left.nodes.map((n, i) => (
                    <NodeBox key={"l" + i} x={LX} y={ly[i]} node={n} accent="#2dd4bf" dim={left.empty} />
                ))}
                {right.nodes.map((n, i) => (
                    <NodeBox key={"r" + i} x={RX} y={ry[i]} node={n} accent="#a78bfa" dim={right.empty} />
                ))}

                <text x={LX} y={18} className="fill-slate-400" fontSize="9.5" fontWeight="600" letterSpacing="0.16em">
                    SOURCES{left.extra ? `  +${left.extra}` : ""}
                </text>
                <text x={RX + NODE_W} y={18} textAnchor="end" className="fill-slate-400" fontSize="9.5" fontWeight="600" letterSpacing="0.16em">
                    {right.extra ? `+${right.extra}  ` : ""}UPSTREAMS
                </text>
            </svg>
        </div>
    );
}

function NodeBox({ x, y, node, accent, dim }: { x: number; y: number; node: Node; accent: string; dim?: boolean }) {
    return (
        <g transform={`translate(${x}, ${y - NODE_H / 2})`} opacity={dim ? 0.55 : 1}>
            <title>{node.sub ? `${node.label} — ${node.sub}` : node.label}</title>
            <rect width={NODE_W} height={NODE_H} rx="10" className="fill-white/90 stroke-slate-200 dark:fill-slate-900/90 dark:stroke-white/10" strokeWidth="1" strokeDasharray={dim ? "3 3" : undefined} />
            <circle cx="14" cy={NODE_H / 2} r="3.5" fill={accent} />
            <text x="26" y={node.sub ? 14 : NODE_H / 2 + 4} className="fill-slate-800 dark:fill-slate-100" fontSize="11" fontWeight="600">{trim(node.label)}</text>
            {node.sub && <text x="26" y="26" className="fill-slate-400" fontSize="9" fontFamily="JetBrains Mono, monospace">{trim(node.sub, 19)}</text>}
        </g>
    );
}
