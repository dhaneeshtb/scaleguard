import { Select,Button, IconButton, Link, Modal, ModalBody, ModalCloseButton, ModalContent, ModalFooter, ModalHeader, ModalOverlay, useDisclosure } from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState } from 'react';
import { FaArrowAltCircleUp, FaArrowCircleDown, FaCheckCircle, FaCogs, FaEdit, FaMinusCircle, FaPlusCircle, FaTrash, FaTrashAlt } from "react-icons/fa";
import { formatData, renewCertificate, updateSource } from './sourceupdate';
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';
import { LinkIcon } from '@chakra-ui/icons';

export default function Systems() {

    const [systems,setSystems] = useState<any>([]);
    const {auth} = useAuth() as any
   

    const onLoad=()=>{
        axios.get(auth.data.host+"/config/sourcesystems?scaleguard=true",{headers:{
            Authorization:auth.data.token
        }}).then(r=>setSystems(r.data.sourcesystems))
    }

    useEffect(()=>{
        if(auth.data)
        onLoad();
    },[auth.data])

    



    function AttachCertificate({source,id,onUpdate=()=>{}}) {
        const { isOpen, onOpen, onClose } = useDisclosure()
        const [certificates,setCertificates] = useState<any[]>([]);
        const [saving,setSaving] = useState<boolean>(false);
  
        const [selectedId,setSelectedId] = useState<string>(id);
        const [selectedCert,setSelectedCert] = useState<any>(null);

        const [currentMessage,setCurrentMessage] = useState<string>("");


        
        const onLoad=async ()=>{
            // setSaving(true)
            const r=  await axios.get(auth.data.host+"/certificates?scaleguard=true",{headers:{
                Authorization:auth.data.token
            }})
            r.data.forEach(d=>{
                d.json=JSON.parse(d.json)
                d.name=d.json.identifiers.map(s => s.value)[0]
                if(d.id==selectedId){
                    setSelectedCert(d);
                }
            })

            
            setCertificates(r.data)      
        }

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
        await updateSource({...source,certificateId:selectedId},"sourcesystems",auth)
        await onUpdate()
        setSaving(false);
        onClose();
  
      }

      const procureCertificate=async ()=>{
        setSaving(true)
        setCurrentMessage("")
        try{
          await renewCertificate(source.id,auth)
          await onUpdate()
          onClose();
        }catch(e){
          setCurrentMessage("failed to procure the certificates");
        }
        setSaving(false);
  
      }

      useEffect(()=>{

        onLoad();


      },[id])
  
        return (
          <>
            <IconButton rounded={"full"} size={"sm"} aria-label=''  onClick={onOpen} icon={<FaCogs color='green'></FaCogs>} variant={"outline"} colorScheme='teal'>{id?id:"No Certificate"}</IconButton>
  
  
            <Modal isOpen={isOpen} onClose={onClose}>
              <ModalOverlay />
              <ModalContent className='dark:bg-slate-900 dark:text-white shadow-3xl'>
                <ModalHeader>Configure Certificate</ModalHeader>
                <ModalCloseButton />
                <ModalBody className='dark:text-white'>
                <p className='m-3 w-full text-center text-red-600'>{currentMessage}</p>

                  <Button w="full" isLoading={saving} variant='outline' colorScheme='teal' onClick={procureCertificate}>Generate Certificate</Button>

                  <p className='m-3 w-full text-center'>OR</p>
                    <label className="block text-sm font-bold mb-1">
                       Choose Certificate
                      </label>
                      <Select placeholder='Select option' value={selectedId} onChange={(e)=>onSelectId(e.target.value)}>
                        {
                            certificates.map(c=>{
                                return  <option value={c.id}>{c.name+"-"+c.id}</option>
                            })
                        }
                    </Select>
                    {selectedCert &&
                    <><label className="block text-sm font-bold mb-1">
                        Associated Domains
                    </label>
                    <p>{selectedCert.json.identifiers.map(s=>s.value).join(",")}</p></>
                    }
  
                </ModalBody>
      
                <ModalFooter className='dark:text-white'>
                  <Button rounded={"3xl"} colorScheme='gray' mr={3} onClick={onClose}>
                    Close
                  </Button>
                  <Button rounded={"3xl"} isLoading={saving} variant='outline' colorScheme='teal' onClick={onSave}>Save</Button>
                </ModalFooter>
              </ModalContent>
            </Modal>
          </>
        )
      }
  
  return (
    
    <div className="flex flex-col dark:bg-slate-900  ">
    <div className='flex gap-2 justify-end px-[20px]'>
<Link href='/managehost/sourcesystems/new' >

<Button leftIcon={<FaPlusCircle></FaPlusCircle>} variant={"outline"} colorScheme='teal' size={"xs"}>SourceSystem</Button>
</Link>


</div>
      <div className="overflow-x-auto sm:-mx-6 lg:-mx-8 ">
        <div className="inline-block min-w-full py-2 sm:px-6 lg:px-8">
          <div className="overflow-hidden">
            <table className="min-w-full text-left text-sm font-light dark:text-white table-auto w-full">
              <thead className="border-b font-medium dark:border-neutral-500">
                <tr>
                  <th scope="col" className="px-6 py-4 text-[12px]">Name</th>
                  <th scope="col" className="px-6 py-4 text-[12px]">Group ID</th>
                  <th scope="col" className="px-6 py-4 text-[12px]">Scheme</th>
                  <th scope="col" className="px-6 py-4 text-[12px]">Host</th>
                  <th scope="col" className="px-6 py-4 text-[12px]">Port</th>

                  <th scope="col" className="px-6 py-4 text-[12px]">Target</th>
                  {/* <th scope="col" className="px-6 py-4 text-[12px]">Host Groups</th> */}
                  <th scope="col" className="px-6 py-4 text-[12px]">BasePath</th>
                  <th scope="col" className="px-6 py-4 text-[12px]">Certificate</th>
                  <th scope="col" className="px-6 py-4 text-[12px]">Action</th>

                </tr>
              </thead>
              <tbody>
                
                {
                  systems &&  systems.map((system:any)=>{
                        return <tr
                        className="border-b transition duration-300 ease-in-out hover:bg-neutral-100 dark:border-neutral-500 dark:hover:bg-neutral-600">
                        <td className=" px-6 py-4 font-medium">{system.name}</td>
                        <td className=" px-6 py-4">{system.groupId}</td>
                        <td className=" px-6 py-4">{system.scheme}</td>

                        <td className=" px-6 py-4 text-white"><Link className="text-white bg-black  dark:bg-white dark:text-black  rounded-lg p-1 flex items-center gap-2" href={system.scheme+"://"+system.host} target='_blank'><LinkIcon></LinkIcon>{system.host}</Link></td>
                        <td className=" px-6 py-4">{system.port}</td>

                        <td className=" px-6 py-4">{system.target}</td>
                        {/* <td className="px-6 py-4 break-all	">{system.targetSystem && (system.targetSystem.hostGroups as Array<any>).map(hg=>(hg).groupId+"-"+system.targetSystem.scheme+"://"+(hg).host+":"+(hg).port).join(",")}</td> */}

                        <td className=" px-6 py-4">{system.basePath}</td>
                        <td className=" px-6 py-4"><div className='flex gap-2 items-center'>{system.scheme=="https"? (<>{system.certificateId?(<FaCheckCircle className='w-8 h-8' color='green'></FaCheckCircle>):(<FaMinusCircle className='w-8 h-8'  color='red'></FaMinusCircle>)}  {system.certificateId||"Not Configured"} <AttachCertificate source={system} id={system.certificateId} onUpdate={onLoad}></AttachCertificate></>):"Not Applicable"}</div></td>

                        <td className=" px-6 py-4">
                            <div className='flex gap-2'>
                                <DeleteSystem source={"sourcesystems"} id={system.id} onUpdate={onLoad}></DeleteSystem>
                            <Link href={`/managehost/sourcesystems/${system.id}`}><IconButton colorScheme='white' aria-label='' icon={<FaEdit></FaEdit>}  variant={"outline"} size={"xs"}></IconButton></Link> 
                            </div>
                            </td>


                      </tr>
                    })
                }

                
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}