import { Button, IconButton, Link, Badge, Tooltip } from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState, useMemo } from 'react';
import { FaArrowCircleDown, FaArrowCircleUp, FaChevronDown, FaChevronRight, FaEdit, FaNetworkWired, FaPlusCircle, FaServer } from "react-icons/fa";
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

    // Group instances by groupId
    const grouped = useMemo(() => {
        const map = new Map<string, HostGroup[]>();
        (systems || []).forEach((hg: HostGroup) => {
            const key = hg.groupId || 'ungrouped';
            if (!map.has(key)) map.set(key, []);
            map.get(key)!.push(hg);
        });
        // Sort groups alphabetically
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
    const groupCount = grouped.size;

    return (
        <div className="space-y-4">
            {/* Header */}
            <div className='flex items-center justify-between'>
                <div className="flex items-center gap-3">
                    <p className="text-xs text-slate-500 dark:text-slate-400">
                        {groupCount} {groupCount === 1 ? 'group' : 'groups'} · {totalHosts} {totalHosts === 1 ? 'instance' : 'instances'}
                    </p>
                    {totalHosts > 0 && (
                        <div className="flex items-center gap-1.5">
                            <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
                            <span className="text-[10px] font-medium text-emerald-500">
                                {reachableCount}/{totalHosts} reachable
                            </span>
                        </div>
                    )}
                </div>
                <Link href='/managehost/hostgroups/new'>
                    <Button leftIcon={<FaPlusCircle />} colorScheme='teal' size={"xs"} rounded="full" variant="solid">
                        Add Host Group
                    </Button>
                </Link>
            </div>

            {/* Grouped Host Groups */}
            <div className="space-y-3">
                {Array.from(grouped.entries()).map(([groupId, instances]) => {
                    const isCollapsed = collapsedGroups.has(groupId);
                    const groupReachable = instances.filter(h => h.reachable).length;
                    const allReachable = groupReachable === instances.length;
                    const noneReachable = groupReachable === 0;

                    return (
                        <div key={groupId}
                            className="rounded-xl border border-slate-200/40 dark:border-slate-700/40 overflow-hidden transition-all">

                            {/* Group Header — clickable to expand/collapse */}
                            <button
                                onClick={() => toggleGroup(groupId)}
                                className="w-full flex items-center justify-between px-5 py-3 bg-slate-50/80 dark:bg-slate-800/80 hover:bg-slate-100/80 dark:hover:bg-slate-700/40 transition-colors cursor-pointer"
                            >
                                <div className="flex items-center gap-3">
                                    {isCollapsed
                                        ? <FaChevronRight className="text-slate-400 text-[10px]" />
                                        : <FaChevronDown className="text-slate-400 text-[10px]" />
                                    }
                                    <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${allReachable
                                        ? 'bg-gradient-to-br from-emerald-500/20 to-teal-500/20'
                                        : noneReachable
                                            ? 'bg-gradient-to-br from-red-500/20 to-orange-500/20'
                                            : 'bg-gradient-to-br from-amber-500/20 to-yellow-500/20'
                                        }`}>
                                        <FaServer className={`text-xs ${allReachable ? 'text-emerald-500' : noneReachable ? 'text-red-400' : 'text-amber-500'
                                            }`} />
                                    </div>
                                    <div className="text-left">
                                        <p className="font-semibold text-sm text-slate-800 dark:text-white">
                                            {groupId}
                                        </p>
                                        <p className="text-[10px] text-slate-400">
                                            {instances.length} {instances.length === 1 ? 'instance' : 'instances'}
                                        </p>
                                    </div>
                                </div>
                                <div className="flex items-center gap-2">
                                    <div className="flex items-center gap-1.5">
                                        <span className={`w-2 h-2 rounded-full ${allReachable ? 'bg-emerald-400 animate-pulse'
                                            : noneReachable ? 'bg-red-400' : 'bg-amber-400 animate-pulse'
                                            }`}></span>
                                        <span className={`text-[10px] font-semibold ${allReachable ? 'text-emerald-500'
                                            : noneReachable ? 'text-red-400' : 'text-amber-500'
                                            }`}>
                                            {groupReachable}/{instances.length}
                                        </span>
                                    </div>
                                </div>
                            </button>

                            {/* Instance Rows */}
                            {!isCollapsed && (
                                <table className="min-w-full text-left text-sm">
                                    <thead>
                                        <tr className="text-[10px] uppercase tracking-wider text-slate-400 dark:text-slate-500 border-b border-slate-100 dark:border-slate-700/40">
                                            <th className="px-5 py-2 font-medium">Host</th>
                                            <th className="px-5 py-2 font-medium text-center">Type</th>
                                            <th className="px-5 py-2 font-medium text-center">Status</th>
                                            <th className="px-5 py-2 font-medium text-center">Network</th>
                                            <th className="px-5 py-2 font-medium text-center">Weight</th>
                                            <th className="px-5 py-2 font-medium text-center">Load</th>
                                            <th className="px-5 py-2 font-medium text-right">Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100/60 dark:divide-slate-700/30">
                                        {instances.map((system: HostGroup) => (
                                            <tr key={system.id}
                                                className="text-slate-700 dark:text-slate-300 hover:bg-slate-50/50 dark:hover:bg-slate-700/20 transition-colors group">
                                                {/* Host */}
                                                <td className="px-5 py-2.5">
                                                    <div className="flex items-center gap-2">
                                                        <div className={`w-6 h-6 rounded-md flex items-center justify-center flex-shrink-0 ${system.reachable
                                                            ? 'bg-emerald-500/10'
                                                            : 'bg-red-500/10'
                                                            }`}>
                                                            {system.reachable
                                                                ? <FaArrowCircleUp className="text-emerald-500 text-[10px]" />
                                                                : <FaArrowCircleDown className="text-red-400 text-[10px]" />
                                                            }
                                                        </div>
                                                        <div>
                                                            <p className="font-semibold text-xs text-slate-800 dark:text-white font-mono">
                                                                {system.scheme ? `${system.scheme}://` : ''}{system.host}:{system.port}
                                                            </p>
                                                            {system.health && (
                                                                <p className="text-[9px] text-slate-400 font-mono truncate max-w-[200px]">
                                                                    health: {system.health}
                                                                </p>
                                                            )}
                                                        </div>
                                                    </div>
                                                </td>

                                                {/* Type */}
                                                <td className="px-5 py-2.5 text-center">
                                                    <Badge
                                                        colorScheme={system.type === 'active' || system.type === 'Active' ? 'blue' : 'gray'}
                                                        fontSize="9px" px={2} py={0.5} borderRadius="full" textTransform="uppercase" fontWeight="bold"
                                                    >
                                                        {system.type || 'N/A'}
                                                    </Badge>
                                                </td>

                                                {/* Active Status */}
                                                <td className="px-5 py-2.5 text-center">
                                                    <Badge
                                                        colorScheme={system.active ? 'green' : 'red'}
                                                        fontSize="9px" px={2} py={0.5} borderRadius="full"
                                                    >
                                                        {system.active ? "ACTIVE" : "INACTIVE"}
                                                    </Badge>
                                                </td>

                                                {/* Network Status */}
                                                <td className="px-5 py-2.5 text-center">
                                                    <div className="flex items-center gap-1.5 justify-center">
                                                        {system.reachable ? (
                                                            <>
                                                                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
                                                                <span className="text-[10px] font-semibold text-emerald-500">ONLINE</span>
                                                            </>
                                                        ) : (
                                                            <>
                                                                <span className="w-1.5 h-1.5 rounded-full bg-red-400"></span>
                                                                <span className="text-[10px] font-semibold text-red-400">OFFLINE</span>
                                                            </>
                                                        )}
                                                    </div>
                                                </td>

                                                {/* Weight */}
                                                <td className="px-5 py-2.5 text-center">
                                                    <span className="font-mono text-xs text-slate-600 dark:text-slate-300">
                                                        {system.weight ?? 1}
                                                    </span>
                                                </td>

                                                {/* Load Factor */}
                                                <td className="px-5 py-2.5 text-center">
                                                    <div className="flex flex-col items-center gap-0.5">
                                                        <span className="font-mono text-[10px] font-bold text-slate-700 dark:text-slate-200">
                                                            {system.loadFactor}
                                                        </span>
                                                        <div className="w-10 h-1 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
                                                            <div
                                                                className="h-full bg-gradient-to-r from-teal-400 to-cyan-400 rounded-full transition-all"
                                                                style={{ width: `${Math.min(system.loadFactor * 10, 100)}%` }}
                                                            ></div>
                                                        </div>
                                                    </div>
                                                </td>

                                                {/* Actions */}
                                                <td className="px-5 py-2.5">
                                                    <div className='flex gap-1 justify-end opacity-0 group-hover:opacity-100 transition-opacity'>
                                                        <Link href={`/managehost/hostgroups/${system.id}`}>
                                                            <Tooltip label="Edit" hasArrow>
                                                                <IconButton aria-label='Edit' icon={<FaEdit />} variant={"ghost"} size={"xs"} colorScheme='blue' />
                                                            </Tooltip>
                                                        </Link>
                                                        <DeleteSystem source={"hostgroups"} id={system.id} onUpdate={onLoad} />
                                                    </div>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            )}
                        </div>
                    );
                })}
            </div>

            {/* Empty State */}
            {(!systems || systems.length === 0) && (
                <div className="rounded-xl border border-slate-200/40 dark:border-slate-700/40 text-center py-12">
                    <FaNetworkWired className="mx-auto text-3xl text-slate-300 dark:text-slate-600 mb-3" />
                    <p className="text-sm text-slate-400">No host groups configured</p>
                    <Link href='/managehost/hostgroups/new'>
                        <Button mt={3} size="sm" colorScheme="teal" rounded="full" leftIcon={<FaPlusCircle />}>
                            Add Host Group
                        </Button>
                    </Link>
                </div>
            )}
        </div>
    );
}