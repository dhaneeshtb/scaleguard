import { Button, IconButton, Modal, ModalBody, ModalCloseButton, ModalContent, ModalFooter, ModalHeader, ModalOverlay, useDisclosure } from "@chakra-ui/react";
import axios from "axios";
import { useEffect, useState } from "react";
import { useAuth } from "../contexts/AuthContext";
import { FaExclamationTriangle, FaTrash, FaTrashAlt } from "react-icons/fa";
import { deleteSource } from "./sourceupdate";

export default function DeleteSystem({ source, id, onUpdate = () => { }, buttonType = "small", onAction = null }: { source: string, id: string, onUpdate?: any, buttonType?: string, onAction?: any }) {
    const { isOpen, onOpen, onClose } = useDisclosure()
    const [saving, setSaving] = useState<boolean>(false);
    const { auth } = useAuth() as any

    const onSave = async () => {
        setSaving(true)
        if (onAction) {
            (onAction as any)();
        } else {
            await deleteSource(source, id, auth);
            await onUpdate()
        }
        setSaving(false);
        onClose();
    }

    useEffect(() => { }, [id, source])

    return (
        <>
            {buttonType == "small" ?
                <IconButton colorScheme='red' aria-label='Delete' onClick={onOpen} icon={<FaTrashAlt />} variant={"outline"} size={"xs"} rounded="lg" />
                :
                <Button isLoading={saving} onClick={onOpen} colorScheme='red' leftIcon={<FaTrash />} variant={"outline"} size={"xs"} rounded="full">Delete</Button>
            }

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
                                Are you sure you want to delete this <span className="font-semibold text-slate-700 dark:text-slate-300">{source}</span>?
                            </p>
                            <p className="text-[11px] text-slate-400 dark:text-slate-500 font-mono mt-2 break-all max-w-xs">
                                {id}
                            </p>
                        </div>
                    </ModalBody>

                    <ModalFooter px={8} pb={6} pt={2} gap={3} justifyContent="center">
                        <Button
                            onClick={onClose}
                            variant="ghost"
                            rounded="xl"
                            size="sm"
                            px={6}
                            color="gray.600"
                            className="dark:!text-slate-400"
                        >
                            Cancel
                        </Button>
                        <Button
                            isLoading={saving}
                            colorScheme='red'
                            onClick={onSave}
                            rounded="xl"
                            size="sm"
                            px={6}
                            leftIcon={<FaTrash />}
                            _hover={{ transform: "translateY(-1px)", shadow: "lg" }}
                            transition="all 0.2s"
                        >
                            Delete
                        </Button>
                    </ModalFooter>
                </ModalContent>
            </Modal>
        </>
    )
}