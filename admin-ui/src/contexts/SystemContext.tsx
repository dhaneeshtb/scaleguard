// src/contexts/ThemeContext.js

import React, { createContext, useContext, useEffect, useState } from 'react';
import useDarkSide from '../components/usedarkmode';
import { useAuth } from './AuthContext';
import axios from 'axios';
import { ConfigureSystem } from '../components/ConfigureSystem';
import { setPriority } from 'os';
import { ConfigureServer } from '../components/ConfigureServer';

const SystemContext = createContext<any>(null);

export const SystemContextProvider = ({ children }:{children:any}) => {
  const [properties, setProperties] = useState<any>();

  const [isReachable,setReachable] = useState(true)

  const {auth} = useAuth() as any;

  const updateProperties=(key,value)=>{

    setProperties({...properties,[key]:value})

  }
  const loadProperties=async ()=>{
    try{
     const r = await axios.get(auth.data.host+"/systems",{
        headers:{
          Authorization:auth.data.token
        }
    })
    setProperties(r.data.reduce((acc,r)=>{
      acc[r.name]=r;
      return acc;
    },{}))
  }catch(e){

    setReachable(false)

  }
  }

  useEffect(()=>{

    if(auth.data)
    loadProperties();

  },[auth])

  return (

    <SystemContext.Provider value={{ properties, updateProperties }}>
      {properties?  properties.hostName? children : <ConfigureSystem onUpdate={loadProperties} auth={auth}></ConfigureSystem>:
      auth.data? <ConfigureServer onUpdate={updateProperties} ></ConfigureServer>:children
      }
    </SystemContext.Provider>

  );
};

export default function useSystemContext() {
    return useContext(SystemContext);
}


