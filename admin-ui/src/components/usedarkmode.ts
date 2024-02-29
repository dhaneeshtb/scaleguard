import { useEffect, useState } from "react";
import { flushSync } from 'react-dom';

export default function useDarkSide() {
    const [theme, setTheme] = useState<string>(localStorage.theme);

    const toggleTheme=async (theme:string)=>{
        await (document as any).startViewTransition(() => {
            flushSync(() => {
                setTheme(theme);
            });
        }).ready;
        document.documentElement.animate(
            // {
            //   clipPath: [
            //     `circle(0px at ${x}px ${y}px)`,
            //     `circle(200px at ${x}px ${y}px)`,
            //   ],
            // },
            {
              duration: 500,
              easing: 'ease-in-out',
              pseudoElement: '::view-transition-new(root)',
            }
          );

    }
  
    useEffect(() => {
      const root = window.document.documentElement;
      root.classList.remove(theme === 'dark' ? 'light' : 'dark');
      root.classList.add(theme);

      root.classList.remove(theme === 'dark' ? 'lightback' : 'darkback');
      root.classList.add(theme === 'dark' ? 'darkback' : 'lightback');

      


      
  
      // save theme to local storage
      localStorage.setItem('theme', theme);
    }, [theme]);
  
    return [theme, toggleTheme];
  }