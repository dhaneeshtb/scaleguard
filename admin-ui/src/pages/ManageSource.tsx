import { AnimatePresence } from "framer-motion";
import React, { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import { Button, IconButton, Select } from "@chakra-ui/react";
import { FaCross, FaSave, FaWindowClose } from "react-icons/fa";
import axios from "axios";
import { useNavigate, useParams } from "react-router-dom";
import { formatData, getBaseSchema, getBaseSchemaDef, updateSource } from "../components/sourceupdate";
import { useAuth } from "../contexts/AuthContext";
import CodeMirror from "@uiw/react-codemirror";
import { json } from "@codemirror/lang-json";
import { linter, lintGutter } from "@codemirror/lint";
import { Checkbox, CheckboxGroup } from '@chakra-ui/react'
import { Tabs, TabList, TabPanels, Tab, TabPanel, Box } from "@chakra-ui/react";

const ManageSource = () => {
    const navigate = useNavigate();
    const { id, type="sourcesystems" } = useParams() as any;

    const { auth } = useAuth() as any;

    const [isLoading,setLoading] =useState(false);

    useEffect(() => {
        if (id && id != "new")
            axios.get(`${auth.data.host}/config/${type}/${id}?scaleguard=true`, {
                headers: {
                    Authorization: auth.data.token
                }
            }).then(r => setObject((r.data[type])[0]))
        else
            setObject(getBaseSchema(type));
    }, [id, type])

    const [object, setObject] = useState({})


    const onSave = async (baseObject) => {
        setLoading(true);
        if(baseObject["includeHeaders"] && typeof baseObject["includeHeaders"]!="object"){
            baseObject["includeHeaders"]=JSON.parse(baseObject["includeHeaders"]);
        }
        if(baseObject["excludeHeaders"] && typeof baseObject["excludeHeaders"]!="object"){
            baseObject["excludeHeaders"]=JSON.parse(baseObject["excludeHeaders"]);
        }
        if(baseObject["cachedResources"] && typeof baseObject["cachedResources"]!="object"){
            baseObject["cachedResources"]=JSON.parse(baseObject["cachedResources"]);
        }
        await updateSource(baseObject, type, auth);
        setLoading(false);
        navigate("/home")
    }

    const [certificates, setCertificates] = useState<any>([]);
    const loadCerts = async () => {
        const r = await axios.get(auth.data.host + "/certificates?scaleguard=true", {
            headers: {
                Authorization: auth.data.token
            }
        });
        setCertificates(formatData(r.data))
    }

    useEffect(() => {
        loadCerts();
    }, [])

    const RenderScreen = ({type, bs, baseObject, setBaseObject }) => {

        const schmaDef = getBaseSchemaDef(type)

        const compScreen = (k) => {


            switch (k) {
                case "certificateId": 
                    return <Select className="text-black dark:text-white" placeholder='Select option' value={baseObject[k]} onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })} >
                                {
                                    certificates.map(c => {
                                        return <option value={c.id}>{c.json.identifiers.map(s => s.value).join(",") + ":" + c.id}</option>
                                    })
                                }
                            </Select>;
                case "type": 
                return <Select className="text-black dark:text-white" placeholder='Select option' value={baseObject[k]} onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })} >
                        {
                            ( ["active", "standby"]).map(c => {
                                return <option value={c}>{c}</option>
                            })
                        }</Select>

                case "scheme": 
                    return <Select className="text-black dark:text-white" placeholder='Select option' value={baseObject[k]} onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })} >
                            {
                                (type=="sourcesystems"? ["http", "https", "tcp","kafka"]: ["http", "https", "tcp"]).map(c => {
                                    return <option value={c}>{c}</option>
                                })
                            }</Select>
                case "autoProcure": 
                        return <Checkbox
                            isChecked={baseObject[k]}
                            onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.checked })}
                          >
                          </Checkbox>
                default: 
                    return bs[k] && (typeof bs[k] == "object") ?
                    <CodeMirror
                    value={typeof baseObject[k]=="object"? JSON.stringify(baseObject[k]):baseObject[k]}
                    height="100px"
                    extensions={[json(), lintGutter()]}
                    onChange={(value, viewUpdate) => {
                    //   setJsonFile(value);
                        setBaseObject({ ...baseObject, [k]: value })
                    //   updateMarkers();
                    }}
                    style={{
                      border: "1px solid silver",
                      color:"black"
                    }}
                  />

                        // <input onChange={(e) => setBaseObject({ ...baseObject, [k]: JSON.parse(e.target.value) })} value={JSON.stringify(baseObject[k])} className="shadow appearance-none border rounded w-full py-2 px-1 text-black" />
                        :
                        <input onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })} value={k=="port" && baseObject['scheme']=="https" ?"443":baseObject[k]} className="shadow appearance-none border rounded w-full py-2 px-1 text-black" />
            }
        }

        const filterField=(scheme,k,baseObject)=>{
            if(baseObject[scheme]=="https"){
                baseObject['port']="443"
            }
           if(scheme=="tcp"){
            return k!="host" && k!="basePath" && k!="async" && k!="jwtKeylookup" && k!="callbackId" && k!="certificateId" && k!="includeHeaders" && k!="excludeHeaders" && k!="cachedResources" && k!="enableCache";
           }else if(scheme=="kafka"){
              return   k!="async" && k!="jwtKeylookup" && k!="callbackId" && k!="certificateId";
           }else{
            return true;
           }
        }

        function getHint(type,name){

            if(name=="basePath" && type=="kafka"){
                return <span >(comma separated topic names)</span>
            }else{
                return <></>
            }

        }
        return <>{Object.keys(bs).filter(k=>(type=="sourcesystems"||type=="targetsystems" ? k=="secappid"?false: filterField(baseObject["scheme"],k,baseObject):true)).map((k) => {
            return  <div className="flex flex-col">
                <label className="block text-black dark:text-white text-sm font-normal mb-1 ">
                    <span className="capitalize">{schmaDef?schmaDef[k].displayName :k} </span> 
                    
                    {schmaDef && schmaDef[k].mandatory ?<span className="text-red-400 text-lg">*</span>:<></>}

                       {getHint(baseObject["scheme"],k)}
                </label>
                {compScreen(k)}

                {schmaDef && schmaDef[k].hint ?<span className="italic text-gray-400 text-xs">{schmaDef[k].hint}</span>:<></>}



                </div>


        })}
        </>

    }

    const RenderSchema = ({ type, object }) => {
        const [baseObject, setBaseObject] = useState(object);
        const [bs, setBs] = useState(getBaseSchema(type))
        const [securityType, setSecurityType] = useState(object.secappid||"");
        const [apps, setApps] = useState<any[]>([]);


    const loadApps=async ()=>{
        const r=await axios.get(`${auth.data.host}/apps?scaleguard=true`, {
             headers: {
                 Authorization: auth.data.token
             }
         })
         setApps(r.data);
     }
     useEffect(()=>{
        loadApps();
     },[])
        return <>
            <div className="relative p-6 flex-auto dark:bg-slate-900 shadow-3xl">


                <form className=" shadow-md rounded px-8 pt-6 pb-8 w-full flex flex-col gap-2">

                <Tabs colorScheme='teal' className='text-gray-500 dark:text-gray-400 dark:bg-slate-900  rounded-xl '>
      <TabList>
        <Tab>General</Tab>
        <Tab>Security</Tab>
      </TabList>
      <TabPanels>
      <TabPanel>
            <RenderScreen type={type} setBaseObject={setBaseObject} baseObject={baseObject} bs={bs}></RenderScreen>

            </TabPanel>
        <TabPanel>
           
          <Box mb={4}>
            <Select value={securityType} onChange={(e) => setSecurityType(e.target.value)}>
            <option value={""}>--none--</option>
                {apps.map(a=>{
                    return    <option value={a.id}>{a.name+"-"+a.id}</option>

                })}
            
            </Select>
          </Box>
          <Box>
            Selected Security App: <strong>{securityType}</strong>
          </Box>
        </TabPanel>
        
      </TabPanels>
    </Tabs>



                </form>
            </div>
            <div className="flex items-center justify-end p-6 border-t border-solid border-blueGray-200 rounded-b">
                <a
                    href="/" className="text-red-500 background-transparent font-bold uppercase px-6 py-2 text-sm outline-none focus:outline-none mr-1 mb-1"
                    type="button"
                    onClick={() => { }}
                >
                    Close
                </a>
                <Button
                    colorScheme="teal"
                    variant={"outline"}
                    leftIcon={<FaSave></FaSave>}
                    isLoading={isLoading}

                    className="text-white bg-yellow-500 active:bg-yellow-700 font-bold  text-sm px-6 py-3 rounded shadow hover:shadow-lg outline-none focus:outline-none mr-1 mb-1"
                    type="button"
                    onClick={() => onSave({...baseObject,secappid:securityType})}
                >
                    Submit
                </Button>
            </div>
        </>

    }

    return (
        <>


            <div className="dark:bg-slate-800 flex justify-center items-center overflow-x-hidden overflow-y-auto  inset-0 z-50 outline-none focus:outline-none">



                <div className={`relative w-full my-6 mx-auto max-w-xl h-full `}>
                    <div className="border-0 rounded-lg shadow-lg relative flex flex-col w-full dark:bg-slate-900 text-white  outline-none focus:outline-none  overflow-auto">
                        <div className="flex items-start justify-between p-5 border-b border-solid border-gray-300 rounded-t  ">
                            <h3 className="text-black dark:text-white text-3xl font-semibold capitalize">{type} Info</h3>

                        </div>


                        <RenderSchema type={type} object={object}></RenderSchema>





                    </div>
                </div>

            </div>


        </>

    );
};

export default ManageSource;