// src/contexts/ThemeContext.js

import React, { createContext, useContext, useState } from 'react';
import useDarkSide from '../components/usedarkmode';

const ThemeContext = createContext<any>(null);

export const ThemeProvider = ({ children }:{children:any}) => {
  const [theme, setTheme] = useDarkSide();

  return (
    <ThemeContext.Provider value={{ theme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
};

export default function useThemeContext() {
    return useContext(ThemeContext);
}


