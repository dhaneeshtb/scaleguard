import { motion } from "framer-motion";

const LoadingScreen = () => {
  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      className="fixed inset-0 z-[9999] flex items-center justify-center bg-[#060912]"
    >
      <div className="pointer-events-none absolute left-1/2 top-1/2 h-80 w-80 -translate-x-1/2 -translate-y-1/2 rounded-full bg-teal-500/10 blur-3xl" />
      <div className="relative flex flex-col items-center">
        <div className="relative flex h-20 w-20 items-center justify-center">
          <span className="absolute inset-0 animate-spin rounded-full border-2 border-transparent border-t-teal-300 border-r-violet-400" style={{ animationDuration: '1.1s' }} />
          <span className="absolute inset-2 rounded-full border border-white/10" />
          <img src={`${process.env.PUBLIC_URL}/lb.svg`} alt="" className="h-9 w-9" />
        </div>
        <p className="mt-6 text-[11px] font-semibold uppercase tracking-[0.22em] text-slate-400">Connecting to control plane</p>
      </div>
    </motion.div>
  );
};

export default LoadingScreen;
