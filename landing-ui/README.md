# ScaleGuard landing site (scaleguard.dev)

A static, dependency-free site: plain HTML, CSS and JavaScript. No build step, no framework, no external requests (no web fonts,
CDNs or trackers), so it loads fast and can be hosted anywhere, including on ScaleGuard itself.

```
landing-ui/
  index.html          the page (content, SEO tags and JSON-LD structured data)
  css/styles.css      all styles; the last block is the black "mission control" theme (full-viewport hero, uppercase type, hairline grids)
  js/main.js          theme, tabs, copy buttons, request simulator, scroll story (all optional enhancements)
  tools/hero_art.py   generates the hero illustration (two inline SVGs) into index.html
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

## The hero illustration

A hand-built vector diagram inlined in `index.html`, so it is sharp at any size and pixel density and costs no extra request:
requests from the internet pass through the TLS, rate-limit, WAF and auth gates inside one ScaleGuard block, into the shield (with two
slowly counter-rotating orbits) and fan out to three active nodes and two DR standbys. Attacks are stopped at the WAF (403 burst),
floods bounce off the rate limiter (429).

- **Two compositions:** `.art-wide` (1600 x 560) from 1000 px up, `.art-compact` (400 x 326, capped at 560 px wide) below, so labels stay
  readable on phones. Fonts and colours come from `css/styles.css` (`.art …` rules).
- **Motion:** packets are SMIL `animateMotion` along hidden paths; dashes and orbits are CSS. `main.js` pauses the SVG off-screen and
  for `prefers-reduced-motion` (the CSS animations stop too).
- **Editing:** change `tools/hero_art.py` (layout constants, labels, timings) and run it on the page:
  `python3 tools/hero_art.py index.html`. It replaces the `.hero-art` block in place.

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
