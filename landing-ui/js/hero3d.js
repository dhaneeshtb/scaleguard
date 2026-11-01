/* ScaleGuard hero scene: "the sentinel".
 * Requests stream left to right through inspection gates (TLS, rate limit, WAF, auth) into a glowing shield and fan out to an
 * active-active node cluster. Attacks burst at the WAF gate, floods bounce off the rate limiter.
 * Loaded lazily by main.js; three.js is vendored (js/vendor, MIT), nothing is fetched from a CDN. */
import * as THREE from './vendor/three.module.min.js';

const tmpC = new THREE.Color(), TEAL = new THREE.Color('#2dd4bf'), VIOLET = new THREE.Color('#8b5cf6'), RED = new THREE.Color('#fb7185'), AMBER = new THREE.Color('#fbbf24'), WHITE = new THREE.Color('#ffffff');
const GATES = [
  { key: 'tls', x: -7.2, r: 2, label: 'TLS' },
  { key: 'rate', x: -5.6, r: 2, label: 'Rate limit' },
  { key: 'waf', x: -4.0, r: 2, label: 'WAF' },
  { key: 'auth', x: -2.4, r: 2, label: 'Auth' }
];
const NODES = [
  { p: [5.6, 1.7, 0], c: TEAL, name: 'node-1' }, { p: [5.6, 0, 0], c: TEAL, name: 'node-2' }, { p: [5.6, -1.7, 0], c: TEAL, name: 'node-3' },
  { p: [7.3, 0.85, -1], c: VIOLET, name: 'DR' }, { p: [7.3, -0.85, -1], c: VIOLET, name: 'DR' }
];
const SPAWN_X = -9.5, END_X = 5.0, PATHS = ['/api/orders', '/api/users/42', '/checkout', '/search?q=shoes', '/static/app.js', '/v1/events', '/login', '/graphql'];
const ATTACKS = [["/search?q=1' OR '1'='1", 'waf:sqli-union'], ['/?id=<script>alert(1)</script>', 'waf:xss'], ['/../../etc/passwd', 'waf:path-traversal'], ['/?c=${jndi:ldap://x}', 'waf:log4shell']];
const smooth = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
const rand = (a, b) => a + Math.random() * (b - a);

function shieldShape(s) {
  const P = (x, y) => [(x - 16) * s, (16 - y) * s], sh = new THREE.Shape();
  sh.moveTo(...P(16, 2)); sh.lineTo(...P(28, 6.5)); sh.lineTo(...P(28, 16));
  sh.bezierCurveTo(...P(28, 22.2), ...P(23.2, 27.2), ...P(16, 30));
  sh.bezierCurveTo(...P(8.8, 27.2), ...P(4, 22.2), ...P(4, 16));
  sh.lineTo(...P(4, 6.5)); sh.lineTo(...P(16, 2));
  return sh;
}

export function start(stage, opts = {}) {
  const reduce = !!opts.reduce, canvas = $('canvas', stage), small = matchMedia('(max-width: 700px)').matches;
  function $(s, r) { return (r || document).querySelector(s); }

  let renderer;
  try { renderer = new THREE.WebGLRenderer({ canvas, antialias: !small, alpha: true, powerPreference: 'high-performance' }); }
  catch (e) { stage.dispatchEvent(new CustomEvent('hero3d:fail')); return null; }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, small ? 1.5 : 2));
  renderer.setClearColor(0x000000, 0);
  canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); stage.dispatchEvent(new CustomEvent('hero3d:fail')); });

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100);
  const world = new THREE.Group(); scene.add(world);
  // a faint floor grid under the pipeline for depth
  const floor = new THREE.GridHelper(30, 30, 0x2dd4bf, 0x2dd4bf); floor.position.set(0, -2.7, 0);
  floor.material.transparent = true; floor.material.opacity = 0.1; floor.material.depthWrite = false; world.add(floor);
  world.position.x = -0.2;

  /* ── background: faint dust and a floor grid for depth ── */
  {
    const n = small ? 160 : 380, g = new THREE.BufferGeometry(), p = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) { p[i * 3] = rand(-14, 14); p[i * 3 + 1] = rand(-6, 6); p[i * 3 + 2] = rand(-14, -3); }
    g.setAttribute('position', new THREE.BufferAttribute(p, 3));
    world.add(new THREE.Points(g, new THREE.PointsMaterial({ color: 0x8aa0c8, size: 0.045, transparent: true, opacity: 0.45, depthWrite: false })));
    const grid = new THREE.GridHelper(40, 40, 0x2dd4bf, 0x2a3350); grid.position.y = -3.2; grid.material.transparent = true; grid.material.opacity = 0.16; world.add(grid);
  }

  /* ── the shield: gradient body, glowing rim, scanning band, the logo's three bars ── */
  const shieldMat = new THREE.ShaderMaterial({
    uniforms: { uTime: { value: 0 }, uA: { value: TEAL }, uB: { value: VIOLET }, uPulse: { value: 0 } },
    vertexShader: 'varying vec3 vP; varying vec3 vN; varying vec3 vV; void main(){ vP=position; vN=normalMatrix*normal; vec4 mv=modelViewMatrix*vec4(position,1.0); vV=-mv.xyz; gl_Position=projectionMatrix*mv; }',
    fragmentShader: 'uniform float uTime; uniform vec3 uA; uniform vec3 uB; uniform float uPulse; varying vec3 vP; varying vec3 vN; varying vec3 vV;' +
      'void main(){ float g=clamp((vP.x*0.55+vP.y*0.55)/1.9+0.5,0.0,1.0); vec3 c=mix(uA,uB,g);' +
      ' float f=pow(1.0-abs(dot(normalize(vN),normalize(vV))),2.2); float band=smoothstep(0.0,0.14,1.0-abs(fract(vP.y*0.35-uTime*0.28)-0.5)*2.0);' +
      ' c=c*0.78+vec3(0.55,0.9,1.0)*f*0.9+vec3(1.0)*band*0.16+vec3(1.0)*uPulse*0.5; gl_FragColor=vec4(c,0.94); }',
    transparent: true
  });
  const shield = new THREE.Group(); world.add(shield);
  {
    const geo = new THREE.ExtrudeGeometry(shieldShape(0.105), { depth: 0.34, bevelEnabled: true, bevelThickness: 0.07, bevelSize: 0.06, bevelSegments: 4, curveSegments: 22 });
    geo.translate(0, 0, -0.17);
    shield.add(new THREE.Mesh(geo, shieldMat));
    const edges = new THREE.LineSegments(new THREE.EdgesGeometry(geo, 28), new THREE.LineBasicMaterial({ color: 0x9ff3e7, transparent: true, opacity: 0.55 }));
    shield.add(edges);
    const barMat = new THREE.MeshBasicMaterial({ color: 0x080a10 });
    [[14, 12.5], [9, 17], [4, 21.5]].forEach(([w, y]) => {
      const b = new THREE.Mesh(new THREE.BoxGeometry(w * 0.105, 0.2, 0.12), barMat); b.position.set(0, (16 - y) * 0.105, 0.3); shield.add(b); const b2 = b.clone(); b2.position.z = -0.3; shield.add(b2);
    });
    shield.scale.setScalar(1.25);
    // halo behind the shield
    const halo = new THREE.Mesh(new THREE.CircleGeometry(2.3, 64), new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
      vertexShader: 'varying vec2 vU; void main(){ vU=uv; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }',
      fragmentShader: 'varying vec2 vU; void main(){ float d=length(vU-0.5)*2.0; float a=pow(max(0.0,1.0-d),2.4)*0.42; gl_FragColor=vec4(mix(vec3(0.18,0.83,0.75),vec3(0.55,0.36,0.96),vU.x),a); }'
    }));
    halo.position.z = -0.4; world.add(halo);
  }

  /* ── gates: a ring plus a faint disc that flashes when something happens there ── */
  const gates = {};
  GATES.forEach((gt) => {
    const grp = new THREE.Group(); grp.position.x = gt.x; world.add(grp);
    const ringMat = new THREE.MeshBasicMaterial({ vertexColors: true, transparent: true, opacity: 0.95 });
    // top-to-bottom gradient: teal to violet (rose to amber for the WAF)
    const top = gt.key === 'waf' ? new THREE.Color('#fb7185') : new THREE.Color('#2dd4bf'), bot = gt.key === 'waf' ? new THREE.Color('#fbbf24') : new THREE.Color('#8b5cf6');
    const grad = (g, ax) => { const pa = g.attributes.position, col = new Float32Array(pa.count * 3), c = new THREE.Color(); for (let i = 0; i < pa.count; i++) { c.copy(top).lerp(bot, (1 - pa.getY(i) / gt.r) / 2); col.set([c.r, c.g, c.b], i * 3); } g.setAttribute('color', new THREE.BufferAttribute(col, 3)); return g; };
    const ring = new THREE.Mesh(grad(new THREE.TorusGeometry(gt.r, 0.026, 8, 160)), ringMat); ring.rotation.y = Math.PI / 2; ring.position.x = 0.13; grp.add(ring);
    const ring2 = new THREE.Mesh(ring.geometry, ringMat); ring2.rotation.y = Math.PI / 2; ring2.position.x = -0.13; grp.add(ring2);
    // a translucent open tunnel between the two rims gives the gate depth
    const tunnel = grad(new THREE.CylinderGeometry(gt.r, gt.r, 0.26, 72, 1, true).rotateZ(Math.PI / 2));
    grp.add(new THREE.Mesh(tunnel, new THREE.MeshBasicMaterial({ vertexColors: true, transparent: true, opacity: 0.09, side: THREE.DoubleSide, depthWrite: false, blending: THREE.AdditiveBlending })));
    // fine tick marks just inside the ring, like a gauge bezel
    const tk = [], TICKS = 90; for (let i = 0; i < TICKS; i++) { const a = i / TICKS * Math.PI * 2, l = i % 5 ? 0.07 : 0.16, c = Math.cos(a), sn = Math.sin(a); tk.push(0, c * (gt.r - 0.1), sn * (gt.r - 0.1), 0, c * (gt.r - 0.1 - l), sn * (gt.r - 0.1 - l)); }
    const tg = new THREE.BufferGeometry(); tg.setAttribute('position', new THREE.Float32BufferAttribute(tk, 3)); tg.attributes.position.getY = (i) => tk[i * 3 + 1]; grad(tg);
    grp.add(new THREE.LineSegments(tg, new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, opacity: 0.5 })));
    const disc = new THREE.Mesh(new THREE.CircleGeometry(gt.r, 72), new THREE.ShaderMaterial({
      uniforms: { uFlash: { value: 0 }, uTint: { value: gt.key === 'waf' ? RED : TEAL } },
      transparent: true, depthWrite: false, side: THREE.DoubleSide, blending: THREE.AdditiveBlending,
      vertexShader: 'varying vec2 vU; void main(){ vU=uv; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }',
      fragmentShader: 'uniform float uFlash; uniform vec3 uTint; varying vec2 vU; void main(){ float d=length(vU-0.5)*2.0; float base=0.02+0.03*d; gl_FragColor=vec4(uTint, (base+uFlash*0.35)*smoothstep(1.0,0.82,d)); }'
    }));
    disc.rotation.y = Math.PI / 2; grp.add(disc);
    gates[gt.key] = { ring, ringMat, disc, flash: 0, x: gt.x, grp };
  });

  /* ── the ScaleGuard block: one glass box around every filter and the shield ── */
  {
    const X0 = -8.2, X1 = 1.7, H = 2.5, box = new THREE.BoxGeometry(X1 - X0, H * 2, H * 2);
    const edges = new THREE.EdgesGeometry(box), pa = edges.attributes.position, col = new Float32Array(pa.count * 3), c = new THREE.Color();
    for (let i = 0; i < pa.count; i++) { c.copy(TEAL).lerp(VIOLET, (1 - pa.getY(i) / H) / 2); col.set([c.r, c.g, c.b], i * 3); }
    edges.setAttribute('color', new THREE.BufferAttribute(col, 3));
    const blk = new THREE.Group(); blk.position.x = (X0 + X1) / 2; world.add(blk);
    blk.add(new THREE.LineSegments(edges, new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, opacity: 1 })));
    blk.add(new THREE.Mesh(box, new THREE.MeshBasicMaterial({ color: 0x2dd4bf, transparent: true, opacity: 0.07, side: THREE.DoubleSide, depthWrite: false, blending: THREE.AdditiveBlending })));
  }

  /* ── node cluster (active-active, with two DR nodes) ── */
  const nodeMeshes = NODES.map((n, i) => {
    const m = new THREE.Mesh(new THREE.OctahedronGeometry(0.24, 0), new THREE.MeshBasicMaterial({ color: n.c.clone() }));
    m.position.set(...n.p); m.userData = { pulse: 0, base: n.c, i }; world.add(m);
    const ringG = new THREE.Mesh(new THREE.RingGeometry(0.34, 0.37, 40), new THREE.MeshBasicMaterial({ color: n.c, transparent: true, opacity: 0.35, side: THREE.DoubleSide, depthWrite: false }));
    ringG.position.copy(m.position); ringG.userData.follow = true; ringG.rotation.y = Math.PI / 2; world.add(ringG); m.userData.halo = ringG;
    return m;
  });
  {
    const pts = []; for (let i = 0; i < NODES.length; i++) for (let j = i + 1; j < NODES.length; j++) { pts.push(...NODES[i].p, ...NODES[j].p); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3));
    world.add(new THREE.LineSegments(g, new THREE.LineBasicMaterial({ color: 0x6f86ff, transparent: true, opacity: 0.16 })));
  }

  /* ── request particles (one GPU Points object, simulated in JS) ── */
  const N = small ? 480 : 1000;
  const P = new Float32Array(N * 3), C = new Float32Array(N * 3), Z = new Float32Array(N), A = new Float32Array(N);
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(P, 3)); geo.setAttribute('color', new THREE.BufferAttribute(C, 3));
  geo.setAttribute('size', new THREE.BufferAttribute(Z, 1)); geo.setAttribute('alpha', new THREE.BufferAttribute(A, 1));
  const ptMat = new THREE.ShaderMaterial({
    uniforms: { uScale: { value: 400 } }, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    vertexShader: 'attribute float size; attribute float alpha; attribute vec3 color; varying vec3 vC; varying float vA; uniform float uScale;' +
      'void main(){ vC=color; vA=alpha; vec4 mv=modelViewMatrix*vec4(position,1.0); gl_PointSize=size*uScale/-mv.z; gl_Position=projectionMatrix*mv; }',
    fragmentShader: 'varying vec3 vC; varying float vA; void main(){ vec2 d=gl_PointCoord-0.5; float r=length(d)*2.0; float a=smoothstep(1.0,0.0,r); a=a*a; gl_FragColor=vec4(vC+vec3(0.35)*a*a, a*vA); }'
  });
  const points = new THREE.Points(geo, ptMat); points.frustumCulled = false; world.add(points);

  const parts = Array.from({ length: N }, () => ({ x: 0, y0: 0, z0: 0, ph: 0, sp: 0, type: 0, tgt: 0, state: 0, t: 0, path: '', yz: [0, 0], hit: false }));
  function respawn(p, anywhere, forceAttack) {
    const r = Math.sqrt(Math.random()) * 2.0, a = Math.random() * Math.PI * 2;
    p.y0 = Math.cos(a) * r; p.z0 = Math.sin(a) * r; p.ph = Math.random() * 6.28; p.sp = rand(2.3, 3.9);
    const roll = Math.random();
    p.type = forceAttack || roll < 0.055 ? 1 : roll < 0.095 ? 2 : 0;     // 1 attack, 2 flood
    p.tgt = Math.floor(Math.random() * 3);                              // a primary node (DR nodes copy, they do not take requests here)
    if (Math.random() < 0.22) p.tgt = 3 + Math.floor(Math.random() * 2);  // the DR site serves traffic too
    p.x = anywhere ? rand(SPAWN_X, END_X - 0.4) : SPAWN_X - rand(0, 1.5); p.state = 0; p.t = 0; p.hit = false;
    p.path = p.type === 1 ? ATTACKS[Math.floor(Math.random() * ATTACKS.length)] : PATHS[Math.floor(Math.random() * PATHS.length)];
    if (anywhere && p.type !== 0) { p.x = p.type === 1 ? rand(SPAWN_X, GATES[2].x - 0.3) : rand(SPAWN_X, GATES[1].x - 0.3); }
  }
  parts.forEach((p) => respawn(p, true));

  /* ── sparks for bursts ── */
  const SN = 260, SP = new Float32Array(SN * 3), SC = new Float32Array(SN * 3), SZ = new Float32Array(SN), SA = new Float32Array(SN), sv = [];
  for (let i = 0; i < SN; i++) sv.push({ v: new THREE.Vector3(), life: 0 });
  const sg = new THREE.BufferGeometry();
  sg.setAttribute('position', new THREE.BufferAttribute(SP, 3)); sg.setAttribute('color', new THREE.BufferAttribute(SC, 3)); sg.setAttribute('size', new THREE.BufferAttribute(SZ, 1)); sg.setAttribute('alpha', new THREE.BufferAttribute(SA, 1));
  const sparks = new THREE.Points(sg, ptMat); sparks.frustumCulled = false; world.add(sparks);
  let sparkCursor = 0;
  function burst(x, y, z, color, count) {
    for (let k = 0; k < count; k++) {
      const i = sparkCursor++ % SN, s = sv[i];
      s.v.set(rand(-0.6, 0.9), rand(-1, 1), rand(-1, 1)).normalize().multiplyScalar(rand(0.8, 2.6)); s.life = rand(0.5, 1.0);
      SP[i * 3] = x; SP[i * 3 + 1] = y; SP[i * 3 + 2] = z; SC[i * 3] = color.r; SC[i * 3 + 1] = color.g; SC[i * 3 + 2] = color.b; SZ[i] = rand(0.05, 0.12);
    }
  }

  /* ── HUD: counters and a rolling access-log ── */
  const hud = { served: 0, blocked: 0, limited: 0 }, elS = $('#hud-served', stage), elB = $('#hud-blocked', stage), elL = $('#hud-limited', stage), elLog = $('#hud-log', stage);
  const lines = []; let lastLine = 0;
  function logLine(text, cls) {
    const now = performance.now(); if (now - lastLine < 230) return; lastLine = now;
    lines.push('<span class="' + cls + '">' + text + '</span>'); if (lines.length > 5) lines.shift(); if (elLog) elLog.innerHTML = lines.join('\n');
  }
  function hudUpdate() { if (elS) { elS.textContent = hud.served.toLocaleString(); elB.textContent = hud.blocked.toLocaleString(); elL.textContent = hud.limited.toLocaleString(); } }

  /* ── labels (DOM, projected each frame) ── */
  const labelEls = {}; $$('[data-label]', stage).forEach((el) => { labelEls[el.getAttribute('data-label')] = el; });
  function $$(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }
  const labelAnchors = {
    // filter labels sit just above the block, its name just below
    tls: new THREE.Vector3(GATES[0].x, GATES[0].r + 1.0, 0), rate: new THREE.Vector3(GATES[1].x, GATES[1].r + 1.0, 0), waf: new THREE.Vector3(GATES[2].x, GATES[2].r + 1.0, 0), auth: new THREE.Vector3(GATES[3].x, GATES[3].r + 1.0, 0),
    shield: new THREE.Vector3(-3.25, -3.1, 0), nodes: new THREE.Vector3(6.1, 2.55, -0.6)
  };
  const tmp = new THREE.Vector3();

  /* ── simulation ── */
  const pos = (p, out) => {
    const t = smooth(-6.8, -0.9, p.x); let f = 1 - 0.62 * t, y = p.y0 * f, z = p.z0 * f;
    if (p.x > 0.55) {
      const n = NODES[p.tgt].p, s0 = 0.55, e = Math.min(1, (p.x - s0) / (n[0] - s0)), k = e * e * (3 - 2 * e), y0 = p.y0 * (1 - 0.62), z0 = p.z0 * (1 - 0.62);
      y = y0 + (n[1] - y0) * k; z = z0 + (n[2] - z0) * k;
    }
    out[0] = p.x; out[1] = y + Math.sin(time * 2 + p.ph) * 0.045; out[2] = z + Math.cos(time * 1.7 + p.ph) * 0.045;
  };
  let time = 0; const o = [0, 0, 0];
  function step(dt) {
    time += dt;
    for (let i = 0; i < N; i++) {
      const p = parts[i];
      if (p.state === 0) {
        p.x += p.sp * dt * (1 - 0.45 * (1 - smooth(0.1, 0.9, Math.abs(p.x))));
        if (p.type === 1 && p.x >= GATES[2].x && !p.hit) {            // attack stopped at the WAF
          p.hit = true; pos(p, o); gates.waf.flash = 1; burst(o[0], o[1], o[2], RED, 22); hud.blocked++;
          const a = p.path; logLine('GET ' + a[0].slice(0, 28) + '  <b>403</b>  ' + a[1], 'bad'); p.state = 2; p.t = 0; continue;
        }
        if (p.type === 2 && p.x >= GATES[1].x && !p.hit) {            // flood bounced by the rate limiter
          p.hit = true; gates.rate.flash = 1; hud.limited++; logLine('GET ' + p.path + '  <b>429</b>  rate-limited', 'warn'); p.state = 3; p.t = 0; continue;
        }
        if (p.type === 0 && !p.hit) { for (const g of GATES) if (p.x >= g.x && p.x - p.sp * dt < g.x) gates[g.key].flash = Math.min(0.3, gates[g.key].flash + 0.04); }
        if (p.x >= END_X) {                                           // served
          hud.served++; const m = nodeMeshes[p.tgt]; m.userData.pulse = Math.min(0.5, m.userData.pulse + 0.06);
          logLine('GET ' + p.path + '  <b>200</b>  ' + NODES[p.tgt].name + '  ' + (6 + Math.floor(Math.random() * 18)) + 'ms', 'ok');
          respawn(p, false);
        }
      } else if (p.state === 2) { p.t += dt; if (p.t > 0.2) respawn(p, false); }
      else if (p.state === 3) { p.x -= p.sp * dt * 1.4; p.t += dt; if (p.x < SPAWN_X + 1 || p.t > 1.6) respawn(p, false); }
    }
    for (const k in gates) { const g = gates[k]; g.flash *= Math.pow(0.25, dt); g.disc.material.uniforms.uFlash.value = g.flash; g.ringMat.opacity = 0.75 + g.flash * 0.25; }
    nodeMeshes.forEach((m) => { m.userData.pulse *= Math.pow(0.15, dt); m.scale.setScalar(1 + m.userData.pulse * 0.6); m.material.color.copy(m.userData.base).lerp(WHITE, m.userData.pulse * 0.8); m.rotation.y += dt * 0.6; m.userData.halo.scale.setScalar(1 + m.userData.pulse * 0.5); });
    shieldMat.uniforms.uTime.value = time; shieldMat.uniforms.uPulse.value = Math.max(0, shieldMat.uniforms.uPulse.value - dt * 1.5);
    for (let i = 0; i < SN; i++) {
      const s = sv[i]; if (s.life <= 0) { SA[i] = 0; continue; }
      s.life -= dt; SP[i * 3] += s.v.x * dt; SP[i * 3 + 1] += s.v.y * dt; SP[i * 3 + 2] += s.v.z * dt; s.v.multiplyScalar(1 - dt * 1.6); SA[i] = Math.max(0, s.life * 1.4);
    }
  }
  function writeBuffers() {
    for (let i = 0; i < N; i++) {
      const p = parts[i]; pos(p, o); P[i * 3] = o[0]; P[i * 3 + 1] = o[1]; P[i * 3 + 2] = o[2];
      let c = TEAL, size = 0.115, a = 0.9;
      if (p.type === 1) { c = RED; size = 0.16; } else if (p.type === 2) { c = AMBER; size = 0.14; }
      else if (p.x > 0.55) { c = tmpC.copy(TEAL).lerp(VIOLET, smooth(0.55, END_X, p.x)); size = 0.115; }
      const ns = 1 - smooth(0.2, 1.4, Math.abs(p.x)); size *= 1 + 0.6 * ns; a += (1 - a) * ns;
      if (p.state === 2) a *= Math.max(0, 1 - p.t / 0.18); if (p.state === 3) a = Math.max(0, 0.8 - p.t * 0.6);
      if (p.x < SPAWN_X + 1.2) a *= smooth(SPAWN_X - 0.2, SPAWN_X + 1.2, p.x);
      if (p.x > END_X - 0.6) a *= smooth(END_X, END_X - 0.6, p.x);
      C[i * 3] = c.r; C[i * 3 + 1] = c.g; C[i * 3 + 2] = c.b; Z[i] = size; A[i] = a;
    }
    geo.attributes.position.needsUpdate = geo.attributes.color.needsUpdate = geo.attributes.size.needsUpdate = geo.attributes.alpha.needsUpdate = true;
    sg.attributes.position.needsUpdate = sg.attributes.color.needsUpdate = sg.attributes.size.needsUpdate = sg.attributes.alpha.needsUpdate = true;
  }

  /* ── interaction: pointer parallax, click = attack wave ── */
  const target = { x: 0, y: 0 }, cur = { x: 0, y: 0 };
  stage.addEventListener('pointermove', (e) => { const r = stage.getBoundingClientRect(); target.x = ((e.clientX - r.left) / r.width - 0.5) * 2; target.y = ((e.clientY - r.top) / r.height - 0.5) * 2; });
  stage.addEventListener('pointerleave', () => { target.x = 0; target.y = 0; });
  let paused = false;

  /* ── size, camera, render loop ── */
  function resize() {
    const w = Math.max(240, stage.clientWidth), h = Math.max(240, stage.clientHeight);
    renderer.setSize(w, h, false); camera.aspect = w / h;
    // Frame by pixels per world unit: the whole pipeline fits across the width and sits above the copy.
    const tanH = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2));
    const ppu = Math.min(w / 21, h * 0.078), z = Math.max(9, h / (2 * tanH * ppu));
    camera.position.set(-0.2, 2.1, z); camera.lookAt(-0.2, 0, 0);
    // turn every ring so it is seen at the same angle: equal width on screen, not narrower toward the shield
    GATES.forEach((gt) => { gates[gt.key].grp.rotation.y = -(Math.atan2(z, camera.position.x - gt.x) - 1.4); });
    camera.setViewOffset(w, h, 0, h * 0.2, w, h); // scene sits in the upper part, copy below
    camera.updateProjectionMatrix();
    ptMat.uniforms.uScale.value = h * renderer.getPixelRatio() * 1.05;
    draw();
  }
  function project() {
    for (const k in labelEls) {
      const a = labelAnchors[k]; if (!a) continue;
      tmp.copy(a); world.localToWorld(tmp); tmp.project(camera);
      const half = labelEls[k].offsetWidth / 2 + 8, x = Math.min(stage.clientWidth - half, Math.max(half, (tmp.x * 0.5 + 0.5) * stage.clientWidth)), y = (-tmp.y * 0.5 + 0.5) * stage.clientHeight;   // keep labels inside the stage
      labelEls[k].style.transform = 'translate(' + x.toFixed(1) + 'px,' + y.toFixed(1) + 'px) translate(-50%,-50%)';
    }
  }
  function draw() {
    cur.x += (target.x - cur.x) * 0.035; cur.y += (target.y - cur.y) * 0.035;
    world.rotation.y = -0.15 + cur.x * 0.12 + Math.sin(time * 0.15) * 0.05; world.rotation.x = 0.06 + cur.y * 0.1;
    shield.rotation.y = time * 0.9; shield.position.y = Math.sin(time * 0.9) * 0.06;
    writeBuffers(); renderer.render(scene, camera); project(); hudUpdate();
  }
  let last = 0, raf = 0, visible = true;
  function loop(now) {
    raf = 0; if (paused || !visible || document.hidden) return;
    const dt = Math.min(0.05, (now - last) / 1000 || 0.016); last = now; step(dt); draw(); raf = requestAnimationFrame(loop);
  }
  function kick() { if (!raf && !paused && visible && !document.hidden && !reduce) { last = performance.now(); raf = requestAnimationFrame(loop); } }
  new IntersectionObserver((es) => { visible = es[0].isIntersecting; if (visible) kick(); }, { threshold: 0.01 }).observe(stage);
  document.addEventListener('visibilitychange', kick);
  new ResizeObserver(resize).observe(stage);

  // warm up so the first frame already looks alive
  for (let i = 0; i < 90; i++) step(1 / 30);
  resize();
  stage.classList.add('live');
  if (!reduce) kick();
  return { destroy() { cancelAnimationFrame(raf); renderer.dispose(); } };
}
