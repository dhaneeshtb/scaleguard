import { Button, Badge } from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState } from 'react';
import { FaGlobe, FaPlusCircle, FaSave, FaNetworkWired } from "react-icons/fa";
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';
import NameServers from './NameServers';


export default function DNS() {
    const [systems, setSystems] = useState<any>();
    const [loading, setLoading] = useState<boolean>(false);
    const { auth } = useAuth() as any;

    const [name, setName] = useState<string>();
    const [type, setType] = useState<string>("base");
    const [ttl, setTTL] = useState<number>(600);
    const [ip, setIp] = useState<string>();
    const [nameServers, setNameServers] = useState<any[]>([]);
    const [status, setStatus] = useState<any>();
    const [dnsError, setDnsError] = useState<string>();
    const [testDomain, setTestDomain] = useState<string>("");
    const [testing, setTesting] = useState<boolean>(false);
    const [testResult, setTestResult] = useState<any>();

    const runTest = async () => {
        setTesting(true); setTestResult(undefined); setDnsError(undefined);
        try {
            const r = await axios.get(auth.data.host + "/dns/test?scaleguard=true&domain=" + encodeURIComponent(testDomain.trim()), {
                headers: { Authorization: auth.data.token }
            });
            setTestResult(r.data);
        } catch (e: any) {
            setDnsError(e?.response?.data?.error || "Could not run the name server test");
        }
        setTesting(false);
    }

    const load = async () => {
        const r = await axios.get(auth.data.host + "/dns?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        });
        const res = r.data;
        const ns = Object.keys(res).map(s => res[s]).reduce((acc, r) => r.length > 0 ? acc.push(...r) && acc : acc, []).filter(x => x.type == "base")
        setSystems(res)
        setNameServers(ns);
        const st = await axios.get(auth.data.host + "/dns/status?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        });
        setStatus(st.data);
    }

    useEffect(() => { load(); }, [])

    // Start/stop is stored cluster-wide: every connected node starts or stops its DNS server to match.
    const toggleServer = async () => {
        setLoading(true); setDnsError(undefined);
        try {
            await axios.post(auth.data.host + "/dns/" + (status?.enabled ? "stop" : "start") + "?scaleguard=true", {},
                { headers: { Authorization: auth.data.token } });
        } catch (e: any) {
            setDnsError(e?.response?.data?.error || "Could not change the DNS server state");
        }
        await load()
        setLoading(false);
    }

    const deleteItem = async (id) => {
        setLoading(true)
        await axios.delete(auth.data.host + "/dns/" + id + "?scaleguard=true", {
            headers: { Authorization: auth.data.token }
        })
        await load()
        setLoading(false);
    }

    const save = async () => {
        setLoading(true)
        await axios.post(auth.data.host + "/dns?scaleguard=true", {
            name: name, ip: ip, type: "record", ttl: ttl
        }, { headers: { Authorization: auth.data.token } })
        setName(""); setIp(""); setTTL(600);
        await load()
        setLoading(false);
    }

    const saveNameserver = async (name, ip) => {
        setLoading(true)
        await axios.post(auth.data.host + "/dns?scaleguard=true", {
            type: "base", ttl: 600, name: name, ip: ip
        }, { headers: { Authorization: auth.data.token } })
        await load()
        setLoading(false);
    }

    // Count all DNS records
    const recordCount = systems ? Object.keys(systems).reduce((acc, key) => acc + systems[key].filter(s => s.type !== "base").length, 0) : 0;

    const inputClasses = "w-full px-4 py-2.5 rounded-xl bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-white text-sm focus:ring-2 focus:ring-blue-500/40 focus:border-blue-500 transition-all outline-none";

    return (
        <div className="p-6 sm:p-8 max-w-5xl mx-auto space-y-6">
            {/* Page Header */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <div className="p-2.5 bg-gradient-to-br from-blue-500/10 to-indigo-500/10 rounded-xl">
                        <FaGlobe className="text-blue-500 text-lg" />
                    </div>
                    <div>
                        <h2 className="text-xl font-bold dark:text-white">DNS Management</h2>
                        <p className="text-xs text-slate-400">{recordCount} record{recordCount !== 1 ? 's' : ''} · {nameServers.length} name server{nameServers.length !== 1 ? 's' : ''}</p>
                    </div>
                </div>
                {status && (
                    <div className="flex items-center gap-3">
                        <Badge colorScheme={status.running ? "green" : status.enabled ? "orange" : "gray"}>
                            {status.running ? `Running on :${status.port}` : status.enabled ? "Enabled, not running here" : "Stopped"}
                        </Badge>
                        <Button size="sm" colorScheme={status.enabled ? "red" : "green"} isLoading={loading} onClick={toggleServer}>
                            {status.enabled ? "Stop DNS server" : "Start DNS server"}
                        </Button>
                    </div>
                )}
            </div>
            {dnsError && <p className="text-sm text-red-500">{dnsError}</p>}

            {/* Name server test */}
            <div className="bg-white dark:bg-slate-800/50 rounded-2xl border border-slate-200/60 dark:border-slate-700/50 p-5 shadow-sm space-y-3">
                <div>
                    <h3 className="text-sm font-bold text-slate-800 dark:text-white">Test name server setup</h3>
                    <p className="text-[11px] text-slate-400">Checks that this server answers and that your domain is delegated to it.</p>
                </div>
                <div className="flex gap-2">
                    <input value={testDomain} onChange={(e) => setTestDomain(e.target.value)} className={inputClasses}
                        placeholder="e.g. example.com" />
                    <Button colorScheme="blue" isLoading={testing} isDisabled={!testDomain.trim()} onClick={runTest}>Test</Button>
                </div>
                {testResult && (
                    <div className="space-y-2">
                        <Badge colorScheme={testResult.ok ? "green" : "red"}>{testResult.ok ? "Configured" : "Not fully configured"}</Badge>
                        {testResult.checks.map((c: any, i: number) => (
                            <div key={i} className="flex gap-2 text-sm">
                                <span className={c.ok ? "text-green-500" : "text-red-500"}>{c.ok ? "✓" : "✗"}</span>
                                <span className="text-slate-700 dark:text-slate-300">{c.name}<span className="block text-xs text-slate-400">{c.detail}</span></span>
                            </div>
                        ))}
                    </div>
                )}
                <details open={testResult && !testResult.ok}>
                    <summary className="text-xs font-medium text-slate-500 cursor-pointer">How to configure</summary>
                    <ol className="mt-2 space-y-1 text-xs text-slate-500 list-none">
                        {(testResult?.instructions || [
                            "1. Start the DNS server here and open UDP and TCP port 53 to the internet.",
                            "2. Add a name server below (e.g. ns1.example.com); it uses this server's IPs.",
                            "3. At your registrar, create glue records for it pointing to this server's IPs.",
                            "4. At your registrar, set your domain's name servers to it.",
                            "5. Add records for the domain here, then run the test (delegation can take up to 48 hours)."
                        ]).map((t: string, i: number) => <li key={i}>{t}</li>)}
                    </ol>
                </details>
            </div>

            {/* Top Section: Name Servers + Add Record */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {/* Name Servers Panel */}
                <div className="bg-white dark:bg-slate-800/50 rounded-2xl border border-slate-200/60 dark:border-slate-700/50 p-5 shadow-sm">
                    <NameServers nameServers={nameServers} onSave={saveNameserver} onDelete={deleteItem} />
                </div>

                {/* Add DNS Record Form */}
                <div className="bg-white dark:bg-slate-800/50 rounded-2xl border border-slate-200/60 dark:border-slate-700/50 overflow-hidden shadow-sm">
                    {/* Form Header */}
                    <div className="relative overflow-hidden">
                        <div className="absolute inset-0 bg-gradient-to-br from-blue-700 to-indigo-800"></div>
                        <div className="absolute inset-0 opacity-[0.05]" style={{
                            backgroundImage: 'linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)',
                            backgroundSize: '20px 20px'
                        }}></div>
                        <div className="relative px-6 py-4">
                            <div className="flex items-center gap-3">
                                <div className="p-2 bg-white/10 rounded-xl">
                                    <FaPlusCircle className="text-white text-sm" />
                                </div>
                                <div>
                                    <h3 className="text-base font-bold text-white">Add DNS Record</h3>
                                    <p className="text-[11px] text-white/50">Configure a new DNS entry</p>
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Form Fields */}
                    <div className="p-5 space-y-4">
                        <div className="space-y-1.5">
                            <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                Domain Name <span className="text-red-400 text-xs">*</span>
                            </label>
                            <input
                                value={name}
                                onChange={(e) => setName(e.target.value.trim())}
                                className={inputClasses}
                                placeholder="e.g. api.example.com"
                            />
                            <p className="text-[10px] text-slate-400 pl-1">The DNS record hostname</p>
                        </div>

                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                    IP / Host <span className="text-red-400 text-xs">*</span>
                                </label>
                                <input
                                    value={ip}
                                    onChange={(e) => setIp(e.target.value.trim())}
                                    className={inputClasses}
                                    placeholder="e.g. 10.0.0.1"
                                />
                            </div>
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
                                    TTL (seconds)
                                </label>
                                <input
                                    value={ttl}
                                    onChange={(e) => setTTL(+e.target.value)}
                                    className={inputClasses}
                                    placeholder="e.g. 600"
                                    type="number"
                                />
                            </div>
                        </div>

                        <div className="flex justify-end pt-1">
                            <Button
                                isDisabled={!name || !ttl || !ip}
                                isLoading={loading}
                                onClick={() => save()}
                                leftIcon={<FaSave />}
                                colorScheme='blue'
                                rounded="xl"
                                size="sm"
                                px={6}
                                _hover={{ transform: "translateY(-1px)", shadow: "lg" }}
                                transition="all 0.2s"
                            >
                                Save Record
                            </Button>
                        </div>
                    </div>
                </div>
            </div>

            {/* DNS Records Table */}
            <div className="bg-white dark:bg-slate-800/50 rounded-2xl border border-slate-200/60 dark:border-slate-700/50 overflow-hidden shadow-sm">
                <div className="px-5 py-4 border-b border-slate-100 dark:border-slate-700/40">
                    <div className="flex items-center gap-2">
                        <FaNetworkWired className="text-blue-500 text-sm" />
                        <h3 className="text-sm font-bold text-slate-800 dark:text-white">DNS Records</h3>
                        <Badge colorScheme="blue" fontSize="10px" px={2} borderRadius="full" ml={1}>
                            {recordCount}
                        </Badge>
                    </div>
                </div>

                {recordCount > 0 ? (
                    <table className="min-w-full text-left text-sm">
                        <thead>
                            <tr className="bg-slate-50/80 dark:bg-slate-800/80 text-xs uppercase tracking-wider text-slate-500 dark:text-slate-400">
                                <th className="px-5 py-3 font-medium">#ID</th>
                                <th className="px-5 py-3 font-medium">Name</th>
                                <th className="px-5 py-3 font-medium">IP Address</th>
                                <th className="px-5 py-3 font-medium">Type</th>
                                <th className="px-5 py-3 font-medium">TTL</th>
                                <th className="px-5 py-3 font-medium text-right">Actions</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100 dark:divide-slate-700/40">
                            {systems && Object.keys(systems).map((key: any) => {
                                return systems[key].filter(s => s.type !== "base").map(system => (
                                    <tr key={system.id} className="text-slate-700 dark:text-slate-300 hover:bg-slate-50/50 dark:hover:bg-slate-700/20 transition-colors">
                                        <td className="px-5 py-3">
                                            <span className="text-xs font-mono text-slate-400">{system.id}</span>
                                        </td>
                                        <td className="px-5 py-3">
                                            <div className="flex items-center gap-2.5">
                                                <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-500/10 to-indigo-500/10 flex items-center justify-center flex-shrink-0">
                                                    <FaGlobe className="text-blue-500 text-xs" />
                                                </div>
                                                <span className="text-sm font-semibold text-slate-800 dark:text-white">{system.name}</span>
                                            </div>
                                        </td>
                                        <td className="px-5 py-3">
                                            <code className="text-xs font-mono text-slate-500 dark:text-slate-400 bg-slate-100 dark:bg-slate-800 px-2.5 py-1 rounded-lg">
                                                {system.ip}
                                            </code>
                                        </td>
                                        <td className="px-5 py-3">
                                            <Badge colorScheme="purple" fontSize="10px" px={2} borderRadius="full" textTransform="uppercase">
                                                {system.type}
                                            </Badge>
                                        </td>
                                        <td className="px-5 py-3">
                                            <span className="text-xs text-slate-500 font-mono">{system.ttl}s</span>
                                        </td>
                                        <td className="px-5 py-3 text-right">
                                            <DeleteSystem id={system.id} source={"dns"} onAction={() => deleteItem(system.id) as any} buttonType='big' />
                                        </td>
                                    </tr>
                                ))
                            })}
                        </tbody>
                    </table>
                ) : (
                    <div className="text-center py-12">
                        <FaGlobe className="mx-auto text-3xl text-slate-300 dark:text-slate-600 mb-3" />
                        <p className="text-sm text-slate-400">No DNS records configured</p>
                        <p className="text-xs text-slate-400/70 mt-1">Add a record using the form above</p>
                    </div>
                )}
            </div>
        </div>
    );
}