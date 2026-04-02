import { Button, IconButton, Link, Badge, Tooltip } from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState } from 'react';
import { FaEdit, FaPlug, FaPlusCircle } from "react-icons/fa";
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';

export default function Targets() {
    const [systems, setSystems] = useState<any>([]);
    const { auth } = useAuth() as any;

    const onLoad = () => {
        axios.get(auth.data.host + "/config/targetsystems?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        }).then(r => setSystems(r.data.targetsystems))
            .catch(err => console.error("Failed to load target systems:", err));
    };

    useEffect(() => { onLoad(); }, []);

    return (
        <div className="space-y-4">
            {/* Header */}
            <div className='flex items-center justify-between'>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                    {systems.length} target {systems.length === 1 ? 'upstream' : 'upstreams'} configured
                </p>
                <Link href='/managehost/targetsystems/new'>
                    <Button leftIcon={<FaPlusCircle />} colorScheme='teal' size={"xs"} rounded="full" variant="solid">
                        Add Target
                    </Button>
                </Link>
            </div>

            {/* Table */}
            <div className="overflow-x-auto rounded-xl border border-slate-200/40 dark:border-slate-700/40">
                <table className="min-w-full text-left text-sm">
                    <thead>
                        <tr className="bg-slate-50/80 dark:bg-slate-800/80 text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400">
                            <th className="px-5 py-3 font-medium">Target ID</th>
                            <th className="px-5 py-3 font-medium">Group</th>
                            <th className="px-5 py-3 font-medium">Protocol</th>
                            <th className="px-5 py-3 font-medium">Base Path</th>
                            <th className="px-5 py-3 font-medium">Host Group</th>
                            <th className="px-5 py-3 font-medium text-center">Cache</th>
                            <th className="px-5 py-3 font-medium">Cache Pattern</th>
                            <th className="px-5 py-3 font-medium text-right">Actions</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 dark:divide-slate-700/40">
                        {systems && systems.map((system: any) => (
                            <tr key={system.id}
                                className="text-slate-700 dark:text-slate-300 hover:bg-slate-50/50 dark:hover:bg-slate-700/20 transition-colors group">
                                {/* Target ID */}
                                <td className="px-5 py-3">
                                    <div className="flex items-center gap-2">
                                        <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-violet-500/20 to-purple-500/20 flex items-center justify-center flex-shrink-0">
                                            <span className="text-violet-500 text-[10px] font-bold uppercase">
                                                {system.id?.substring(0, 2)}
                                            </span>
                                        </div>
                                        <span className="font-semibold text-xs text-slate-800 dark:text-white truncate max-w-[180px]">{system.id}</span>
                                    </div>
                                </td>

                                {/* Group */}
                                <td className="px-5 py-3">
                                    <span className="text-xs font-mono text-slate-500 dark:text-slate-400">{system.groupId}</span>
                                </td>

                                {/* Protocol */}
                                <td className="px-5 py-3">
                                    <Badge
                                        colorScheme={system.scheme === 'https' ? 'green' : system.scheme === 'http' ? 'blue' : 'purple'}
                                        fontSize="10px" px={2} py={0.5} borderRadius="full" textTransform="uppercase" fontWeight="bold"
                                    >
                                        {system.scheme}
                                    </Badge>
                                </td>

                                {/* Base Path */}
                                <td className="px-5 py-3">
                                    <code className="text-xs text-slate-500 dark:text-slate-400">{system.basePath || "/"}</code>
                                </td>

                                {/* Host Group */}
                                <td className="px-5 py-3">
                                    <span className="text-xs font-mono text-slate-600 dark:text-slate-400">{system.hostGroupId || "—"}</span>
                                </td>

                                {/* Cache */}
                                <td className="px-5 py-3 text-center">
                                    <Badge
                                        colorScheme={system.enableCache ? 'green' : 'gray'}
                                        fontSize="10px" px={2} py={0.5} borderRadius="full"
                                    >
                                        {system.enableCache ? "ON" : "OFF"}
                                    </Badge>
                                </td>

                                {/* Cache Pattern */}
                                <td className="px-5 py-3">
                                    {system.cachedResources && system.cachedResources.length > 0 ? (
                                        <code className="text-[10px] text-slate-500 dark:text-slate-400 bg-slate-100 dark:bg-slate-700/50 px-2 py-0.5 rounded break-all">
                                            {JSON.stringify(system.cachedResources)}
                                        </code>
                                    ) : (
                                        <span className="text-[10px] text-slate-400">—</span>
                                    )}
                                </td>

                                {/* Actions */}
                                <td className="px-5 py-3">
                                    <div className='flex gap-1 justify-end'>
                                        <Link href={`/managehost/targetsystems/${system.id}`}>
                                            <Tooltip label="Edit" hasArrow>
                                                <IconButton aria-label='Edit' icon={<FaEdit />} variant={"ghost"} size={"xs"} colorScheme='blue' />
                                            </Tooltip>
                                        </Link>
                                        <DeleteSystem source={"targetsystems"} id={system.id} onUpdate={onLoad} />
                                    </div>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>

                {/* Empty State */}
                {(!systems || systems.length === 0) && (
                    <div className="text-center py-12">
                        <FaPlug className="mx-auto text-3xl text-slate-300 dark:text-slate-600 mb-3" />
                        <p className="text-sm text-slate-400">No target upstreams configured</p>
                        <Link href='/managehost/targetsystems/new'>
                            <Button mt={3} size="sm" colorScheme="teal" rounded="full" leftIcon={<FaPlusCircle />}>
                                Add Target
                            </Button>
                        </Link>
                    </div>
                )}
            </div>
        </div>
    );
}