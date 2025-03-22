import { Outlet, Link } from "react-router-dom";
import ThemeSwitcher from "./components/Themeswitcher";
import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
    Menu,
    MenuButton,
    MenuList,
    MenuItem,
    MenuItemOption,
    MenuGroup,
    MenuOptionGroup,
    MenuDivider,
    Button,
  } from '@chakra-ui/react'
import { ChevronDownIcon } from "@chakra-ui/icons";
import { useAuth } from "./contexts/AuthContext";
import { FaUserAlt, FaUserCircle } from "react-icons/fa";
import { ConfigureSystem } from "./components/ConfigureSystem";

const Layout = () => {

    
    const className = "justify-center navbar flex items-center w-sceen";

    const {auth,setAuthData} = useAuth() as any;

    const logout=()=>{

        localStorage.removeItem("authData")
        setAuthData({data:null})
    }
    
  
  return (
    <>
   <AnimatePresence>
     <nav className={className}>
     <div className="flex justify-center items-center  absolute right-0 top-0">
                <ThemeSwitcher />

              
            </div>
        <div className="mx-auto px-4 sm:px-6 lg:px-8 w-full max-w-7xl ">
          <div className="flex items-center justify-between h-16">
            <div className="flex items-center w-full">
              <div className="flex-shrink-0">
                <div className="flex items-center text-white font-bold">
                <img
                  className="h-16 w-16"
                  src="/lb.svg"
                  alt="Workflow"
                />
                Scaleguard
                </div>
              </div>
              <div className="hidden md:block w-full">
                <div className="ml-10 flex items-baseline space-x-4 justify-end">
                  <a
                    href="/"
                    className=" hover:bg-gray-700 text-white px-3 py-2 rounded-md text-sm font-medium"
                  >
                    Home
                  </a>

                  <a
                    href="/hostgroups"
                    className="text-gray-300 hover:bg-gray-700 hover:text-white px-3 py-2 rounded-md text-sm font-medium"
                  >
                    Hostgroup
                  </a>

                  <a
                    href="/certificates"
                    className="text-gray-300 hover:bg-gray-700 hover:text-white px-3 py-2 rounded-md text-sm font-medium"
                  >
                    Certificates
                  </a>

                <ConfigureSystem onUpdate={()=>{}} auth={auth} load={false}></ConfigureSystem>


                  <Menu>
  <MenuButton colorScheme="black" className="hover:bg-gray-700" as={Button} rightIcon={<ChevronDownIcon />}>
   <div className="flex gap-2 items-center"> <FaUserCircle></FaUserCircle>{auth.data.username}</div>
  </MenuButton>
  <MenuList >
    <MenuItem  onClick={logout}>Logout</MenuItem>
  </MenuList>
</Menu>

                 

                  
                </div>
              </div>
            </div>
            <div className="-mr-2 flex md:hidden">
              <button
                type="button"
                className="bg-gray-800 inline-flex items-center justify-center p-2 rounded-md text-gray-400 hover:text-white hover:bg-gray-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-offset-gray-800 focus:ring-white"
                aria-controls="mobile-menu"
                aria-expanded="false"
              >
                <span className="sr-only">Open main menu</span>

                <svg
                  className="block h-6 w-6"
                  xmlns="http://www.w3.org/2000/svg"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  aria-hidden="true"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M4 6h16M4 12h16M4 18h16"
                  />
                </svg>

                <svg
                  className="hidden h-6 w-6"
                  xmlns="http://www.w3.org/2000/svg"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  aria-hidden="true"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </div>
          </div>
        </div>

        <div className="md:hidden" id="mobile-menu">
          <div className="px-2 pt-2 pb-3 space-y-1 sm:px-3">
            {/* <a
              href="#"
              className="hover:bg-gray-700 text-white block px-3 py-2 rounded-md text-base font-medium"
            >
              Dashboard
            </a> */}

            {/* <a
              href="#"
              className="text-gray-300 hover:bg-gray-700 hover:text-white block px-3 py-2 rounded-md text-base font-medium"
            >
              Team
            </a> */}

            {/* <a
              href="#"
              className="text-gray-300 hover:bg-gray-700 hover:text-white block px-3 py-2 rounded-md text-base font-medium"
            >
              Projects
            </a> */}

           
          </div>
        </div>
      </nav>
      {/* <motion.div
      className="dark:bg-slate-900"
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true }}
      transition={{ duration: 0.3 }}
      variants={{
        visible: { opacity: 1, scale: 1 },
        hidden: { opacity: 0, scale: 0 }
      }}
    > */}

<motion.div
            initial={{ opacity: 0, y: 40 }}
            animate={{ opacity: 1, y: 0 ,paddingTop: "60px", paddingBottom:  "60px"}}
            transition={{
              delay: 0.15,
              duration: 0.95,
              ease: [0.165, 0.84, 0.44, 1],
            }}
            className="md:px-[100px] dark:bg-slate-800"
          >
  {/* <motion.div
className=""
initial={{ opacity: 0, scale: 0.5 }}
    animate={{ opacity: 1, scale: 1 ,paddingTop: "60px", paddingBottom:  "60px"}}
    transition={{ duration: 0.5 }}>
        */}
   

    {/* <div className=" md:px-[100px] dark:bg-slate-800"> */}
    <Outlet />

    {/* </div> */}
    </motion.div>
    </AnimatePresence>
    </>
  )
};

export default Layout;