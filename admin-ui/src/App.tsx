import React from 'react';
import logo from './logo.svg';
import './App.css';
import './Modal.css';

import ThemeSwitcher from './components/Themeswitcher';
import Systems from './components/Systems';
import Stats from './components/Stats';
import Modal from './components/Modal';
import RootComponents from './components/RootComponents';
import { BrowserRouter, HashRouter, Routes, Route } from "react-router-dom";

// Hash routing is only used for the self-contained offline design preview build.
const Router = process.env.REACT_APP_HASH_ROUTER === "true" ? HashRouter : BrowserRouter;
import Home from './pages/Home';
import Layout from './Layout';
import HostGroups from './components/HostGroups';
import ManageHost from './pages/ManageHost';
import Certificates from './components/Certificates';
import { ProtectedRoute } from './components/ProtectedRoute';
import SignIn from './sign-in/SignIn';
import Landing from './landing';
import DNS from './components/DNS';
import Security from './pages/security/Security';
import ManageSource from './pages/ManageSource';
import AsyncEngines from './pages/asyncengines/AsyncEngines';
import RateLimiting from './pages/protection/RateLimiting';
import Firewall from './pages/protection/Firewall';

function App() {
  return (
    <Router>
      <Routes>
        <Route path="/sign-in" element={<SignIn />} />
        <Route path="/" element={<Landing />} />
        <Route
          path="/*"
          element={
            <ProtectedRoute
              element={
                <Layout>
                  <Routes>
                    <Route path="/home" element={<Home />} />
                    <Route path="/security" element={<Security />} />
                    <Route path="/hostgroups" element={<HostGroups />} />
                    <Route path="/managehost" element={<ManageHost />} />
                    <Route path="/asyncengines" element={<AsyncEngines />} />

                    
                    <Route path="/managehost/sourcesystems/:id" element={<ManageSource />} />

                    <Route path="/managehost/:type/:id" element={<ManageHost />} />
                    <Route path="/certificates" element={<Certificates />} />
                    <Route path="/dns" element={<DNS />} />
                    <Route path="/ratelimit" element={<RateLimiting />} />
                    <Route path="/firewall" element={<Firewall />} />
                  </Routes>
                </Layout>
              }
            />
          }
        />
      </Routes>
    </Router>
  );
}

export default App;
