/** ScaleGuard design system — "Deep Signal" */
module.exports = {
  darkMode: 'class',
  content: ["./src/**/*.{js,jsx,ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      colors: {
        // Cool, ink-tinted neutrals. Every page uses slate, so this re-skins the whole app.
        slate: {
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
          950: '#060912',
        },
        signal: {
          cyan: '#22d3ee',
          teal: '#2dd4bf',
          violet: '#a78bfa',
          indigo: '#818cf8',
          lime: '#a3e635',
        },
      },
      boxShadow: {
        glow: '0 0 0 1px rgba(45,212,191,.25), 0 8px 40px -8px rgba(34,211,238,.35)',
        'glow-violet': '0 0 0 1px rgba(167,139,250,.25), 0 8px 40px -8px rgba(139,92,246,.4)',
        card: '0 1px 0 0 rgba(255,255,255,.04) inset, 0 20px 40px -24px rgba(0,0,0,.6)',
      },
      keyframes: {
        shimmer: { '0%': { transform: 'translateX(-100%)' }, '100%': { transform: 'translateX(100%)' } },
        'pulse-ring': { '0%': { transform: 'scale(.8)', opacity: '.7' }, '100%': { transform: 'scale(2.4)', opacity: '0' } },
        'spin-slow': { to: { transform: 'rotate(360deg)' } },
        float: { '0%,100%': { transform: 'translateY(0)' }, '50%': { transform: 'translateY(-6px)' } },
      },
      animation: {
        'pulse-ring': 'pulse-ring 2s cubic-bezier(.2,.6,.3,1) infinite',
        'spin-slow': 'spin-slow 18s linear infinite',
        float: 'float 6s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}
