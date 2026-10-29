import { extendTheme, type ThemeConfig } from '@chakra-ui/react';
import { mode } from '@chakra-ui/theme-tools';

// Shared ink palette — mirrors the Tailwind `slate` override so Chakra surfaces match.
const ink = {
  50: '#f6f8fc',
  100: '#eef1f8',
  200: '#dde3ef',
  300: '#c0c9dc',
  400: '#8c97b2',
  500: '#636f8d',
  600: '#454f6b',
  700: '#283049',
  800: '#141a2b',
  900: '#0c1120',
};

const config: ThemeConfig = {
  initialColorMode: 'dark',
  useSystemColorMode: false,
};

const theme = extendTheme({
  config,
  fonts: {
    heading: `'Inter', ui-sans-serif, system-ui, sans-serif`,
    body: `'Inter', ui-sans-serif, system-ui, sans-serif`,
    mono: `'JetBrains Mono', ui-monospace, monospace`,
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
        dialog: { bg: mode('white', '#0c1120')(props) },
      }),
    },
    Modal: {
      baseStyle: (props: any) => ({
        dialog: { bg: mode('white', '#0c1120')(props) },
        overlay: { bg: 'blackAlpha.600', backdropFilter: 'blur(6px)' },
      }),
    },
    Menu: {
      baseStyle: (props: any) => ({
        list: {
          bg: mode('white', '#0f1526')(props),
          borderColor: mode('slate.200', 'whiteAlpha.100')(props),
          boxShadow: '0 24px 48px -16px rgba(0,0,0,.5)',
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
      baseStyle: { bg: '#0f1526', color: 'slate.100', rounded: 'md', fontSize: 'xs', px: 2, py: 1 },
    },
  },
});

export default theme;
