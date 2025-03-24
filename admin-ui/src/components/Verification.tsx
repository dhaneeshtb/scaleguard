import { Button, Modal, ModalOverlay, ModalContent, ModalHeader, ModalCloseButton, ModalBody, ModalFooter, Radio, RadioGroup, Stack, useDisclosure, Text } from "@chakra-ui/react";
import { useState } from "react";
import { FaCheck } from "react-icons/fa";
import { Alert, AlertIcon, AlertTitle, AlertDescription, CloseButton, Box } from "@chakra-ui/react";

export default function VerificationMethodModal({id,onContinue}) {
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [method, setMethod] = useState("http");
  const [errorMessage, setErrorMessage] = useState("");

  const [loading,setLoading] = useState(false);
  const handleConfirm = async () => {
    
    setErrorMessage("")
    setLoading(true)
    const resp = await onContinue(id,method)
    if(resp.status==200){
      onClose();
    }else{
      setErrorMessage(resp.data.message)
    }
    setLoading(false)

  };



  return (
    <>

    <Button isLoading={loading} onClick={onOpen} colorScheme='green' leftIcon={<FaCheck></FaCheck>} variant={"outline"} size={"xs"}>Verify</Button>
      {/* <Button colorScheme="blue" onClick={onOpen}>Select Verification Method</Button> */}

      <Modal isOpen={isOpen} onClose={onClose} isCentered>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Select Verification Method</ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            <RadioGroup onChange={setMethod} value={method}>
              <Stack spacing={4}>
                <Radio value="http">HTTP</Radio>
                <Radio value="dns">DNS</Radio>
              </Stack>
            </RadioGroup>

            

           {errorMessage && <Alert status="error" borderRadius="md" boxShadow="lg" className="flex flex-col mt-2 p-2">
        
        <Box flex="1" >
          <AlertTitle className="flex flex-row mb-2"><AlertIcon />Error!</AlertTitle>
          <AlertDescription className="text-sm break-all">{errorMessage}</AlertDescription>
        </Box>
        <CloseButton position="absolute" right={2} top={2} onClick={() => setErrorMessage("")} />
      </Alert> }
          </ModalBody>

          <ModalFooter>
          <Button  colorScheme="gray" rounded={"3xl"}  onClick={onClose}  mr={3}>Cancel</Button>

            <Button isLoading={loading} colorScheme="teal" variant="outline" rounded={"3xl"}   onClick={handleConfirm}>
              Confirm
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
}
