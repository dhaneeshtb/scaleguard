import { AnimatePresence } from "framer-motion";
import React, { useState } from "react";
import { motion } from "framer-motion";
import Backdrop from "./Backdrop";
import { IconButton } from "@chakra-ui/react";
import { FaCross, FaWindowClose } from "react-icons/fa";

const dropIn = {
    hidden: {
      y: "-100vh",
      opacity: 0,
    },
    visible: {
      y: "0",
      opacity: 1,
      transition: {
        duration: 0.1,
        type: "spring",
        damping: 25,
        stiffness: 500,
      },
    },
    exit: {
      y: "100vh",
      opacity: 0,
    },
  };
  
const Modal = () => {
  const [showModal, setShowModal] = useState(false);

  const schema={
    "host" : "",
    "port" : "443",
    "health" : "",
    "scheme" : "https",
    "priority" : 0,
    "type" : "active",
    "groupId" : "hostgroup1",
    "loadFactor" : 0.0,
    "reachable" : true,
    "active" : true
  }
  
  return (
    <>
      <button
        className="bg-blue-200 text-black active:bg-blue-500 
      font-bold px-6 py-3 rounded shadow hover:shadow-lg outline-none focus:outline-none mr-1 mb-1"
        type="button"
        onClick={() => setShowModal(true)}
      >
        Fill Details
      </button>
      <AnimatePresence
    // Disable any initial animations on children that
    // are present when the component is first rendered
    initial={false}
    // Only render one component at a time.
    // The exiting component will finish its exit
    // animation before entering component is rendered
    mode="wait"
    // Fires when all exiting nodes have completed animating out
    onExitComplete={() => null}
>

      {showModal ? (
        <>

          
              <div className=" flex justify-center items-center overflow-x-hidden overflow-y-auto fixed inset-0 z-50 outline-none focus:outline-none">
            
              <motion.div
            initial={{ y: 50, opacity: 0 }}
            animate={{
              y: 0,
              opacity: 1
            }}
            exit={{
              y: -50,
              opacity: 0
            }}
            transition={{ type: "spring", bounce: 0, duration: 0.4 }}
            className=" max-h-[800px] absolute z-10 p-5 bg-indigo-600 h-auto w-full max-w-md rounded text-white"
          >

            <div className={`relative w-auto my-6 mx-auto max-w-3xl h-full `}>
              <div className="border-0 rounded-lg shadow-lg relative flex flex-col w-full bg-white outline-none focus:outline-none max-h-[800px] overflow-auto">
                <div className="flex items-start justify-between p-5 border-b border-solid border-gray-300 rounded-t  ">
                  <h3 className="text-3xl font=semibold">General Info</h3>
                  <IconButton
                  rounded={"full"}
                    onClick={() => setShowModal(false)}
                    icon={<FaWindowClose ></FaWindowClose>}
                    aria-label=""
                  >

                    
                  </IconButton>
                </div>
                <div className="relative p-6 flex-auto">


                  <form className="bg-gray-200 shadow-md rounded px-8 pt-6 pb-8 w-full">

                    {
                        Object.keys(schema).map((k)=>{
                            return <>
                            <label className="block text-black text-sm font-bold mb-1 capitalize">
                      {k}
                    </label>
                    <input className="shadow appearance-none border rounded w-full py-2 px-1 text-black" />
                            </>

                        })
                    }
                    
                    
                  </form>
                </div>
                <div className="flex items-center justify-end p-6 border-t border-solid border-blueGray-200 rounded-b">
                  <button
                    className="text-red-500 background-transparent font-bold uppercase px-6 py-2 text-sm outline-none focus:outline-none mr-1 mb-1"
                    type="button"
                    onClick={() => setShowModal(false)}
                  >
                    Close
                  </button>
                  <button
                    className="text-white bg-yellow-500 active:bg-yellow-700 font-bold uppercase text-sm px-6 py-3 rounded shadow hover:shadow-lg outline-none focus:outline-none mr-1 mb-1"
                    type="button"
                    onClick={() => setShowModal(false)}
                  >
                    Submit
                  </button>
                </div>
              </div>
            </div>
            </motion.div>
          </div>

        
        </>
        
        
      ) : null}

                </AnimatePresence>

    </>
    
  );
};

export default Modal;