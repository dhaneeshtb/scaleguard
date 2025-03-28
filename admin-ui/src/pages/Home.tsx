
import React from 'react';
import logo from './logo.svg';


import ThemeSwitcher from '../components/Themeswitcher';
import Systems from '../components/Systems';
import Stats from '../components/Stats';
import Modal from '../components/Modal';
import RootComponents from '../components/RootComponents';
import { BrowserRouter, Routes, Route } from "react-router-dom";
import QuickMapping from '../components/QuickMapping';

function Home() {


    return (





        <div className='min-h-screen '>
            



            <Stats></Stats>

            <div className='flex justify-end'>
                <QuickMapping onMappingComplete={()=>{    window.location.reload()}}></QuickMapping>
            </div>
            <RootComponents></RootComponents>
        </div>




    );
}

export default Home;
