import { Button, IconButton, Modal, ModalBody, ModalCloseButton, ModalContent, ModalFooter, ModalHeader, ModalOverlay, useDisclosure } from "@chakra-ui/react";
import axios from "axios";
import { useEffect, useState } from "react";
import { useAuth } from "../contexts/AuthContext";
import { FaCogs, FaTrash, FaTrashAlt } from "react-icons/fa";
import { deleteSource } from "./sourceupdate";

export default function DeleteSystem({source,id,onUpdate=()=>{},buttonType="small",onAction=null}:{source:string,id:string,onUpdate?:any,buttonType?:string,onAction?:any}) {
    const { isOpen, onOpen, onClose } = useDisclosure()
    const [certificates,setCertificates] = useState<any[]>([]);
    const [saving,setSaving] = useState<boolean>(false);

    const [selectedId,setSelectedId] = useState<string>(id);
    const [selectedCert,setSelectedCert] = useState<any>(null);
    const {auth} = useAuth() as any



    
    

    const onSelectId=(selectedId)=>{
        certificates.forEach(d=>{
            if(d.id==selectedId){
                setSelectedCert(d);
            }
        })
        setSelectedId(selectedId)
    }
  const onSave=async ()=>{
    setSaving(true)
    if(onAction){
        (onAction as any)();
    }else{
        await deleteSource(source,id,auth);
        await onUpdate()
    }
    setSaving(false);
    onClose();

  }

  useEffect(()=>{



  },[id,source])

    return (
      <>


       {buttonType=="small"? <IconButton colorScheme='red' aria-label='' onClick={onOpen}  icon={<FaTrashAlt></FaTrashAlt>}  variant={"outline"} size={"xs"}></IconButton>

       : <Button isLoading={saving} onClick={onOpen} colorScheme='red' leftIcon={<FaTrash></FaTrash>} variant={"outline"} size={"xs"}>Delete</Button> 
    }

        <Modal isOpen={isOpen} onClose={onClose}>
          <ModalOverlay />
          <ModalContent className='dark:bg-slate-900 dark:text-white shadow-3xl'>
            <ModalHeader>Delete {source} {id}</ModalHeader>
            <ModalCloseButton />
            <ModalBody className='dark:text-white'>
                <label className="block text-sm font-bold mb-1">
                    Are you sure to delte the {source}
                  </label>
                  
                

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