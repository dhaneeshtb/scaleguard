import React, { useContext } from 'react';
import { Route, redirect, useNavigate } from 'react-router-dom';
import { authContext, useAuth } from '../contexts/AuthContext';
import Layout from '../Layout';
export const ProtectedRoute = () => {
    const navigate = useNavigate();
    const { auth } = useAuth() as any;
    if (!auth.loading && !auth.data) {
      navigate("/sign-in");
    }
    return (
      auth.loading?<></>:(auth.data? <Layout></Layout>:<></>)
    );
  };