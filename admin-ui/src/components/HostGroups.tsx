import { IconButton, Link, Badge, Tooltip } from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState, useMemo } from 'react';
import { FaArrowCircleDown, FaArrowCircleUp, FaChevronDown, FaChevronRight, FaEdit, FaNetworkWired, FaPlusCircle } from "react-icons/fa";
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';

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

export default function HostGroups({ initialData }: { initialData?: any[] }) {
    const [systems, setSystems] = useState<HostGroup[]>(initialData || []);
    const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());
    const { auth } = useAuth() as any;

    const onLoad = () => {
        axios.get(auth.data.host + "/config/hostgroups?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        }).then(r => setSystems(r.data.hostgroups))
            .catch(err => console.error("Failed to load host groups:", err));
    };

    useEffect(() => { if (!initialData) onLoad(); }, []);

    const grouped = useMemo(() => {
        const map = new Map<string, HostGroup[]>();
        (systems || []).forEach((hg: HostGroup) => {
            const key = hg.groupId || 'ungrouped';
            if (!map.has(key)) map.set(key, []);
            map.get(key)!.push(hg);
        });
        return new Map(Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0])));
    }, [systems]);

    const toggleGroup = (groupId: string) => {
        setCollapsedGroups(prev => {
            const next = new Set(prev);
            if (next.has(groupId)) next.delete(groupId);
            else next.add(groupId);
            return next;
        });
    };

    const totalHosts = systems.length;
    const reachableCount = systems.filter((s: HostGroup) => s.reachable).length;

    return (
        <div className="space-y-1.5">
            {/* Header */}
            <div className='flex items-center justify-between mb-2'>
                <div className="flex items-center gap-2">
                    <span className="text-[10px] text-slate-400">{grouped.size} groups · {totalHosts} instances</span>
                    {totalHosts > 0 && (
                        <span className="text-[10px] text-emerald-500">● {reachableCount}/{totalHosts}</span>
                    )}
                </div>
                <Link href='/managehost/hostgroups/new'>
                    <IconButton aria-label='Add' icon={<FaPlusCircle />} colorScheme='teal' size={"xs"} variant="ghost" />
                </Link>
            </div>

            {/* Groups */}
            {Array.from(grouped.entries()).map(([groupId, instances]) => {
                const isCollapsed = collapsedGroups.has(groupId);
                const groupReachable = instances.filter(h => h.reachable).length;
                const allOk = groupReachable === instances.length;
                const noneOk = groupReachable === 0;

                return (
                    <div key={groupId} className="rounded-lg border border-slate-200/30 dark:border-slate-700/30 overflow-hidden">
                        {/* Group Header */}
                        <button
                            onClick={() => toggleGroup(groupId)}
                            className="w-full flex items-center justify-between px-3 py-1.5 bg-slate-50/60 dark:bg-slate-800/60 hover:bg-slate-100/60 dark:hover:bg-slate-700/30 transition-colors"
                        >
                            <div className="flex items-center gap-2">
                                {isCollapsed
                                    ? <FaChevronRight className="text-slate-400 text-[8px]" />
                                    : <FaChevronDown className="text-slate-400 text-[8px]" />
                                }
                                <span className={`w-1.5 h-1.5 rounded-full ${allOk ? 'bg-emerald-400' : noneOk ? 'bg-red-400' : 'bg-amber-400'}`}></span>
                                <span className="font-semibold text-xs text-slate-700 dark:text-slate-200">{groupId}</span>
                                <span className="text-[10px] text-slate-400">{instances.length}</span>
                            </div>
                            <span className={`text-[10px] font-medium ${allOk ? 'text-emerald-500' : noneOk ? 'text-red-400' : 'text-amber-500'}`}>
                                {groupReachable}/{instances.length}
                            </span>
                        </button>

                        {/* Instances */}
                        {!isCollapsed && (
                            <div className="divide-y divide-slate-100/40 dark:divide-slate-700/20">
                                {instances.map((s: HostGroup) => (
                                    <div key={s.id} className="flex items-center px-3 py-1 hover:bg-slate-50/40 dark:hover:bg-slate-700/10 transition-colors group text-[11px]">
                                        {/* Status dot + host */}
                                        <div className="flex items-center gap-1.5 flex-1 min-w-0">
                                            {s.reachable
                                                ? <FaArrowCircleUp className="text-emerald-500 text-[9px] flex-shrink-0" />
                                                : <FaArrowCircleDown className="text-red-400 text-[9px] flex-shrink-0" />
                                            }
                                            <span className="font-mono text-slate-700 dark:text-slate-300 truncate">
                                                {s.host}:{s.port}
                                            </span>
                                        </div>

                                        {/* Badges row */}
                                        <div className="flex items-center gap-1.5 flex-shrink-0">
                                            {s.type && (
                                                <Badge colorScheme={s.type?.toLowerCase() === 'active' ? 'blue' : 'gray'}
                                                    fontSize="8px" px={1.5} py={0} borderRadius="full" textTransform="uppercase">
                                                    {s.type}
                                                </Badge>
                                            )}
                                            <Badge colorScheme={s.reachable ? 'green' : 'red'}
                                                fontSize="8px" px={1.5} py={0} borderRadius="full">
                                                {s.reachable ? 'ON' : 'OFF'}
                                            </Badge>
                                            <span className="text-[9px] text-slate-400 font-mono w-6 text-right">w{s.weight ?? 1}</span>
                                            <span className="text-[9px] text-slate-400 font-mono w-8 text-right">{s.loadFactor}</span>

                                            {/* Actions — visible on hover */}
                                            <div className='flex gap-0 opacity-0 group-hover:opacity-100 transition-opacity ml-1'>
                                                <Link href={`/managehost/hostgroups/${s.id}`}>
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
                        )}
                    </div>
                );
            })}

            {/* Empty */}
            {(!systems || systems.length === 0) && (
                <div className="text-center py-8">
                    <FaNetworkWired className="mx-auto text-2xl text-slate-300 dark:text-slate-600 mb-2" />
                    <p className="text-xs text-slate-400">No host groups configured</p>
                    <Link href='/managehost/hostgroups/new'>
                        <span className="text-xs text-teal-500 hover:underline cursor-pointer">+ Add one</span>
                    </Link>
                </div>
            )}
        </div>
    );
}