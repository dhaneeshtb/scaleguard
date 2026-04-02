import React, { useEffect, useState } from 'react';
import { Input, Button, Modal, ModalOverlay, ModalContent, ModalCloseButton, ModalBody, ModalFooter, useDisclosure, Text, Badge } from '@chakra-ui/react';
import { v4 as uuidv4 } from 'uuid';
import axios from 'axios';
import { useAuth } from '../../contexts/AuthContext';
import { FaExclamationTriangle, FaKey, FaPlusCircle, FaShieldAlt, FaTrash, FaTrashAlt, FaUserPlus } from 'react-icons/fa';

type Client = { id: string; name: string; appid: string; clientid: string; clientsecret: string; expiry: number; };
type App = { id: string; name: string; description: string; clients: Client[]; };

export default function AppManagement() {
    const [apps, setApps] = useState<App[]>([]);
    const [appName, setAppName] = useState<string>('');
    const [appDescription, setAppDescription] = useState<string>('');
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [selectedAppId, setSelectedAppId] = useState<string | null>(null);
    const [clientId, setClientId] = useState<string>('');
    const [clientName, setClientName] = useState<string>('');
    const [clientSecret, setClientSecret] = useState<string>('');
    const [expiry, setExpiry] = useState<number>(30);
    const { isOpen, onOpen, onClose } = useDisclosure();
    const [deleteTarget, setDeleteTarget] = useState<{ type: 'app' | 'client'; appId?: string; clientId?: string } | null>(null);
    const [errorMessage, setErrorMessage] = useState<string>('');
    const { auth } = useAuth() as any;
    const [isLoading, setLoading] = useState(false);

    const loadApps = async () => {
        const r = await axios.get(`${auth.data.host}/apps?scaleguard=true`, { headers: { Authorization: auth.data.token } });
        setApps(r.data);
    }
    const saveApp = async (app) => {
        await axios.post(`${auth.data.host}/apps?scaleguard=true`, app, { headers: { Authorization: auth.data.token } });
        await loadApps();
    }
    const deleteApp = async (appid) => {
        await axios.delete(`${auth.data.host}/apps/${appid}?scaleguard=true`, { headers: { Authorization: auth.data.token } });
        await loadApps();
    }
    const saveClient = async (client) => {
        await axios.post(`${auth.data.host}/clients?scaleguard=true`, client, { headers: { Authorization: auth.data.token } });
        await loadApps();
    }
    const deleteClient = async (clientid) => {
        await axios.delete(`${auth.data.host}/clients/${clientid}?scaleguard=true`, { headers: { Authorization: auth.data.token } });
        await loadApps();
    }

    useEffect(() => { loadApps(); }, [auth]);

    const createApp = async () => {
        if (!appName.trim() || !appDescription.trim()) { setErrorMessage('App Name and Description are required.'); return; }
        setErrorMessage('');
        const newApp: App = { id: uuidv4(), name: appName, description: appDescription, clients: [] };
        await saveApp(newApp);
        setAppName(''); setAppDescription('');
    };

    const openAddClientModal = (appId: string) => {
        setSelectedAppId(appId); setClientName(''); setClientId(uuidv4()); setClientSecret(uuidv4()); setExpiry(30); setIsModalOpen(true);
    };

    const addClient = async () => {
        if (!selectedAppId) return;
        const expiryDay = new Date().getTime() + (expiry * 60 * 60 * 24 * 1000);
        const newClient: Client = { id: clientId, name: clientName, appid: selectedAppId, clientid: clientId, clientsecret: clientSecret, expiry: expiryDay };
        await saveClient(newClient);
        setIsModalOpen(false);
    };

    const confirmDelete = (type: 'app' | 'client', appId?: string, clientId?: string) => {
        setDeleteTarget({ type, appId, clientId }); onOpen();
    };

    const deleteItem = async () => {
        if (!deleteTarget) return;
        if (deleteTarget.type === 'app' && deleteTarget.appId) { await deleteApp(deleteTarget.appId); }
        else if (deleteTarget.type === 'client' && deleteTarget.appId && deleteTarget.clientId) { await deleteClient(deleteTarget.clientId); }
        onClose();
    };

    return (
        <div className="p-6 sm:p-8 max-w-5xl mx-auto">
            {/* Page Header */}
            <div className="flex items-center gap-3 mb-6">
                <div className="p-2.5 bg-gradient-to-br from-violet-500/10 to-purple-500/10 rounded-xl">
                    <FaShieldAlt className="text-violet-500 text-lg" />
                </div>
                <div>
                    <h2 className="text-xl font-bold dark:text-white">App Security</h2>
                    <p className="text-xs text-slate-400">{apps.length} application{apps.length !== 1 ? 's' : ''} configured</p>
                </div>
            </div>

            {/* Create App Form */}
            <div className="bg-white dark:bg-slate-800/50 rounded-2xl border border-slate-200/60 dark:border-slate-700/50 p-5 mb-6">
                <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-3">Create New Application</h3>
                <div className="flex flex-col sm:flex-row gap-3">
                    <Input
                        placeholder="App Name"
                        value={appName}
                        onChange={(e) => setAppName(e.target.value)}
                        borderRadius="xl"
                        bg="gray.50"
                        _dark={{ bg: "gray.800" }}
                        _focus={{ borderColor: "violet.500", boxShadow: "0 0 0 1px var(--chakra-colors-purple-500)" }}
                        size="sm"
                    />
                    <Input
                        placeholder="App Description"
                        value={appDescription}
                        onChange={(e) => setAppDescription(e.target.value)}
                        borderRadius="xl"
                        bg="gray.50"
                        _dark={{ bg: "gray.800" }}
                        _focus={{ borderColor: "violet.500", boxShadow: "0 0 0 1px var(--chakra-colors-purple-500)" }}
                        size="sm"
                    />
                    <Button
                        onClick={createApp}
                        colorScheme='teal'
                        size="sm"
                        leftIcon={<FaPlusCircle />}
                        rounded="xl"
                        px={5}
                        flexShrink={0}
                        _hover={{ transform: "translateY(-1px)", shadow: "md" }}
                        transition="all 0.2s"
                    >
                        Create
                    </Button>
                </div>
                {errorMessage && <Text color="red.400" fontSize="xs" mt={2}>{errorMessage}</Text>}
            </div>

            {/* Apps List */}
            <div className="space-y-4">
                {apps.map((app) => (
                    <div key={app.id} className="bg-white dark:bg-slate-800/50 rounded-2xl border border-slate-200/60 dark:border-slate-700/50 overflow-hidden">
                        {/* App Header */}
                        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100 dark:border-slate-700/40">
                            <div className="flex items-center gap-3">
                                <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-violet-500 to-purple-600 flex items-center justify-center">
                                    <span className="text-white text-xs font-bold">{app.name.substring(0, 2).toUpperCase()}</span>
                                </div>
                                <div>
                                    <h4 className="text-sm font-bold text-slate-800 dark:text-white">{app.name}</h4>
                                    <p className="text-[11px] text-slate-400">{app.description}</p>
                                </div>
                            </div>
                            <div className="flex items-center gap-2">
                                <Button size="xs" leftIcon={<FaUserPlus />} onClick={() => openAddClientModal(app.id)} colorScheme="teal" rounded="full" variant="ghost">
                                    Add Client
                                </Button>
                                <Button size="xs" leftIcon={<FaTrashAlt />} colorScheme="red" variant="ghost" rounded="full" onClick={() => confirmDelete('app', app.id)}>
                                    Delete
                                </Button>
                            </div>
                        </div>

                        {/* Clients */}
                        <div className="px-5 py-3">
                            {app.clients && app.clients.length > 0 ? (
                                <div className="space-y-3">
                                    {app.clients.map(client => (
                                        <div key={client.id} className="flex items-start justify-between p-3 rounded-xl bg-slate-50 dark:bg-slate-800/60 border border-slate-100 dark:border-slate-700/30">
                                            <div className="space-y-1">
                                                <div className="flex items-center gap-2">
                                                    <FaKey className="text-amber-500 text-[10px]" />
                                                    <span className="text-sm font-semibold text-slate-800 dark:text-white">{client.name}</span>
                                                </div>
                                                <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-0.5 pl-5">
                                                    <p className="text-[11px] text-slate-400"><span className="text-slate-500 dark:text-slate-400 font-medium">ID:</span> <span className="font-mono">{client.clientid}</span></p>
                                                    <p className="text-[11px] text-slate-400"><span className="text-slate-500 dark:text-slate-400 font-medium">Secret:</span> <span className="font-mono">{client.clientsecret}</span></p>
                                                    <p className="text-[11px] text-slate-400">
                                                        <span className="text-slate-500 dark:text-slate-400 font-medium">Expires:</span>{' '}
                                                        {client.expiry === 0 ? <Badge colorScheme="green" fontSize="9px" borderRadius="full">No Expiry</Badge> : new Date(client.expiry).toLocaleDateString()}
                                                    </p>
                                                </div>
                                            </div>
                                            <Button size="xs" leftIcon={<FaTrashAlt />} colorScheme="red" variant="ghost" rounded="full" onClick={() => confirmDelete('client', app.id, client.id)}>
                                                Delete
                                            </Button>
                                        </div>
                                    ))}
                                </div>
                            ) : (
                                <p className="text-xs text-slate-400 text-center py-3">No clients configured</p>
                            )}
                        </div>
                    </div>
                ))}

                {apps.length === 0 && (
                    <div className="text-center py-12">
                        <FaShieldAlt className="mx-auto text-3xl text-slate-300 dark:text-slate-600 mb-3" />
                        <p className="text-sm text-slate-400">No applications configured</p>
                    </div>
                )}
            </div>

            {/* Add Client Modal */}
            <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} isCentered motionPreset="slideInBottom">
                <ModalOverlay bg="blackAlpha.600" backdropFilter="blur(8px)" />
                <ModalContent
                    borderRadius="2xl"
                    shadow="2xl"
                    mx={4}
                    overflow="hidden"
                    className="!bg-white dark:!bg-slate-900 !border !border-slate-200 dark:!border-slate-700"
                >
                    {/* Header */}
                    <div className="relative overflow-hidden">
                        <div className="absolute inset-0 bg-gradient-to-br from-violet-600 to-purple-700"></div>
                        <div className="absolute inset-0 opacity-[0.05]" style={{
                            backgroundImage: 'linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)',
                            backgroundSize: '20px 20px'
                        }}></div>
                        <div className="relative px-8 py-5">
                            <div className="flex items-center gap-3">
                                <div className="p-2 bg-white/15 rounded-xl">
                                    <FaKey className="text-white text-sm" />
                                </div>
                                <div>
                                    <h3 className="text-lg font-bold text-white">Add Client</h3>
                                    <p className="text-xs text-white/60">Create API credentials for this application</p>
                                </div>
                            </div>
                        </div>
                    </div>
                    <ModalCloseButton color="white" rounded="full" top={4} right={4} />

                    <ModalBody py={6} px={8}>
                        <div className="space-y-4">
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">Client Name</label>
                                <Input value={clientName} onChange={e => setClientName(e.target.value)} borderRadius="xl" className="!bg-slate-50 dark:!bg-slate-800" _focus={{ borderColor: "purple.500", boxShadow: "0 0 0 1px var(--chakra-colors-purple-500)" }} placeholder="e.g. Production Client" />
                            </div>
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">Client ID</label>
                                <Input value={clientId} isReadOnly borderRadius="xl" className="!bg-slate-100 dark:!bg-slate-800" fontFamily="mono" fontSize="xs" />
                                <p className="text-[10px] text-slate-400 pl-1">Auto-generated UUID</p>
                            </div>
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">Client Secret</label>
                                <Input value={clientSecret} isReadOnly borderRadius="xl" className="!bg-slate-100 dark:!bg-slate-800" fontFamily="mono" fontSize="xs" />
                                <p className="text-[10px] text-slate-400 pl-1">Auto-generated secret — copy and store securely</p>
                            </div>
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">Expiry (Days)</label>
                                <Input type="number" value={expiry} onChange={(e) => setExpiry(Number(e.target.value))} borderRadius="xl" className="!bg-slate-50 dark:!bg-slate-800" _focus={{ borderColor: "purple.500", boxShadow: "0 0 0 1px var(--chakra-colors-purple-500)" }} />
                                <p className="text-[10px] text-slate-400 pl-1">Set to 0 for no expiry</p>
                            </div>
                        </div>
                    </ModalBody>

                    <ModalFooter px={8} pb={6} gap={3} className="!border-t !border-slate-100 dark:!border-slate-700">
                        <Button onClick={() => setIsModalOpen(false)} variant="ghost" rounded="xl" size="sm" px={5}>Cancel</Button>
                        <Button onClick={addClient} colorScheme="purple" rounded="xl" size="sm" px={5} leftIcon={<FaPlusCircle />} _hover={{ transform: "translateY(-1px)", shadow: "lg" }} transition="all 0.2s">
                            Create Client
                        </Button>
                    </ModalFooter>
                </ModalContent>
            </Modal>

            {/* Delete Confirmation Modal */}
            <Modal isOpen={isOpen} onClose={onClose} isCentered motionPreset="slideInBottom">
                <ModalOverlay bg="blackAlpha.600" backdropFilter="blur(8px)" />
                <ModalContent
                    borderRadius="2xl"
                    shadow="2xl"
                    mx={4}
                    className="!bg-white dark:!bg-slate-900 !border !border-slate-200 dark:!border-slate-700"
                >
                    <ModalCloseButton rounded="full" top={4} right={4} />
                    <ModalBody pt={8} pb={4} px={8}>
                        <div className="flex flex-col items-center text-center">
                            <div className="w-14 h-14 rounded-full bg-red-500/10 flex items-center justify-center mb-4">
                                <FaExclamationTriangle className="text-red-500 text-xl" />
                            </div>
                            <h3 className="text-lg font-bold text-slate-800 dark:text-white mb-1">
                                Confirm Deletion
                            </h3>
                            <p className="text-sm text-slate-500 dark:text-slate-400">
                                Are you sure you want to delete this <span className="font-semibold text-slate-700 dark:text-slate-300">{deleteTarget?.type}</span>?
                            </p>
                        </div>
                    </ModalBody>
                    <ModalFooter px={8} pb={6} pt={2} gap={3} justifyContent="center">
                        <Button onClick={onClose} variant="ghost" rounded="xl" size="sm" px={5}>Cancel</Button>
                        <Button onClick={deleteItem} colorScheme="red" rounded="xl" size="sm" px={5} leftIcon={<FaTrash />} _hover={{ transform: "translateY(-1px)", shadow: "lg" }} transition="all 0.2s">
                            Delete
                        </Button>
                    </ModalFooter>
                </ModalContent>
            </Modal>
        </div>
    );
}