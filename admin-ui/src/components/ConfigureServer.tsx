import { Button, Modal, ModalBody, ModalContent, ModalOverlay, useDisclosure } from "@chakra-ui/react";
import { useEffect, useState } from "react";
import { useAuth } from "../contexts/AuthContext";
import { FaExclamationCircle, FaSignOutAlt } from "react-icons/fa";
import Loader from "../loaders/loader";

export function ConfigureServer({ onUpdate }) {
    const { isOpen, onOpen, onClose } = useDisclosure()
    const [saving, setSaving] = useState<boolean>(false);
    const { auth, setAuthData } = useAuth() as any;

    useEffect(() => {
        onOpen();
        const interval = setInterval(() => { onUpdate(); }, 3000)
        return () => { clearInterval(interval) }
    }, [])

    const logout = () => {
        localStorage.removeItem("authData")
        setAuthData({ data: null })
        window.location.reload();
    }

    return (
        <>
            <Modal isOpen={isOpen} onClose={onClose} closeOnOverlayClick={false} closeOnEsc={false} isCentered>
                <ModalOverlay bg="blackAlpha.700" backdropFilter="blur(12px)" />
                <ModalContent
                    borderRadius="2xl"
                    shadow="2xl"
                    mx={4}
                    overflow="hidden"
                    className="!bg-white dark:!bg-slate-900 !border !border-slate-200 dark:!border-slate-700"
                >
                    <ModalBody py={10} px={8}>
                        <div className="flex flex-col items-center text-center">
                            {/* Warning Icon */}
                            <div className="w-16 h-16 rounded-full bg-amber-500/10 flex items-center justify-center mb-5">
                                <FaExclamationCircle className="text-amber-500 text-2xl" />
                            </div>

                            {/* Title */}
                            <h3 className="text-lg font-bold text-slate-800 dark:text-white mb-2">
                                Host Unreachable
                            </h3>

                            {/* Host info */}
                            <p className="text-sm text-slate-500 dark:text-slate-400 mb-1">
                                Unable to reach the configured host
                            </p>
                            <p className="text-xs font-mono text-slate-400 dark:text-slate-500 bg-slate-100 dark:bg-slate-800 px-3 py-1.5 rounded-lg mb-5 break-all max-w-xs">
                                {auth.data?.host}
                            </p>

                            {/* Loader */}
                            <div className="mb-5">
                                <Loader />
                            </div>

                            <p className="text-xs text-slate-400 dark:text-slate-500 mb-5">
                                Attempting to reconnect automatically...
                            </p>

                            {/* Logout */}
                            <Button
                                onClick={logout}
                                variant="outline"
                                colorScheme="red"
                                rounded="xl"
                                size="sm"
                                px={6}
                                leftIcon={<FaSignOutAlt />}
                                _hover={{ bg: "red.50", _dark: { bg: "red.900/20" } }}
                            >
                                Logout & Reconnect
                            </Button>
                        </div>
                    </ModalBody>
                </ModalContent>
            </Modal>
        </>
    )
}
