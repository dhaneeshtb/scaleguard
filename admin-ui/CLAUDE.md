# admin-ui — agent guide

React 18 + TypeScript (CRA / react-scripts 5), Tailwind 3 (`darkMode: 'class'`) + Chakra UI 2, framer-motion,
react-router 6, axios. Built output goes to `../adminui` (`./build.sh`), served by the JAR at `/adminui`.

## Structure
- `src/Layout.tsx` — sidebar (nav sections), top bar, ⌘K `CommandPalette`, control-plane ping. Add pages to `navSections`.
- `src/App.tsx` — routes. `src/pages/protection/` (Firewall, RateLimiting), `src/pages/Home.tsx` (overview),
  `src/components/EntityEditor.tsx` — the single editor for source systems / target systems / host groups
  (used by `pages/ManageHost.tsx` and `pages/ManageSource.tsx`).
- `src/components/ui/controls.tsx` — shared atoms: PageHeader, Card, Field, ToggleRow, Segmented, Stat, Pill, SaveBar.
- `src/components/sourceupdate.ts` — schemas for the three entity types; `updateSource` POSTs only schema keys
  (add a key there or it won't be saved).
- Design tokens: `tailwind.config.js` (slate is re-tinted ink, fonts Inter/JetBrains Mono), `src/index.css`
  (`sg-card`, `sg-eyebrow`, `sg-kbd`, `sg-gradient-text`, backdrop), `src/theme.ts` (Chakra, synced to the Tailwind theme).

## API usage
Every call: `` `${auth.data.host}/<path>?scaleguard=true` `` with header `Authorization: auth.data.token`
(`useAuth()` from `contexts/AuthContext`).

## Rules learned the hard way
- Never define components inside another component's body — they remount on every parent render and wipe input state.
- Use react-router `Link` / `navigate`, never `<a href="/...">` for in-app pages (the JAR serves the UI under `/adminui`).
- `npx tsc --noEmit -p .` prints ~9 errors from `node_modules/@types/d3-*`; they're pre-existing noise — filter with `grep "^src/"`.
- ESLint config has no `react-hooks` plugin: an `eslint-disable … react-hooks/exhaustive-deps` comment *fails the build*.
- To preview without a server: `../dev-tools/ui-preview/build-preview.sh` → open `design-preview.html` (mock API in `mock.js`).
