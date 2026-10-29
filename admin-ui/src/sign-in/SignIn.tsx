import React, { useEffect, useState } from 'react';
import Typewriter from 'typewriter-effect';
import axios from 'axios';
import { useAuth } from '../contexts/AuthContext';
import { useNavigate } from 'react-router-dom';
import { motion } from "framer-motion";
import { FiAlertCircle, FiArrowRight, FiEye, FiEyeOff, FiGlobe, FiLock, FiUser } from 'react-icons/fi';
import TrafficFlow from '../components/TrafficFlow';

const demoSources = [
  { name: 'api.acme.io', host: 'api.acme.io', port: 443 },
  { name: 'app.acme.io', host: 'app.acme.io', port: 443 },
  { name: 'ws.acme.io', host: 'ws.acme.io', port: 443 },
];
const demoTargets = [
  { name: 'pool-east-1', host: '10.0.1.12', port: 8080 },
  { name: 'pool-east-2', host: '10.0.1.13', port: 8080 },
  { name: 'pool-west-1', host: '10.0.2.20', port: 8080 },
  { name: 'events-kafka', host: '10.0.3.5', port: 9092 },
];

export default function SignIn() {
  const [isLoading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const navigate = useNavigate();
  const [host, setHost] = useState<string | null>("");
  const { auth, setAuthData } = useAuth() as any;

  useEffect(() => {
    if (!auth.loading && auth.data) {
      navigate("/home");
    }
  }, [auth, navigate]);

  const signIn = async (host, username, password): Promise<any> => {
    try {
      return await axios.post(
        host + "/signin?scaleguard=true",
        { username, password },
        { headers: { 'Access-Control-Allow-Origin': '*' } }
      );
    } catch (e: any) {
      return e.response;
    }
  };

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>): Promise<void> => {
    e.preventDefault();
    setLoading(true);
    setError("");
    const _target = e.target as any;
    const username = _target.username.value;
    const password = _target.password.value;
    const hostURL = _target.hostURL.value;
    localStorage.setItem("host", hostURL);
    const result = await signIn(hostURL, username, password);
    setLoading(false);

    if (!result || result.status !== 200) {
      setError(result ? "Invalid username or password." : "Couldn't reach that host. Check the URL and try again.");
    } else {
      const authData = { ...result.data, host: hostURL, username: username };
      window.localStorage.setItem('authData', JSON.stringify(authData));
      setAuthData({ loading: false, data: authData });
      navigate("/home");
    }
  };

  useEffect(() => {
    setHost(localStorage.getItem("host"));
  }, []);

  const field = "peer w-full rounded-xl border border-slate-200 bg-white/70 py-3 pl-10 pr-4 text-[14px] text-slate-900 outline-none transition-all placeholder:text-slate-400 focus:border-teal-400 focus:ring-4 focus:ring-teal-400/15 dark:border-white/10 dark:bg-white/[0.03] dark:text-white";

  return (
    <main className="relative grid min-h-screen lg:grid-cols-[1.15fr_1fr]">
      <div className="sg-backdrop" aria-hidden />

      {/* Showcase */}
      <section className="relative hidden overflow-hidden border-r border-white/[0.06] bg-slate-950 lg:flex lg:flex-col lg:justify-between lg:p-12">
        <div className="pointer-events-none absolute -left-40 top-20 h-[28rem] w-[28rem] rounded-full bg-teal-500/20 blur-[120px]" />
        <div className="pointer-events-none absolute -bottom-40 right-0 h-[30rem] w-[30rem] rounded-full bg-violet-600/20 blur-[120px]" />
        <div className="pointer-events-none absolute inset-0 opacity-[0.07]" style={{ backgroundImage: 'linear-gradient(rgba(148,163,214,.5) 1px, transparent 1px), linear-gradient(90deg, rgba(148,163,214,.5) 1px, transparent 1px)', backgroundSize: '44px 44px', maskImage: 'radial-gradient(ellipse at center, #000 30%, transparent 75%)', WebkitMaskImage: 'radial-gradient(ellipse at center, #000 30%, transparent 75%)' }} />

        <div className="relative flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-teal-400/20 to-violet-500/20 ring-1 ring-inset ring-white/10">
            <img src="/lb.svg" alt="" className="h-7 w-7" />
          </span>
          <span className="text-lg font-bold tracking-tight text-white">ScaleGuard</span>
        </div>

        <div className="relative">
          <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-teal-300/80">Intelligent traffic control</p>
          <h1 className="mt-4 max-w-xl text-[44px] font-bold leading-[1.05] tracking-tight text-white">
            Route, balance and secure<br />
            <span className="sg-gradient-text">
              <Typewriter
                options={{
                  strings: ["every request.", "every upstream.", "every certificate.", "every DNS record."],
                  autoStart: true, loop: true, deleteSpeed: 30, delay: 55,
                  wrapperClassName: 'sg-gradient-text', cursorClassName: 'text-teal-300 Typewriter__cursor',
                }}
              />
            </span>
          </h1>
          <div className="mt-10 h-[300px] max-w-2xl rounded-2xl border border-white/[0.06] bg-white/[0.02] p-4 backdrop-blur-sm">
            <div className="dark h-full"><TrafficFlow sources={demoSources} targets={demoTargets} /></div>
          </div>
        </div>

        <div className="relative flex items-center gap-6 text-[12px] text-slate-400">
          <span className="flex items-center gap-2"><span className="h-1.5 w-1.5 rounded-full bg-teal-400" /> Auto TLS</span>
          <span className="flex items-center gap-2"><span className="h-1.5 w-1.5 rounded-full bg-cyan-400" /> Host-group balancing</span>
          <span className="flex items-center gap-2"><span className="h-1.5 w-1.5 rounded-full bg-violet-400" /> Async engines</span>
        </div>
      </section>

      {/* Form */}
      <section className="relative flex items-center justify-center p-6 sm:p-10">
        <motion.div
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, ease: [0.165, 0.84, 0.44, 1] }}
          className="w-full max-w-[400px]"
        >
          <div className="mb-8 flex items-center gap-3 lg:hidden">
            <img src="/lb.svg" className="h-9 w-9" alt="" />
            <span className="text-lg font-bold tracking-tight text-slate-900 dark:text-white">ScaleGuard</span>
          </div>

          <h2 className="text-[28px] font-bold tracking-tight text-slate-900 dark:text-white">Welcome back</h2>
          <p className="mt-1.5 text-[14px] text-slate-500 dark:text-slate-400">Sign in to your ScaleGuard control plane.</p>

          {error && (
            <motion.div initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }}
              className="mt-6 flex items-start gap-2.5 rounded-xl border border-rose-500/25 bg-rose-500/10 px-3.5 py-3 text-[13px] text-rose-600 dark:text-rose-300">
              <FiAlertCircle className="mt-0.5 shrink-0" /> {error}
            </motion.div>
          )}

          <form className="mt-7 space-y-4" onSubmit={handleSubmit}>
            <label className="block">
              <span className="mb-1.5 block text-[12.5px] font-medium text-slate-600 dark:text-slate-300">Admin username</span>
              <span className="relative block">
                <FiUser className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400" />
                <input id="username" name="username" type="text" autoComplete="username" required placeholder="admin" className={field} />
              </span>
            </label>
            <label className="block">
              <span className="mb-1.5 block text-[12.5px] font-medium text-slate-600 dark:text-slate-300">Password</span>
              <span className="relative block">
                <FiLock className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400" />
                <input id="password" name="password" type={showPassword ? "text" : "password"} autoComplete="current-password" required placeholder="••••••••" className={field + " pr-11"} />
                <button type="button" onClick={() => setShowPassword(s => !s)} className="absolute right-2 top-1/2 -translate-y-1/2 rounded-lg p-2 text-slate-400 hover:text-slate-700 dark:hover:text-slate-200" aria-label={showPassword ? "Hide password" : "Show password"}>
                  {showPassword ? <FiEyeOff /> : <FiEye />}
                </button>
              </span>
            </label>
            <label className="block">
              <span className="mb-1.5 block text-[12.5px] font-medium text-slate-600 dark:text-slate-300">Control plane URL</span>
              <span className="relative block">
                <FiGlobe className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400" />
                <input key={host || "empty"} defaultValue={host || ""} id="hostURL" name="hostURL" type="text" required placeholder="https://lb.example.com:8443" className={field + " font-mono text-[13px]"} />
              </span>
            </label>

            <button
              type="submit"
              disabled={isLoading}
              className="group relative mt-2 flex h-12 w-full items-center justify-center gap-2 overflow-hidden rounded-xl bg-gradient-to-r from-teal-400 via-cyan-400 to-violet-500 text-[14px] font-semibold text-slate-950 shadow-glow transition-all hover:brightness-110 disabled:opacity-70"
            >
              <span className="absolute inset-0 -translate-x-full bg-gradient-to-r from-transparent via-white/40 to-transparent transition-transform duration-700 group-hover:translate-x-full" />
              {isLoading ? (
                <span className="h-4 w-4 animate-spin rounded-full border-2 border-slate-950/30 border-t-slate-950" />
              ) : (
                <>Sign in <FiArrowRight className="transition-transform group-hover:translate-x-0.5" /></>
              )}
            </button>
          </form>

          <p className="mt-8 text-center text-[11.5px] leading-relaxed text-slate-500">
            By signing in you agree to the <a href="#" className="text-slate-700 underline-offset-2 hover:underline dark:text-slate-300">Terms of Use</a> and <a href="#" className="text-slate-700 underline-offset-2 hover:underline dark:text-slate-300">Privacy Statement</a>.
          </p>
        </motion.div>
      </section>
    </main>
  );
}
