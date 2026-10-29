import { extendTheme, type ThemeConfig } from '@chakra-ui/react';
import { mode } from '@chakra-ui/theme-tools';

// Pure neutral palette — mirrors the Tailwind slate override so Chakra surfaces match.
// Zero blue hue: every shade is a true neutral gray.
const ink = {
  50:  '#f9f9f9',
  100: '#f2f2f2',
  200: '#e5e5e5',
  300: '#c8c8c8',
  400: '#9a9a9a',
  500: '#6b6b6b',
  600: '#4a4a4a',
  700: '#2e2e2e',
  800: '#1a1a1a',
  900: '#111111',
};

const config: ThemeConfig = {
  initialColorMode: 'dark',
  useSystemColorMode: false,
};

const theme = extendTheme({
  config,
  fonts: {
    heading: `'Inter', ui-sans-serif, system-ui, sans-serif`,
    body:    `'Inter', ui-sans-serif, system-ui, sans-serif`,
    mono:    `'JetBrains Mono', ui-monospace, monospace`,
  },
  colors: { gray: ink, slate: ink },
  radii: { md: '0.5rem', lg: '0.75rem', xl: '1rem' },
  styles: {
    global: (props: any) => ({
      body: {
        bg: 'var(--sg-bg)',
        color: mode('slate.900', 'slate.100')(props),
      },
    }),
  },
  components: {
    Drawer: {
      baseStyle: (props: any) => ({
        dialog: { bg: mode('white', '#0f0f0f')(props) },
      }),
    },
    Modal: {
      baseStyle: (props: any) => ({
        dialog: { bg: mode('white', '#0f0f0f')(props) },
        overlay: { bg: 'blackAlpha.700', backdropFilter: 'blur(6px)' },
      }),
    },
    Menu: {
      baseStyle: (props: any) => ({
        list: {
          bg: mode('white', '#161616')(props),
          borderColor: mode('slate.200', 'whiteAlpha.100')(props),
          boxShadow: '0 24px 48px -16px rgba(0,0,0,.7)',
          rounded: 'xl',
          p: 1.5,
        },
        item: {
          rounded: 'lg',
          bg: 'transparent',
          _hover: { bg: mode('slate.100', 'whiteAlpha.100')(props) },
          _focus: { bg: mode('slate.100', 'whiteAlpha.100')(props) },
        },
      }),
    },
    Button: {
      baseStyle: { fontWeight: 600, letterSpacing: '-0.005em' },
    },
    Input: {
      defaultProps: { focusBorderColor: 'teal.400' },
    },
    Select: {
      defaultProps: { focusBorderColor: 'teal.400' },
    },
    Tooltip: {
      baseStyle: { bg: '#161616', color: 'slate.100', rounded: 'md', fontSize: 'xs', px: 2, py: 1 },
    },
  },
});

export default theme;
