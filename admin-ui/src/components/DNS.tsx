import {
  Button, IconButton, Link,
  Modal,
  ModalOverlay,
  ModalContent,
  ModalHeader,
  ModalFooter,
  ModalBody,
  ModalCloseButton,
  useDisclosure,
  Heading,
  Text
} from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState } from 'react';
import {
  FaArrowCircleDown, FaArrowCircleUp, FaCheck, FaClipboard, FaEdit, FaFileDownload, FaInfoCircle, FaPlusCircle, FaSave, FaTrash,
} from "react-icons/fa";
import { CopyToClipboard } from 'react-copy-to-clipboard';
import { useToast } from '@chakra-ui/react'
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';
import { formatData } from './sourceupdate';
import NameServers from './NameServers';


export default function DNS() {

  const [systems, setSystems] = useState<any>();
  const [loading, setLoading] = useState<boolean>(false);
  const { auth } = useAuth() as any;

  const [name, setName] = useState<string>();
  const [type, setType] = useState<string>("base");
  const [ttl, setTTL] = useState<number>(600);
  const [ip, setIp] = useState<string>();
  const [nameServers, setNameServers] = useState<any[]>([]);
  const [nameServer, setNameServer] = useState<string>("");



  const load = async () => {
    const r = await axios.get(auth.data.host + "/dns?scaleguard=true", {
      headers: {
        Authorization: auth.data.token
      }
    });
    const res=r.data;
    const ns = Object.keys(res).map(s=>res[s]).reduce((acc,r)=>r.length>0?acc.push(...r) && acc:acc,[]).filter(x=>x.type=="base")
    setSystems(res)
    setNameServers(ns);
  }
  useEffect(() => {
    load();

  }, [])

  const deleteItem = async (id) => {
    setLoading(true)
    await axios.delete(auth.data.host + "/dns/" + id + "?scaleguard=true", {
      headers: {
        Authorization: auth.data.token
      }
    })
    await load()
    setLoading(false);

  }
  
  const save=async ()=>{
    setLoading(true)
    await axios.post(auth.data.host + "/dns?scaleguard=true",{
      name:name,
      ip:ip,
      type:"record",
      ttl:ttl
    }, {
      headers: {
        Authorization: auth.data.token
      }
    })
    await load()
    setLoading(false);
  }


  const saveNameserver=async (name,ip)=>{
    setLoading(true)
    await axios.post(auth.data.host + "/dns?scaleguard=true",{
      type:"base",
      ttl:600,
      name:name,
      ip:ip
    }, {
      headers: {
        Authorization: auth.data.token
      }
    })
    await load()
    setLoading(false);
  }






  return (
    <div className="flex flex-col dark:bg-slate-900 ">
 <div className="flex w-full justify-center ">
      <NameServers nameServers={nameServers} onSave={saveNameserver} onDelete={deleteItem}></NameServers>
      </div>


      <div className="rounded-lg shadow-lg flex justify-center items-center mt-1">
        <div className="container mx-auto bg-indigo-800 dark:bg-indigo-900 rounded-lg p-14">
          <div>
            <h1 className="text-center font-bold text-white text-4xl">Configure dns entries</h1>
            <p className="text-black dark:text-white mx-auto font-normal text-sm my-6 max-w-lg">
              
            </p>
            <div className="flex flex-col lg:flex-row items-center bg-white rounded-lg overflow-hidden px-2 py-1 justify-between gap-2 items-center">
              <div className='w-full lg:w-1/3 flex flex-col justify-start'>
                <Text>Name</Text>
              <input value={name} onChange={(e)=>setName(e.target.value)} className="w-full  text-base text-gray-400 flex-grow rounded-lg border-2 px-2 py-2" type="text" placeholder="DNS base domain name" />

              </div>
              <div className='w-full lg:w-1/3  flex flex-col justify-start'>
              <Text>Host</Text>

              <input   value={ip} onChange={(e)=>setIp(e.target.value)} className="w-full text-base text-gray-400 flex-grow rounded-lg border-2 px-2 py-2" type="text" placeholder="IP Address" />
              </div>
                {/* <select  value={type} onChange={(e)=>setType(e.target.value)}  id="type" className="w-1/2 text-base text-gray-800 outline-none border-2 px-4 py-2 rounded-lg">
                  <option value="base" selected>base</option>
                  <option value="record">record</option>
                </select> */}

                {/* <select  value={nameServer} onChange={(e)=>setNameServer(e.target.value)}  id="type" className="w-1/2 text-base text-gray-800 outline-none border-2 px-4 py-2 rounded-lg">
                  {
                    nameServers.map(n=>{
                      return <option value={n.name}>{n.name}</option>
                    })
                  }
                  
                </select> */}
 <div className='w-full lg:w-1/3 flex flex-col justify-start'>
 <Text>TTL</Text>
                <input  value={ttl} onChange={(e)=>setTTL(+e.target.value)} className="  text-base text-gray-400  rounded-lg border-2 px-2 py-2" type="text" placeholder="TTL" />
                  </div>
                  <div className=' flex flex-col justify-start'>
                  <div className='h-6'> </div>
              <Button isDisabled={!name||!ttl} onClick={()=>save()} leftIcon={<FaSave></FaSave>} bg={"bg-indigo-500"} className="w-full lg:w-[200px] bg-indigo-500 text-white text-base rounded-lg px-4 py-2 font-thin">Save</Button>
                  </div>
            </div>
          </div>
        </div>
      </div>

      <div className="overflow-x-auto sm:-mx-6 ">
        <div className="inline-block min-w-full py-2 sm:px-6 lg:px-8">
          <div className="overflow-hidden">
            <table className="min-w-full text-left text-sm font-light dark:text-white">
              <thead className="border-b font-medium dark:border-neutral-500">
                <tr>
                  <th scope="col" className="px-6 py-4 ">#id</th>
                  <th scope="col" className="px-6 py-4">Name</th>
                  <th scope="col" className="px-6 py-4">IP</th>
                  <th scope="col" className="px-6 py-4">Type</th>
                  <th scope="col" className="px-6 py-4">TTL</th>
                  <th scope="col" className="px-6 py-4">Action</th>
                </tr>
              </thead>
              <tbody>

                {
                  systems && Object.keys(systems).map((key: any) => {

                    return systems[key].filter(s=>s.type!="base").map(system=>{
                      return   <tr
                      className="border-b transition duration-300 ease-in-out hover:bg-neutral-100 dark:border-neutral-500 dark:hover:bg-neutral-600">
                      <td className="px-6 py-4 font-medium">{system.id}</td>
                      <td className="px-6 py-4 font-medium">{system.name}</td>
                      <td className="px-6 py-4 font-medium">{system.ip}</td>
                      <td className="px-6 py-4 font-medium">{system.type}</td>
                      <td className="px-6 py-4 font-medium">{system.ttl}</td>

                      <td><div className=" px-6 py-4 flex gap-2 justify-center items-center">
                        <DeleteSystem id={system.id} source={"dns"} onAction={() => deleteItem(system.id) as any} buttonType='big'></DeleteSystem>
                      </div> </td>
                    </tr>
                    })
                   
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