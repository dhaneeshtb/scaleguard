import { Button, Modal, ModalOverlay, ModalContent, ModalHeader, ModalCloseButton, ModalBody, ModalFooter, Radio, RadioGroup, Stack, useDisclosure } from "@chakra-ui/react";
import { useState } from "react";
import { FaCheck } from "react-icons/fa";

export default function VerificationMethodModal({id,onContinue}) {
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [method, setMethod] = useState("http");

  const [loading,setLoading] = useState(false);
  const handleConfirm = async () => {
    setLoading(true)
    await onContinue(id,method)
    setLoading(false)
    onClose();

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
          </ModalBody>

          <ModalFooter>
          <Button  colorScheme="gray" rounded={"3xl"}  onClick={onClose}  mr={3}>Cancel</Button>

            <Button colorScheme="teal" variant="outline" rounded={"3xl"}   onClick={handleConfirm}>
              Confirm
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
}
