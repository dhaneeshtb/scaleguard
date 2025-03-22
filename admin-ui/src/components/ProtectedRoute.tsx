import React, { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';

interface ProtectedRouteProps {
  element: React.ReactNode;
}

export const ProtectedRoute: React.FC<ProtectedRouteProps> = ({ element }) => {
  const navigate = useNavigate();
  const { auth } = useAuth() as any;

  useEffect(() => {
    if (!auth.loading && !auth.data) {
      navigate("/sign-in");
    }
  }, [auth, navigate]);

  if (auth.loading) return null;

  return auth.data ? <>{element}</> : null;
};
