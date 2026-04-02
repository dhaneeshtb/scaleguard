import { Button, Modal, ModalOverlay, ModalContent, ModalCloseButton, ModalBody, ModalFooter, Radio, RadioGroup, Stack, useDisclosure } from "@chakra-ui/react";
import { useState } from "react";
import { FaCheck, FaGlobe, FaShieldAlt } from "react-icons/fa";
import { Alert, AlertIcon, AlertTitle, AlertDescription, CloseButton, Box } from "@chakra-ui/react";

export default function VerificationMethodModal({ id, onContinue }) {
    const { isOpen, onOpen, onClose } = useDisclosure();
    const [method, setMethod] = useState("http");
    const [errorMessage, setErrorMessage] = useState("");
    const [loading, setLoading] = useState(false);

    const handleConfirm = async () => {
        setErrorMessage("")
        setLoading(true)
        const resp = await onContinue(id, method)
        if (resp.status == 200) {
            onClose();
        } else {
            setErrorMessage(resp.data.message)
        }
        setLoading(false)
    };

    return (
        <>
            <Button isLoading={loading} onClick={onOpen} colorScheme='green' leftIcon={<FaCheck />} variant={"outline"} size={"xs"} rounded="full">Verify</Button>

            <Modal isOpen={isOpen} onClose={onClose} isCentered motionPreset="slideInBottom">
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
                        <div className="absolute inset-0 bg-gradient-to-br from-emerald-600 to-teal-700"></div>
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
                                    <h3 className="text-lg font-bold text-white">Certificate Verification</h3>
                                    <p className="text-xs text-white/60">Choose a validation method</p>
                                </div>
                            </div>
                        </div>
                    </div>
                    <ModalCloseButton color="white" rounded="full" top={4} right={4} />

                    <ModalBody py={6} px={8}>
                        <p className="text-sm text-slate-600 dark:text-slate-400 mb-4">
                            Select how you want to verify domain ownership:
                        </p>
                        <RadioGroup onChange={setMethod} value={method}>
                            <Stack spacing={3}>
                                <label className={`flex items-center gap-3 p-3 rounded-xl border cursor-pointer transition-all ${method === 'http'
                                    ? 'border-teal-500 bg-teal-500/5 dark:bg-teal-500/10'
                                    : 'border-slate-200 dark:border-slate-700 hover:border-teal-300 dark:hover:border-teal-600'
                                    }`}>
                                    <Radio value="http" colorScheme="teal" />
                                    <div>
                                        <p className="text-sm font-medium text-slate-800 dark:text-white flex items-center gap-2">
                                            <FaGlobe className="text-blue-500 text-xs" /> HTTP Challenge
                                        </p>
                                        <p className="text-[11px] text-slate-400 mt-0.5">Verify via HTTP file on your server</p>
                                    </div>
                                </label>
                                <label className={`flex items-center gap-3 p-3 rounded-xl border cursor-pointer transition-all ${method === 'dns'
                                    ? 'border-teal-500 bg-teal-500/5 dark:bg-teal-500/10'
                                    : 'border-slate-200 dark:border-slate-700 hover:border-teal-300 dark:hover:border-teal-600'
                                    }`}>
                                    <Radio value="dns" colorScheme="teal" />
                                    <div>
                                        <p className="text-sm font-medium text-slate-800 dark:text-white flex items-center gap-2">
                                            <FaGlobe className="text-violet-500 text-xs" /> DNS Challenge
                                        </p>
                                        <p className="text-[11px] text-slate-400 mt-0.5">Verify via DNS TXT record</p>
                                    </div>
                                </label>
                            </Stack>
                        </RadioGroup>

                        {errorMessage && (
                            <Alert status="error" borderRadius="xl" mt={4} bg="red.50" _dark={{ bg: "red.900/20" }}>
                                <AlertIcon />
                                <Box flex="1">
                                    <AlertTitle fontSize="sm">Verification Failed</AlertTitle>
                                    <AlertDescription fontSize="xs" className="break-all">{errorMessage}</AlertDescription>
                                </Box>
                                <CloseButton position="absolute" right={2} top={2} onClick={() => setErrorMessage("")} size="sm" />
                            </Alert>
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
                            isLoading={loading}
                            colorScheme="teal"
                            onClick={handleConfirm}
                            rounded="xl"
                            size="sm"
                            px={5}
                            leftIcon={<FaCheck />}
                            _hover={{ transform: "translateY(-1px)", shadow: "lg" }}
                            transition="all 0.2s"
                        >
                            Verify Certificate
                        </Button>
                    </ModalFooter>
                </ModalContent>
            </Modal>
        </>
    );
}
