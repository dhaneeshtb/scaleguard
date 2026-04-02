import React, { useState } from "react";
import {
    Box, Button, Input, VStack, HStack, Text, IconButton, useDisclosure,
    Modal, ModalOverlay, ModalContent, ModalCloseButton, ModalBody, ModalFooter
} from "@chakra-ui/react";
import { DeleteIcon } from "@chakra-ui/icons";
import { FaExclamationTriangle, FaGlobe, FaPlusCircle, FaTrash } from "react-icons/fa";

const NameServers = ({ nameServers, onSave, onDelete }: { nameServers: any[], onSave: (name, ip) => Promise<void>, onDelete: (id) => Promise<void> }) => {
    const [newNameServer, setNewNameServer] = useState("");
    const { isOpen, onOpen, onClose } = useDisclosure();
    const [deleteIndex, setDeleteIndex] = useState<string | null>(null);

    const addNameServer = async () => {
        if (newNameServer.trim() !== "") {
            await onSave(newNameServer.trim(), "")
            setNewNameServer("");
        }
    };

    const confirmDelete = (serverid) => {
        setDeleteIndex(serverid);
        onOpen();
    };

    const deleteNameServer = async () => {
        if (deleteIndex !== null) {
            await onDelete(deleteIndex)
            onClose();
        }
    };

    return (
        <div className="w-full max-w-md">
            {/* Header */}
            <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-2">
                    <div className="p-2 bg-gradient-to-br from-blue-500/10 to-indigo-500/10 rounded-xl">
                        <FaGlobe className="text-blue-500 text-sm" />
                    </div>
                    <div>
                        <h3 className="text-sm font-bold text-slate-800 dark:text-white">Name Servers</h3>
                        <p className="text-[10px] text-slate-400">{nameServers.length} configured</p>
                    </div>
                </div>
            </div>

            {/* Server List */}
            <div className="space-y-2 mb-4">
                {nameServers.map((server, index) => (
                    <div key={index} className="flex items-center gap-3 p-3 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/30 hover:border-blue-300 dark:hover:border-blue-600 transition-all group">
                        <div className="w-7 h-7 rounded-full bg-gradient-to-br from-blue-500 to-indigo-500 flex items-center justify-center flex-shrink-0">
                            <span className="text-[10px] font-bold text-white">{index + 1}</span>
                        </div>
                        <span className="text-sm font-mono text-slate-700 dark:text-slate-300 flex-1 truncate">{server.name}</span>
                        <IconButton
                            aria-label="Delete Name Server"
                            icon={<DeleteIcon />}
                            onClick={() => confirmDelete(server.id)}
                            colorScheme="red"
                            variant="ghost"
                            rounded="full"
                            size="sm"
                            opacity={0.5}
                            _groupHover={{ opacity: 1 }}
                        />
                    </div>
                ))}
            </div>

            {/* Add Input */}
            <div className="flex gap-2">
                <Input
                    placeholder="Enter name server"
                    value={newNameServer}
                    onChange={(e) => setNewNameServer(e.target.value)}
                    borderRadius="xl"
                    bg="white"
                    className="dark:!bg-slate-800"
                    _focus={{ borderColor: "blue.500", boxShadow: "0 0 0 1px var(--chakra-colors-blue-500)" }}
                    size="sm"
                />
                <IconButton
                    rounded="full"
                    icon={<FaPlusCircle />}
                    aria-label="Add"
                    size="sm"
                    colorScheme="blue"
                    onClick={addNameServer}
                    isDisabled={!newNameServer.trim()}
                />
            </div>

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
                                Delete Name Server
                            </h3>
                            <p className="text-sm text-slate-500 dark:text-slate-400">
                                Are you sure you want to remove this name server?
                            </p>
                        </div>
                    </ModalBody>
                    <ModalFooter px={8} pb={6} pt={2} gap={3} justifyContent="center">
                        <Button onClick={onClose} variant="ghost" rounded="xl" size="sm" px={5}>
                            Cancel
                        </Button>
                        <Button
                            colorScheme="red"
                            onClick={deleteNameServer}
                            rounded="xl"
                            size="sm"
                            px={5}
                            leftIcon={<FaTrash />}
                            _hover={{ transform: "translateY(-1px)", shadow: "lg" }}
                            transition="all 0.2s"
                        >
                            Delete
                        </Button>
                    </ModalFooter>
                </ModalContent>
            </Modal>
        </div>
    );
};

export default NameServers;