"""Generates the hero illustration (two SVGs: wide >= 1000px, compact below) and splices it into landing-ui/index.html."""
import re, sys

SHIELD = "M16 2l12 4.5V16c0 6.2-4.8 11.2-12 14C8.8 27.2 4 22.2 4 16V6.5L16 2z"
BARS = "M9 12.5h14M11.5 17h9M14 21.5h4"
TEAL, VIOLET, ROSE, AMBER = "#2dd4bf", "#8b5cf6", "#fb7185", "#fbbf24"

def defs(p):
    return f'''<defs>
  <linearGradient id="{p}edge" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{TEAL}"/><stop offset=".55" stop-color="#6d7cf6"/><stop offset="1" stop-color="{VIOLET}"/></linearGradient>
  <linearGradient id="{p}panel" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0e1628"/><stop offset="1" stop-color="#060910"/></linearGradient>
  <linearGradient id="{p}tile" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#131d33"/><stop offset="1" stop-color="#0a101d"/></linearGradient>
  <linearGradient id="{p}shield" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7af0de"/><stop offset=".45" stop-color="{TEAL}"/><stop offset="1" stop-color="{VIOLET}"/></linearGradient>
  <radialGradient id="{p}glow"><stop offset="0" stop-color="{TEAL}" stop-opacity=".32"/><stop offset=".55" stop-color="{VIOLET}" stop-opacity=".1"/><stop offset="1" stop-color="{VIOLET}" stop-opacity="0"/></radialGradient>
  <radialGradient id="{p}pk"><stop offset="0" stop-color="#fff"/><stop offset=".3" stop-color="#7af0de" stop-opacity=".9"/><stop offset="1" stop-color="{TEAL}" stop-opacity="0"/></radialGradient>
  <radialGradient id="{p}pkv"><stop offset="0" stop-color="#fff"/><stop offset=".3" stop-color="#c4b5fd" stop-opacity=".9"/><stop offset="1" stop-color="{VIOLET}" stop-opacity="0"/></radialGradient>
  <radialGradient id="{p}pkr"><stop offset="0" stop-color="#fff"/><stop offset=".3" stop-color="#fda4af" stop-opacity=".95"/><stop offset="1" stop-color="{ROSE}" stop-opacity="0"/></radialGradient>
  <radialGradient id="{p}pka"><stop offset="0" stop-color="#fff"/><stop offset=".3" stop-color="#fcd34d" stop-opacity=".95"/><stop offset="1" stop-color="{AMBER}" stop-opacity="0"/></radialGradient>
  <radialGradient id="{p}fadeg"><stop offset="0" stop-color="#fff"/><stop offset=".7" stop-color="#fff" stop-opacity=".35"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
  <mask id="{p}fade"><rect width="100%" height="100%" fill="url(#{p}fadeg)"/></mask>
  <pattern id="{p}grid" width="32" height="32" patternUnits="userSpaceOnUse"><path d="M32 0H0V32" fill="none" stroke="#fff" stroke-opacity=".045"/></pattern>
  <filter id="{p}blur" x="-60%" y="-60%" width="220%" height="220%"><feGaussianBlur stdDeviation="3"/></filter>
  <filter id="{p}halo" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="26"/></filter>
</defs>'''

def packet(p, path, dur, begin, grad="pk", r=9, core=2.6, extra=""):
    return (f'<g opacity="0"><circle r="{r}" fill="url(#{p}{grad})"/><circle r="{core}" fill="#fff"/>'
            f'<animateMotion dur="{dur}s" begin="{begin}s" repeatCount="indefinite" calcMode="linear"{extra}><mpath href="#{path}"/></animateMotion>'
            f'<animate attributeName="opacity" values="0;1;1;0" keyTimes="0;.05;.95;1" dur="{dur}s" begin="{begin}s" repeatCount="indefinite"/></g>')

def refusal(p, path, dur, begin, x, y, grad, color, code, back, r=9, core=2.6, ring=30, fs=12):
    """A packet that stops at a gate: blocked (burst) or rate-limited (bounces back). Ring + code flash at the gate."""
    if back:   # 429: reaches the gate at 55%, drifts back while fading
        motion = f'keyPoints="0;1;.82" keyTimes="0;.55;1"'
        op = 'values="0;1;1;0;0" keyTimes="0;.05;.55;.8;1"'
    else:      # 403: vanishes at the gate
        motion = f'keyPoints="0;1;1" keyTimes="0;.55;1"'
        op = 'values="0;1;1;0;0" keyTimes="0;.05;.54;.56;1"'
    t = f'dur="{dur}s" begin="{begin}s" repeatCount="indefinite"'
    return (f'<g opacity="0"><circle r="{r}" fill="url(#{p}{grad})"/><circle r="{core}" fill="#fff"/>'
            f'<animateMotion {t} calcMode="linear" {motion}><mpath href="#{path}"/></animateMotion>'
            f'<animate attributeName="opacity" {op} {t}/></g>'
            f'<circle cx="{x}" cy="{y}" r="4" fill="none" stroke="{color}" stroke-width="1.6" opacity="0">'
            f'<animate attributeName="r" values="4;4;{ring};{ring}" keyTimes="0;.55;.7;1" {t}/>'
            f'<animate attributeName="opacity" values="0;0;.95;0;0" keyTimes="0;.549;.55;.72;1" {t}/></circle>'
            f'<text x="{x}" y="{y + ring + fs + 4}" class="a-code" font-size="{fs}" fill="{color}" text-anchor="middle" opacity="0">{code}'
            f'<animate attributeName="opacity" values="0;0;1;1;0;0" keyTimes="0;.55;.58;.75;.82;1" {t}/></text>')

def gate(p, cx, tile_y, tile_h, tile_w, icon_y, bus_y, icon, label, sub, bad, s):
    """One filter tile: icon, a capsule the traffic passes through, label and sub-label. s = scale for the compact art."""
    c = "#fda4af" if bad else "#7af0de"
    rgb = "251,113,133" if bad else "45,212,191"
    cap_w, cap_h = 18 * s, 78 * s
    out = [f'<rect x="{cx - tile_w / 2}" y="{tile_y}" width="{tile_w}" height="{tile_h}" rx="{18 * s}" fill="url(#{p}tile)" stroke="#fff" stroke-opacity=".08"/>',
           f'<path d="M{cx - tile_w / 2 + 16 * s} {tile_y + .5}H{cx + tile_w / 2 - 16 * s}" stroke="#fff" stroke-opacity=".14"/>',
           f'<circle cx="{cx}" cy="{icon_y}" r="{23 * s}" fill="rgba({rgb},.08)" stroke="rgba({rgb},.4)"/>',
           f'<use href="#{icon}" x="{cx - 12 * s}" y="{icon_y - 12 * s}" width="{24 * s}" height="{24 * s}" fill="none" stroke="{c}" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>',
           f'<rect x="{cx - cap_w / 2}" y="{bus_y - cap_h / 2}" width="{cap_w}" height="{cap_h}" rx="{cap_w / 2}" fill="rgba({rgb},.06)" stroke="rgba({rgb},.55)" stroke-width="1.3"/>',
           f'<path d="M{cx - cap_w / 2 - 7 * s} {bus_y}h{4 * s}M{cx + cap_w / 2 + 3 * s} {bus_y}h{4 * s}" stroke="{c}" stroke-opacity=".7" stroke-width="1.3"/>']
    return "".join(out)

def wide():
    p, BUS = "w-", 300
    o = [f'<svg class="art art-wide" viewBox="0 0 1600 560" role="img" aria-labelledby="{p}t"><title id="{p}t">Requests from the internet pass through TLS, rate limiting, the WAF and auth inside ScaleGuard, then fan out to three active nodes with a standby DR site. Attacks are blocked at the WAF, floods at the rate limiter.</title>', defs(p)]
    o.append(f'<rect width="1600" height="560" fill="url(#{p}grid)" mask="url(#{p}fade)"/>')
    # internet: lines converging on the gateway
    ins = [110, 200, 300, 390, 480]
    o.append(f'<linearGradient id="{p}in" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="270" y2="0"><stop offset="0" stop-color="{TEAL}" stop-opacity="0"/><stop offset="1" stop-color="{TEAL}" stop-opacity=".5"/></linearGradient>')
    for y in ins:
        o.append(f'<path d="M0 {y}C140 {y} 160 {BUS} 270 {BUS}" fill="none" stroke="url(#{p}in)" stroke-width="1.2"/>')
    o.append('<text x="24" y="70" class="a-cap">Internet</text>')
    # the ScaleGuard block
    o.append(f'<rect x="270" y="80" width="840" height="440" rx="28" fill="url(#{p}edge)" opacity=".14" filter="url(#{p}halo)"/>')
    o.append(f'<rect x="270" y="80" width="840" height="440" rx="28" fill="url(#{p}panel)" stroke="url(#{p}edge)" stroke-width="1.6"/>')
    o.append('<path d="M302 80.8H1078" stroke="#fff" stroke-opacity=".18"/>')
    o.append('<use href="#i-logo" x="302" y="105" width="24" height="24"/>')
    o.append('<text x="338" y="123" class="a-title">ScaleGuard</text>')
    o.append('<text x="1078" y="123" class="a-cap" text-anchor="end">One process · one config</text>')
    o.append('<path d="M302 148H1078" stroke="#fff" stroke-opacity=".07"/>')
    # bus with a flowing dash on top
    o.append(f'<path d="M270 {BUS}H1110" stroke="{TEAL}" stroke-opacity=".22" stroke-width="1.2"/>')
    o.append(f'<path class="a-flow" d="M270 {BUS}H1110" stroke="#7af0de" stroke-opacity=".55" stroke-width="1.2"/>')
    G = [("i-lock", "TLS", "auto-HTTPS", False), ("i-gauge", "Rate limit", "per IP · per key", False),
         ("i-shield", "WAF", "OWASP CRS", True), ("i-key", "Auth", "OAuth 2.0 · JWT", False)]
    xs = [375, 529, 683, 837]
    for cx, (ic, lab, sub, bad) in zip(xs, G):
        o.append(gate(p, cx, 172, 256, 130, 218, BUS, ic, lab, sub, bad, 1))
        o.append(f'<text x="{cx}" y="384" class="a-lab{" bad" if bad else ""}" text-anchor="middle">{lab}</text>')
        o.append(f'<text x="{cx}" y="406" class="a-sub" text-anchor="middle">{sub}</text>')
    # fan-out to the cluster
    o.append(f'<linearGradient id="{p}out" gradientUnits="userSpaceOnUse" x1="1110" y1="0" x2="1200" y2="0"><stop offset="0" stop-color="{VIOLET}" stop-opacity=".8"/><stop offset="1" stop-color="{TEAL}" stop-opacity=".55"/></linearGradient>')
    NY = [170, 300, 430]
    for y in NY:
        o.append(f'<path d="M1110 {BUS}C1150 {BUS} 1160 {y} 1200 {y}" fill="none" stroke="url(#{p}out)" stroke-width="1.3"/>')
    # packet paths
    paths = {}
    for i, (yi, yn) in enumerate([(110, 170), (300, 300), (480, 430), (200, 170), (390, 430), (300, 170), (200, 300), (390, 300)]):
        paths[f"{p}p{i}"] = f"M0 {yi}C140 {yi} 160 {BUS} 270 {BUS}H1110C1150 {BUS} 1160 {yn} 1200 {yn}"
    paths[f"{p}pa"] = f"M0 200C140 200 160 {BUS} 270 {BUS}H683"
    paths[f"{p}pb"] = f"M0 480C140 480 160 {BUS} 270 {BUS}H683"
    paths[f"{p}pr"] = f"M0 390C140 390 160 {BUS} 270 {BUS}H529"
    o.append("<defs>" + "".join(f'<path id="{k}" d="{v}"/>' for k, v in paths.items()) + "</defs>")
    for i in range(8):
        o.append(packet(p, f"{p}p{i}", 5.6, -(i * 0.7)))
    o.append(refusal(p, f"{p}pa", 7, -1.2, 683, BUS, "pkr", ROSE, "403", False))
    o.append(refusal(p, f"{p}pb", 7, -4.7, 683, BUS, "pkr", ROSE, "403", False))
    o.append(refusal(p, f"{p}pr", 6, -3.0, 529, BUS, "pka", AMBER, "429", True))
    # the shield: glow, two counter-rotating orbits, the logo
    cx, cy = 1005, BUS
    o.append(f'<circle cx="{cx}" cy="{cy}" r="150" fill="url(#{p}glow)"/>')
    o.append(f'<g class="a-orb" style="transform-origin:{cx}px {cy}px"><circle cx="{cx}" cy="{cy}" r="104" fill="none" stroke="url(#{p}edge)" stroke-width="1.4" stroke-dasharray="1.5 7" stroke-linecap="round"/><circle cx="{cx}" cy="{cy - 104}" r="3.5" fill="#7af0de"/></g>')
    o.append(f'<g class="a-orb rev" style="transform-origin:{cx}px {cy}px"><circle cx="{cx}" cy="{cy}" r="122" fill="none" stroke="#fff" stroke-opacity=".12" stroke-width="1" stroke-dasharray="54 16"/><circle cx="{cx + 122}" cy="{cy}" r="2.6" fill="#c4b5fd"/></g>')
    o.append(f'<g transform="translate({cx - 80} {cy - 82}) scale(5)"><path d="{SHIELD}" fill="url(#{p}shield)" opacity=".55" filter="url(#{p}blur)"/>'
             f'<path d="{SHIELD}" fill="url(#{p}shield)"/><path d="{SHIELD}" fill="none" stroke="#fff" stroke-opacity=".45" stroke-width=".22"/>'
             f'<path d="{BARS}" stroke="#070a12" stroke-width="2.2" stroke-linecap="round"/></g>')
    o.append(f'<text x="{cx}" y="458" class="a-sub" text-anchor="middle">route · cache · proxy</text>')
    # cluster: three active nodes, two DR standbys
    o.append('<text x="1285" y="118" class="a-cap" text-anchor="middle">Active-active</text>')
    for i, y in enumerate(NY):
        o.append(f'<rect x="1200" y="{y - 30}" width="170" height="60" rx="14" fill="url(#{p}tile)" stroke="{TEAL}" stroke-opacity=".4"/>'
                 f'<circle cx="1226" cy="{y}" r="4.5" fill="#34d399"/><circle cx="1226" cy="{y}" r="4.5" fill="none" stroke="#34d399"><animate attributeName="r" values="4.5;11" dur="2.4s" begin="{-i * .8}s" repeatCount="indefinite"/><animate attributeName="opacity" values=".7;0" dur="2.4s" begin="{-i * .8}s" repeatCount="indefinite"/></circle>'
                 f'<text x="1246" y="{y - 2}" class="a-node">node-{i + 1}</text><text x="1246" y="{y + 16}" class="a-sub">{"master" if i == 0 else "active"}</text>')
    o.append('<text x="1495" y="194" class="a-cap" text-anchor="middle">DR site</text>')
    for i, y in enumerate([240, 360]):
        o.append(f'<path class="a-flow v" d="M1370 {NY[i] + (0 if i == 0 else 130)}C1395 {NY[i] + (0 if i == 0 else 130)} 1395 {y} 1420 {y}" fill="none" stroke="{VIOLET}" stroke-opacity=".6" stroke-width="1.2"/>')
        o.append(f'<rect x="1420" y="{y - 26}" width="150" height="52" rx="13" fill="#0a0f1c" stroke="{VIOLET}" stroke-opacity=".55" stroke-dasharray="4 4"/>'
                 f'<circle cx="1444" cy="{y}" r="4" fill="{VIOLET}"/><text x="1460" y="{y - 1}" class="a-node">dr-{i + 1}</text><text x="1460" y="{y + 15}" class="a-sub">standby</text>')
    o.append("</svg>")
    return "".join(o)

def compact():
    p, BUS, s = "c-", 128, .5
    o = [f'<svg class="art art-compact" viewBox="0 0 400 326" role="img" aria-labelledby="{p}t"><title id="{p}t">Requests pass through TLS, rate limiting, the WAF and auth inside ScaleGuard, then fan out to three active nodes. Attacks are blocked at the WAF.</title>', defs(p)]
    o.append(f'<rect width="400" height="326" fill="url(#{p}grid)" mask="url(#{p}fade)"/>')
    o.append(f'<rect x="8" y="22" width="384" height="222" rx="18" fill="url(#{p}panel)" stroke="url(#{p}edge)" stroke-width="1.2"/>')
    o.append('<path d="M24 22.6H376" stroke="#fff" stroke-opacity=".18"/>')
    o.append('<use href="#i-logo" x="22" y="35" width="15" height="15"/>')
    o.append('<text x="44" y="47" class="a-title" font-size="10.5">ScaleGuard</text>')
    o.append('<text x="378" y="47" class="a-cap" font-size="8" text-anchor="end">One process</text>')
    o.append('<path d="M22 60H378" stroke="#fff" stroke-opacity=".07"/>')
    o.append(f'<path d="M0 {BUS}H332" stroke="{TEAL}" stroke-opacity=".22"/><path class="a-flow" d="M0 {BUS}H332" stroke="#7af0de" stroke-opacity=".55"/>')
    G = [("i-lock", "TLS", False), ("i-gauge", "Rate limit", False), ("i-shield", "WAF", True), ("i-key", "Auth", False)]
    xs = [52, 117, 182, 247]
    for cx, (ic, lab, bad) in zip(xs, G):
        o.append(gate(p, cx, 72, 116, 56, 94, BUS, ic, lab, "", bad, s))
        o.append(f'<text x="{cx}" y="206" class="a-lab{" bad" if bad else ""}" font-size="8.6" text-anchor="middle">{lab}</text>')
    NX = [80, 200, 320]
    for x in NX:
        o.append(f'<path d="M332 170V226C332 252 {x} 236 {x} 266" fill="none" stroke="{VIOLET}" stroke-opacity=".6"/>')
    paths = {f"{p}p{i}": f"M-12 {BUS}H332V226C332 252 {x} 236 {x} 266" for i, x in enumerate(NX + NX)}
    paths[f"{p}pa"] = f"M-12 {BUS}H182"
    paths[f"{p}pr"] = f"M-12 {BUS}H117"
    o.append("<defs>" + "".join(f'<path id="{k}" d="{v}"/>' for k, v in paths.items()) + "</defs>")
    for i in range(6):
        o.append(packet(p, f"{p}p{i}", 4.6, -(i * 0.77), r=6, core=1.8))
    o.append(refusal(p, f"{p}pa", 6, -1.0, 182, BUS, "pkr", ROSE, "403", False, r=6, core=1.8, ring=16, fs=8))
    o.append(refusal(p, f"{p}pr", 5.4, -3.4, 117, BUS, "pka", AMBER, "429", True, r=6, core=1.8, ring=16, fs=8))
    cx, cy = 332, BUS
    o.append(f'<circle cx="{cx}" cy="{cy}" r="70" fill="url(#{p}glow)"/>')
    o.append(f'<g class="a-orb" style="transform-origin:{cx}px {cy}px"><circle cx="{cx}" cy="{cy}" r="44" fill="none" stroke="url(#{p}edge)" stroke-width="1.1" stroke-dasharray="1.2 5" stroke-linecap="round"/><circle cx="{cx}" cy="{cy - 44}" r="2.2" fill="#7af0de"/></g>')
    o.append(f'<g class="a-orb rev" style="transform-origin:{cx}px {cy}px"><circle cx="{cx}" cy="{cy}" r="53" fill="none" stroke="#fff" stroke-opacity=".12" stroke-dasharray="26 8"/></g>')
    k = 2.2
    o.append(f'<g transform="translate({cx - 16 * k} {cy - 16.4 * k}) scale({k})"><path d="{SHIELD}" fill="url(#{p}shield)" opacity=".55" filter="url(#{p}blur)"/>'
             f'<path d="{SHIELD}" fill="url(#{p}shield)"/><path d="{SHIELD}" fill="none" stroke="#fff" stroke-opacity=".45" stroke-width=".4"/>'
             f'<path d="{BARS}" stroke="#070a12" stroke-width="2.2" stroke-linecap="round"/></g>')
    for i, x in enumerate(NX):
        o.append(f'<rect x="{x - 46}" y="266" width="92" height="32" rx="10" fill="url(#{p}tile)" stroke="{TEAL}" stroke-opacity=".4"/>'
                 f'<circle cx="{x - 30}" cy="282" r="3" fill="#34d399"/><text x="{x - 20}" y="285.5" class="a-node" font-size="9.5">node-{i + 1}</text>')
    o.append('<text x="200" y="318" class="a-cap" font-size="7.5" text-anchor="middle">Active-active · DR standby</text>')
    o.append("</svg>")
    return "".join(o)

def fs(svg): return re.sub(r'font-size="([\d.]+)"', r'style="font-size:\1px"', svg)

html_path = sys.argv[1]
src = open(html_path).read()
# CSS classes beat SVG presentation attributes, so per-text sizes go in style
art = '<div class="hero-art">\n    ' + fs(wide()) + '\n    ' + fs(compact()) + '\n  </div>\n  '
# replaces the current illustration (or, the first time, the old WebGL stage)
new, n = re.subn(r'<div class="hero-art">.*?</svg>\n  </div>\n  |<div class="hero-stage" id="stage" data-stage hidden>.*?</div>\n  </div>\n  ', lambda m: art, src, flags=re.S)
assert n == 1, "hero illustration block not found"
new = new.replace('<section class="hero" aria-labelledby="h1">', '<section class="hero has-3d" aria-labelledby="h1">', 1)
open(html_path, "w").write(new)
print("ok", len(wide()), len(compact()))
