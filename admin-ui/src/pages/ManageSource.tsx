import React, { useEffect, useState } from "react";
import { Button, Select, Badge, Box } from "@chakra-ui/react";
import { FaArrowLeft, FaCog, FaLock, FaSave, FaServer, FaShieldAlt } from "react-icons/fa";
import axios from "axios";
import { useNavigate, useParams } from "react-router-dom";
import { formatData, getBaseSchema, getBaseSchemaDef, updateSource } from "../components/sourceupdate";
import { useAuth } from "../contexts/AuthContext";
import CodeMirror from "@uiw/react-codemirror";
import { json } from "@codemirror/lang-json";
import { lintGutter } from "@codemirror/lint";
import { Checkbox } from '@chakra-ui/react';
import { Tabs, TabList, TabPanels, Tab, TabPanel } from "@chakra-ui/react";

const ManageSource = () => {
    const navigate = useNavigate();
    const { id, type = "sourcesystems" } = useParams() as any;
    const { auth } = useAuth() as any;
    const [isLoading, setLoading] = useState(false);
    const [asyncEngines, setAsyncEngines] = useState<any[]>([]);
    const isNew = !id || id === "new";

    useEffect(() => {
        if (id && id !== "new")
            axios.get(`${auth.data.host}/config/${type}/${id}?scaleguard=true`, {
                headers: { Authorization: auth.data.token }
            }).then(r => setObject((r.data[type])[0]));
        else
            setObject(getBaseSchema(type));
    }, [id, type]);

    const [object, setObject] = useState({});

    const onSave = async (baseObject) => {
        setLoading(true);
        try {
            const saveObj = { ...baseObject };
            if (saveObj["includeHeaders"] && typeof saveObj["includeHeaders"] !== "object") {
                saveObj["includeHeaders"] = JSON.parse(saveObj["includeHeaders"]);
            }
            if (saveObj["excludeHeaders"] && typeof saveObj["excludeHeaders"] !== "object") {
                saveObj["excludeHeaders"] = JSON.parse(saveObj["excludeHeaders"]);
            }
            if (saveObj["cachedResources"] && typeof saveObj["cachedResources"] !== "object") {
                saveObj["cachedResources"] = JSON.parse(saveObj["cachedResources"]);
            }
            await updateSource(saveObj, type, auth);
            navigate("/home");
        } catch (e) {
            console.error("Failed to save:", e);
        } finally {
            setLoading(false);
        }
    };

    const [certificates, setCertificates] = useState<any>([]);
    const loadCerts = async () => {
        const r = await axios.get(auth.data.host + "/certificates?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        });
        setCertificates(formatData(r.data));
    };

    const loadAsyncEngines = async () => {
        const r = await axios.get(auth.data.host + "/asyncengines?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        });
        const res = r.data;
        setAsyncEngines(Object.keys(res).map(r => {
            const o = res[r];
            return { id: o.id, name: o.name, type: o.type, topic: o.payload?.topic || "event-stream", host: o.payload.host, port: o.payload.port };
        }));
    };

    useEffect(() => {
        loadCerts();
        loadAsyncEngines();
    }, []);

    const RenderScreen = ({ type, bs, baseObject, setBaseObject }) => {
        const schmaDef = getBaseSchemaDef(type);

        const inputClasses = "w-full px-4 py-2.5 rounded-xl bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-white text-sm focus:ring-2 focus:ring-teal-500/40 focus:border-teal-500 transition-all outline-none";
        const selectProps = {
            className: "text-slate-800 dark:text-white !bg-slate-50 dark:!bg-[rgba(30,41,59,0.8)]",
            size: "md" as const,
            borderRadius: "xl",
            borderColor: "gray.200",
            _focus: { borderColor: "teal.500", boxShadow: "0 0 0 1px var(--chakra-colors-teal-500)" },
            sx: { option: { background: "var(--chakra-colors-gray-800)", color: "white" } },
        };

        const compScreen = (k) => {
            switch (k) {
                case "certificateId":
                    return <Select key={k} {...selectProps} placeholder='Select certificate' value={baseObject[k]} onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })}>
                        {certificates.map(c => (
                            <option key={c.id} value={c.id}>{c.json.identifiers.map(s => s.value).join(",") + ":" + c.id}</option>
                        ))}
                    </Select>;
                case "type":
                    return <Select key={k} {...selectProps} placeholder='Select type' value={baseObject[k]} onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })}>
                        {["active", "standby"].map(c => (<option key={c} value={c}>{c}</option>))}
                    </Select>;
                case "async":
                    return <Select key={k} {...selectProps} placeholder='Select' value={baseObject[k]} onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })}>
                        {["true", "false"].map(c => (<option key={c} value={c}>{c}</option>))}
                    </Select>;
                case "scheme":
                    return <Select key={k} {...selectProps} placeholder='Select scheme' value={baseObject[k]} onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })}>
                        {(type === "sourcesystems" ? ["http", "https", "tcp", "kafka"] : ["http", "https", "tcp"]).map(c => (
                            <option key={c} value={c}>{c}</option>
                        ))}
                    </Select>;
                case "autoProcure":
                    return <div className="flex items-center gap-3 py-1">
                        <Checkbox key={k} isChecked={baseObject[k]} onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.checked })} colorScheme="teal" size="lg" />
                        <span className="text-xs text-slate-500 dark:text-slate-400">Automatically procure and renew SSL certificates</span>
                    </div>;
                default:
                    return bs[k] && (typeof bs[k] == "object") ?
                        <div className="rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700">
                            <CodeMirror key={k}
                                value={typeof baseObject[k] === "object" ? JSON.stringify(baseObject[k]) : baseObject[k]}
                                height="100px"
                                extensions={[json(), lintGutter()]}
                                onChange={(value) => { setBaseObject({ ...baseObject, [k]: value }); }}
                                theme="dark"
                            />
                        </div>
                        :
                        <input key={k} onChange={(e) => setBaseObject({ ...baseObject, [k]: e.target.value })} value={k === "port" && baseObject['scheme'] === "https" ? "443" : baseObject[k]} className={inputClasses} placeholder={schmaDef?.[k]?.hint || ""} />;
            }
        };

        const filterField = (scheme, k) => {
            if (scheme === "tcp") {
                return k !== "asyncEngine" && k !== "host" && k !== "basePath" && k !== "async" && k !== "jwtKeylookup" && k !== "callbackId" && k !== "certificateId" && k !== "includeHeaders" && k !== "excludeHeaders" && k !== "cachedResources" && k !== "enableCache";
            } else if (scheme === "kafka") {
                return k !== "asyncEngine" && k !== "async" && k !== "jwtKeylookup" && k !== "callbackId" && k !== "certificateId";
            } else {
                return k !== "asyncEngine" && true;
            }
        };

        function getHint(type, name) {
            if (name === "basePath" && type === "kafka") {
                return <span className="text-amber-400 text-[10px] ml-1">(comma separated topic names)</span>;
            }
            return null;
        }

        const visibleKeys = Object.keys(bs).filter(k =>
            (type === "sourcesystems" || type === "targetsystems" ? k === "secappid" ? false : filterField(baseObject["scheme"], k) : true)
        );

        return (
            <div className="space-y-5">
                {visibleKeys.map((k) => (
                    <div key={k}>
                        <div className="flex gap-3 w-full">
                            <div className="flex flex-col w-full space-y-1.5">
                                <label className="flex items-center gap-1.5">
                                    <span className="text-sm font-medium text-slate-700 dark:text-slate-300 capitalize">
                                        {schmaDef ? schmaDef[k]?.displayName : k}
                                    </span>
                                    {schmaDef && schmaDef[k]?.mandatory && <span className="text-red-400 text-xs">*</span>}
                                    {getHint(baseObject["scheme"], k)}
                                </label>
                                {compScreen(k)}
                                {schmaDef && schmaDef[k]?.hint && (
                                    <p className="text-[11px] text-slate-400 dark:text-slate-500 pl-1">{schmaDef[k].hint}</p>
                                )}
                            </div>

                            {/* Async Engine Selector */}
                            {(k === "async" && (baseObject["async"] === "true" || baseObject["async"] === true)) && (
                                <div className="flex flex-col w-3/4 space-y-1.5">
                                    <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                        Choose Engine
                                    </label>
                                    <Select {...selectProps} placeholder='Select engine' value={baseObject['asyncEngine']} onChange={(e) => setBaseObject({ ...baseObject, 'asyncEngine': e.target.value })}>
                                        {asyncEngines.map(c => (
                                            <option key={c.name} value={c.name}>{c.name}</option>
                                        ))}
                                    </Select>
                                </div>
                            )}
                        </div>
                    </div>
                ))}
            </div>
        );
    };

    const RenderSchema = ({ type, object }) => {
        const [baseObject, setBaseObject] = useState(object);
        const [bs] = useState(getBaseSchema(type));
        const [securityType, setSecurityType] = useState((object as any).secappid || "");
        const [apps, setApps] = useState<any[]>([]);

        const loadApps = async () => {
            const r = await axios.get(`${auth.data.host}/apps?scaleguard=true`, {
                headers: { Authorization: auth.data.token }
            });
            setApps(r.data);
        };

        useEffect(() => { loadApps(); }, []);

        const selectProps = {
            borderRadius: "xl",
            bg: "var(--chakra-colors-gray-50)",
            _dark: { bg: "rgba(30,41,59,0.8)" },
            borderColor: "gray.200",
            _focus: { borderColor: "teal.500", boxShadow: "0 0 0 1px var(--chakra-colors-teal-500)" },
        };

        return (
            <>
                <div className="p-6 sm:p-8">
                    <Tabs colorScheme='teal' variant="soft-rounded">
                        <TabList className="gap-2 mb-6">
                            <Tab className="!text-xs !font-medium !uppercase !tracking-wider" borderRadius="xl" px={5} py={2}>
                                <FaCog className="mr-2 text-xs" /> General
                            </Tab>
                            <Tab className="!text-xs !font-medium !uppercase !tracking-wider" borderRadius="xl" px={5} py={2}>
                                <FaShieldAlt className="mr-2 text-xs" /> Security
                            </Tab>
                        </TabList>
                        <TabPanels>
                            <TabPanel px={0}>
                                <RenderScreen type={type} setBaseObject={setBaseObject} baseObject={baseObject} bs={bs} />
                            </TabPanel>
                            <TabPanel px={0}>
                                <div className="space-y-5">
                                    <div className="space-y-1.5">
                                        <label className="text-sm font-medium text-slate-700 dark:text-slate-300 flex items-center gap-2">
                                            <FaLock className="text-xs text-teal-500" />
                                            Security Application
                                        </label>
                                        <Select {...selectProps} value={securityType} onChange={(e) => setSecurityType(e.target.value)}>
                                            <option value={""}>— None —</option>
                                            {apps.map(a => (
                                                <option key={a.id} value={a.id}>{a.name} – {a.id}</option>
                                            ))}
                                        </Select>
                                    </div>
                                    {securityType && (
                                        <div className="bg-teal-500/5 border border-teal-500/20 rounded-xl p-4">
                                            <p className="text-xs text-slate-500 dark:text-slate-400">Selected Security App</p>
                                            <p className="text-sm font-semibold text-slate-800 dark:text-white font-mono mt-1">{securityType}</p>
                                        </div>
                                    )}
                                </div>
                            </TabPanel>
                        </TabPanels>
                    </Tabs>
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between p-6 sm:px-8 border-t border-slate-200/40 dark:border-slate-700/40 bg-slate-50/50 dark:bg-slate-800/30 rounded-b-2xl">
                    <button
                        onClick={() => navigate("/home")}
                        className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-white transition-colors"
                    >
                        <FaArrowLeft className="text-xs" />
                        Back to Dashboard
                    </button>
                    <Button
                        colorScheme="teal"
                        leftIcon={<FaSave />}
                        isLoading={isLoading}
                        onClick={() => onSave({ ...baseObject, secappid: securityType })}
                        rounded="xl"
                        size="md"
                        px={6}
                        _hover={{ transform: "translateY(-1px)", shadow: "lg" }}
                        transition="all 0.2s"
                    >
                        {isNew ? "Create" : "Save Changes"}
                    </Button>
                </div>
            </>
        );
    };

    return (
        <div className="min-h-screen py-8 px-4">
            <div className="max-w-2xl mx-auto">
                {/* Header Card */}
                <div className="relative overflow-hidden rounded-t-2xl">
                    <div className="absolute inset-0 bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900"></div>
                    <div className="absolute inset-0 bg-gradient-to-br from-teal-500/20 to-cyan-500/20 opacity-30"></div>
                    <div className="absolute inset-0 opacity-[0.03]" style={{
                        backgroundImage: 'linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)',
                        backgroundSize: '24px 24px'
                    }}></div>
                    <div className="relative px-6 sm:px-8 py-6">
                        <div className="flex items-center gap-3">
                            <div className="p-2.5 bg-white/10 rounded-xl border border-white/10">
                                <FaServer className="text-white text-lg" />
                            </div>
                            <div>
                                <div className="flex items-center gap-2">
                                    <h1 className="text-xl font-bold text-white">Source System</h1>
                                    <Badge colorScheme={isNew ? "green" : "blue"} fontSize="10px" px={2} borderRadius="full">
                                        {isNew ? "NEW" : "EDIT"}
                                    </Badge>
                                </div>
                                {!isNew && (
                                    <p className="text-xs text-slate-400 font-mono mt-0.5 truncate max-w-md">{id}</p>
                                )}
                            </div>
                        </div>
                    </div>
                </div>

                {/* Form Card */}
                <div className="bg-white dark:bg-slate-900 border border-slate-200/60 dark:border-slate-700/50 border-t-0 rounded-b-2xl shadow-xl shadow-slate-200/20 dark:shadow-slate-900/40">
                    <RenderSchema type={type} object={object} />
                </div>
            </div>
        </div>
    );
};

export default ManageSource;