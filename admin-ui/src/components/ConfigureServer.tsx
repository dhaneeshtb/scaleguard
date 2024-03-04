import { Button,Switch,Flex, IconButton, Modal, ModalBody, ModalCloseButton, ModalContent, ModalFooter, ModalHeader, ModalOverlay, useDisclosure, FormLabel } from "@chakra-ui/react";
import axios from "axios";
import { useEffect, useState } from "react";
import { useAuth } from "../contexts/AuthContext";
import { FaCogs, FaPlusCircle, FaTrash, FaTrashAlt } from "react-icons/fa";
import { deleteSource } from "./sourceupdate";
import { hostname } from "os";
import useSystemContext from "../contexts/SystemContext";
import Loader from "../loaders/loader";

export function ConfigureServer({ onUpdate }) {
  const { isOpen, onOpen, onClose } = useDisclosure()
  const [saving, setSaving] = useState<boolean>(false);
  const {auth,setAuthData} = useAuth() as  any;

  useEffect(()=>{
    onOpen();

   const interval= setInterval(()=>{

      onUpdate();

    },3000)

    return ()=>{
      clearInterval(interval)
    }

  },[])
  

  const logout=()=>{

    localStorage.removeItem("authData")
    setAuthData({data:null})
    window.location.reload();

}

  return (
    <>


      <Modal isOpen={isOpen} onClose={onClose} closeOnOverlayClick={false} closeOnEsc={false}>
        <ModalOverlay />
        <ModalContent  className='dark:bg-black dark:text-white shadow-3xl'>
          {/* {!load && <ModalCloseButton></ModalCloseButton>} */}
          <ModalHeader>Host {auth.data?.host} Unreachable</ModalHeader>
          <ModalBody className='dark:text-white flex flex-col gap-2'>
            <label>Unable to reach the configured host. Logout and connect to diffrenet host or try refresh</label>
            <Loader></Loader>

            <Button onClick={logout} variant={"outline"} colorScheme="teal">Logout</Button>

          </ModalBody>

          
        </ModalContent>
      </Modal>
    </>
  )
}
