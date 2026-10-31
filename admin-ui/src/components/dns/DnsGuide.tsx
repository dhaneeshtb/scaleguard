import React, { useEffect, useState } from "react";
import { FiCheck, FiCopy } from "react-icons/fi";
import { useAuth } from "../../contexts/AuthContext";
import { Card } from "../ui/controls";
import { dnsCall } from "./dnsApi";

function Cmd({ children }: { children: string }) {
    const [copied, setCopied] = useState(false);
    const copy = async () => {
        try { await navigator.clipboard.writeText(children); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch { /* clipboard blocked: the text is still selectable */ }
    };
    return (
        <div className="group relative mt-2 rounded-lg bg-slate-900/90 p-3 pr-10 font-mono text-[12px] leading-relaxed text-slate-100">
            <pre className="overflow-x-auto whitespace-pre-wrap break-all">{children}</pre>
            <button type="button" onClick={copy} aria-label="Copy command" className="absolute right-2 top-2 rounded p-1.5 text-slate-400 hover:bg-white/10 hover:text-white">{copied ? <FiCheck /> : <FiCopy />}</button>
        </div>
    );
}

function Step({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
    return (
        <li className="flex gap-3">
            <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-teal-400/15 text-[12px] font-bold text-teal-700 dark:text-teal-300">{n}</span>
            <div className="min-w-0 flex-1 text-[13px] leading-relaxed text-slate-600 dark:text-slate-300">
                <p className="text-[13.5px] font-semibold text-slate-900 dark:text-white">{title}</p>
                {children}
            </div>
        </li>
    );
}

const Em = ({ children }: { children: React.ReactNode }) => <code className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[12px] text-slate-800 dark:bg-white/10 dark:text-slate-100">{children}</code>;

const TROUBLE: [string, string][] = [
    ["REFUSED from your server", "The name isn't in a domain this server serves and recursion isn't allowed for your address. Declare the domain under Domains served (Server settings)."],
    ["\"No answer\" (NODATA)", "The domain exists but has no record of that type. A declared domain answers A/AAAA with your nodes automatically; for other types (TXT, MX…) add a record."],
    ["NXDOMAIN for the name server (ns1…)", "The A record for the name server is missing at the DNS host of the parent domain, or you're asking before delegation exists. Add the A record, wait, retry."],
    ["Works against your IP, fails via 8.8.8.8", "Delegation isn't in place or is cached. Check the NS record at the DNS host, then wait for the TTL (up to 48 hours worst case)."],
    ["Timeout from your IP", "The DNS server isn't running, or UDP/TCP port 53 is blocked by a firewall or cloud security group."],
    ["Start/Add record is greyed out", "The server must be started and at least one name server set (Server settings) before records or domains can be added."],
    ["Works on one node only", "Check the Nodes tab: every node should show the DNS server Running. Settings and records replicate in a few seconds."],
];

export default function DnsGuide({ status, domains, onOpenSettings }: { status: any; domains: any[]; onOpenSettings: () => void }) {
    const { auth } = useAuth() as any;
    const [ips, setIps] = useState<string[]>([]);
    const [nameServer, setNameServer] = useState("");

    useEffect(() => {
        dnsCall(auth, "get", "/dns/settings").then(r => {
            setIps(r.data.detectedIps || []);
            setNameServer(String(r.data.settings?.dnsNameServers || "").split(",")[0].trim());
        }).catch(() => { /* guide still works with the placeholders */ });
    }, [auth]);

    const domain = domains.length ? String(domains[0].name).replace(/\.$/, "") : "app.example.com";
    const ip = ips[0] || "<server-ip>";
    const port = status?.port || 53;
    const parts = domain.split(".");
    const sub = parts.length > 2;
    const label = parts[0];
    const parent = sub ? parts.slice(1).join(".") : domain;
    const ns = nameServer.replace(/\.$/, "") || `ns1.${parent}`;

    return (
        <div className="space-y-5 pb-8">
            <Card title="Set up ScaleGuard as your DNS server" description="Follow these steps in order. Names, IPs and the port below come from your configuration; examples are shown where nothing is set yet.">
                <ol className="space-y-5">
                    <Step n={1} title="Prepare the host">
                        <p>You need a server with a <b>public IP</b>, <b>UDP and TCP port {port}</b> open to the internet (firewall and cloud security group, on every node), and a domain you control.</p>
                    </Step>
                    <Step n={2} title="Start the DNS server">
                        <p>Use <Em>Start DNS server</Em> at the top of this page. Start, stop and all settings replicate to every connected node.</p>
                    </Step>
                    <Step n={3} title="Add a name server">
                        <p>In <button type="button" onClick={onOpenSettings} className="font-semibold text-teal-600 hover:underline dark:text-teal-300">Server settings</button>, set <b>Name servers</b> to a host name inside your domain, for example <Em>{ns}</Em>. It resolves to this server's public IP{ips.length > 1 ? "s" : ""}: <Em>{ips.length ? ips.join(", ") : "<server-ip>"}</Em>. Add a second one on a second node for redundancy.</p>
                    </Step>
                    <Step n={4} title="Declare the domain you want to serve">
                        <p>Under <b>Domains served</b>, add <Em>{domain}</Em>. By default the domain and every name inside it answer with the public IPs of all connected nodes, load-balanced. To point a name somewhere else, add a record for it in the Records tab. A record you add replaces the default for that name.</p>
                    </Step>
                    <Step n={5} title="Check that the server answers">
                        <p>From any machine, ask your server directly (this works before delegation):</p>
                        <Cmd>{`nslookup ${domain} ${ip}`}</Cmd>
                        <p className="mt-2">You should get an address back. <Em>REFUSED</Em> means the domain isn't declared; a timeout means port {port} is blocked.</p>
                    </Step>
                    <Step n={6} title="Delegate the domain at your DNS host or registrar">
                        <p className="font-semibold text-slate-800 dark:text-slate-100">Option A: a subdomain such as {domain}</p>
                        <p>Only the subdomain moves here; the rest of <Em>{parent}</Em> stays where it is. At the DNS host of <Em>{parent}</Em> (Namecheap: Domain List, Manage, Advanced DNS) add two records:</p>
                        <div className="mt-2 overflow-x-auto"><table className="min-w-full text-left text-[12.5px]">
                            <thead><tr className="text-[11px] uppercase tracking-wider text-slate-400"><th className="py-1 pr-4 font-medium">Type</th><th className="py-1 pr-4 font-medium">Host</th><th className="py-1 font-medium">Value</th></tr></thead>
                            <tbody className="font-mono">
                                <tr><td className="py-1 pr-4">A</td><td className="py-1 pr-4">{ns.endsWith("." + parent) ? ns.slice(0, -(parent.length + 1)) : ns}</td><td className="py-1">{ip}</td></tr>
                                <tr><td className="py-1 pr-4">NS</td><td className="py-1 pr-4">{sub ? label : "(not applicable)"}</td><td className="py-1">{ns}.</td></tr>
                            </tbody></table></div>
                        <p className="mt-2">The A record lets resolvers find your name server; the NS record hands the subdomain to it. Skip the A record only if the name server's host name already resolves elsewhere.</p>
                        <p className="mt-3 font-semibold text-slate-800 dark:text-slate-100">Option B: a whole registered domain such as {parent}</p>
                        <p>Add all its records in the Records tab first, because the old DNS host stops being used. Then at the registrar: register <Em>{ns}</Em> as a <b>personal / glue name server</b> with IP <Em>{ip}</Em> (Namecheap: Advanced DNS, Personal DNS Server), and set the domain's nameservers to <b>Custom DNS</b> with <Em>{ns}</Em>. Registries often want two name servers.</p>
                    </Step>
                    <Step n={7} title="Verify from the public internet">
                        <p>Delegation can take minutes to hours (up to 48 hours worst case) to spread.</p>
                        <Cmd>{`nslookup -type=NS ${domain} 8.8.8.8`}</Cmd>
                        <Cmd>{`nslookup ${domain} 8.8.8.8`}</Cmd>
                        <p className="mt-2">The first should list <Em>{ns}</Em>; the second should return your address. Then run <b>Test everything</b> in Server settings: it checks the server, name servers, public IPs, forwarders, every cluster node and the delegation of each declared domain.</p>
                    </Step>
                    <Step n={8} title="Running a cluster">
                        <p>Nodes that join are added to the answers within about 5 seconds, and a node whose DNS server stops is removed. Use a low TTL (60 to 300 seconds) on records you want to fail over quickly. The Nodes tab shows each node's DNS state.</p>
                    </Step>
                </ol>
            </Card>

            <Card title="Troubleshooting" description="What the common results mean.">
                <dl className="divide-y divide-slate-100 dark:divide-white/5">
                    {TROUBLE.map(([sym, fix]) => (
                        <div key={sym} className="grid gap-1 py-3 sm:grid-cols-3 sm:gap-4">
                            <dt className="text-[13px] font-semibold text-slate-800 dark:text-slate-100">{sym}</dt>
                            <dd className="text-[13px] leading-relaxed text-slate-600 dark:text-slate-300 sm:col-span-2">{fix}</dd>
                        </div>
                    ))}
                </dl>
            </Card>
        </div>
    );
}
