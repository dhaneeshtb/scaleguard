import { AnimatePresence } from "framer-motion";
import React, { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import { Button, IconButton, Select } from "@chakra-ui/react";
import { FaCross, FaSave, FaWindowClose } from "react-icons/fa";
import axios from "axios";
import { useNavigate, useParams } from "react-router-dom";
import { formatData, getBaseSchema, updateSource } from "../components/sourceupdate";
import { useAuth } from "../contexts/AuthContext";
import CodeMirror from "@uiw/react-codemirror";
import { json } from "@codemirror/lang-json";
import { linter, lintGutter } from "@codemirror/lint";
const ManageHost = () => {
    const navigate = useNavigate();
    const { id, type } = useParams() as any;

    const { auth } = useAuth() as any;

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
        navigate("/")
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
                case "scheme": 
                    return <Select className="text-black dark:text-white" placeholder='Select option' value={baseObject[k]} onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })} >
                            {
                                (type=="sourcesystems"? ["http", "https", "tcp","kafka"]: ["http", "https", "tcp"]).map(c => {
                                    return <option value={c}>{c}</option>
                                })
                            }</Select>
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
                        <input onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })} value={baseObject[k]} className="shadow appearance-none border rounded w-full py-2 px-1 text-black" />
            }
        }

        const filterField=(scheme,k)=>{
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
        return <>{Object.keys(bs).filter(k=>(type=="sourcesystems"||type=="targetsystems" ? filterField(baseObject["scheme"],k):true)).map((k) => {
            return  <div>
                <label className="block text-black dark:text-white text-sm font-normal mb-1 ">
                    <span className="capitalize">{k}</span> {getHint(baseObject["scheme"],k)}
                </label>
                {compScreen(k)}

                </div>


        })}
        </>

    }

    const RenderSchema = ({ type, object }) => {
        const [baseObject, setBaseObject] = useState(object);
        const [bs, setBs] = useState(getBaseSchema(type))

        return <>
            <div className="relative p-6 flex-auto dark:bg-slate-900 shadow-3xl">


                <form className=" shadow-md rounded px-8 pt-6 pb-8 w-full flex flex-col gap-2">

                    <RenderScreen type={type} setBaseObject={setBaseObject} baseObject={baseObject} bs={bs}></RenderScreen>


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

                    className="text-white bg-yellow-500 active:bg-yellow-700 font-bold  text-sm px-6 py-3 rounded shadow hover:shadow-lg outline-none focus:outline-none mr-1 mb-1"
                    type="button"
                    onClick={() => onSave(baseObject)}
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

export default ManageHost;