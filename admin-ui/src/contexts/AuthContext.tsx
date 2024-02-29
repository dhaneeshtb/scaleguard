import React, { createContext, useState, useEffect, useContext } from 'react';
import { useNavigate } from 'react-router-dom';

export const authContext = createContext({});

const AuthProvider = ({ children }) => {
  const [auth, setAuthData] = useState({ loading: true, data: null });



  useEffect(() => {

    const at=window.localStorage.getItem('authData');
    if(at){
    setAuthData({ loading: false, data:window.localStorage.getItem('authData')? JSON.parse(window.localStorage.getItem('authData') as any) :null});
    }else{
      setAuthData({loading: false,data:null})
    }
  }, []);
//2. if object with key 'authData' exists in localStorage, we are putting its value in auth.data and we set loading to false. 
//This function will be executed every time component is mounted (every time the user refresh the page);

  // useEffect(() => {
  //   if(auth.data)
  //     window.localStorage.setItem('authData', JSON.stringify(auth.data));
  // }, [auth.data]);
// 1. when **auth.data** changes we are setting **auth.data** in localStorage with the key 'authData'.

  return (
    <authContext.Provider value={{ auth, setAuthData }}>
      {children}
    </authContext.Provider>
  );
};

export const useAuth=()=>{
  return useContext(authContext);
}

export default AuthProvider;