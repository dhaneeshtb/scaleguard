import { Button, IconButton, Modal, ModalBody, ModalCloseButton, ModalContent, ModalFooter, ModalHeader, ModalOverlay, Text, useDisclosure, useToast } from "@chakra-ui/react";
import axios from "axios";
import { useEffect, useState } from "react";
import { useAuth } from "../contexts/AuthContext";
import { FaCogs, FaPlusCircle, FaTrash, FaTrashAlt } from "react-icons/fa";
import { deleteSource } from "./sourceupdate";
import {
  Box,
  Input,
  VStack,
  HStack,
} from "@chakra-ui/react";
import { AddIcon, DeleteIcon } from "@chakra-ui/icons";
export default function QuickMapping({onMappingComplete}) {
  const { isOpen, onOpen, onClose } = useDisclosure()
  const [saving, setSaving] = useState<boolean>(false);

  const { auth } = useAuth() as any
  const [systemName, setSystemName] = useState("");

  const [source, setSource] = useState("");
  const [targets, setTargets] = useState([""]);
  const toast = useToast();

  const handleAddTarget = () => {
    setTargets([...targets, ""]);
  };

  const handleRemoveTarget = (index) => {
    setTargets(targets.filter((_, i) => i !== index));
  };





  const onSave = async () => {
    setSaving(true)
    try{
    await axios.post(auth.data.host + "/config/quickmapping?scaleguard=true", {
      "name":systemName,
      "sourceURL": source,
      "targets": targets
    }, {
      headers: {
        Authorization: auth.data.token
      }
    })

    toast({
      title: "Saving successful!",
      status: "success",
      duration: 2000,
      isClosable: true,
    });


    setSaving(false);
    onClose();
    onMappingComplete();
  }catch(e:any){
    e.response.data

    toast({
      title: "Saving Failed ",
      status: "error",
      duration: 2000,
      isClosable: true,
    });


    setSaving(false);
  }

  }
  const handleTargetChange = (index, value) => {
    const newTargets = [...targets];
    newTargets[index] = value;
    setTargets(newTargets);
  };

  useEffect(() => {



  }, [])

  return (
    <>



      <Button isLoading={saving} onClick={onOpen} colorScheme='green' leftIcon={<FaPlusCircle></FaPlusCircle>} variant={"solid"} size={"md"} rounded={"full"}>Quick Mapping</Button>


      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent className='dark:bg-slate-900 dark:text-white shadow-3xl'>
          <ModalHeader>Quick Mapping</ModalHeader>
          <ModalCloseButton />
          <ModalBody className='dark:text-white'>


            <Box p={4} borderWidth={1} borderRadius="md" w="400px">
              
              <VStack spacing={4}>

              <VStack spacing={1} className="w-full">
              <Text className=" text-sm text-gray-400 text-left w-full">System Name(my_mapping)</Text>
              <Input
                  placeholder="System Name"
                  value={systemName}
                  onChange={(e) => setSystemName(e.target.value)}
                />
                </VStack>

                <VStack spacing={1} className="w-full">
                <Text className=" text-sm text-gray-400 text-left w-full">Source System URL:(https://a.example.com)</Text>
                <Input
                  placeholder="Source URL"
                  value={source}
                  onChange={(e) => setSource(e.target.value)}
                />
                
                </VStack>
                <Button leftIcon={<AddIcon />} onClick={handleAddTarget} colorScheme="blue" w="full">
                  Add Target
                </Button>
                <Text className=" text-sm text-gray-400 text-left w-full">Target URL:(http://17.1.2.4:9000)</Text>

                {targets.map((target, index) => (
                  <HStack key={index} w="full">
                    <Input
                      placeholder="Target URL"
                      value={target}
                      onChange={(e) => handleTargetChange(index, e.target.value)}
                    />
                    <IconButton
                      icon={<DeleteIcon />}
                      onClick={() => handleRemoveTarget(index)}
                      aria-label="Remove Target"
                      isDisabled={targets.length === 1}
                    />
                  </HStack>
                ))}
              </VStack>
            </Box>

          </ModalBody>

          <ModalFooter className='dark:text-white'>
            <Button colorScheme='blue' mr={3} onClick={onClose}>
              Close
            </Button>
            <Button isLoading={saving} variant='outline' colorScheme='teal' onClick={onSave}>Confirm</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  )
}