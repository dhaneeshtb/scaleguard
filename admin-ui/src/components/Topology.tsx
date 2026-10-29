import React, { useMemo } from 'react';
import ReactFlow, {
    Node,
    Edge,
    Background,
    MarkerType,
    Position,
    Handle,
} from 'reactflow';
import 'reactflow/dist/style.css';
import { FaServer, FaPlug, FaNetworkWired } from 'react-icons/fa';

/* ─── Uniform Node Components with Handles ─── */

function SourceNode({ data }: { data: any }) {
    const hl = data._highlight;
    return (
        <div className={`w-[200px] min-h-[72px] px-4 py-3 rounded-xl border-2 shadow-md flex flex-col justify-center relative ${hl ? 'border-blue-500 ring-2 ring-blue-400/30 bg-blue-50 dark:bg-blue-900/40' : 'border-blue-300 dark:border-blue-600 bg-white dark:bg-slate-800'}`}>
            <Handle type="target" position={Position.Left} style={{ background: '#3b82f6', width: 8, height: 8, border: '2px solid white' }} />
            <Handle type="source" position={Position.Right} style={{ background: '#3b82f6', width: 8, height: 8, border: '2px solid white' }} />
            <div className="flex items-center gap-2 mb-1">
                <div className="w-6 h-6 rounded-md bg-blue-500 flex items-center justify-center flex-shrink-0">
                    <FaServer className="text-white text-[10px]" />
                </div>
                <span className="text-[9px] uppercase tracking-wider font-bold text-blue-500">Source</span>
            </div>
            <p className="font-bold text-sm text-slate-900 dark:text-white truncate">{data.name || data.host}</p>
            <p className="font-mono text-[10px] text-slate-500 dark:text-slate-400 truncate">{data.scheme}://{data.host}:{data.port}</p>
        </div>
    );
}

function TargetNode({ data }: { data: any }) {
    const hl = data._highlight;
    return (
        <div className={`w-[200px] min-h-[72px] px-4 py-3 rounded-xl border-2 shadow-md flex flex-col justify-center relative ${hl ? 'border-violet-500 ring-2 ring-violet-400/30 bg-violet-50 dark:bg-violet-900/40' : 'border-violet-300 dark:border-violet-600 bg-white dark:bg-slate-800'}`}>
            <Handle type="target" position={Position.Left} style={{ background: '#8b5cf6', width: 8, height: 8, border: '2px solid white' }} />
            <Handle type="source" position={Position.Right} style={{ background: '#8b5cf6', width: 8, height: 8, border: '2px solid white' }} />
            <div className="flex items-center gap-2 mb-1">
                <div className="w-6 h-6 rounded-md bg-violet-500 flex items-center justify-center flex-shrink-0">
                    <FaPlug className="text-white text-[10px]" />
                </div>
                <span className="text-[9px] uppercase tracking-wider font-bold text-violet-500">Target</span>
            </div>
            <p className="font-bold text-sm text-slate-900 dark:text-white truncate">{data.name || data.groupId}</p>
            <p className="font-mono text-[10px] text-slate-500 dark:text-slate-400 truncate">{data.groupId}</p>
        </div>
    );
}

function HostNode({ data }: { data: any }) {
    const hl = data._highlight;
    const online = data.reachable;
    return (
        <div className={`w-[200px] min-h-[72px] px-4 py-3 rounded-xl border-2 shadow-md flex flex-col justify-center relative ${hl
            ? (online ? 'border-emerald-500 ring-2 ring-emerald-400/30 bg-emerald-50 dark:bg-emerald-900/40' : 'border-red-500 ring-2 ring-red-400/30 bg-red-50 dark:bg-red-900/40')
            : (online ? 'border-emerald-300 dark:border-emerald-600 bg-white dark:bg-slate-800' : 'border-red-300 dark:border-red-600 bg-white dark:bg-slate-800')
        }`}>
            <Handle type="target" position={Position.Left} style={{ background: online ? '#10b981' : '#ef4444', width: 8, height: 8, border: '2px solid white' }} />
            <Handle type="source" position={Position.Right} style={{ background: online ? '#10b981' : '#ef4444', width: 8, height: 8, border: '2px solid white' }} />
            <div className="flex items-center gap-2 mb-1">
                <div className={`w-6 h-6 rounded-md ${online ? 'bg-emerald-500' : 'bg-red-500'} flex items-center justify-center flex-shrink-0`}>
                    <FaNetworkWired className="text-white text-[10px]" />
                </div>
                <span className={`text-[9px] uppercase tracking-wider font-bold ${online ? 'text-emerald-500' : 'text-red-500'}`}>
                    Host {online ? '● ON' : '● OFF'}
                </span>
            </div>
            <p className="font-bold text-sm text-slate-900 dark:text-white truncate">{data.host}</p>
            <p className="font-mono text-[10px] text-slate-500 dark:text-slate-400 truncate">{data.scheme || 'http'}://{data.host}:{data.port}</p>
        </div>
    );
}

const nodeTypes = { source: SourceNode, target: TargetNode, host: HostNode };

/* ─── Helpers ─── */

function resolveTarget(src: any, targets: any[]): any | null {
    if (!src.target) return null;
    return targets.find(t => t.groupId === src.target || t.id === src.target) || null;
}

function getHostsForTarget(tgt: any, hostGroups: any[]): any[] {
    const hgId = tgt.hostGroupId || tgt.groupId;
    return hostGroups.filter(hg => hg.groupId === hgId);
}

function makeSrcTgtEdge(srcId: string, tgtId: string): Edge {
    return {
        id: `e-${srcId}-${tgtId}`,
        source: srcId,
        target: tgtId,
        type: 'smoothstep',
        animated: true,
        style: { stroke: '#7c3aed', strokeWidth: 3 },
        markerEnd: { type: MarkerType.ArrowClosed, color: '#7c3aed', width: 20, height: 20 },
    };
}

function makeTgtHostEdge(tgtId: string, hostId: string, online: boolean): Edge {
    return {
        id: `e-${tgtId}-${hostId}`,
        source: tgtId,
        target: hostId,
        type: 'smoothstep',
        animated: online,
        style: {
            stroke: online ? '#10b981' : '#ef4444',
            strokeWidth: online ? 3 : 2,
            strokeDasharray: online ? undefined : '8 4',
        },
        markerEnd: { type: MarkerType.ArrowClosed, color: online ? '#10b981' : '#ef4444', width: 20, height: 20 },
    };
}

/* ─── Context Types ─── */
type FlowContext =
    | { type: 'source'; source: any }
    | { type: 'target'; target: any }
    | { type: 'hostgroup'; groupId: string };

interface InlineTopologyProps {
    context: FlowContext;
    sources: any[];
    targets: any[];
    hostGroups: any[];
}

export default function InlineTopology({ context, sources, targets, hostGroups }: InlineTopologyProps) {
    const { nodes, edges } = useMemo(() => {
        const nodes: Node[] = [];
        const edges: Edge[] = [];

        const COL_1 = 0;
        const COL_2 = 280;
        const COL_3 = 560;
        const ROW_GAP = 100;

        if (context.type === 'source') {
            const src = context.source;
            const tgt = resolveTarget(src, targets);
            const hosts = tgt ? getHostsForTarget(tgt, hostGroups) : [];
            const rows = Math.max(hosts.length, 1);
            const mid = (rows - 1) * ROW_GAP / 2;

            nodes.push({ id: 'src', type: 'source', position: { x: COL_1, y: mid }, data: { ...src, _highlight: true } });

            if (tgt) {
                nodes.push({ id: 'tgt', type: 'target', position: { x: COL_2, y: mid }, data: tgt });
                edges.push(makeSrcTgtEdge('src', 'tgt'));

                hosts.forEach((hg, i) => {
                    const hId = `h-${hg.id}`;
                    nodes.push({ id: hId, type: 'host', position: { x: COL_3, y: i * ROW_GAP }, data: hg });
                    edges.push(makeTgtHostEdge('tgt', hId, hg.reachable));
                });
            }

        } else if (context.type === 'target') {
            const tgt = context.target;
            const hosts = getHostsForTarget(tgt, hostGroups);
            const connSrcs = sources.filter(s => s.target === tgt.groupId || s.target === tgt.id);
            const rows = Math.max(hosts.length, connSrcs.length, 1);
            const mid = (rows - 1) * ROW_GAP / 2;

            connSrcs.forEach((s, i) => {
                const sId = `s-${s.id}`;
                const y = connSrcs.length === 1 ? mid : i * ROW_GAP;
                nodes.push({ id: sId, type: 'source', position: { x: COL_1, y }, data: s });
                edges.push(makeSrcTgtEdge(sId, 'tgt'));
            });

            nodes.push({ id: 'tgt', type: 'target', position: { x: COL_2, y: mid }, data: { ...tgt, _highlight: true } });

            hosts.forEach((hg, i) => {
                const hId = `h-${hg.id}`;
                nodes.push({ id: hId, type: 'host', position: { x: COL_3, y: i * ROW_GAP }, data: hg });
                edges.push(makeTgtHostEdge('tgt', hId, hg.reachable));
            });

        } else if (context.type === 'hostgroup') {
            const hgGroupId = context.groupId;
            const hosts = hostGroups.filter(hg => hg.groupId === hgGroupId);
            const connTgts = targets.filter(t => (t.hostGroupId || t.groupId) === hgGroupId);
            const connSrcs = sources.filter(s => connTgts.some(t => s.target === t.groupId || s.target === t.id));
            const rows = Math.max(hosts.length, connSrcs.length, connTgts.length, 1);
            const mid = (rows - 1) * ROW_GAP / 2;

            connSrcs.forEach((s, i) => {
                const sId = `s-${s.id}`;
                const y = connSrcs.length === 1 ? mid : i * ROW_GAP;
                nodes.push({ id: sId, type: 'source', position: { x: COL_1, y }, data: s });
            });

            connTgts.forEach((tgt, i) => {
                const tId = `t-${tgt.id}`;
                const y = connTgts.length === 1 ? mid : i * ROW_GAP;
                nodes.push({ id: tId, type: 'target', position: { x: COL_2, y }, data: tgt });

                connSrcs.forEach(s => {
                    if (s.target === tgt.groupId || s.target === tgt.id) {
                        edges.push(makeSrcTgtEdge(`s-${s.id}`, tId));
                    }
                });

                hosts.forEach(hg => {
                    edges.push(makeTgtHostEdge(tId, `h-${hg.id}`, hg.reachable));
                });
            });

            hosts.forEach((hg, i) => {
                const hId = `h-${hg.id}`;
                nodes.push({ id: hId, type: 'host', position: { x: COL_3, y: i * ROW_GAP }, data: { ...hg, _highlight: true } });
            });
        }

        return { nodes, edges };
    }, [context, sources, targets, hostGroups]);

    if (nodes.length === 0) {
        return <div className="text-center py-4 text-xs text-slate-400">No routing chain found</div>;
    }

    const hostCount = nodes.filter(n => n.type === 'host').length;
    const srcCount = nodes.filter(n => n.type === 'source').length;
    const h = Math.max(Math.max(hostCount, srcCount) * 100 + 60, 180);

    return (
        <div className="rounded-xl border border-slate-200/40 dark:border-slate-700/40 overflow-hidden bg-slate-50/50 dark:bg-slate-900/20" style={{ height: Math.min(h, 360) }}>
            <ReactFlow
                nodes={nodes}
                edges={edges}
                nodeTypes={nodeTypes}
                fitView
                fitViewOptions={{ padding: 0.2 }}
                minZoom={0.3}
                maxZoom={1.3}
                panOnDrag={true}
                zoomOnScroll={false}
                preventScrolling={false}
                nodesDraggable={false}
                nodesConnectable={false}
                elementsSelectable={false}
                proOptions={{ hideAttribution: true }}
            >
                <Background color="#cbd5e1" gap={20} size={0.8} />
            </ReactFlow>
        </div>
    );
}
