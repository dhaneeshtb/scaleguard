import { Button, IconButton, Link } from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState } from 'react';
import { FaArrowCircleDown, FaArrowCircleUp, FaEdit, FaPlusCircle } from "react-icons/fa";
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';

export default function HostGroups() {

    const [systems,setSystems] = useState<any>([]);


    const {auth} =useAuth() as any;

    const onLoad=()=>{
      axios.get(auth.data.host +"/config/hostgroups?scaleguard=true",{headers:{
        Authorization:auth.data.token
    }}).then(r=>setSystems(r.data.hostgroups))
    }

    useEffect(()=>{
      onLoad();

    },[])
  return (
    <div className="flex flex-col dark:bg-slate-900 ">
        <div className='flex gap-2 justify-end px-[20px]'>
  <Link href='/managehost/hostgroups/new' >

    <Button leftIcon={<FaPlusCircle></FaPlusCircle>} variant={"outline"} colorScheme='teal' size={"xs"}>HostGroups</Button>
  </Link>


</div>
      <div className="overflow-x-auto sm:-mx-6 lg:-mx-8">
        <div className="inline-block min-w-full py-2 sm:px-6 lg:px-8">
          <div className="overflow-hidden">
            <table className="min-w-full text-left text-sm font-light dark:text-white">
              <thead className="border-b font-medium dark:border-neutral-500">
                <tr>
                  <th scope="col" className="px-6 py-4 ">#id</th>
                  <th scope="col" className="px-6 py-4">Group Id</th>
                  <th scope="col" className="px-6 py-4 ">Host</th>
                  <th scope="col" className="px-6 py-4">Type</th>
                  <th scope="col" className="px-6 py-4">Status</th>
                  <th scope="col" className="px-6 py-4">Network Status</th>

                  <th scope="col" className="px-6 py-4">Load Factor</th>
                  <th scope="col" className="px-6 py-4">Action</th>

                </tr>
              </thead>
              <tbody>
                
                {
                  systems &&  systems.map((system:any)=>{
                        return <tr
                        className="border-b transition duration-300 ease-in-out hover:bg-neutral-100 dark:border-neutral-500 dark:hover:bg-neutral-600">
                        <td className="whitespace-nowrap px-6 py-4 font-medium">{system.id}</td>
                        <td className="whitespace-nowrap px-6 py-4">{system.groupId}</td>
                        <td className="whitespace-nowrap px-6 py-4">{system.host+":"+system.port}</td>
                        <td className="whitespace-nowrap px-6 py-4">{system.type}</td>
                        <td className="whitespace-nowrap px-6 py-4">{system.active+""}</td>
                        <td className="whitespace-nowrap px-6 py-4 flex items-center gap-2"><span>{system.reachable+""}</span> {system.reachable?<FaArrowCircleUp color='green'></FaArrowCircleUp>:<FaArrowCircleDown color='red'></FaArrowCircleDown>} </td>

                        <td className="whitespace-nowrap px-6 py-4">{system.loadFactor}</td>
                        <td className="whitespace-nowrap px-6 py-4">
                          
                          <div className='flex gap-2'>
                                <DeleteSystem source={"hostgroups"} id={system.id} onUpdate={onLoad}></DeleteSystem>
                                <Link href={`/managehost/hostgroups/${system.id}`}><IconButton colorScheme='white' aria-label='' icon={<FaEdit></FaEdit>}  variant={"outline"} size={"xs"}></IconButton></Link> 
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