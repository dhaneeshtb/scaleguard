import { useEffect, useState } from "react";
import { flushSync } from 'react-dom';

const initialTheme = (): string => {
  try {
    const t = localStorage.theme;
    return t === 'light' || t === 'dark' ? t : 'dark';
  } catch {
    return 'dark';
  }
};

export default function useDarkSide() {
  const [theme, setTheme] = useState<string>(initialTheme);

  const toggleTheme = async (next: string) => {
    const doc = document as any;
    // Smooth cross-fade where the View Transitions API exists; plain switch elsewhere.
    if (!doc.startViewTransition || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setTheme(next);
      return;
    }
    try {
      await doc.startViewTransition(() => {
        flushSync(() => setTheme(next));
      }).ready;
    } catch {
      setTheme(next);
    }
  };

  useEffect(() => {
    const root = window.document.documentElement;
    root.classList.remove(theme === 'dark' ? 'light' : 'dark', 'lightback', 'darkback');
    root.classList.add(theme);
    try { localStorage.setItem('theme', theme); } catch { /* storage unavailable */ }
  }, [theme]);

  return [theme, toggleTheme] as [string, (t: string) => Promise<void>];
}
