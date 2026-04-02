import { Select, Button, IconButton, Link, Modal, ModalBody, ModalCloseButton, ModalContent, ModalFooter, ModalHeader, ModalOverlay, useDisclosure, Badge, Tooltip } from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState } from 'react';
import { FaCheckCircle, FaCogs, FaEdit, FaExternalLinkAlt, FaLock, FaMinusCircle, FaPiedPiper, FaPlusCircle, FaShieldAlt, FaUnlock } from "react-icons/fa";
import { renewCertificate, updateSource } from './sourceupdate';
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';

export default function Systems() {
    const [systems, setSystems] = useState<any>([]);
    const { auth } = useAuth() as any;

    const onLoad = () => {
        axios.get(auth.data.host + "/config/sourcesystems?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        }).then(r => setSystems(r.data.sourcesystems))
            .catch(err => console.error("Failed to load source systems:", err));
    };

    useEffect(() => {
        if (auth.data) onLoad();
    }, [auth.data]);

    function AttachCertificate({ source, id, onUpdate = () => { } }) {
        const { isOpen, onOpen, onClose } = useDisclosure();
        const [certificates, setCertificates] = useState<any[]>([]);
        const [saving, setSaving] = useState<boolean>(false);
        const [selectedId, setSelectedId] = useState<string>(id);
        const [selectedCert, setSelectedCert] = useState<any>(null);
        const [currentMessage, setCurrentMessage] = useState<string>("");

        const onLoad = async () => {
            const r = await axios.get(auth.data.host + "/certificates?scaleguard=true", {
                headers: { Authorization: auth.data.token }
            });
            r.data.forEach(d => {
                d.json = JSON.parse(d.json);
                d.name = d.json.identifiers.map(s => s.value)[0];
                if (d.id === selectedId) setSelectedCert(d);
            });
            setCertificates(r.data);
        };

        const onSelectId = (selectedId) => {
            certificates.forEach(d => {
                if (d.id === selectedId) setSelectedCert(d);
            });
            setSelectedId(selectedId);
        };

        const onSave = async () => {
            setSaving(true);
            await updateSource({ ...source, certificateId: selectedId }, "sourcesystems", auth);
            await onUpdate();
            setSaving(false);
            onClose();
        };

        const procureCertificate = async () => {
            setSaving(true);
            setCurrentMessage("");
            try {
                await renewCertificate(source.id, auth);
                await onUpdate();
                onClose();
            } catch (e) {
                setCurrentMessage("Failed to procure the certificate");
            }
            setSaving(false);
        };

        useEffect(() => { onLoad(); }, [id]);

        return (
            <>
                <Tooltip label="Configure Certificate" hasArrow>
                    <IconButton rounded={"full"} size={"xs"} aria-label='Configure Certificate' onClick={onOpen} icon={<FaCogs />} variant={"ghost"} colorScheme='teal' />
                </Tooltip>
                <Modal isOpen={isOpen} onClose={onClose} isCentered motionPreset="slideInBottom">
                    <ModalOverlay bg="blackAlpha.600" backdropFilter="blur(8px)" />
                    <ModalContent
                        borderRadius="2xl"
                        shadow="2xl"
                        mx={4}
                        overflow="hidden"
                        className="!bg-white dark:!bg-slate-900 !border !border-slate-200 dark:!border-slate-700"
                    >
                        {/* Gradient Header */}
                        <div className="relative overflow-hidden">
                            <div className="absolute inset-0 bg-gradient-to-br from-emerald-600 to-teal-700"></div>
                            <div className="absolute inset-0 opacity-[0.05]" style={{
                                backgroundImage: 'linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)',
                                backgroundSize: '20px 20px'
                            }}></div>
                            <div className="relative px-8 py-5">
                                <div className="flex items-center gap-3">
                                    <div className="p-2 bg-white/15 rounded-xl">
                                        <FaLock className="text-white text-sm" />
                                    </div>
                                    <div>
                                        <h3 className="text-lg font-bold text-white">Configure Certificate</h3>
                                        <p className="text-xs text-white/60">Attach or generate an SSL certificate</p>
                                    </div>
                                </div>
                            </div>
                        </div>
                        <ModalCloseButton color="white" rounded="full" top={4} right={4} />

                        <ModalBody py={6} px={8}>
                            {currentMessage && (
                                <div className="mb-4 p-3 rounded-xl bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800/40">
                                    <p className="text-sm text-red-600 dark:text-red-400 text-center">{currentMessage}</p>
                                </div>
                            )}

                            {/* Auto-generate option */}
                            <div className="mb-5">
                                <Button
                                    w="full"
                                    isLoading={saving}
                                    colorScheme='teal'
                                    variant="outline"
                                    onClick={procureCertificate}
                                    rounded="xl"
                                    size="sm"
                                    py={5}
                                    leftIcon={<FaShieldAlt />}
                                    _hover={{ bg: "teal.50", _dark: { bg: "teal.900/20" } }}
                                >
                                    Auto-Generate Certificate
                                </Button>
                            </div>

                            {/* Divider */}
                            <div className="flex items-center gap-3 mb-5">
                                <div className="flex-1 h-px bg-slate-200 dark:bg-slate-700"></div>
                                <span className="text-[11px] font-medium text-slate-400 uppercase tracking-wider">or select existing</span>
                                <div className="flex-1 h-px bg-slate-200 dark:bg-slate-700"></div>
                            </div>

                            {/* Certificate selector */}
                            <div className="space-y-1.5 mb-4">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                    Choose Certificate
                                </label>
                                <Select
                                    placeholder='Select a certificate'
                                    value={selectedId}
                                    onChange={(e) => onSelectId(e.target.value)}
                                    borderRadius="xl"
                                    className="!bg-slate-50 dark:!bg-[rgba(30,41,59,0.8)]"
                                    _focus={{ borderColor: "teal.500", boxShadow: "0 0 0 1px var(--chakra-colors-teal-500)" }}
                                    size="sm"
                                >
                                    {certificates.map(c => (
                                        <option key={c.id} value={c.id}>{c.json.identifiers.map(s => s.value).join(", ")}</option>
                                    ))}
                                </Select>
                            </div>

                            {/* Selected cert info card */}
                            {selectedCert && (
                                <div className="p-3 rounded-xl bg-emerald-50/50 dark:bg-emerald-900/10 border border-emerald-200/60 dark:border-emerald-800/30">
                                    <div className="flex items-center gap-2 mb-1.5">
                                        <FaCheckCircle className="text-emerald-500 text-xs" />
                                        <span className="text-xs font-semibold text-slate-700 dark:text-slate-300">Associated Domains</span>
                                    </div>
                                    <div className="flex flex-wrap gap-1.5">
                                        {selectedCert.json.identifiers.map((s, i) => (
                                            <span key={i} className="text-[11px] font-mono bg-white dark:bg-slate-800 px-2 py-0.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400">
                                                {s.value}
                                            </span>
                                        ))}
                                    </div>
                                </div>
                            )}
                        </ModalBody>

                        <ModalFooter
                            px={8} pb={6}
                            gap={3}
                            className="!border-t !border-slate-100 dark:!border-slate-700"
                        >
                            <Button onClick={onClose} variant="ghost" rounded="xl" size="sm" px={5}>
                                Cancel
                            </Button>
                            <Button
                                isLoading={saving}
                                colorScheme='teal'
                                onClick={onSave}
                                rounded="xl"
                                size="sm"
                                px={5}
                                leftIcon={<FaCheckCircle />}
                                _hover={{ transform: "translateY(-1px)", shadow: "lg" }}
                                transition="all 0.2s"
                            >
                                Save Certificate
                            </Button>
                        </ModalFooter>
                    </ModalContent>
                </Modal>
            </>
        );
    }

    return (
        <div className="space-y-4">
            {/* Header */}
            <div className='flex items-center justify-between'>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                    {systems.length} source {systems.length === 1 ? 'system' : 'systems'} configured
                </p>
                <Link href='/managehost/sourcesystems/new'>
                    <Button leftIcon={<FaPlusCircle />} colorScheme='teal' size={"xs"} rounded="full" variant="solid">
                        Add Source
                    </Button>
                </Link>
            </div>

            {/* Table */}
            <div className="overflow-x-auto rounded-xl border border-slate-200/40 dark:border-slate-700/40">
                <table className="min-w-full text-left text-sm">
                    <thead>
                        <tr className="bg-slate-50/80 dark:bg-slate-800/80 text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400">
                            <th className="px-5 py-3 font-medium">Service</th>
                            <th className="px-5 py-3 font-medium">Protocol</th>
                            <th className="px-5 py-3 font-medium">Host</th>
                            <th className="px-5 py-3 font-medium text-center">Port</th>
                            <th className="px-5 py-3 font-medium">Target</th>
                            <th className="px-5 py-3 font-medium">Path</th>
                            <th className="px-5 py-3 font-medium text-center">SSL</th>
                            <th className="px-5 py-3 font-medium text-right">Actions</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 dark:divide-slate-700/40">
                        {systems && systems.map((system: any) => (
                            <tr key={system.id}
                                className="text-slate-700 dark:text-slate-300 hover:bg-slate-50/50 dark:hover:bg-slate-700/20 transition-colors group">
                                {/* Service Name */}
                                <td className="px-5 py-3">
                                    <div className="flex items-center gap-2">
                                        <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-teal-500/20 to-cyan-500/20 flex items-center justify-center flex-shrink-0">
                                            <span className="text-teal-500 text-[10px] font-bold uppercase">
                                                {system.name?.substring(0, 2)}
                                            </span>
                                        </div>
                                        <div>
                                            <p className="font-semibold text-xs text-slate-800 dark:text-white">{system.name}</p>
                                            <p className="text-[10px] text-slate-400 font-mono">{system.groupId}</p>
                                        </div>
                                    </div>
                                </td>

                                {/* Protocol */}
                                <td className="px-5 py-3">
                                    <Badge
                                        colorScheme={system.scheme === 'https' ? 'green' : system.scheme === 'tcp' ? 'purple' : 'gray'}
                                        fontSize="10px"
                                        px={2}
                                        py={0.5}
                                        borderRadius="full"
                                        textTransform="uppercase"
                                        fontWeight="bold"
                                    >
                                        {system.scheme === 'https' ? <FaLock className="inline mr-1" style={{ fontSize: '8px' }} /> : <FaUnlock className="inline mr-1" style={{ fontSize: '8px' }} />}
                                        {system.scheme}
                                    </Badge>
                                </td>

                                {/* Host */}
                                <td className="px-5 py-3">
                                    <Link
                                        href={system.scheme + "://" + system.host}
                                        target='_blank'
                                        className="inline-flex items-center gap-1 text-xs font-mono text-blue-500 dark:text-blue-400 hover:text-blue-600 dark:hover:text-blue-300 transition-colors"
                                    >
                                        {system.tunnel && <FaPiedPiper className='text-red-500' />}
                                        {system.host}
                                        <FaExternalLinkAlt className="text-[8px] opacity-0 group-hover:opacity-100 transition-opacity" />
                                    </Link>
                                </td>

                                {/* Port */}
                                <td className="px-5 py-3 text-center">
                                    <span className="font-mono text-xs text-slate-600 dark:text-slate-400 bg-slate-100 dark:bg-slate-700/50 px-2 py-0.5 rounded">
                                        {system.port}
                                    </span>
                                </td>

                                {/* Target */}
                                <td className="px-5 py-3">
                                    <span className="text-xs font-mono text-slate-600 dark:text-slate-400">{system.target}</span>
                                </td>

                                {/* Base Path */}
                                <td className="px-5 py-3">
                                    <code className="text-xs text-slate-500 dark:text-slate-400">{system.basePath || "/"}</code>
                                </td>

                                {/* SSL Certificate */}
                                <td className="px-5 py-3 text-center">
                                    {system.scheme === "https" ? (
                                        <div className='flex items-center gap-1.5 justify-center'>
                                            {system.certificateId ? (
                                                <Tooltip label={system.certificateId} hasArrow>
                                                    <span><FaCheckCircle className='text-emerald-500' /></span>
                                                </Tooltip>
                                            ) : (
                                                <Tooltip label="No certificate configured" hasArrow>
                                                    <span><FaMinusCircle className='text-red-400' /></span>
                                                </Tooltip>
                                            )}
                                            <AttachCertificate source={system} id={system.certificateId} onUpdate={onLoad} />
                                        </div>
                                    ) : (
                                        <span className="text-[10px] text-slate-400">—</span>
                                    )}
                                </td>

                                {/* Actions */}
                                <td className="px-5 py-3">
                                    <div className='flex gap-1 justify-end'>
                                        <Link href={`/managehost/sourcesystems/${system.id}`}>
                                            <Tooltip label="Edit" hasArrow>
                                                <IconButton aria-label='Edit' icon={<FaEdit />} variant={"ghost"} size={"xs"} colorScheme='blue' />
                                            </Tooltip>
                                        </Link>
                                        <DeleteSystem source={"sourcesystems"} id={system.id} onUpdate={onLoad} />
                                    </div>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>

                {/* Empty State */}
                {(!systems || systems.length === 0) && (
                    <div className="text-center py-12">
                        <FaShieldAlt className="mx-auto text-3xl text-slate-300 dark:text-slate-600 mb-3" />
                        <p className="text-sm text-slate-400">No source systems configured</p>
                        <Link href='/managehost/sourcesystems/new'>
                            <Button mt={3} size="sm" colorScheme="teal" rounded="full" leftIcon={<FaPlusCircle />}>
                                Create First Source
                            </Button>
                        </Link>
                    </div>
                )}
            </div>
        </div>
    );
}