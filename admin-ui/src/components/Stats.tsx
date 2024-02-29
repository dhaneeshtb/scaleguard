import axios from "axios";
import { useEffect, useState } from "react";
import { useAuth } from "../contexts/AuthContext";

export default function Stats(){
    const [stats,setStats] = useState<any>([]);
    const {auth} = useAuth() as any;

    useEffect(()=>{

        axios.get(auth.data.host+"/stats?scaleguard=true",{
            headers:{
                Authorization:auth.data.token
            }
        }).then(r=>setStats(r.data))

    },[])
    return  <div className="flex gap-2 flex-wrap  w-full">
    { stats && stats.map((stat:any)=>{

        return   <div className="bg-white dark:bg-slate-900 rounded-lg px-3 py-3 ring-1 ring-slate-500/5 shadow-2xl w-full md:max-w-[300px]">
        <div>
          {/* <span className="inline-flex items-center justify-center p-2 bg-indigo-500 rounded-md shadow-lg">
            <svg className="h-6 w-6 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
              
            </svg>
          </span> */}
        </div>
        <div className="flex justify-between">
        <h3 className="text-slate-900 dark:text-white mt-2 text-base text-xs font-small tracking-tight">{stat.key.split(":").slice(2).join(":")}</h3>
        <h3 className="text-slate-900 dark:text-white mt-2 text-base text-xs font-small tracking-tight">Requests Served: {stat.total}</h3>

        </div>
        <p className="text-slate-900 dark:text-white mt-2 text-base text-xs  font-small tracking-tight">Timings (Milliseconds)</p>

        <table className="min-w-full text-left text-sm font-light dark:text-white">
              <thead className="border-b text-xs font-small dark:border-neutral-500">
                <tr>
                  <th scope="col" className="font-small px-6 py-2">Min</th>
                  <th scope="col" className="font-small px-6 py-2 ">Max</th>
                  <th scope="col" className="font-small px-6 py-2">Average</th>
                </tr>
              </thead>
              <tbody>
              <tr>
                  <td scope="col" className="px-6 py-2">{stat.min}</td>
                  <td scope="col" className="px-6 py-2 ">{stat.max}</td>
                  <td scope="col" className="px-6 py-2">{stat.avg}</td>
                </tr>
                </tbody>
                </table>
      </div>

    }) }
  
  </div>
}