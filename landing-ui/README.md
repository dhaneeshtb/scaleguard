# ScaleGuard landing site (scaleguard.dev)

A static, dependency-free site: plain HTML, CSS and JavaScript. No build step, no framework, no external requests (no web fonts,
CDNs or trackers), so it loads fast and can be hosted anywhere, including on ScaleGuard itself.

```
landing-ui/
  index.html          the page (content, SEO tags and JSON-LD structured data)
  css/styles.css      all styles; the last block is the black "mission control" theme (full-viewport hero, uppercase type, hairline grids)
  js/main.js          theme, tabs, copy buttons, request simulator, scroll story (all optional enhancements)
  js/hero3d.js        the 3D hero scene (three.js), loaded lazily after first paint
  js/vendor/          three.module.min.js (three.js r170, MIT, licence alongside), served from this site, never from a CDN
  assets/             screenshots (WebP, 1600 px and 800 px), social card, logo, icons
  install.sh          copy of ../install.sh, served at https://scaleguard.dev/install.sh
  robots.txt  sitemap.xml  llms.txt  site.webmanifest  404.html
  CNAME  .nojekyll  _headers      hosting files
```

## Preview locally

```bash
cd landing-ui && python3 -m http.server 4173      # then open http://localhost:4173/
```

(Use a server rather than `file://`: the page uses root-relative URLs such as `/css/styles.css`.)

## The 3D hero ("the sentinel")

The hero is a full-viewport, dark, SpaceX-style launch screen (telemetry row, huge uppercase headline, T+ clock). Behind it, a WebGL scene (three.js): requests stream left to right through TLS, rate-limit, WAF and auth gates into a glowing
3D ScaleGuard shield and fan out to an active-active node cluster (three primary nodes and two DR nodes). Attacks burst at the WAF gate,
floods bounce off the rate limiter, a live HUD counts served / blocked / rate-limited requests and prints access-log lines. Move the
pointer to look around.

- **Cost:** three.js is 692 KB (172 KB gzipped) and is imported only after the page has painted (`requestIdleCallback`), so it does not
  delay the first paint or the text. The scene is one draw call for about 1,500 particles; it ran at the display refresh rate on a laptop GPU.
- **Falls back cleanly:** no WebGL, a lost GPU context, or the browser's "data saver" setting leaves the normal centred hero in place
  (the `has-3d` class is only added when the scene can run). With `prefers-reduced-motion` it draws a single still frame and shows no controls.
- **Good citizen:** it pauses when scrolled off-screen or in a background tab, caps the pixel ratio (2x desktop, 1.5x phones), uses fewer
  particles on phones,. The canvas has a text description for screen readers; the animation is decorative.
- **Security:** nothing is fetched from a third party, so the strict `script-src 'self'` policy in `vercel.json` / `_headers` still holds.
- **Updating three.js:** `npm pack three@<version>` in an empty folder, copy `build/three.module.min.js` and `LICENSE` into `js/vendor/`.
  Releases after r170 no longer ship a minified single file, so newer versions need a bundler (for example esbuild with tree-shaking)
  or the page will download about 2 MB. Re-test the scene after any upgrade.
- **Tuning:** gate positions, colours, particle counts and the camera are constants at the top of `js/hero3d.js`.

## Host it on Vercel (scaleguard.dev)

`landing-ui` is a static site, so Vercel needs no build. `vercel.json` already carries the security headers, caching, the
`install.sh` content type and the `www` to apex redirect.

1. **Vercel dashboard → Add New → Project**, import the `dhaneeshtb/scaleguard` repository.
2. **Root Directory: `landing-ui`** (Edit next to the repository name).
3. **Framework Preset: Other.** Leave **Build Command**, **Output Directory** and **Install Command** empty (or override each to blank).
4. Deploy. Every push to the production branch redeploys; pull requests get preview URLs.
5. **Settings → Domains:** add `scaleguard.dev` (set it as the primary) and `www.scaleguard.dev`. Add the DNS records Vercel shows at your
   registrar (typically an `A` record for the apex and a `CNAME` for `www`; use the values in the dashboard, they are project specific).
   HTTPS certificates are issued automatically. The `www` to apex redirect is in `vercel.json`.

Or from a terminal: `cd landing-ui && npx vercel` (first run links the project), then `npx vercel --prod`.

Because the Vercel root is this folder it cannot see `../install.sh`, so **keep the copy here current**: `cp ../install.sh install.sh`
(check with `cmp install.sh ../install.sh`). The page advertises `curl -fsSL https://scaleguard.dev/install.sh | sh`.

`_headers`, `CNAME` and `.nojekyll` are for other hosts and are ignored by Vercel (and listed in `.vercelignore`).

## Other hosts

**GitHub Pages** (the `CNAME` file is already here): publish this folder (for example a `gh-pages` branch, or Settings → Pages →
GitHub Actions deploying `landing-ui/`). At your DNS provider add

| Type | Name | Value |
|---|---|---|
| A | `@` | `185.199.108.153`, `185.199.109.153`, `185.199.110.153`, `185.199.111.153` |
| CNAME | `www` | `<your-github-user>.github.io` |

then tick **Enforce HTTPS** in the Pages settings.

**Cloudflare Pages / Netlify:** point the project at `landing-ui/` (no build command, output directory `.`) and add `scaleguard.dev`
as a custom domain. They also read `_headers` (security headers, caching, correct content types for `install.sh` and `llms.txt`).

**On ScaleGuard itself:** create a route for `scaleguard.dev` and serve this folder from any static origin. `_headers` shows the
headers to set.

Redirect `www.scaleguard.dev` to `https://scaleguard.dev/` so there is one canonical address.

## SEO and answer-engine (AEO) setup that is already in place

- One `<h1>`, a logical heading outline, descriptive `alt` text, explicit image sizes (no layout shift), WebP images with a 800 px
  variant for phones, lazy loading below the fold, `fetchpriority="high"` on the hero image.
- `<title>` (58 chars), meta description (146 chars), canonical `https://scaleguard.dev/`, Open Graph and Twitter cards with a
  1200 x 630 image, theme colours, web manifest, favicon set.
- **Structured data (JSON-LD)**: `Organization`, `WebSite`, two `SoftwareApplication` entries (ScaleGuard and the open-source
  SafeExpose tunnel client, both free, MIT), `HowTo` (install in three steps) and `FAQPage` with nine short questions. The FAQ text in the
  JSON-LD is identical to the visible FAQ, as search engines require.
- **Answer engines**: the FAQ answers lead with a direct one- or two-sentence answer; `llms.txt` summarises the product, key facts
  (including honest limits) and links to the docs; `robots.txt` explicitly allows the main search and AI crawlers.
- `sitemap.xml` with image entries, a branded `404.html` (`noindex`).

### After it goes live

1. Verify the domain in **Google Search Console** and **Bing Webmaster Tools**, then submit `https://scaleguard.dev/sitemap.xml`.
2. Check the page with Google's **Rich Results Test** and the **Schema Markup Validator** (the `FAQPage`, `HowTo` and
   `SoftwareApplication` blocks).
3. Run Lighthouse / PageSpeed Insights (aim for 95+ on performance, accessibility and SEO) and fix anything it reports for your host.
4. Test the Open Graph card with a link preview (for example by pasting the URL into a chat or social post).
5. Optional: ping IndexNow so Bing and others recrawl quickly after changes.
6. Link to it: put `https://scaleguard.dev` in the GitHub repository description and website field, and in both READMEs.

## Keeping it truthful and in sync

- **`install.sh`**: `cp ../install.sh install.sh` whenever the installer changes. The page, JSON-LD and `llms.txt` all advertise
  `curl -fsSL https://scaleguard.dev/install.sh | sh`. That command needs a published release package (`../make-release.sh`
  builds it); until one exists, tell people to run `./install.sh` from a clone.
- **FAQ**: change the visible answer and the JSON-LD answer together.
- **Numbers**: the performance figures come from the repository README (one core, small responses, October 2026). Update them
  from there, with the date, and keep the "where others are ahead" notes.
- **`sitemap.xml`**: bump `<lastmod>` when the content changes.
- **Screenshots**: `docs/images/*.png` are the sources; convert with `cwebp -q 80` (and `-resize 800 500` for the small version).
- **SafeExpose is "fully open source"**: its README says MIT, but the repository still needs a `LICENSE` file for that to be
  true in the eyes of GitHub, package indexes and search engines.
