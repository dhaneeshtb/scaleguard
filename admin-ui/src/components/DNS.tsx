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
  Heading
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


export default function DNS() {

  const [systems, setSystems] = useState<any>();
  const [loading, setLoading] = useState<boolean>(false);
  const { auth } = useAuth() as any;

  const [name, setName] = useState<string>();
  const [type, setType] = useState<string>("base");
  const [ttl, setTTL] = useState<number>(600);
  const [ip, setIp] = useState<string>();


  const load = async () => {
    const r = await axios.get(auth.data.host + "/dns?scaleguard=true", {
      headers: {
        Authorization: auth.data.token
      }
    });
    setSystems(r.data)
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
      type:type,
      ttl:ttl
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


      <div className="bg-black flex justify-center items-center mt-1">
        <div className="container mx-auto bg-indigo-800 dark:bg-indigo-900 rounded-sm p-14">
          <div>
            <h1 className="text-center font-bold text-white text-4xl">Configure dns entries</h1>
            <p className="text-black dark:text-white mx-auto font-normal text-sm my-6 max-w-lg">
              
            </p>
            <div className="flex flex-col lg:flex-row items-center bg-white rounded-lg overflow-hidden px-2 py-1 justify-between gap-2">
              <input value={name} onChange={(e)=>setName(e.target.value)} className="w-full  text-base text-gray-400 flex-grow rounded-lg border-2 px-2 py-2" type="text" placeholder="DNS base domain name" />
              <input disabled={type=="base"}  value={ip} onChange={(e)=>setIp(e.target.value)} className="w-full text-base text-gray-400 flex-grow rounded-lg border-2 px-2 py-2" type="text" placeholder="IP Address" />
              <div className="flex items-center rounded-lg w-full gap-2">
                <select  value={type} onChange={(e)=>setType(e.target.value)}  id="type" className="w-1/2 text-base text-gray-800 outline-none border-2 px-4 py-2 rounded-lg">
                  <option value="base" selected>base</option>
                  <option value="record">record</option>
                </select>
                <input  value={ttl} onChange={(e)=>setTTL(+e.target.value)} className="w-1/2  text-base text-gray-400  rounded-lg border-2 px-2 py-2" type="text" placeholder="TTL" />
              </div>
              <Button isDisabled={!name||!ttl} onClick={()=>save()} leftIcon={<FaSave></FaSave>} bg={"bg-indigo-500"} className="w-full lg:w-1/2 bg-indigo-500 text-white text-base rounded-lg px-4 py-2 font-thin">Save</Button>
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

                    return systems[key].map(system=>{
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