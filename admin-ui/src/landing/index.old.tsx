import styles from '@/styles/Home.module.css'
import React, { useEffect, useState } from 'react';

import Typewriter from 'typewriter-effect';
import { Button, Link, Select } from '@chakra-ui/react'
import axios from 'axios';
import { useAuth } from '../contexts/AuthContext';
import { useNavigate } from 'react-router-dom';
import { FaCogs, FaDownload } from 'react-icons/fa';
import './revolve.css'
export default function Landing() {

  const [isLoading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const navigate = useNavigate();



  return (

    <main className="min-h-screen bg-slate-800 overflow-auto">


      <div className=" flex flex-col justify-between h-full  shadow-md bg-cover text-gray-800  overflow-hidden cursor-pointer w-full object-cover object-center  shadow-md h-full" >
        {/* <div className="absolute bg-gradient-to-t from-green-400 to-blue-400  opacity-50 inset-0 z-0"></div> */}
        <img src="/sg.png" className='object-contain lg:absolute w-full '></img>

        <div className=" h-full">
          <div className="grid grid-cols-1 lg:grid-cols-7  gap-4 p-5 h-full">
            <div className="hidden lg:block flex items-center text-green-500 text-lg font-bold text-center p-10 rounded-lg md:col-span-4 h-full justify-center">



              {/* style={{ backgroundPositionX: "", "backgroundImage": "url('/sg.png')" }} */}
              {/* <div className="text-white text-5xl text-lg">
                <Typewriter

                  options={{
                    strings: [
                      "Connect your systems",
                      "Procure certificates effortlessly",
                      "API Support for dynamic host registrations"
                    ],
                    autoStart: true,
                    loop: true,
                    deleteSpeed: 50
                  }}
                />
              </div> */}

            </div>
            <div className=" text-green-500 text-lg font-bold text-center flex-col flex items-center justify--center rounded-lg col-span-3 h-full">


            <div className="circle">
 <div className="noise animated">
  <img src="/lb.svg"></img>
 </div>
</div>

              <div className='z-10 lg:mt-20'>
                <div className="grid grid-cols-1 md:grid-cols-2  gap-6">
                  <div className="p-6 bg-black rounded-md shadow-md text-white">
                    <h3 className="text-xl font-semibold mb-4 text-md text-left ">High Scalability</h3>
                    <p className="text-gray-700 text-sm text-left">Efficiently handle a large number of concurrent connections and scale horizontally as demand increases.</p>
                  </div>

                  <div className="p-6 bg-black rounded-md shadow-md text-white">
                    <h3 className="text-xl font-semibold mb-4 text-left" >Automatic Certificate Provisioning</h3>
                    <p className="text-gray-700 text-sm text-left">Automated provisioning and management of SSL/TLS certificates for secure communication.</p>
                  </div>

                  <div className="p-6 bg-black rounded-md shadow-md text-white">
                    <h3 className="text-xl font-semibold mb-4 text-left">Request Caching</h3>
                    <p className="text-gray-700 text-sm text-left">Store frequently requested content at the load balancer level to improve response times and reduce load on backend servers.</p>
                  </div>

                  <div className="p-6 bg-black rounded-md shadow-md text-white">
                    <h3 className="text-xl font-semibold mb-4 text-left">Async API Support</h3>
                    <p className="text-gray-700 text-sm text-left">Efficiently handle asynchronous communication patterns for real-time applications or services.</p>
                  </div>
                </div>
              </div>
              <div className='flex gap-2 mt-4 justify-end w-full'>
                <Button  colorScheme="blue" leftIcon={<FaDownload></FaDownload>}>Download</Button>
                <Link href='/sign-in'><Button colorScheme='orange' leftIcon={<FaCogs></FaCogs>}>Connect your instance</Button></Link>
              </div>

            </div>
          </div>
        </div>

      </div>











    </main>

  )
}

// Home.getLayout = function getLayout(page:any) {
//   return (
//       <>{page}</>
//   )
// }
