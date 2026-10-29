import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './App';
import reportWebVitals from './reportWebVitals';
import { ThemeProvider } from './contexts/Themeprovider';
import { ChakraProvider, ColorModeScript } from '@chakra-ui/react';
import AuthProvider from './contexts/AuthContext';
import { SystemContextProvider } from './contexts/SystemContext';
import theme from './theme';

const root = ReactDOM.createRoot(
  document.getElementById('root') as HTMLElement
);

root.render(
  <React.StrictMode>
    <ThemeProvider>
      <ColorModeScript initialColorMode={theme.config.initialColorMode} />
      <ChakraProvider theme={theme}>
        <AuthProvider>
          <SystemContextProvider>
            <App />
          </SystemContextProvider>
        </AuthProvider>
      </ChakraProvider>
    </ThemeProvider>
  </React.StrictMode>
);

reportWebVitals();
