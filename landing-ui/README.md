# ScaleGuard landing site (scaleguard.dev)

A static, dependency-free site: plain HTML, CSS and JavaScript. No build step, no framework, no external requests (no web fonts,
CDNs or trackers), so it loads fast and can be hosted anywhere, including on ScaleGuard itself.

```
landing-ui/
  index.html          the page (content, SEO tags and JSON-LD structured data)
  css/styles.css      all styles, dark + light themes
  js/main.js          theme, tabs, copy buttons, request simulator, scroll story (all optional enhancements)
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

## Host it under scaleguard.dev

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
  SafeExpose tunnel client, both free, MIT), `HowTo` (install in three steps) and `FAQPage` with ten questions. The FAQ text in the
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
