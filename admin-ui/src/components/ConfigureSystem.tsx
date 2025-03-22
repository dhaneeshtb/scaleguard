import { Button,Switch,Flex, IconButton, Modal, ModalBody, ModalCloseButton, ModalContent, ModalFooter, ModalHeader, ModalOverlay, useDisclosure, FormLabel } from "@chakra-ui/react";
import axios from "axios";
import { useEffect, useState } from "react";
import { useAuth } from "../contexts/AuthContext";
import { FaCogs, FaPlusCircle, FaTrash, FaTrashAlt } from "react-icons/fa";
import { deleteSource } from "./sourceupdate";
import { hostname } from "os";
import useSystemContext from "../contexts/SystemContext";

export function ConfigureSystem({ onUpdate,auth ,load=true}) {
  const { isOpen, onOpen, onClose } = useDisclosure()
  const {properties,updateProperties} = useSystemContext();
  const [domainNames, setDomainNames] = useState<string>(properties?.hostName?.value);
  const [saving, setSaving] = useState<boolean>(false);
  
  const [autoCertificates, setAutocertificate] = useState<boolean>(true);

  const onCreate = async () => {
    setSaving(true)
    onCreateInternal();
  }

  const onCreateInternal = async () => {
    try{
    await axios.post(auth.data.host + "/systems/configure?scaleguard=true", {
     "hostName":domainNames,
     id:properties?.hostName?.id,
     autoCertificates:autoCertificates
    }, {
      headers: {
        Authorization: auth.data.token
      }
    })
  
    await onUpdate()
    setSaving(false);
    onClose();
  }catch(e){
    setSaving(false);

  }

  }

  useEffect(()=>{
    if(load){
      onOpen();
    }

  },[])

  return (
    <>
     {!load && <Button onClick={onOpen} leftIcon={<FaCogs></FaCogs>} variant={"outline"} colorScheme='teal'>System</Button>}


      <Modal isOpen={isOpen} onClose={onClose} closeOnOverlayClick={false} closeOnEsc={false}>
        <ModalOverlay />
        <ModalContent  className='dark:bg-slate-900 dark:text-white shadow-3xl'>
          {!load && <ModalCloseButton></ModalCloseButton>}
          <ModalHeader>Configure System</ModalHeader>
          <ModalBody className='dark:text-white'>
            <label className="block text-sm font-bold mb-1">
              Set System Hostname
            </label>
            <input value={domainNames} onChange={(e) => setDomainNames(e.target.value)} className="dark:bg-black shadow appearance-none border rounded w-full py-2 px-1 " />

            <Flex justify="left" align="left" mt={2}>
              <FormLabel htmlFor="email-alerts">Procure certificates automatically</FormLabel>
              <Switch id="autoCertificates"  isChecked={autoCertificates} onChange={(e)=>setAutocertificate(e.target.checked)}/>
            </Flex>

          </ModalBody>

          <ModalFooter className='dark:text-white'>
            
            <Button isLoading={saving} variant='outline' colorScheme='teal' onClick={onCreate}>Save</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  )
}
