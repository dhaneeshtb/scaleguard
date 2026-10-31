import { Link, useLocation, useNavigate } from "react-router-dom";
import { useCallback, useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Menu, MenuButton, MenuList, MenuItem, Tooltip, useColorMode } from '@chakra-ui/react';
import axios from "axios";
import {
    FiActivity, FiBarChart2, FiHeart, FiChevronsLeft, FiChevronsRight, FiCommand, FiGlobe, FiLock, FiLogOut,
    FiMenu, FiMoon, FiSearch, FiShield, FiSun, FiX, FiZap, FiChevronDown, FiSliders, FiKey, FiServer,
} from "react-icons/fi";
import type { IconType } from "react-icons";
import { useAuth } from "./contexts/AuthContext";
import useThemeContext from "./contexts/Themeprovider";
import useSystemContext from "./contexts/SystemContext";
import { ConfigureSystem } from "./components/ConfigureSystem";
import CommandPalette, { Command } from "./components/CommandPalette";

interface LayoutProps {
    children: React.ReactNode;
}

interface NavLink { to: string; label: string; icon: IconType; match?: string[] }

const navSections: { title: string; links: NavLink[] }[] = [
    {
        title: "Traffic",
        links: [
            { to: "/home", label: "Overview", icon: FiActivity, match: ["/home", "/managehost", "/hostgroups"] },
            { to: "/analytics", label: "Analytics", icon: FiBarChart2 },
            { to: "/backends", label: "Backend health", icon: FiHeart },
            { to: "/asyncengines", label: "Async Engines", icon: FiZap },
        ],
    },
    {
        title: "Protect",
        links: [
            { to: "/firewall", label: "Firewall (WAF)", icon: FiShield },
            { to: "/ratelimit", label: "Rate limiting", icon: FiSliders },
            { to: "/security", label: "App Security", icon: FiKey },
        ],
    },
    {
        title: "Edge",
        links: [
            { to: "/certificates", label: "Certificates", icon: FiLock },
            { to: "/dns", label: "DNS", icon: FiGlobe },
        ],
    },
    {
        title: "Platform",
        links: [
            { to: "/functions", label: "Cloud functions", icon: FiZap },
            { to: "/cluster", label: "Cluster", icon: FiServer },
        ],
    },
];

const allLinks = navSections.flatMap(s => s.links);

type PlaneStatus = { state: "checking" | "online" | "offline"; latency?: number };

/** Measures round-trip time to the control plane so the shell always shows real connectivity. */
function useControlPlane(auth: any): PlaneStatus {
    const [status, setStatus] = useState<PlaneStatus>({ state: "checking" });
    useEffect(() => {
        if (!auth?.data) return;
        let cancelled = false;
        const ping = async () => {
            const t0 = performance.now();
            try {
                await axios.get(auth.data.host + "/systems", { headers: { Authorization: auth.data.token }, timeout: 8000 });
                if (!cancelled) setStatus({ state: "online", latency: Math.round(performance.now() - t0) });
            } catch {
                if (!cancelled) setStatus({ state: "offline" });
            }
        };
        ping();
        const id = setInterval(ping, 30000);
        return () => { cancelled = true; clearInterval(id); };
    }, [auth?.data]);
    return status;
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
    const [mobileOpen, setMobileOpen] = useState(false);
    const [paletteOpen, setPaletteOpen] = useState(false);
    const [collapsed, setCollapsed] = useState<boolean>(() => {
        try { return localStorage.getItem("sg.sidebar") === "collapsed"; } catch { return false; }
    });
    const { auth, setAuthData } = useAuth() as any;
    const { theme, setTheme } = useThemeContext();
    const { properties } = useSystemContext() as any;
    const { setColorMode } = useColorMode();
    const location = useLocation();
    const navigate = useNavigate();
    const plane = useControlPlane(auth);

    // Keep Chakra components in the same mode as the Tailwind theme.
    useEffect(() => { setColorMode(theme === "light" ? "light" : "dark"); }, [theme, setColorMode]);

    useEffect(() => {
        try { localStorage.setItem("sg.sidebar", collapsed ? "collapsed" : "expanded"); } catch { /* ignore */ }
    }, [collapsed]);

    useEffect(() => { setMobileOpen(false); }, [location.pathname]);

    useEffect(() => {
        const onKey = (e: KeyboardEvent) => {
            if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
                e.preventDefault();
                setPaletteOpen(o => !o);
            }
        };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, []);

    const logout = useCallback(() => {
        localStorage.removeItem("authData");
        setAuthData({ data: null });
    }, [setAuthData]);

    const toggleTheme = useCallback(() => setTheme(theme === "dark" ? "light" : "dark"), [theme, setTheme]);

    const isActive = (link: NavLink) =>
        (link.match || [link.to]).some(p => location.pathname.startsWith(p));

    const current = allLinks.find(isActive);
    const hostname = properties?.hostName?.value || "ScaleGuard";
    const username: string = auth?.data?.username || "admin";

    const commands: Command[] = useMemo(() => [
        ...allLinks.map(l => ({ id: l.to, label: l.label, group: "Navigate", icon: l.icon, hint: l.to, run: () => navigate(l.to) })),
        { id: "theme", label: theme === "dark" ? "Switch to light theme" : "Switch to dark theme", group: "Actions", icon: theme === "dark" ? FiSun : FiMoon, run: toggleTheme },
        { id: "collapse", label: collapsed ? "Expand sidebar" : "Collapse sidebar", group: "Actions", icon: collapsed ? FiChevronsRight : FiChevronsLeft, run: () => setCollapsed(c => !c) },
        { id: "logout", label: "Sign out", group: "Account", icon: FiLogOut, run: logout },
    ], [navigate, theme, toggleTheme, collapsed, logout]);

    const sidebarWidth = collapsed ? "md:w-[76px]" : "md:w-[248px]";
    const contentPad = collapsed ? "md:pl-[76px]" : "md:pl-[248px]";

    const renderSidebar = (compact: boolean) => (
        <div className="flex h-full flex-col">
            {/* Brand */}
            <div className={`flex h-16 items-center ${compact ? "justify-center" : "gap-2.5 px-5"}`}>
                <Link to="/home" className="group flex items-center gap-2.5">
                    <span className="relative flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-teal-400/20 to-violet-500/20 ring-1 ring-inset ring-white/10">
                        <img src={`${process.env.PUBLIC_URL}/lb.svg`} alt="" className="h-6 w-6 transition-transform duration-500 group-hover:rotate-[20deg]" />
                    </span>
                    {!compact && (
                        <span className="leading-none">
                            <span className="block text-[15px] font-bold tracking-tight text-slate-900 dark:text-white">ScaleGuard</span>
                            <span className="mt-1 block text-[10px] font-medium uppercase tracking-[0.18em] text-teal-600 dark:text-teal-300/80">Control plane</span>
                        </span>
                    )}
                </Link>
            </div>

            {/* Search trigger */}
            <div className={compact ? "px-3" : "px-4"}>
                <button
                    onClick={() => setPaletteOpen(true)}
                    className={`group flex w-full items-center gap-2.5 rounded-xl border border-slate-200 bg-white/60 text-slate-500 transition-colors hover:border-teal-400/40 hover:text-slate-700 dark:border-white/[0.07] dark:bg-white/[0.03] dark:text-slate-400 dark:hover:text-slate-200 ${compact ? "h-10 justify-center" : "h-10 px-3"}`}
                    aria-label="Open command palette"
                >
                    <FiSearch className="text-[14px]" />
                    {!compact && <>
                        <span className="flex-1 text-left text-[13px]">Search…</span>
                        <span className="flex items-center gap-0.5"><span className="sg-kbd"><FiCommand className="text-[9px]" /></span><span className="sg-kbd">K</span></span>
                    </>}
                </button>
            </div>

            {/* Nav */}
            <nav className="mt-4 flex-1 space-y-6 overflow-y-auto px-3 pb-4">
                {navSections.map(section => (
                    <div key={section.title}>
                        {!compact
                            ? <p className="sg-eyebrow mb-2 px-3">{section.title}</p>
                            : <div className="mx-auto mb-2 h-px w-6 bg-slate-200 dark:bg-white/10" />}
                        <div className="space-y-0.5">
                            {section.links.map(link => {
                                const active = isActive(link);
                                const Icon = link.icon;
                                const item = (
                                    <Link
                                        key={link.to}
                                        to={link.to}
                                        className={`group relative flex items-center rounded-xl text-[13.5px] font-medium transition-colors ${compact ? "h-10 justify-center" : "h-10 gap-3 px-3"} ${active
                                            ? "text-slate-900 dark:text-white"
                                            : "text-slate-500 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-white/[0.04] dark:hover:text-white"}`}
                                    >
                                        {active && (
                                            <motion.span
                                                layoutId="sg-nav-active"
                                                className="absolute inset-0 rounded-xl border border-teal-500/20 bg-gradient-to-r from-teal-400/[0.14] via-cyan-400/[0.06] to-transparent dark:border-teal-300/15"
                                                transition={{ type: "spring", stiffness: 420, damping: 34 }}
                                            />
                                        )}
                                        {active && <span className="absolute -left-3 top-2.5 h-5 w-[3px] rounded-r-full bg-gradient-to-b from-teal-300 to-cyan-400" />}
                                        <Icon className={`relative text-[16px] ${active ? "text-teal-600 dark:text-teal-300" : ""}`} />
                                        {!compact && <span className="relative">{link.label}</span>}
                                    </Link>
                                );
                                return compact
                                    ? <Tooltip key={link.to} label={link.label} placement="right" hasArrow>{item}</Tooltip>
                                    : item;
                            })}
                        </div>
                    </div>
                ))}
            </nav>

            {/* Control plane status */}
            <div className={compact ? "px-3 pb-3" : "px-4 pb-4"}>
                {compact ? (
                    <Tooltip label={plane.state === "online" ? `Control plane · ${plane.latency} ms` : plane.state === "offline" ? "Control plane unreachable" : "Checking control plane…"} placement="right" hasArrow>
                        <div className="flex h-10 items-center justify-center"><StatusDot state={plane.state} /></div>
                    </Tooltip>
                ) : (
                    <div className="sg-card overflow-hidden p-3.5">
                        <div className="flex items-center justify-between">
                            <span className="sg-eyebrow">Control plane</span>
                            <StatusDot state={plane.state} />
                        </div>
                        <p className="mt-2 truncate text-[13px] font-semibold text-slate-800 dark:text-slate-100" title={hostname}>{hostname}</p>
                        <div className="mt-1 flex items-center justify-between text-[11px] text-slate-500 dark:text-slate-400">
                            <span className="truncate font-mono" title={auth?.data?.host}>{(auth?.data?.host || "").replace(/^https?:\/\//, "")}</span>
                            <span className="ml-2 shrink-0 font-mono">
                                {plane.state === "online" ? `${plane.latency} ms` : plane.state === "offline" ? "offline" : "…"}
                            </span>
                        </div>
                    </div>
                )}
                <button
                    onClick={() => setCollapsed(c => !c)}
                    className={`mt-2 hidden h-9 w-full items-center rounded-xl text-[12px] font-medium text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-white/[0.04] dark:hover:text-slate-200 md:flex ${compact ? "justify-center" : "gap-2 px-3"}`}
                    aria-label={compact ? "Expand sidebar" : "Collapse sidebar"}
                >
                    {compact ? <FiChevronsRight /> : <><FiChevronsLeft /> Collapse</>}
                </button>
            </div>
        </div>
    );

    return (
        <div className="relative min-h-screen" style={{ ["--sg-sidebar" as any]: collapsed ? "76px" : "248px" }}>
            <div className="sg-backdrop" aria-hidden />

            {/* Desktop sidebar */}
            <aside className={`fixed inset-y-0 left-0 z-40 hidden border-r border-slate-200/80 bg-white/70 backdrop-blur-2xl transition-[width] duration-300 ease-out dark:border-white/[0.06] dark:bg-[#0f0f0f]/95 md:block ${sidebarWidth}`}>
                {renderSidebar(collapsed)}
            </aside>

            {/* Mobile drawer */}
            <AnimatePresence>
                {mobileOpen && (
                    <>
                        <motion.div className="fixed inset-0 z-40 bg-slate-950/60 backdrop-blur-sm md:hidden"
                            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setMobileOpen(false)} />
                        <motion.aside className="fixed inset-y-0 left-0 z-50 w-[272px] border-r border-white/[0.06] bg-white dark:bg-[#111111] md:hidden"
                            initial={{ x: -280 }} animate={{ x: 0 }} exit={{ x: -280 }} transition={{ type: "spring", stiffness: 380, damping: 36 }}>
                            <button onClick={() => setMobileOpen(false)} className="absolute right-3 top-4 rounded-lg p-2 text-slate-400 hover:bg-white/5" aria-label="Close menu"><FiX /></button>
                            {renderSidebar(false)}
                        </motion.aside>
                    </>
                )}
            </AnimatePresence>

            <div className={`relative z-10 transition-[padding] duration-300 ease-out ${contentPad}`}>
                {/* Top bar */}
                <header className="sticky top-0 z-30 border-b border-slate-200/70 bg-white/60 dark:bg-[#0f0f0f]/80 backdrop-blur-xl dark:border-white/[0.05]">
                    <div className="mx-auto flex h-16 max-w-[1600px] items-center gap-3 px-4 sm:px-6 lg:px-10">
                        <button className="-ml-1 rounded-lg p-2 text-slate-500 hover:bg-slate-100 dark:hover:bg-white/5 md:hidden" onClick={() => setMobileOpen(true)} aria-label="Open menu">
                            <FiMenu className="text-lg" />
                        </button>

                        <div className="flex min-w-0 items-center gap-2 text-[13px]">
                            <span className="hidden text-slate-400 sm:inline">{hostname}</span>
                            <span className="hidden text-slate-300 dark:text-slate-600 sm:inline">/</span>
                            <span className="truncate font-semibold text-slate-900 dark:text-white">{current?.label || "Overview"}</span>
                        </div>

                        <div className="ml-auto flex items-center gap-1.5 sm:gap-2">
                            <button onClick={() => setPaletteOpen(true)} className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-white/5 md:hidden" aria-label="Search">
                                <FiSearch />
                            </button>

                            <div className="hidden sm:block">
                                <ConfigureSystem onUpdate={() => { }} auth={auth} load={false} />
                            </div>

                            <Tooltip label={theme === "dark" ? "Light theme" : "Dark theme"} hasArrow>
                                <button
                                    onClick={toggleTheme}
                                    className="relative flex h-9 w-9 items-center justify-center overflow-hidden rounded-xl border border-slate-200 bg-white/60 text-slate-500 transition-colors hover:text-slate-900 dark:border-white/[0.07] dark:bg-white/[0.03] dark:text-slate-400 dark:hover:text-white"
                                    aria-label="Toggle theme"
                                >
                                    <AnimatePresence mode="wait" initial={false}>
                                        <motion.span key={theme} initial={{ y: 14, opacity: 0, rotate: -45 }} animate={{ y: 0, opacity: 1, rotate: 0 }} exit={{ y: -14, opacity: 0, rotate: 45 }} transition={{ duration: 0.2 }}>
                                            {theme === "dark" ? <FiMoon /> : <FiSun />}
                                        </motion.span>
                                    </AnimatePresence>
                                </button>
                            </Tooltip>

                            <Menu placement="bottom-end">
                                <MenuButton className="rounded-xl border border-transparent py-1 pl-1 pr-2 transition-colors hover:border-slate-200 hover:bg-white/60 dark:hover:border-white/[0.07] dark:hover:bg-white/[0.03]">
                                    <span className="flex items-center gap-2">
                                        <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-teal-400 to-violet-500 text-[11px] font-bold uppercase text-white shadow-glow">
                                            {username.charAt(0)}
                                        </span>
                                        <span className="hidden text-[13px] font-medium text-slate-700 dark:text-slate-200 sm:inline">{username}</span>
                                        <FiChevronDown className="hidden text-xs text-slate-400 sm:inline" />
                                    </span>
                                </MenuButton>
                                <MenuList minW="220px" zIndex={60}>
                                    <div className="mb-1 border-b border-slate-200 px-3 pb-2.5 pt-1.5 dark:border-white/[0.07]">
                                        <p className="text-[13px] font-semibold text-slate-900 dark:text-white">{username}</p>
                                        <p className="text-[11px] text-slate-500">Administrator</p>
                                    </div>
                                    <MenuItem icon={<FiSearch />} fontSize="13px" onClick={() => setPaletteOpen(true)} command="⌘K">Command palette</MenuItem>
                                    <MenuItem icon={<FiLogOut />} fontSize="13px" color="red.400" onClick={logout}>Sign out</MenuItem>
                                </MenuList>
                            </Menu>
                        </div>
                    </div>
                </header>

                <motion.main
                    key={location.pathname}
                    initial={{ opacity: 0, y: 12 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ duration: 0.45, ease: [0.165, 0.84, 0.44, 1] }}
                    className="mx-auto max-w-[1600px] px-4 pb-16 pt-6 sm:px-6 lg:px-10"
                >
                    {children}
                </motion.main>
            </div>

            <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} commands={commands} />
        </div>
    );
};

function StatusDot({ state }: { state: PlaneStatus["state"] }) {
    const color = state === "online" ? "bg-emerald-400" : state === "offline" ? "bg-rose-500" : "bg-amber-400";
    return (
        <span className="relative flex h-2.5 w-2.5">
            {state === "online" && <span className={`absolute inline-flex h-full w-full rounded-full ${color} animate-pulse-ring`} />}
            <span className={`relative inline-flex h-2.5 w-2.5 rounded-full ${color}`} />
        </span>
    );
}

export default Layout;
