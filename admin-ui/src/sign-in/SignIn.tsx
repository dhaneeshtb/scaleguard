import styles from '@/styles/Home.module.css'
import React, { useEffect, useState } from 'react';

import Typewriter from 'typewriter-effect';
import { Select } from '@chakra-ui/react'
import axios from 'axios';
import { useAuth } from '../contexts/AuthContext';
import { useNavigate } from 'react-router-dom';

export default function SignIn() {

 const [isLoading,setLoading] =useState(false);
 const [error,setError] = useState("");
 const navigate = useNavigate();


 const [host,setHost] = useState<string|null>("")
 const signIn=async (host,username,password):Promise<any>=>{
  try{

    
  const r =await axios.post(host+"/signin?scaleguard=true",{username,password},{
    
    
    headers:{
      'Access-Control-Allow-Origin': '*',

    }
  });
  return r;
  }catch(e:any){
    return e.response;
  }
 }
 const {auth,setAuthData} = useAuth() as any;

 const handleSubmit = async (
  e: React.FormEvent<HTMLFormElement>
): Promise<void> => {
  setLoading(true)
  e.preventDefault();
  setError("");
  setTimeout(async () => {
    const _target = e.target as any;
    const username = _target.username.value;
    const password = _target.password.value;
    const hostURL = _target.hostURL.value;
    localStorage.setItem("host",hostURL);
    const result = await signIn(
      hostURL,
      username,
      password,
    );
    setLoading(false)

    if (result.status!=200) {
      setError("Invalid username or password. Retry with right credentials");
    } else {
      setLoading(false)

      const authData={...result.data,host:hostURL,username:username};
      window.localStorage.setItem('authData', JSON.stringify(authData));

      setAuthData({loading:false,data:authData});
      navigate("/")
    }
  }, 1000);
};

useEffect(()=>{

  setHost(localStorage.getItem("host"));

},[])
  
  return (
      
      <main className="min-h-screen">


        <div className="relative flex flex-col justify-between min-h-screen  bg-white shadow-md bg-cover text-gray-800  overflow-hidden cursor-pointer w-full object-cover object-center  shadow-md h-64" style={{ backgroundPositionX: "", "backgroundImage": "url('/sg.png')" }}>
          {/* <div className="absolute bg-gradient-to-t from-green-400 to-blue-400  opacity-50 inset-0 z-0"></div> */}

          <div className="relative h-full">
            <div className="grid grid-cols-1 lg:grid-cols-7  gap-4 p-5 h-full">
              <div className="hidden lg:block flex items-center text-green-500 text-lg font-bold text-center p-10 rounded-lg md:col-span-4 h-full justify-center">


                <div className="text-white text-5xl text-lg">
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
                </div>

              </div>
              <div className=" text-green-500 text-lg font-bold text-center flex items-center justify--center rounded-lg col-span-2">




                <div className="relative shadow-lg bg-gradient-to-br from-sky-50 to-gray-200 w-full rounded-3xl">
                  <div className="relative  m-auto  text-gray-500  w-full">
                    <div className="m-auto ">


                      <div className="rounded-xl bg-white shadow-xl">


                        <div className="p-6 sm:p-6">


                          <div className="space-y-4">
                            <img src="/lb.svg" loading="lazy" className="w-20" alt="tailus logo" />
                            <h2 className="mb-8 text-2xl text-cyan-900 font-bold">Sign in to Scaleguard.</h2>
                          </div>

                          <div className="w-full mx-auto my-5 bg-white p-4 rounded-xl shadow shadow-slate-300">

                            <form  className="my-5" onSubmit={handleSubmit}>
                              <div className="flex flex-col space-y-5">
                                {error && <p className="text-xs left text-red-500">{error}
                                </p>
                                }
                                <label >
                                  <p className="font-medium text-slate-400 pb-2 text-left">Admin Username</p>
                                  <input id="username" name="username" type="text" className="w-full py-3 border border-slate-200 rounded-lg px-3 focus:outline-none focus:border-slate-500 hover:shadow" placeholder="Enter username" />
                                </label>

                                <label >
                                  <p className="font-medium text-slate-400 pb-2 text-left">Password</p>
                                  <input id="password" name="password" type="password" className="w-full py-3 rounded-lg border border-slate-200  px-3 focus:outline-none focus:border-slate-500 hover:shadow" placeholder="Enter password" />
                                </label>

                                <label >
                                  <p className="font-medium text-slate-400 pb-2 text-left">Host URL</p>
                                  <input defaultValue={host as any|""} id="hostURL" name="hostURL" type="text" className="w-full py-3 rounded-lg border border-slate-200  px-3 focus:outline-none focus:border-slate-500 hover:shadow" placeholder="Enter password" />
                                </label>

                                



                                <button type="submit"  className="w-full py-3 font-medium text-white bg-indigo-600 hover:bg-indigo-500 rounded-lg border-indigo-500 hover:shadow inline-flex space-x-2 items-center justify-center" disabled={isLoading}>
                                 
                                 {

                                      isLoading?(
                                        <svg aria-hidden="true" className="w-8 h-8 mr-2 text-gray-200 animate-spin dark:text-gray-600 fill-blue-600" viewBox="0 0 100 101" fill="none" xmlns="http://www.w3.org/2000/svg">
                                        <path d="M100 50.5908C100 78.2051 77.6142 100.591 50 100.591C22.3858 100.591 0 78.2051 0 50.5908C0 22.9766 22.3858 0.59082 50 0.59082C77.6142 0.59082 100 22.9766 100 50.5908ZM9.08144 50.5908C9.08144 73.1895 27.4013 91.5094 50 91.5094C72.5987 91.5094 90.9186 73.1895 90.9186 50.5908C90.9186 27.9921 72.5987 9.67226 50 9.67226C27.4013 9.67226 9.08144 27.9921 9.08144 50.5908Z" fill="currentColor"/>
                                        <path d="M93.9676 39.0409C96.393 38.4038 97.8624 35.9116 97.0079 33.5539C95.2932 28.8227 92.871 24.3692 89.8167 20.348C85.8452 15.1192 80.8826 10.7238 75.2124 7.41289C69.5422 4.10194 63.2754 1.94025 56.7698 1.05124C51.7666 0.367541 46.6976 0.446843 41.7345 1.27873C39.2613 1.69328 37.813 4.19778 38.4501 6.62326C39.0873 9.04874 41.5694 10.4717 44.0505 10.1071C47.8511 9.54855 51.7191 9.52689 55.5402 10.0491C60.8642 10.7766 65.9928 12.5457 70.6331 15.2552C75.2735 17.9648 79.3347 21.5619 82.5849 25.841C84.9175 28.9121 86.7997 32.2913 88.1811 35.8758C89.083 38.2158 91.5421 39.6781 93.9676 39.0409Z" fill="currentFill"/>
                                    </svg>

                                      ):(
                                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth="1.5" stroke="currentColor" className="w-8 h-8 mr-2">
                                        <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 5.25a3 3 0 013 3m3 0a6 6 0 01-7.029 5.912c-.563-.097-1.159.026-1.563.43L10.5 17.25H8.25v2.25H6v2.25H2.25v-2.818c0-.597.237-1.17.659-1.591l6.499-6.499c.404-.404.527-1 .43-1.563A6 6 0 1121.75 8.25z"></path>
                                      </svg>

                                      )

                                 }
                                 

                                

                                  <span>Sign In</span>
                                </button>
                               
                              </div>
                            </form>
                          </div>
                         



                          <div className="mt-12 space-y-4 text-gray-600 text-center sm:-mb-30">
                            <p className="text-xs">By proceeding, you agree to our <a href="#" className="underline">Terms of Use</a> and confirm you have read our <a href="#" className="underline">Privacy and Cookie Statement</a>.</p>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
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
