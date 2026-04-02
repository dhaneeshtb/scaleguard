import React, { useEffect, useState } from "react";
import { Button, IconButton, Input, Select, Badge, useToast } from "@chakra-ui/react";
import { EditIcon, DeleteIcon } from "@chakra-ui/icons";
import { useAuth } from "../../contexts/AuthContext";
import axios from "axios";
import { FaBolt, FaEdit, FaExclamationTriangle, FaPlusCircle, FaSave, FaStream, FaTimes, FaTrash } from "react-icons/fa";

interface Queue {
    id?: string;
    name: string;
    type: "embedded" | "kafka" | string;
    brokers?: string;
    topic: string;
}

const AsyncEngines = () => {
    const { auth } = useAuth() as any;
    const [loading, setLoading] = useState<boolean>(false);
    const [queueData, setQueueData] = useState<Queue[]>([]);
    const [newQueue, setNewQueue] = useState<Queue>({ name: "", type: "embedded", brokers: "", topic: "" });
    const [editingIndex, setEditingIndex] = useState(null);
    const toast = useToast();

    const load = async () => {
        const r = await axios.get(auth.data.host + "/asyncengines?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        });
        const res = r.data;
        setQueueData(Object.keys(res).map(r => {
            const o = res[r];
            return { id: o.id, name: o.name, type: o.type, topic: o.payload?.topic || "event-stream", brokers: o.payload.brokers };
        }));
    }

    const save = async () => {
        setLoading(true);
        try {
            await axios.post(auth.data.host + "/asyncengines?scaleguard=true", {
                name: newQueue.name, id: newQueue.name, type: newQueue.type,
                payload: { topic: newQueue.topic, brokers: newQueue.brokers }
            }, { headers: { Authorization: auth.data.token } });
            setNewQueue({ type: "embedded", brokers: "", topic: "", name: "" });
            setEditingIndex(null);
            await load();
            toast({ title: "Engine saved successfully!", status: "success", duration: 2000, isClosable: true });
        } catch (e) {
            toast({ title: "Update Failed!", status: "error", duration: 2000, isClosable: true });
        }
        setLoading(false);
    }

    useEffect(() => { load(); }, []);

    const getQueueUrl = (queue) => {
        if (queue.type === "embedded") return `${queue.type}://${queue.topic}`;
        return `${queue.type}://${queue.brokers}/${queue.topic}`;
    };

    const handleAddQueue = async () => { await save(); };

    const handleEditQueue = (index) => {
        setNewQueue(queueData[index]);
        setEditingIndex(index);
    };

    const handleDeleteQueue = async (index) => {
        const queue = queueData[index];
        if (!queue?.id) return;
        try {
            await axios.delete(auth.data.host + "/asyncengines/" + queue.id + "?scaleguard=true", {
                headers: { Authorization: auth.data.token }
            });
            await load();
            toast({ title: "Engine deleted", status: "info", duration: 2000, isClosable: true });
        } catch (e) {
            toast({ title: "Delete Failed!", status: "error", duration: 2000, isClosable: true });
        }
    };

    const inputClasses = "w-full px-4 py-2.5 rounded-xl bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-white text-sm focus:ring-2 focus:ring-cyan-500/40 focus:border-cyan-500 transition-all outline-none";

    return (
        <div className="p-6 sm:p-8 max-w-5xl mx-auto space-y-6">
            {/* Page Header */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <div className="p-2.5 bg-gradient-to-br from-cyan-500/10 to-blue-500/10 rounded-xl">
                        <FaBolt className="text-cyan-500 text-lg" />
                    </div>
                    <div>
                        <h2 className="text-xl font-bold dark:text-white">Async Engines</h2>
                        <p className="text-xs text-slate-400">{queueData.length} engine{queueData.length !== 1 ? 's' : ''} configured</p>
                    </div>
                </div>
            </div>

            {/* Engines Table */}
            {queueData.length > 0 ? (
                <div className="bg-white dark:bg-slate-800/50 rounded-2xl border border-slate-200/60 dark:border-slate-700/50 overflow-hidden shadow-sm">
                    <table className="min-w-full text-left text-sm">
                        <thead>
                            <tr className="bg-slate-50/80 dark:bg-slate-800/80 text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400">
                                <th className="px-5 py-3.5 font-medium">Engine</th>
                                <th className="px-5 py-3.5 font-medium">Type</th>
                                <th className="px-5 py-3.5 font-medium">Connection URL</th>
                                <th className="px-5 py-3.5 font-medium text-right">Actions</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100 dark:divide-slate-700/40">
                            {queueData.map((queue, index) => (
                                <tr key={index} className="text-slate-700 dark:text-slate-300 hover:bg-slate-50/50 dark:hover:bg-slate-700/20 transition-colors">
                                    <td className="px-5 py-4">
                                        <div className="flex items-center gap-3">
                                            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-cyan-500 to-blue-600 flex items-center justify-center flex-shrink-0">
                                                <FaStream className="text-white text-xs" />
                                            </div>
                                            <div>
                                                <p className="text-sm font-semibold text-slate-800 dark:text-white">{queue.name}</p>
                                                <p className="text-[11px] text-slate-400 font-mono">{queue.id}</p>
                                            </div>
                                        </div>
                                    </td>
                                    <td className="px-5 py-4">
                                        <Badge
                                            colorScheme={queue.type === 'kafka' ? 'orange' : 'cyan'}
                                            fontSize="10px" px={2.5} py={0.5} borderRadius="full"
                                            textTransform="uppercase" fontWeight="bold"
                                        >
                                            {queue.type}
                                        </Badge>
                                    </td>
                                    <td className="px-5 py-4">
                                        <code className="text-xs font-mono text-slate-500 dark:text-slate-400 bg-slate-100 dark:bg-slate-800 px-2.5 py-1 rounded-lg">
                                            {getQueueUrl(queue)}
                                        </code>
                                    </td>
                                    <td className="px-5 py-4 text-right">
                                        <div className="flex gap-1.5 justify-end">
                                            <IconButton
                                                icon={<FaEdit />}
                                                size="sm"
                                                aria-label="Edit"
                                                onClick={() => handleEditQueue(index)}
                                                variant="ghost"
                                                colorScheme="blue"
                                                rounded="full"
                                            />
                                            <IconButton
                                                icon={<FaTrash />}
                                                size="sm"
                                                aria-label="Delete"
                                                onClick={() => handleDeleteQueue(index)}
                                                variant="ghost"
                                                colorScheme="red"
                                                rounded="full"
                                            />
                                        </div>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            ) : (
                <div className="bg-white dark:bg-slate-800/50 rounded-2xl border border-slate-200/60 dark:border-slate-700/50 p-12 text-center">
                    <FaBolt className="mx-auto text-3xl text-slate-300 dark:text-slate-600 mb-3" />
                    <p className="text-sm text-slate-400">No async engines configured</p>
                    <p className="text-xs text-slate-400/70 mt-1">Add an engine below to get started</p>
                </div>
            )}

            {/* Add / Edit Engine Form */}
            <div className="bg-white dark:bg-slate-800/50 rounded-2xl border border-slate-200/60 dark:border-slate-700/50 overflow-hidden shadow-sm">
                {/* Form Header */}
                <div className="relative overflow-hidden">
                    <div className="absolute inset-0 bg-gradient-to-br from-slate-800 to-slate-900"></div>
                    <div className="absolute inset-0 bg-gradient-to-br from-cyan-500/15 to-blue-500/15"></div>
                    <div className="absolute inset-0 opacity-[0.03]" style={{
                        backgroundImage: 'linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)',
                        backgroundSize: '20px 20px'
                    }}></div>
                    <div className="relative px-6 py-4 flex items-center justify-between">
                        <div className="flex items-center gap-3">
                            <div className="p-2 bg-white/10 rounded-xl">
                                {editingIndex !== null ? <FaEdit className="text-white text-sm" /> : <FaPlusCircle className="text-white text-sm" />}
                            </div>
                            <div>
                                <h3 className="text-base font-bold text-white">
                                    {editingIndex !== null ? "Edit Engine" : "Add New Engine"}
                                </h3>
                                <p className="text-[11px] text-white/50">
                                    {editingIndex !== null ? "Modify engine configuration" : "Configure a new event queue engine"}
                                </p>
                            </div>
                        </div>
                        {editingIndex !== null && (
                            <Button
                                size="xs"
                                variant="ghost"
                                color="white"
                                leftIcon={<FaTimes />}
                                onClick={() => { setEditingIndex(null); setNewQueue({ name: "", type: "embedded", topic: "", brokers: "" }); }}
                                rounded="full"
                                _hover={{ bg: "whiteAlpha.200" }}
                            >
                                Cancel
                            </Button>
                        )}
                    </div>
                </div>

                {/* Form Body */}
                <div className="p-6 space-y-4">
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        {/* Engine Type */}
                        <div className="space-y-1.5">
                            <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                Engine Type <span className="text-red-400 text-xs">*</span>
                            </label>
                            <Select
                                placeholder="Select type"
                                value={newQueue.type}
                                onChange={(e) => setNewQueue({ ...newQueue, type: e.target.value })}
                                borderRadius="xl"
                                bg="gray.50"
                                _dark={{ bg: "rgba(30,41,59,0.8)" }}
                                _focus={{ borderColor: "cyan.500", boxShadow: "0 0 0 1px var(--chakra-colors-cyan-500)" }}
                                size="sm"
                            >
                                <option value="embedded">Embedded</option>
                                <option value="kafka">Kafka</option>
                            </Select>
                            <p className="text-[10px] text-slate-400 pl-1">Embedded for in-process, Kafka for distributed</p>
                        </div>

                        {/* Engine Name */}
                        <div className="space-y-1.5">
                            <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                Engine Name <span className="text-red-400 text-xs">*</span>
                            </label>
                            <input
                                className={inputClasses}
                                placeholder="e.g. my-event-engine"
                                value={newQueue.name}
                                onChange={(e) => setNewQueue({ ...newQueue, name: e.target.value })}
                            />
                            <p className="text-[10px] text-slate-400 pl-1">Unique identifier for this engine</p>
                        </div>
                    </div>

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        {/* Topic */}
                        <div className="space-y-1.5">
                            <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                Topic <span className="text-red-400 text-xs">*</span>
                            </label>
                            <input
                                className={inputClasses}
                                placeholder="e.g. event-stream"
                                value={newQueue.topic}
                                onChange={(e) => setNewQueue({ ...newQueue, topic: e.target.value })}
                            />
                            <p className="text-[10px] text-slate-400 pl-1">The topic/channel name for events</p>
                        </div>

                        {/* Brokers (Kafka only) */}
                        {newQueue.type === "kafka" && (
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                    Brokers <span className="text-red-400 text-xs">*</span>
                                </label>
                                <input
                                    className={inputClasses}
                                    placeholder="e.g. localhost:9092"
                                    value={newQueue.brokers}
                                    onChange={(e) => setNewQueue({ ...newQueue, brokers: e.target.value })}
                                />
                                <p className="text-[10px] text-slate-400 pl-1">Comma-separated Kafka broker addresses</p>
                            </div>
                        )}
                    </div>

                    {/* Submit */}
                    <div className="flex justify-end pt-2">
                        <Button
                            isLoading={loading}
                            isDisabled={!newQueue.name || !newQueue.topic || (newQueue.type === "kafka" ? !newQueue.brokers : false)}
                            colorScheme="cyan"
                            onClick={handleAddQueue}
                            rounded="xl"
                            size="sm"
                            px={6}
                            leftIcon={editingIndex !== null ? <FaSave /> : <FaPlusCircle />}
                            _hover={{ transform: "translateY(-1px)", shadow: "lg" }}
                            transition="all 0.2s"
                        >
                            {editingIndex !== null ? "Update Engine" : "Add Engine"}
                        </Button>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default AsyncEngines;
