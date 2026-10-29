/** ScaleGuard design system — "Pure Dark" */
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
        // Pure neutral grays — zero blue hue. Every "slate" class becomes true dark/gray.
        slate: {
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
          950: '#0a0a0a',
        },
        signal: {
          cyan: '#22d3ee',
          teal: '#2dd4bf',
          violet: '#a78bfa',
          indigo: '#818cf8',
          lime:   '#a3e635',
        },
      },
      boxShadow: {
        glow:         '0 0 0 1px rgba(45,212,191,.25), 0 8px 40px -8px rgba(34,211,238,.35)',
        'glow-violet':'0 0 0 1px rgba(167,139,250,.25), 0 8px 40px -8px rgba(139,92,246,.4)',
        card:         '0 1px 0 0 rgba(255,255,255,.04) inset, 0 20px 40px -24px rgba(0,0,0,.7)',
      },
      keyframes: {
        shimmer:      { '0%': { transform: 'translateX(-100%)' }, '100%': { transform: 'translateX(100%)' } },
        'pulse-ring': { '0%': { transform: 'scale(.8)', opacity: '.7' }, '100%': { transform: 'scale(2.4)', opacity: '0' } },
        'spin-slow':  { to: { transform: 'rotate(360deg)' } },
        float:        { '0%,100%': { transform: 'translateY(0)' }, '50%': { transform: 'translateY(-6px)' } },
      },
      animation: {
        'pulse-ring': 'pulse-ring 2s cubic-bezier(.2,.6,.3,1) infinite',
        'spin-slow':  'spin-slow 18s linear infinite',
        float:        'float 6s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}
