import { Link as RouterLink } from 'react-router-dom';
import { IconButton, Link, Badge, Tooltip } from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState, useMemo } from 'react';
import { FaArrowCircleUp, FaArrowCircleDown, FaEdit, FaNetworkWired, FaPlusCircle, FaProjectDiagram, FaSearch, FaServer } from "react-icons/fa";
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';
import InlineTopology from './Topology';

interface HostGroup {
    id: string;
    host: string;
    port: string;
    groupId: string;
    type: string;
    active: boolean;
    reachable: boolean;
    loadFactor: number;
    scheme?: string;
    health?: string;
    weight?: number;
}

export default function HostGroups({ initialData, targets: initialTargets, sources: initialSources }: { initialData?: any[]; targets?: any[]; sources?: any[] }) {
    const [systems, setSystems] = useState<HostGroup[]>(initialData || []);
    const [search, setSearch] = useState('');
    const [expandedFlow, setExpandedFlow] = useState<string | null>(null);
    const { auth } = useAuth() as any;

    const onLoad = () => {
        axios.get(auth.data.host + "/config/hostgroups?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        }).then(r => setSystems(r.data.hostgroups))
            .catch(err => console.error("Failed to load host groups:", err));
    };

    useEffect(() => { if (!initialData) onLoad(); }, []);

    const grouped = useMemo(() => {
        const q = search.toLowerCase().trim();
        const map = new Map<string, HostGroup[]>();
        (systems || []).forEach((hg: HostGroup) => {
            const key = hg.groupId || 'ungrouped';
            // If searching, include if group name or host:port matches
            if (q && !key.toLowerCase().includes(q) && !`${hg.host}:${hg.port}`.toLowerCase().includes(q)) return;
            if (!map.has(key)) map.set(key, []);
            map.get(key)!.push(hg);
        });
        return new Map(Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0])));
    }, [systems, search]);

    const totalHosts = systems.length;
    const reachableCount = systems.filter((s: HostGroup) => s.reachable).length;

    return (
        <div>
            {/* Header */}
            <div className='flex items-center justify-between mb-3'>
                <div className="flex items-center gap-3">
                    <span className="text-xs text-slate-500 dark:text-slate-400">
                        {grouped.size} {grouped.size === 1 ? 'group' : 'groups'} · {totalHosts} {totalHosts === 1 ? 'instance' : 'instances'}
                    </span>
                    {totalHosts > 0 && (
                        <div className="flex items-center gap-1">
                            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
                            <span className="text-[10px] text-emerald-500 font-medium">{reachableCount}/{totalHosts} reachable</span>
                        </div>
                    )}
                </div>
                <div className="flex items-center gap-1">
                    <div className="relative">
                        <FaSearch className="absolute left-2 top-1/2 -translate-y-1/2 text-slate-400 text-[10px]" />
                        <input
                            type="text"
                            placeholder="Search groups or hosts..."
                            value={search}
                            onChange={e => setSearch(e.target.value)}
                            className="pl-6 pr-2 py-1 text-xs rounded-lg border border-slate-200/50 dark:border-slate-600/50 bg-white/60 dark:bg-slate-800/60 text-slate-700 dark:text-slate-200 placeholder-slate-400 focus:outline-none focus:ring-1 focus:ring-teal-500/40 w-48 transition-all"
                        />
                    </div>
                    <Link as={RouterLink} to='/managehost/hostgroups/new'>
                        <IconButton aria-label='Add Host Group' icon={<FaPlusCircle />} colorScheme='teal' size="xs" variant="ghost" title="Add Host Group" />
                    </Link>
                </div>
            </div>

            {/* Group Cards Grid */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {Array.from(grouped.entries()).map(([groupId, instances]) => {
                    const up = instances.filter(h => h.reachable).length;
                    const allOk = up === instances.length;
                    const noneOk = up === 0;
                    const statusColor = allOk ? 'emerald' : noneOk ? 'red' : 'amber';

                    return (
                        <div key={groupId}
                            className="rounded-xl border border-slate-200/30 dark:border-slate-700/30 bg-white/50 dark:bg-slate-800/40 overflow-hidden">

                            {/* Card Header */}
                            <div className={`flex items-center justify-between px-3.5 py-2 border-b border-slate-100/40 dark:border-slate-700/30 bg-gradient-to-r ${
                                allOk ? 'from-emerald-500/5 to-teal-500/5' 
                                : noneOk ? 'from-red-500/5 to-orange-500/5' 
                                : 'from-amber-500/5 to-yellow-500/5'
                            }`}>
                                <div className="flex items-center gap-2">
                                    <FaServer className={`text-${statusColor}-500 text-xs`} />
                                    <span className="font-semibold text-sm text-slate-800 dark:text-white">{groupId}</span>
                                </div>
                                <div className="flex items-center gap-2">
                                    <Badge
                                        colorScheme={allOk ? 'green' : noneOk ? 'red' : 'yellow'}
                                        fontSize="9px" px={2} py={0} borderRadius="full" variant="subtle"
                                    >
                                        {up}/{instances.length} up
                                    </Badge>
                                    <Tooltip label="Route Flow" hasArrow>
                                        <IconButton
                                            aria-label='Route flow'
                                            icon={<FaProjectDiagram />}
                                            variant={expandedFlow === groupId ? 'solid' : 'ghost'}
                                            size="xs"
                                            colorScheme='teal'
                                            onClick={() => setExpandedFlow(expandedFlow === groupId ? null : groupId)}
                                        />
                                    </Tooltip>
                                </div>
                            </div>

                            {/* Instance List */}
                            <div className="px-2 py-1.5 space-y-1">
                                {instances.map((s: HostGroup) => (
                                    <div key={s.id}
                                        className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg transition-colors group ${
                                            s.reachable
                                                ? 'bg-slate-50/60 dark:bg-slate-700/20 hover:bg-slate-100/80 dark:hover:bg-slate-700/40'
                                                : 'bg-red-50/40 dark:bg-red-900/10 hover:bg-red-50/70 dark:hover:bg-red-900/20'
                                        }`}>
                                        {/* Left: status + host */}
                                        <div className="flex items-center gap-2 min-w-0">
                                            {s.reachable
                                                ? <FaArrowCircleUp className="text-emerald-500 text-xs flex-shrink-0" />
                                                : <FaArrowCircleDown className="text-red-400 text-xs flex-shrink-0" />
                                            }
                                            <div className="min-w-0">
                                                <span className="font-mono text-xs font-medium text-slate-700 dark:text-slate-200 truncate block">
                                                    {s.host}:{s.port}
                                                </span>
                                            </div>
                                        </div>

                                        {/* Right: badges + actions */}
                                        <div className="flex items-center gap-1.5 flex-shrink-0 ml-2">
                                            {s.type && (
                                                <Badge
                                                    colorScheme={s.type?.toLowerCase() === 'active' ? 'blue' : 'orange'}
                                                    fontSize="8px" px={1.5} borderRadius="full" textTransform="capitalize"
                                                    variant="subtle"
                                                >
                                                    {s.type}
                                                </Badge>
                                            )}
                                            {(s.weight ?? 1) > 1 && (
                                                <Tooltip label={`Weight: ${s.weight}`} hasArrow>
                                                    <Badge fontSize="8px" px={1.5} borderRadius="full" colorScheme="purple" variant="subtle">
                                                        w{s.weight}
                                                    </Badge>
                                                </Tooltip>
                                            )}
                                            <div className='flex opacity-0 group-hover:opacity-100 transition-opacity'>
                                                <Link as={RouterLink} to={`/managehost/hostgroups/${s.id}`}>
                                                    <Tooltip label="Edit" hasArrow>
                                                        <IconButton aria-label='Edit' icon={<FaEdit />} variant="ghost" size="xs" colorScheme='blue' minW="5" h="5" />
                                                    </Tooltip>
                                                </Link>
                                                <DeleteSystem source={"hostgroups"} id={s.id} onUpdate={onLoad} />
                                            </div>
                                        </div>
                                    </div>
                                ))}
                            </div>

                            {/* Inline Topology */}
                            {expandedFlow === groupId && (
                                <div className="px-2 pb-2">
                                    <InlineTopology
                                        context={{ type: 'hostgroup', groupId }}
                                        sources={initialSources || []}
                                        targets={initialTargets || []}
                                        hostGroups={systems}
                                    />
                                </div>
                            )}
                        </div>
                    );
                })}
            </div>

            {/* Empty State */}
            {(!systems || systems.length === 0) && (
                <div className="rounded-xl border border-dashed border-slate-300/40 dark:border-slate-600/40 text-center py-10">
                    <FaNetworkWired className="mx-auto text-2xl text-slate-300 dark:text-slate-600 mb-2" />
                    <p className="text-sm text-slate-400 mb-1">No host groups yet</p>
                    <Link as={RouterLink} to='/managehost/hostgroups/new'>
                        <span className="text-sm text-teal-500 hover:text-teal-400 cursor-pointer font-medium">+ Add your first host group</span>
                    </Link>
                </div>
            )}
        </div>
    );
}