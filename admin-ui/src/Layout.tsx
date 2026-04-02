import { Link, useLocation } from "react-router-dom";
import ThemeSwitcher from "./components/Themeswitcher";
import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
    Menu,
    MenuButton,
    MenuList,
    MenuItem,
    Button,
} from '@chakra-ui/react'
import { ChevronDownIcon } from "@chakra-ui/icons";
import { useAuth } from "./contexts/AuthContext";
import { FaBolt, FaCertificate, FaGlobe, FaHome, FaLock, FaShieldAlt, FaSignOutAlt, FaUserCircle } from "react-icons/fa";
import { ConfigureSystem } from "./components/ConfigureSystem";

interface LayoutProps {
    children: React.ReactNode;
}

const navLinks = [
    { to: "/home", label: "Home", icon: FaHome },
    { to: "/asyncengines", label: "Async Engines", icon: FaBolt },
    { to: "/certificates", label: "Certificates", icon: FaCertificate },
    { to: "/dns", label: "DNS", icon: FaGlobe },
    { to: "/security", label: "App Security", icon: FaShieldAlt },
];

const Layout: React.FC<LayoutProps> = ({ children }) => {
    const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
    const { auth, setAuthData } = useAuth() as any;
    const location = useLocation();

    const logout = () => {
        localStorage.removeItem("authData");
        setAuthData({ data: null });
    };

    const isActive = (path: string) => location.pathname.startsWith(path);

    return (
        <>
            <AnimatePresence>
                <nav className="navbar flex items-center w-screen">
                    {/* Theme switcher - absolute positioned */}
                    <div className="flex justify-center items-center absolute right-0 top-0">
                        <ThemeSwitcher />
                    </div>

                    <div className="mx-auto px-4 sm:px-6 lg:px-8 w-full max-w-7xl">
                        <div className="flex items-center justify-between h-14">
                            {/* Logo Area */}
                            <div className="flex items-center">
                                <Link to="/home" className="flex items-center gap-1 group">
                                    <img
                                        className="h-9 w-9 transition-transform duration-300 group-hover:scale-110"
                                        src="/lb.svg"
                                        alt="Scaleguard"
                                    />
                                    <span className="text-white font-bold text-[15px] tracking-tight ml-0.5">
                                        Scaleguard
                                    </span>
                                </Link>
                            </div>

                            {/* Desktop Navigation */}
                            <div className="hidden md:flex items-center gap-1">
                                {navLinks.map((link) => {
                                    const active = isActive(link.to);
                                    const Icon = link.icon;
                                    return (
                                        <Link
                                            key={link.to}
                                            to={link.to}
                                            className={`
                                                relative flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[13px] font-medium
                                                transition-all duration-200 ease-out
                                                ${active
                                                    ? 'text-white bg-white/[0.08]'
                                                    : 'text-slate-400 hover:text-white hover:bg-white/[0.04]'
                                                }
                                            `}
                                        >
                                            <Icon className={`text-[10px] ${active ? 'text-teal-400' : 'text-slate-500'} transition-colors`} />
                                            {link.label}
                                            {active && (
                                                <motion.div
                                                    layoutId="nav-indicator"
                                                    className="absolute bottom-0 left-3 right-3 h-[2px] rounded-full bg-gradient-to-r from-teal-400 to-cyan-400"
                                                    transition={{ type: "spring", stiffness: 380, damping: 30 }}
                                                />
                                            )}
                                        </Link>
                                    );
                                })}
                            </div>

                            {/* Right Actions */}
                            <div className="hidden md:flex items-center gap-2">
                                <ConfigureSystem onUpdate={() => { }} auth={auth} load={false} />

                                <div className="w-px h-5 bg-slate-700/60 mx-1" />

                                <Menu>
                                    <MenuButton
                                        as={Button}
                                        rightIcon={<ChevronDownIcon />}
                                        variant="ghost"
                                        size="sm"
                                        rounded="lg"
                                        color="slate.300"
                                        fontWeight="medium"
                                        fontSize="13px"
                                        px={3}
                                        h="34px"
                                        _hover={{ bg: "whiteAlpha.100", color: "white" }}
                                        _active={{ bg: "whiteAlpha.150" }}
                                    >
                                        <div className="flex gap-2 items-center">
                                            <div className="w-6 h-6 rounded-full bg-gradient-to-br from-teal-400 to-cyan-500 flex items-center justify-center flex-shrink-0">
                                                <span className="text-[9px] font-bold text-white uppercase">
                                                    {auth.data.username?.charAt(0)}
                                                </span>
                                            </div>
                                            <span className="text-slate-300">{auth.data.username}</span>
                                        </div>
                                    </MenuButton>
                                    <MenuList
                                        bg="gray.900"
                                        borderColor="gray.700"
                                        shadow="xl"
                                        borderRadius="xl"
                                        py={2}
                                        minW="180px"
                                    >
                                        <div className="px-4 py-2 mb-1 border-b border-gray-700/60">
                                            <p className="text-xs font-semibold text-white">{auth.data.username}</p>
                                            <p className="text-[10px] text-slate-500 mt-0.5">Administrator</p>
                                        </div>
                                        <MenuItem
                                            onClick={logout}
                                            bg="transparent"
                                            _hover={{ bg: "red.900/30" }}
                                            color="red.400"
                                            fontSize="13px"
                                            icon={<FaSignOutAlt />}
                                            borderRadius="md"
                                            mx={2}
                                            px={3}
                                        >
                                            Sign Out
                                        </MenuItem>
                                    </MenuList>
                                </Menu>
                            </div>

                            {/* Mobile menu button */}
                            <div className="-mr-2 flex md:hidden">
                                <button
                                    type="button"
                                    className="inline-flex items-center justify-center p-2 rounded-lg text-slate-400 hover:text-white hover:bg-white/10 focus:outline-none transition-colors"
                                    aria-controls="mobile-menu"
                                    aria-expanded={mobileMenuOpen}
                                    onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
                                >
                                    <span className="sr-only">Open main menu</span>
                                    <svg
                                        className={`${mobileMenuOpen ? 'hidden' : 'block'} h-5 w-5`}
                                        xmlns="http://www.w3.org/2000/svg"
                                        fill="none"
                                        viewBox="0 0 24 24"
                                        stroke="currentColor"
                                    >
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 6h16M4 12h16M4 18h16" />
                                    </svg>
                                    <svg
                                        className={`${mobileMenuOpen ? 'block' : 'hidden'} h-5 w-5`}
                                        xmlns="http://www.w3.org/2000/svg"
                                        fill="none"
                                        viewBox="0 0 24 24"
                                        stroke="currentColor"
                                    >
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12" />
                                    </svg>
                                </button>
                            </div>
                        </div>
                    </div>

                    {/* Mobile menu */}
                    {mobileMenuOpen && (
                        <motion.div
                            initial={{ opacity: 0, y: -10 }}
                            animate={{ opacity: 1, y: 0 }}
                            exit={{ opacity: 0, y: -10 }}
                            className="md:hidden absolute top-14 left-0 right-0 bg-slate-900/95 backdrop-blur-xl border-b border-slate-700/40"
                            id="mobile-menu"
                        >
                            <div className="px-4 py-3 space-y-1">
                                {navLinks.map((link) => {
                                    const active = isActive(link.to);
                                    const Icon = link.icon;
                                    return (
                                        <Link
                                            key={link.to}
                                            to={link.to}
                                            className={`
                                                flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all
                                                ${active
                                                    ? 'text-white bg-white/[0.08]'
                                                    : 'text-slate-400 hover:text-white hover:bg-white/[0.04]'
                                                }
                                            `}
                                            onClick={() => setMobileMenuOpen(false)}
                                        >
                                            <Icon className={`text-xs ${active ? 'text-teal-400' : 'text-slate-500'}`} />
                                            {link.label}
                                        </Link>
                                    );
                                })}
                                <div className="pt-2 mt-2 border-t border-slate-700/40">
                                    <button
                                        onClick={() => { logout(); setMobileMenuOpen(false); }}
                                        className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium text-red-400 hover:bg-red-900/20 transition-all w-full"
                                    >
                                        <FaSignOutAlt className="text-xs" />
                                        Sign Out
                                    </button>
                                </div>
                            </div>
                        </motion.div>
                    )}
                </nav>

                <motion.div
                    initial={{ opacity: 0, y: 40 }}
                    animate={{ opacity: 1, y: 0, paddingTop: "72px", paddingBottom: "60px" }}
                    transition={{
                        delay: 0.15,
                        duration: 0.95,
                        ease: [0.165, 0.84, 0.44, 1],
                    }}
                    className="md:px-[100px] dark:bg-slate-800"
                >
                    {children}
                </motion.div>
            </AnimatePresence>
        </>
    );
};

export default Layout;