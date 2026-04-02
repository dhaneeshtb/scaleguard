import {
    Button, IconButton, Link, Badge, Tooltip,
    Modal, ModalOverlay, ModalContent, ModalCloseButton, ModalBody, ModalFooter,
    useDisclosure, Heading
} from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState } from 'react';
import {
    FaArrowCircleDown, FaArrowCircleUp, FaCheckCircle, FaClipboard, FaCertificate,
    FaFileDownload, FaInfoCircle, FaKey, FaPlusCircle, FaShieldAlt
} from "react-icons/fa";
import { CopyToClipboard } from 'react-copy-to-clipboard';
import { useToast } from '@chakra-ui/react';
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';
import { formatData } from './sourceupdate';
import VerificationMethodModal from './Verification';


export default function Certificates() {
    const [systems, setSystems] = useState<any>([]);
    const [loading, setLoading] = useState<boolean>(false);
    const { auth } = useAuth() as any;

    const load = async () => {
        const r = await axios.get(auth.data.host + "/certificates?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        });
        setSystems(formatData(r.data));
    }
    useEffect(() => { load(); }, []);

    const deleteItem = async (id) => {
        setLoading(true);
        await axios.delete(auth.data.host + "/certificates/" + id + "?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        });
        await load();
        setLoading(false);
    }

    const verify = async (id, method) => {
        setLoading(true);
        try {
            const resp = await axios.post(auth.data.host + "/certificates/" + id + "/verify?scaleguard=true", {
                challengeType: method
            }, {
                headers: { Authorization: auth.data.token }
            });
            setLoading(false);
            await load();
            return resp;
        } catch (e: any) {
            return e.response;
        }
    }

    function NewCertificate({ onUpdate }) {
        const { isOpen, onOpen, onClose } = useDisclosure();
        const [domainNames, setDomainNames] = useState<string>("");
        const [saving, setSaving] = useState<boolean>(false);

        const onCreate = async () => {
            setSaving(true);
            await axios.post(auth.data.host + "/certificates?scaleguard=true", {
                domainNames: domainNames.split(",")
            }, {
                headers: { Authorization: auth.data.token }
            });
            await onUpdate();
            setSaving(false);
            onClose();
        }

        return (
            <>
                <Button onClick={onOpen} leftIcon={<FaPlusCircle />} colorScheme='teal' size={"xs"} rounded="full">
                    New Certificate
                </Button>

                <Modal isOpen={isOpen} onClose={onClose} isCentered motionPreset="slideInBottom">
                    <ModalOverlay bg="blackAlpha.600" backdropFilter="blur(8px)" />
                    <ModalContent
                        borderRadius="2xl" shadow="2xl" mx={4} overflow="hidden"
                        className="!bg-white dark:!bg-slate-900 !border !border-slate-200 dark:!border-slate-700"
                    >
                        {/* Header */}
                        <div className="relative overflow-hidden">
                            <div className="absolute inset-0 bg-gradient-to-br from-emerald-600 to-teal-700"></div>
                            <div className="absolute inset-0 opacity-[0.05]" style={{
                                backgroundImage: 'linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)',
                                backgroundSize: '20px 20px'
                            }}></div>
                            <div className="relative px-8 py-5">
                                <div className="flex items-center gap-3">
                                    <div className="p-2 bg-white/15 rounded-xl">
                                        <FaCertificate className="text-white text-sm" />
                                    </div>
                                    <div>
                                        <h3 className="text-lg font-bold text-white">Request Certificate</h3>
                                        <p className="text-xs text-white/60">Issue a new SSL/TLS certificate</p>
                                    </div>
                                </div>
                            </div>
                        </div>
                        <ModalCloseButton color="white" rounded="full" top={4} right={4} />

                        <ModalBody py={6} px={8}>
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                    Domain Names <span className="text-red-400 text-xs">*</span>
                                </label>
                                <input
                                    value={domainNames}
                                    onChange={(e) => setDomainNames(e.target.value)}
                                    className="w-full px-4 py-2.5 rounded-xl bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-white text-sm focus:ring-2 focus:ring-teal-500/40 focus:border-teal-500 transition-all outline-none"
                                    placeholder="example.com, api.example.com"
                                />
                                <p className="text-[11px] text-slate-400 pl-1">Comma-separated domain names for the certificate</p>
                            </div>
                        </ModalBody>

                        <ModalFooter px={8} pb={6} gap={3} className="!border-t !border-slate-100 dark:!border-slate-700">
                            <Button onClick={onClose} variant="ghost" rounded="xl" size="sm" px={5}>Cancel</Button>
                            <Button isLoading={saving} colorScheme='teal' onClick={onCreate} rounded="xl" size="sm" px={5} leftIcon={<FaPlusCircle />}
                                _hover={{ transform: "translateY(-1px)", shadow: "lg" }} transition="all 0.2s">
                                Request Certificate
                            </Button>
                        </ModalFooter>
                    </ModalContent>
                </Modal>
            </>
        )
    }


    function DnsChallenge({ info }) {
        const { isOpen, onOpen, onClose } = useDisclosure();
        return (
            <>
                <Button onClick={onOpen} leftIcon={<FaInfoCircle />} size={"xs"} variant={"outline"} rounded="full" colorScheme="blue">Challenge Info</Button>

                <Modal isOpen={isOpen} onClose={onClose} isCentered motionPreset="slideInBottom" size="lg">
                    <ModalOverlay bg="blackAlpha.600" backdropFilter="blur(8px)" />
                    <ModalContent
                        borderRadius="2xl" shadow="2xl" mx={4} overflow="hidden"
                        className="!bg-white dark:!bg-slate-900 !border !border-slate-200 dark:!border-slate-700"
                    >
                        {/* Header */}
                        <div className="relative overflow-hidden">
                            <div className="absolute inset-0 bg-gradient-to-br from-blue-600 to-indigo-700"></div>
                            <div className="absolute inset-0 opacity-[0.05]" style={{
                                backgroundImage: 'linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)',
                                backgroundSize: '20px 20px'
                            }}></div>
                            <div className="relative px-8 py-5">
                                <div className="flex items-center gap-3">
                                    <div className="p-2 bg-white/15 rounded-xl">
                                        <FaShieldAlt className="text-white text-sm" />
                                    </div>
                                    <div>
                                        <h3 className="text-lg font-bold text-white">Auth Challenge Info</h3>
                                        <p className="text-xs text-white/60">Verification details for domain ownership</p>
                                    </div>
                                </div>
                            </div>
                        </div>
                        <ModalCloseButton color="white" rounded="full" top={4} right={4} />

                        <ModalBody py={6} px={8}>
                            <div className="space-y-5">
                                {/* HTTP Challenge */}
                                <div className="space-y-2">
                                    <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-2">
                                        <Badge colorScheme="blue" fontSize="9px" px={2} borderRadius="full">HTTP</Badge>
                                        Challenge Info
                                    </h4>
                                    <div className="bg-slate-50 dark:bg-slate-800/60 rounded-xl p-3 border border-slate-200 dark:border-slate-700/40">
                                        <code className='text-slate-500 dark:text-slate-400 text-xs break-words'>{info.httpChallenge.message}</code>
                                    </div>
                                </div>

                                {/* DNS Challenge */}
                                {info.dnsChallenge && (
                                    <div className="space-y-2">
                                        <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-2">
                                            <Badge colorScheme="purple" fontSize="9px" px={2} borderRadius="full">DNS</Badge>
                                            Challenge Info
                                        </h4>
                                        <div className="bg-slate-50 dark:bg-slate-800/60 rounded-xl p-3 border border-slate-200 dark:border-slate-700/40">
                                            <code className='text-slate-500 dark:text-slate-400 text-xs break-words'>{info.dnsChallenge.message}</code>
                                        </div>
                                        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700/40 mt-2">
                                            <table className="min-w-full text-left text-sm">
                                                <thead>
                                                    <tr className="bg-slate-50 dark:bg-slate-800/80 text-xs uppercase tracking-wider text-slate-500">
                                                        <th className="px-4 py-2.5 font-medium">Type</th>
                                                        <th className="px-4 py-2.5 font-medium">Name</th>
                                                        <th className="px-4 py-2.5 font-medium">Content</th>
                                                    </tr>
                                                </thead>
                                                <tbody>
                                                    <tr className="border-t border-slate-100 dark:border-slate-700/40">
                                                        <td className="px-4 py-2.5 text-xs font-mono text-slate-600 dark:text-slate-400">{info.dnsChallenge.dnsRecordType}</td>
                                                        <td className="px-4 py-2.5 text-xs font-mono text-slate-600 dark:text-slate-400 break-all">{info.dnsChallenge.domainRecordName}</td>
                                                        <td className="px-4 py-2.5 text-xs font-mono text-slate-600 dark:text-slate-400 break-all">{info.dnsChallenge.dnsRecordValue}</td>
                                                    </tr>
                                                </tbody>
                                            </table>
                                        </div>
                                    </div>
                                )}
                            </div>
                        </ModalBody>

                        <ModalFooter px={8} pb={6} className="!border-t !border-slate-100 dark:!border-slate-700">
                            <Button onClick={onClose} colorScheme="gray" variant="ghost" rounded="xl" size="sm" px={5}>Close</Button>
                        </ModalFooter>
                    </ModalContent>
                </Modal>
            </>
        )
    }

    function HttpChallenge({ id }) {
        const { isOpen, onOpen, onClose } = useDisclosure();
        const [certificate, setCertificate] = useState<any>({});
        const [loading, setLoading] = useState<boolean>(false);
        const toast = useToast();
        const filePrivateKey = new Blob([certificate.privateKey], { type: 'text/plain' });
        const fileCertificate = new Blob([certificate.certificate], { type: 'text/plain' });

        const alertit = (keyType) => {
            toast({ title: keyType + ' Copied.', description: "Copied " + keyType + " to clipboard", status: 'success', position: "top", duration: 3000, isClosable: true });
        }

        const load = async () => {
            try {
                const r = await axios.get(auth.data.host + "/certificates/" + id + "/download?scaleguard=true", {
                    headers: { Authorization: auth.data.token }
                });
                setCertificate(r.data);
            } catch (e) { }
        }

        const openView = async () => { await load(); onOpen(); }
        useEffect(() => { }, [id]);

        return (
            <>
                <Button size={"xs"} variant={"outline"} colorScheme='gray' isLoading={loading} onClick={openView} leftIcon={<FaFileDownload />} rounded="full">Download</Button>

                <Modal isOpen={isOpen} onClose={onClose} size={"xl"} isCentered motionPreset="slideInBottom">
                    <ModalOverlay bg="blackAlpha.600" backdropFilter="blur(8px)" />
                    <ModalContent
                        borderRadius="2xl" shadow="2xl" mx={4} overflow="hidden"
                        className="!bg-white dark:!bg-slate-900 !border !border-slate-200 dark:!border-slate-700"
                    >
                        {/* Header */}
                        <div className="relative overflow-hidden">
                            <div className="absolute inset-0 bg-gradient-to-br from-amber-600 to-orange-700"></div>
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
                                        <h3 className="text-lg font-bold text-white">Download Certificate</h3>
                                        <p className="text-xs text-white/60">Private key and certificate files</p>
                                    </div>
                                </div>
                            </div>
                        </div>
                        <ModalCloseButton color="white" rounded="full" top={4} right={4} />

                        <ModalBody py={6} px={8}>
                            <div className="space-y-5">
                                {/* Private Key */}
                                <div className="space-y-2">
                                    <div className="flex items-center justify-between">
                                        <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-2">
                                            <FaKey className="text-amber-500 text-xs" /> Private Key
                                        </h4>
                                        <div className='flex gap-1.5'>
                                            <Tooltip label="Download" hasArrow>
                                                <a download="private.key" target="_blank" rel="noreferrer" href={URL.createObjectURL(filePrivateKey)}>
                                                    <IconButton variant="ghost" size="xs" colorScheme='teal' aria-label='Download' icon={<FaFileDownload />} rounded="full" />
                                                </a>
                                            </Tooltip>
                                            <CopyToClipboard text={certificate.privateKey} onCopy={() => alertit("Private Key")}>
                                                <Tooltip label="Copy" hasArrow>
                                                    <IconButton aria-label='Copy' icon={<FaClipboard />} colorScheme='teal' size="xs" variant="ghost" rounded="full" />
                                                </Tooltip>
                                            </CopyToClipboard>
                                        </div>
                                    </div>
                                    <textarea value={certificate.privateKey} className='w-full dark:bg-slate-800 bg-slate-50 rounded-xl p-3 text-xs text-slate-600 dark:text-slate-400 font-mono h-[200px] border border-slate-200 dark:border-slate-700 resize-none outline-none' readOnly />
                                </div>

                                {/* Certificate */}
                                <div className="space-y-2">
                                    <div className="flex items-center justify-between">
                                        <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-2">
                                            <FaCertificate className="text-emerald-500 text-xs" /> Certificate
                                        </h4>
                                        <div className='flex gap-1.5'>
                                            <Tooltip label="Download" hasArrow>
                                                <a download="server.crt" target="_blank" rel="noreferrer" href={URL.createObjectURL(fileCertificate)}>
                                                    <IconButton variant="ghost" size="xs" colorScheme='teal' aria-label='Download' icon={<FaFileDownload />} rounded="full" />
                                                </a>
                                            </Tooltip>
                                            <CopyToClipboard text={certificate.certificate} onCopy={() => alertit("Certificate")}>
                                                <Tooltip label="Copy" hasArrow>
                                                    <IconButton aria-label='Copy' icon={<FaClipboard />} colorScheme='teal' size="xs" variant="ghost" rounded="full" />
                                                </Tooltip>
                                            </CopyToClipboard>
                                        </div>
                                    </div>
                                    <textarea value={certificate.certificate} className='w-full dark:bg-slate-800 bg-slate-50 rounded-xl p-3 text-xs text-slate-600 dark:text-slate-400 font-mono h-[200px] border border-slate-200 dark:border-slate-700 resize-none outline-none' readOnly />
                                </div>
                            </div>
                        </ModalBody>
                    </ModalContent>
                </Modal>
            </>
        )
    }

    return (
        <div className="flex flex-col">
            <div className='flex gap-2 justify-end px-5 py-3'>
                <NewCertificate onUpdate={load} />
            </div>
            <div className="overflow-x-auto">
                <div className="inline-block min-w-full py-2 px-4">
                    <div className="overflow-hidden rounded-xl border border-slate-200/40 dark:border-slate-700/40">
                        <table className="min-w-full text-left text-sm">
                            <thead>
                                <tr className="bg-slate-50/80 dark:bg-slate-800/80 text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400">
                                    <th className="px-5 py-3 font-medium">#ID</th>
                                    <th className="px-5 py-3 font-medium">Status</th>
                                    <th className="px-5 py-3 font-medium">Expiry</th>
                                    <th className="px-5 py-3 font-medium">Domains</th>
                                    <th className="px-5 py-3 font-medium">Auth</th>
                                    <th className="px-5 py-3 font-medium">Download</th>
                                    <th className="px-5 py-3 font-medium">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 dark:divide-slate-700/40">
                                {systems && systems.map((system: any) => (
                                    <tr key={system.id} className="text-slate-700 dark:text-slate-300 hover:bg-slate-50/50 dark:hover:bg-slate-700/20 transition-colors">
                                        <td className="px-5 py-3 font-mono text-xs">{system.id}</td>
                                        <td className="px-5 py-3">
                                            <div className='flex items-center gap-1.5'>
                                                {system.json.status === "valid" ?
                                                    <><span className="w-2 h-2 rounded-full bg-emerald-400"></span><Badge colorScheme="green" fontSize="10px" px={2} borderRadius="full">{system.json.status}</Badge></>
                                                    :
                                                    <><span className="w-2 h-2 rounded-full bg-amber-400"></span><Badge colorScheme="yellow" fontSize="10px" px={2} borderRadius="full">{system.json.status}</Badge></>
                                                }
                                            </div>
                                        </td>
                                        <td className="px-5 py-3 text-xs">{system.json.status === "valid" ? new Date(system.expiryTime).toLocaleString() : (system.json.expires ? new Date(system.json.expires).toLocaleString() : "N/A")}</td>
                                        <td className="px-5 py-3 text-xs font-mono">{system.json.identifiers.map(s => s.value).join(", ")}</td>
                                        <td className="px-5 py-3">
                                            {system.json.status === "valid" ?
                                                <Button isDisabled colorScheme='green' leftIcon={<FaCheckCircle />} size="xs" variant="outline" rounded="full">Valid</Button>
                                                : <DnsChallenge info={system} />}
                                        </td>
                                        <td className="px-5 py-3"><HttpChallenge id={system.id} /></td>
                                        <td className="px-5 py-3">
                                            <div className="flex gap-1.5 items-center">
                                                {system.json.status !== "valid" && <VerificationMethodModal id={system.id} onContinue={verify} />}
                                                <DeleteSystem id={system.id} source={"Certificate"} onAction={() => deleteItem(system.id) as any} buttonType='big' />
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                        {(!systems || systems.length === 0) && (
                            <div className="text-center py-12">
                                <FaCertificate className="mx-auto text-3xl text-slate-300 dark:text-slate-600 mb-3" />
                                <p className="text-sm text-slate-400">No certificates configured</p>
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}