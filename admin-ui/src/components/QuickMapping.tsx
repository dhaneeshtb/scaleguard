import { Button, IconButton, Modal, ModalBody, ModalCloseButton, ModalContent, ModalFooter, ModalHeader, ModalOverlay, Text, useDisclosure, useToast } from "@chakra-ui/react";
import axios from "axios";
import { useState } from "react";
import { useAuth } from "../contexts/AuthContext";
import { FaLink, FaPlusCircle, FaRocket } from "react-icons/fa";
import {
    Box,
    Input,
    VStack,
    HStack,
} from "@chakra-ui/react";
import { DeleteIcon } from "@chakra-ui/icons";

export default function QuickMapping({ onMappingComplete }) {
    const { isOpen, onOpen, onClose } = useDisclosure()
    const [saving, setSaving] = useState<boolean>(false);
    const { auth } = useAuth() as any;
    const [systemName, setSystemName] = useState("");
    const [source, setSource] = useState("");
    const [targets, setTargets] = useState([""]);
    const toast = useToast();

    const handleAddTarget = () => { setTargets([...targets, ""]); };
    const handleRemoveTarget = (index) => { setTargets(targets.filter((_, i) => i !== index)); };
    const handleTargetChange = (index, value) => {
        const newTargets = [...targets];
        newTargets[index] = value;
        setTargets(newTargets);
    };

    const onSave = async () => {
        setSaving(true)
        try {
            await axios.post(auth.data.host + "/config/quickmapping?scaleguard=true", {
                "name": systemName,
                "sourceURL": source,
                "targets": targets
            }, {
                headers: { Authorization: auth.data.token }
            })
            toast({ title: "Mapping created successfully!", status: "success", duration: 2000, isClosable: true });
            setSaving(false);
            onClose();
            onMappingComplete();
        } catch (e: any) {
            toast({ title: "Failed to create mapping", description: e?.response?.data?.message || "An error occurred", status: "error", duration: 3000, isClosable: true });
            setSaving(false);
        }
    }

    return (
        <>
            <Button isLoading={saving} onClick={onOpen} colorScheme='green' leftIcon={<FaPlusCircle />} variant={"solid"} size={"md"} rounded={"full"}>Quick Mapping</Button>

            <Modal isOpen={isOpen} onClose={onClose} isCentered motionPreset="slideInBottom" size="md">
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
                        <div className="absolute inset-0 bg-gradient-to-br from-teal-600 to-cyan-700"></div>
                        <div className="absolute inset-0 opacity-[0.05]" style={{
                            backgroundImage: 'linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)',
                            backgroundSize: '20px 20px'
                        }}></div>
                        <div className="relative px-8 py-5">
                            <div className="flex items-center gap-3">
                                <div className="p-2 bg-white/15 rounded-xl">
                                    <FaLink className="text-white text-sm" />
                                </div>
                                <div>
                                    <h3 className="text-lg font-bold text-white">Quick Mapping</h3>
                                    <p className="text-xs text-white/60">Create source-to-target routing in one step</p>
                                </div>
                            </div>
                        </div>
                    </div>
                    <ModalCloseButton color="white" rounded="full" top={4} right={4} />

                    <ModalBody py={6} px={8}>
                        <VStack spacing={5} align="stretch">
                            {/* System Name */}
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                    System Name <span className="text-red-400 text-xs">*</span>
                                </label>
                                <Input
                                    placeholder="e.g. my-api-gateway"
                                    value={systemName}
                                    onChange={(e) => setSystemName(e.target.value)}
                                    borderRadius="xl"
                                    className="!bg-slate-50 dark:!bg-slate-800"
                                    _focus={{ borderColor: "teal.500", boxShadow: "0 0 0 1px var(--chakra-colors-teal-500)" }}
                                />
                                <p className="text-[11px] text-slate-400 pl-1">A unique identifier for this mapping</p>
                            </div>

                            {/* Source URL */}
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                    Source URL <span className="text-red-400 text-xs">*</span>
                                </label>
                                <Input
                                    placeholder="https://api.example.com"
                                    value={source}
                                    onChange={(e) => setSource(e.target.value)}
                                    borderRadius="xl"
                                    className="!bg-slate-50 dark:!bg-slate-800"
                                    _focus={{ borderColor: "teal.500", boxShadow: "0 0 0 1px var(--chakra-colors-teal-500)" }}
                                />
                                <p className="text-[11px] text-slate-400 pl-1">The incoming source system URL</p>
                            </div>

                            {/* Targets */}
                            <div className="space-y-2">
                                <div className="flex items-center justify-between">
                                    <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                        Target URLs <span className="text-red-400 text-xs">*</span>
                                    </label>
                                    <Button
                                        size="xs"
                                        leftIcon={<FaPlusCircle />}
                                        onClick={handleAddTarget}
                                        colorScheme="teal"
                                        variant="ghost"
                                        rounded="full"
                                    >
                                        Add Target
                                    </Button>
                                </div>
                                {targets.map((target, index) => (
                                    <HStack key={index}>
                                        <div className="w-6 h-6 rounded-full bg-teal-500/10 flex items-center justify-center flex-shrink-0">
                                            <span className="text-[10px] font-bold text-teal-600 dark:text-teal-400">{index + 1}</span>
                                        </div>
                                        <Input
                                            placeholder="http://192.168.1.10:8080"
                                            value={target}
                                            onChange={(e) => handleTargetChange(index, e.target.value)}
                                            borderRadius="xl"
                                            className="!bg-slate-50 dark:!bg-slate-800"
                                            _focus={{ borderColor: "teal.500", boxShadow: "0 0 0 1px var(--chakra-colors-teal-500)" }}
                                        />
                                        <IconButton
                                            icon={<DeleteIcon />}
                                            onClick={() => handleRemoveTarget(index)}
                                            aria-label="Remove Target"
                                            isDisabled={targets.length === 1}
                                            size="sm"
                                            variant="ghost"
                                            colorScheme="red"
                                            rounded="full"
                                        />
                                    </HStack>
                                ))}
                            </div>
                        </VStack>
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
                            leftIcon={<FaRocket />}
                            _hover={{ transform: "translateY(-1px)", shadow: "lg" }}
                            transition="all 0.2s"
                        >
                            Create Mapping
                        </Button>
                    </ModalFooter>
                </ModalContent>
            </Modal>
        </>
    )
}