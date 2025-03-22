// src/contexts/ThemeContext.js

import React, { createContext, useContext, useEffect, useState } from 'react';
import useDarkSide from '../components/usedarkmode';
import { useAuth } from './AuthContext';
import axios from 'axios';
import { ConfigureSystem } from '../components/ConfigureSystem';
import { setPriority } from 'os';
import { ConfigureServer } from '../components/ConfigureServer';
import LoadingScreen from './Loading';

const SystemContext = createContext<any>(null);

export const SystemContextProvider = ({ children }: { children: any }) => {
  const [properties, setProperties] = useState<any>();

  const [isReachable, setReachable] = useState(true)
  const [discovery, setDiscovery] = useState(false)


  const { auth } = useAuth() as any;

  const updateProperties = (key, value) => {

    setProperties({ ...properties, [key]: value })

  }
  const loadProperties = async () => {
    try {
      const r = await axios.get(auth.data.host + "/systems", {
        headers: {
          Authorization: auth.data.token
        }
      })
      setReachable(true)
      setProperties(r.data.reduce((acc, r) => {
        acc[r.name] = r;
        return acc;
      }, {}))
    } catch (e) {
      console.log(e)
      setReachable(false)
    } finally {
      setDiscovery(true)
    }
  }

  useEffect(() => {

    if (auth.data)
      loadProperties();
    else {
      if(!auth.loading){
        setDiscovery(true)
      }
      //console.log("Discovery done")
      //setDiscovery(true)
    }

  }, [auth])

  return (

    <SystemContext.Provider value={{ properties, updateProperties }}>

      {
        !discovery ? <LoadingScreen></LoadingScreen> :

          (properties ? (properties.hostName ? children : <ConfigureSystem onUpdate={loadProperties} auth={auth}></ConfigureSystem>) :
            (auth.data ? (<ConfigureServer onUpdate={loadProperties} ></ConfigureServer>) : children))
      }
    </SystemContext.Provider>

  );
};

export default function useSystemContext() {
  return useContext(SystemContext);
}


