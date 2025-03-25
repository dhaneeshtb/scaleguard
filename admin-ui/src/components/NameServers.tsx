import React, { useState } from "react";
import {
  Box,
  Button,
  Input,
  VStack,
  HStack,
  Text,
  IconButton,
  useDisclosure,
  Modal,
  ModalOverlay,
  ModalContent,
  ModalHeader,
  ModalBody,
  ModalFooter
} from "@chakra-ui/react";
import { DeleteIcon } from "@chakra-ui/icons";

const NameServers = ({nameServers,onSave,onDelete}:{nameServers:any[],onSave: (name,ip)=>Promise<void>,onDelete:(id)=>Promise<void>}) => {
//   const [nameServers, setNameServers] = useState(serverlist);
  const [newNameServer, setNewNameServer] = useState("");
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [deleteIndex, setDeleteIndex] = useState<string|null>(null);

  const addNameServer = async () => {
    if (newNameServer.trim() !== "") {
    //   setNameServers([...nameServers, newNameServer.trim()]);
      await onSave(newNameServer.trim(),"")
      setNewNameServer("");
    }
  };

  const confirmDelete = (serverid) => {
    setDeleteIndex(serverid);
    onOpen();
  };

  const deleteNameServer = async (id) => {
    if (deleteIndex !== null) {
      await onDelete(deleteIndex)
      onClose();
    }
  };

  return (
    <Box p={4} w="400px" borderWidth={1} borderRadius="lg" boxShadow="md">
      <VStack spacing={4} align="stretch">
        {nameServers.map((server, index) => (
          <HStack key={index} justifyContent="space-between" p={2} borderWidth={1} borderRadius="md">
            <Text>{server.name}</Text>
            <IconButton
              aria-label="Delete Name Server"
              icon={<DeleteIcon />}
              onClick={() => confirmDelete(server.id)}
              colorScheme="red"
              size="sm"
            />
          </HStack>
        ))}
        <HStack>
          <Input
            placeholder="Enter name server"
            value={newNameServer}
            onChange={(e) => setNewNameServer(e.target.value)}
          />
          <Button colorScheme="blue" onClick={addNameServer}>
            Add
          </Button>
        </HStack>
      </VStack>

      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay>
          <ModalContent>
            <ModalHeader>Confirm Deletion</ModalHeader>
            <ModalBody>
              Are you sure you want to delete this name server?
            </ModalBody>
            <ModalFooter>
              <Button onClick={onClose}>Cancel</Button>
              <Button colorScheme="red" onClick={deleteNameServer} ml={3}>
                Delete
              </Button>
            </ModalFooter>
          </ModalContent>
        </ModalOverlay>
      </Modal>
    </Box>
  );
};

export default NameServers;