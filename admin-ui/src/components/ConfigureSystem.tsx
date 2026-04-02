import { Button, Switch, Flex, Modal, ModalBody, ModalCloseButton, ModalContent, ModalFooter, ModalHeader, ModalOverlay, useDisclosure, FormLabel } from "@chakra-ui/react";
import axios from "axios";
import { useEffect, useState } from "react";
import { useAuth } from "../contexts/AuthContext";
import { FaCogs, FaSave, FaServer } from "react-icons/fa";
import useSystemContext from "../contexts/SystemContext";

export function ConfigureSystem({ onUpdate, auth, load = true }) {
    const { isOpen, onOpen, onClose } = useDisclosure()
    const { properties, updateProperties } = useSystemContext();
    const [domainNames, setDomainNames] = useState<string>(properties?.hostName?.value);
    const [saving, setSaving] = useState<boolean>(false);
    const [autoCertificates, setAutocertificate] = useState<boolean>(true);

    const onCreate = async () => {
        setSaving(true)
        onCreateInternal();
    }

    const onCreateInternal = async () => {
        try {
            await axios.post(auth.data.host + "/systems/configure?scaleguard=true", {
                "hostName": domainNames,
                id: properties?.hostName?.id,
                autoCertificates: autoCertificates
            }, {
                headers: { Authorization: auth.data.token }
            })
            await onUpdate()
            setSaving(false);
            onClose();
        } catch (e) {
            setSaving(false);
        }
    }

    useEffect(() => {
        if (load) { onOpen(); }
    }, [])

    return (
        <>
            {!load && <Button rounded={"full"} onClick={onOpen} leftIcon={<FaCogs />} variant={"outline"} colorScheme='teal'>System</Button>}

            <Modal isOpen={isOpen} onClose={onClose} closeOnOverlayClick={!load} closeOnEsc={!load} isCentered motionPreset="slideInBottom">
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
                        <div className="absolute inset-0 bg-gradient-to-br from-slate-800 to-slate-900"></div>
                        <div className="absolute inset-0 bg-gradient-to-br from-teal-500/20 to-cyan-500/20"></div>
                        <div className="absolute inset-0 opacity-[0.05]" style={{
                            backgroundImage: 'linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)',
                            backgroundSize: '20px 20px'
                        }}></div>
                        <div className="relative px-8 py-5">
                            <div className="flex items-center gap-3">
                                <div className="p-2 bg-white/15 rounded-xl">
                                    <FaServer className="text-white text-sm" />
                                </div>
                                <div>
                                    <h3 className="text-lg font-bold text-white">Configure System</h3>
                                    <p className="text-xs text-white/60">Set up your system hostname and certificates</p>
                                </div>
                            </div>
                        </div>
                    </div>
                    {!load && <ModalCloseButton color="white" rounded="full" top={4} right={4} />}

                    <ModalBody py={6} px={8}>
                        <div className="space-y-5">
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                    System Hostname <span className="text-red-400 text-xs">*</span>
                                </label>
                                <input
                                    value={domainNames}
                                    onChange={(e) => setDomainNames(e.target.value)}
                                    className="w-full px-4 py-2.5 rounded-xl bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-white text-sm focus:ring-2 focus:ring-teal-500/40 focus:border-teal-500 transition-all outline-none"
                                    placeholder="e.g. router.example.com"
                                />
                                <p className="text-[11px] text-slate-400 pl-1">The primary hostname for this Scaleguard instance</p>
                            </div>

                            <div className="flex items-center justify-between p-3 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/30">
                                <div className="flex flex-col">
                                    <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Auto-procure certificates</span>
                                    <span className="text-[11px] text-slate-400">Automatically request and renew SSL certificates</span>
                                </div>
                                <Switch id="autoCertificates" isChecked={autoCertificates} onChange={(e) => setAutocertificate(e.target.checked)} colorScheme="teal" size="md" />
                            </div>
                        </div>
                    </ModalBody>

                    <ModalFooter
                        px={8} pb={6}
                        gap={3}
                        className="!border-t !border-slate-100 dark:!border-slate-700"
                    >
                        <Button
                            isLoading={saving}
                            colorScheme='teal'
                            onClick={onCreate}
                            rounded="xl"
                            size="sm"
                            px={6}
                            leftIcon={<FaSave />}
                            _hover={{ transform: "translateY(-1px)", shadow: "lg" }}
                            transition="all 0.2s"
                            w="full"
                        >
                            Save Configuration
                        </Button>
                    </ModalFooter>
                </ModalContent>
            </Modal>
        </>
    )
}
