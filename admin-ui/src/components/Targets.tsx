import { Button, IconButton, Link } from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState } from 'react';
import { FaArrowCircleDown, FaArrowCircleUp, FaEdit, FaPlusCircle } from "react-icons/fa";
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';

export default function Targets() {

    const [systems,setSystems] = useState<any>([]);

    const {auth} =useAuth() as any;

    const onLoad=()=>{
      axios.get(auth.data.host +"/config/targetsystems?scaleguard=true",{headers:{
        Authorization:auth.data.token
    }}).then(r=>setSystems(r.data.targetsystems))

    }

    useEffect(()=>{
      onLoad();
    },[])
  return (
    <div className="flex flex-col  dark:bg-slate-900">
        <div className='flex gap-2 justify-end px-[20px]'>
  <Link href='/managehost/targetsystems/new' >

    <Button leftIcon={<FaPlusCircle></FaPlusCircle>} variant={"outline"} colorScheme='teal' size={"xs"}>TargetSystem</Button>
  </Link>


</div>
      <div className="overflow-x-auto sm:-mx-6 lg:-mx-8 ">
        <div className="inline-block min-w-full py-2 sm:px-6 lg:px-8">
          <div className="overflow-hidden">
            <table className="min-w-full text-left text-sm font-light dark:text-white w-full">
              <thead className="border-b font-medium dark:border-neutral-500">
                <tr>
                  <th scope="col" className="px-6 py-4 ">#id</th>
                  <th scope="col" className="px-6 py-4">Group Id</th>
                  <th scope="col" className="px-6 py-4">Scheme</th>
                  <th scope="col" className="px-6 py-4">Basepath</th>
                  <th scope="col" className="px-6 py-4">Hostgroup</th>
                  {/* <th scope="col" className="px-6 py-4">Include Headers</th>
                  <th scope="col" className="px-6 py-4">Exclude Headers</th> */}
                  <th scope="col" className="px-6 py-4">Enable Cache</th>
                  <th scope="col" className="px-6 py-4 w-[200px]">Cache Pattern</th>
                  <th scope="col" className="px-6 py-4">Action</th>
                </tr>
              </thead>
              <tbody>
                
                {
                  systems &&  systems.map((system:any)=>{
                        return <tr
                        className="border-b transition duration-300 ease-in-out hover:bg-neutral-100 dark:border-neutral-500 dark:hover:bg-neutral-600">
                        <td className="px-6 py-4 font-medium">{system.id}</td>
                        <td className=" px-6 py-4">{system.groupId}</td>
                        <td className=" px-6 py-4">{system.scheme}</td>
                        <td className=" px-6 py-4">{system.basePath}</td>
                        <td className="px-6 py-4">{system.hostGroupId}</td>
                        {/* <td className=" px-6 py-4">{JSON.stringify(system.includeHeaders)}</td>
                        <td className=" px-6 py-4">{JSON.stringify(system.excludeHeaders)}</td> */}
                        <td className=" px-6 py-4">{system.enableCache+""}</td>

                        
                        <td className="break-all px-6 py-4">{JSON.stringify(system.cachedResources)}</td>

                        <td className="whitespace-nowrap px-6 py-4">
                          

                          <div className='flex gap-2'>
                                <DeleteSystem source={"targetsystems"} id={system.id} onUpdate={onLoad}></DeleteSystem>
                                <Link href={`/managehost/targetsystems/${system.id}`}><IconButton colorScheme='white' aria-label='' icon={<FaEdit></FaEdit>}  variant={"outline"} size={"xs"}></IconButton></Link>
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