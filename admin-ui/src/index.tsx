import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './App';
import reportWebVitals from './reportWebVitals';
import { ThemeProvider } from './contexts/Themeprovider';
import { ChakraProvider } from '@chakra-ui/react'
import { mode } from '@chakra-ui/theme-tools';
import { extendTheme } from '@chakra-ui/react';
import AuthProvider from './contexts/AuthContext';
import { SystemContextProvider } from './contexts/SystemContext';
const root = ReactDOM.createRoot(
  document.getElementById('root') as HTMLElement
);

const styles = {
  global: props => ({
    body: {
      // color: mode('gray.800', 'whiteAlpha.900')(props),
          bg: mode('slate.100', '#141214')(props),
    },
  }),
};

const components = {
  Drawer: {
    // setup light/dark mode component defaults
    baseStyle: props => ({
      dialog: {
        bg: mode('white', '#141214')(props),
      },
    }),
  },
};

const theme = extendTheme({
  initialColorMode: 'dark',
  useSystemColorMode: false,
  components,
  styles,
});

root.render(
  <React.StrictMode>
   <ThemeProvider>
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

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals();
