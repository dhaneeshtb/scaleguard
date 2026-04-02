import { Button, IconButton, Link, Badge, Tooltip } from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState } from 'react';
import { FaArrowCircleDown, FaArrowCircleUp, FaEdit, FaNetworkWired, FaPlusCircle } from "react-icons/fa";
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';

export default function HostGroups() {
    const [systems, setSystems] = useState<any>([]);
    const { auth } = useAuth() as any;

    const onLoad = () => {
        axios.get(auth.data.host + "/config/hostgroups?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        }).then(r => setSystems(r.data.hostgroups))
            .catch(err => console.error("Failed to load host groups:", err));
    };

    useEffect(() => { onLoad(); }, []);

    const reachableCount = systems.filter((s: any) => s.reachable).length;

    return (
        <div className="space-y-4">
            {/* Header */}
            <div className='flex items-center justify-between'>
                <div className="flex items-center gap-3">
                    <p className="text-xs text-slate-500 dark:text-slate-400">
                        {systems.length} host {systems.length === 1 ? 'group' : 'groups'}
                    </p>
                    {systems.length > 0 && (
                        <div className="flex items-center gap-1.5">
                            <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
                            <span className="text-[10px] font-medium text-emerald-500">
                                {reachableCount}/{systems.length} reachable
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

            {/* Table */}
            <div className="overflow-x-auto rounded-xl border border-slate-200/40 dark:border-slate-700/40">
                <table className="min-w-full text-left text-sm">
                    <thead>
                        <tr className="bg-slate-50/80 dark:bg-slate-800/80 text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400">
                            <th className="px-5 py-3 font-medium">Host</th>
                            <th className="px-5 py-3 font-medium">Group</th>
                            <th className="px-5 py-3 font-medium text-center">Type</th>
                            <th className="px-5 py-3 font-medium text-center">Status</th>
                            <th className="px-5 py-3 font-medium text-center">Network</th>
                            <th className="px-5 py-3 font-medium text-center">Load Factor</th>
                            <th className="px-5 py-3 font-medium text-right">Actions</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 dark:divide-slate-700/40">
                        {systems && systems.map((system: any) => (
                            <tr key={system.id}
                                className="text-slate-700 dark:text-slate-300 hover:bg-slate-50/50 dark:hover:bg-slate-700/20 transition-colors group">
                                {/* Host */}
                                <td className="px-5 py-3">
                                    <div className="flex items-center gap-2">
                                        <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${system.reachable
                                            ? 'bg-gradient-to-br from-emerald-500/20 to-teal-500/20'
                                            : 'bg-gradient-to-br from-red-500/20 to-orange-500/20'
                                            }`}>
                                            {system.reachable
                                                ? <FaArrowCircleUp className="text-emerald-500 text-xs" />
                                                : <FaArrowCircleDown className="text-red-400 text-xs" />
                                            }
                                        </div>
                                        <div>
                                            <p className="font-semibold text-xs text-slate-800 dark:text-white font-mono">
                                                {system.host}:{system.port}
                                            </p>
                                            <p className="text-[10px] text-slate-400 font-mono truncate max-w-[200px]">{system.id}</p>
                                        </div>
                                    </div>
                                </td>

                                {/* Group */}
                                <td className="px-5 py-3">
                                    <span className="text-xs font-mono text-slate-500 dark:text-slate-400">{system.groupId}</span>
                                </td>

                                {/* Type */}
                                <td className="px-5 py-3 text-center">
                                    <Badge
                                        colorScheme={system.type === 'active' ? 'blue' : 'gray'}
                                        fontSize="10px" px={2} py={0.5} borderRadius="full" textTransform="uppercase" fontWeight="bold"
                                    >
                                        {system.type}
                                    </Badge>
                                </td>

                                {/* Active Status */}
                                <td className="px-5 py-3 text-center">
                                    <Badge
                                        colorScheme={system.active ? 'green' : 'red'}
                                        fontSize="10px" px={2} py={0.5} borderRadius="full"
                                    >
                                        {system.active ? "ACTIVE" : "INACTIVE"}
                                    </Badge>
                                </td>

                                {/* Network Status */}
                                <td className="px-5 py-3 text-center">
                                    <div className="flex items-center gap-1.5 justify-center">
                                        {system.reachable ? (
                                            <>
                                                <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
                                                <span className="text-[10px] font-semibold text-emerald-500">ONLINE</span>
                                            </>
                                        ) : (
                                            <>
                                                <span className="w-2 h-2 rounded-full bg-red-400"></span>
                                                <span className="text-[10px] font-semibold text-red-400">OFFLINE</span>
                                            </>
                                        )}
                                    </div>
                                </td>

                                {/* Load Factor */}
                                <td className="px-5 py-3 text-center">
                                    <div className="flex flex-col items-center gap-1">
                                        <span className="font-mono text-xs font-bold text-slate-700 dark:text-slate-200">
                                            {system.loadFactor}
                                        </span>
                                        <div className="w-12 h-1.5 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
                                            <div
                                                className="h-full bg-gradient-to-r from-teal-400 to-cyan-400 rounded-full transition-all"
                                                style={{ width: `${Math.min(system.loadFactor * 10, 100)}%` }}
                                            ></div>
                                        </div>
                                    </div>
                                </td>

                                {/* Actions */}
                                <td className="px-5 py-3">
                                    <div className='flex gap-1 justify-end'>
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

                {/* Empty State */}
                {(!systems || systems.length === 0) && (
                    <div className="text-center py-12">
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
        </div>
    );
}