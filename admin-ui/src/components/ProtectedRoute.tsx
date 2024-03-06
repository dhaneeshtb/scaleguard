import React, { useContext, useEffect } from 'react';
import { Route, redirect, useNavigate } from 'react-router-dom';
import { authContext, useAuth } from '../contexts/AuthContext';
import Layout from '../Layout';
export const ProtectedRoute = () => {
    const navigate = useNavigate();
    const { auth } = useAuth() as any;
    
    useEffect(()=>{

      if (!auth.loading && !auth.data) {
        navigate("/sign-in");
      }
    },[auth])
    return (
      auth.loading?<></>:(auth.data? <Layout></Layout>:<></>)
    );
  };