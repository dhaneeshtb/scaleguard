import React from 'react';
import logo from './logo.svg';
import './App.css';
import './Modal.css';

import ThemeSwitcher from './components/Themeswitcher';
import Systems from './components/Systems';
import Stats from './components/Stats';
import Modal from './components/Modal';
import RootComponents from './components/RootComponents';
import { BrowserRouter, Routes, Route } from "react-router-dom";
import Home from './pages/Home';
import Layout from './Layout';
import HostGroups from './components/HostGroups';
import ManageHost from './pages/ManageHost';
import Certificates from './components/Certificates';
import { ProtectedRoute } from './components/ProtectedRoute';
import SignIn from './sign-in/SignIn';
import Landing from './landing';

function App() {

  
  return (


    <BrowserRouter>
      <Routes>

      <Route path="/sign-in" element={<SignIn />}></Route>
      <Route path="/home" element={<Landing></Landing>}></Route>

        <Route  element={<ProtectedRoute />}>
          <Route index element={<Home />} />
          <Route path="/hostgroups" element={<HostGroups />} />
          <Route path="/managehost" element={<ManageHost />} />
          <Route path="/managehost/:type/:id" element={<ManageHost />} />
          <Route path="/certificates" element={<Certificates />} />

        </Route>
      </Routes>
    </BrowserRouter>
    
    
   
  );
}

export default App;
