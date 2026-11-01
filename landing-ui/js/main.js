/* ScaleGuard landing. Vanilla JS, no dependencies. Every interactive part is progressive enhancement: the page reads fine without this file. */
(function () {
  'use strict';
  var doc = document.documentElement;
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  doc.classList.add('js');

  /* ── theme (follows the system until the visitor chooses) ── */
  var stored = null;
  try { stored = localStorage.getItem('sg-theme'); } catch (e) { /* private mode */ }
  var prefersLight = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches;
  doc.setAttribute('data-theme', stored || (prefersLight ? 'light' : 'dark'));
  var themeBtn = $('#theme');
  if (themeBtn) themeBtn.addEventListener('click', function () {
    var next = doc.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    doc.setAttribute('data-theme', next);
    try { localStorage.setItem('sg-theme', next); } catch (e) { /* ignore */ }
  });

  /* ── mobile menu ── */
  var menu = $('#menu'), links = $('#links');
  if (menu && links) {
    menu.addEventListener('click', function () {
      var open = links.classList.toggle('open');
      menu.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    $$('a', links).forEach(function (a) { a.addEventListener('click', function () { links.classList.remove('open'); menu.setAttribute('aria-expanded', 'false'); }); });
  }

  /* ── generic accessible tabs (install commands, screenshots) ── */
  function tabs(list, onSelect) {
    var btns = $$('[role="tab"]', list);
    function select(btn) {
      btns.forEach(function (b) { var on = b === btn; b.setAttribute('aria-selected', on ? 'true' : 'false'); b.tabIndex = on ? 0 : -1; });
      onSelect(btn);
    }
    btns.forEach(function (b, i) {
      b.addEventListener('click', function () { select(b); });
      b.addEventListener('keydown', function (e) {
        var j = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : -1;
        if (j < 0) return;
        e.preventDefault(); var t = btns[(j + btns.length) % btns.length]; t.focus(); select(t);
      });
    });
  }
  var install = $('[data-install]');
  if (install) tabs($('.tabs', install), function (btn) {
    $$('.cmd', install).forEach(function (c) { c.hidden = c.getAttribute('aria-labelledby') !== btn.id; });
  });

  /* ── copy buttons ── */
  $$('[data-copy]').forEach(function (b) {
    b.addEventListener('click', function () {
      var text = b.getAttribute('data-copy'), label = $('span', b);
      function done() { b.classList.add('done'); if (label) label.textContent = 'Copied'; setTimeout(function () { b.classList.remove('done'); if (label) label.textContent = 'Copy'; }, 1800); }
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, fallback); else fallback();
      function fallback() {
        var t = document.createElement('textarea'); t.value = text; t.setAttribute('readonly', ''); t.style.position = 'fixed'; t.style.opacity = '0';
        document.body.appendChild(t); t.select(); try { document.execCommand('copy'); done(); } catch (e) { /* ignore */ } document.body.removeChild(t);
      }
    });
  });

  /* ── screenshots gallery ── */
  var gallery = $('[data-gallery]');
  if (gallery) {
    var titles = { 'route-editor': 'scaleguard / routes', 'firewall': 'scaleguard / firewall', 'rate-limiting': 'scaleguard / rate limiting', 'backend-health': 'scaleguard / backend health' };
    var alts = {
      'route-editor': 'ScaleGuard route editor with a live preview of the request path',
      'firewall': 'ScaleGuard web application firewall: per-route policies, block or detect-only, rule groups',
      'rate-limiting': 'ScaleGuard rate limiting: default limit, burst, trusted proxies and the live list of limited clients',
      'backend-health': 'ScaleGuard backend health: per-backend state, failures, ejections and passive health-check settings'
    };
    var img = $('#g-img'), title = $('#g-title');
    tabs($('.g-tabs', gallery), function (btn) {
      var k = btn.getAttribute('data-shot');
      img.src = '/assets/screens/' + k + '.webp';
      img.srcset = '/assets/screens/' + k + '-800.webp 800w, /assets/screens/' + k + '.webp 1600w';
      img.alt = alts[k]; title.textContent = titles[k];
    });
  }

  /* ── request pipeline simulator ── */
  var flow = $('[data-flow]');
  if (flow) {
    var order = ['client', 'tls', 'ip', 'rate', 'waf', 'auth', 'target'];
    var items = {}; $$('.track li', flow).forEach(function (li) { items[li.getAttribute('data-st')] = li; });
    var badge = $('#res-badge'), rt = $('#res-title'), rd = $('#res-desc'), log = $('#logline code'), tname = $('#target-name'), tsub = $('#target-sub');
    var SC = {
      normal: { stop: null, target: ['Backends', 'load balanced'], badge: ['200 OK', ''], title: 'Routed to a healthy backend',
        desc: 'Round-robin across 10.0.0.5 and 10.0.0.6. Passive health checks watch the answer, and a failing server is ejected within seconds.',
        log: 'GET /api/orders  200  backend:10.0.0.5:3000  12.4ms  trace:4bf92f35' },
      attack: { stop: 'waf', target: ['Backends', 'never reached'], badge: ['403 Forbidden', 'bad'], title: 'Blocked by the web application firewall',
        desc: "The query matches the OWASP rule for SQL injection. The client gets a 403 with an incident ID; the backend never sees the request.",
        log: "GET /search?q=1' OR '1'='1  403  waf:sqli-union  incident:7f3a91  0.4ms" },
      flood: { stop: 'rate', target: ['Backends', 'never reached'], badge: ['429 Too Many Requests', 'bad'], title: 'Slowed down by the rate limiter',
        desc: 'This client used up its token bucket. It gets a 429 with a Retry-After header, and everyone else is unaffected.',
        log: 'GET /api/orders  429  rate-limited  retry-after:1s  client:203.0.113.9  0.2ms' },
      cache: { stop: null, special: true, target: ['Response cache', 'DuckDB-backed'], badge: ['200 · cache HIT', 'cache'], title: 'Served from the cache',
        desc: 'A matching rule with a TTL answers the request without touching the backend or running a function.',
        log: 'GET /catalog  200  cache:HIT  ttl:300s  0.3ms' },
      fn: { stop: null, special: true, target: ['Cloud function', 'sandboxed Groovy'], badge: ['201 Created', ''], title: 'Answered by a cloud function',
        desc: 'No backend at all: a Groovy function attached to the route builds the response, with the same auth, limits and caching as any target.',
        log: 'POST /hooks/deploy  201  function:notify-chat  4.1ms' },
      blocked: { stop: 'ip', target: ['Backends', 'never reached'], badge: ['403 Forbidden', 'bad'], title: 'Refused by the IP rules',
        desc: 'The address is on the gateway blocklist (or outside the route allow-list). It is refused before any other work is done.',
        log: 'GET /admin  403  ip-blocked  client:198.51.100.23  0.1ms' }
    };
    var played = false, timers = [];
    function clear() { timers.forEach(clearTimeout); timers = []; }
    function reset() { order.forEach(function (k) { items[k].classList.remove('pass', 'stop', 'cur', 'special'); }); }
    function play(key) {
      var s = SC[key]; clear(); reset();
      tname.textContent = s.target[0]; tsub.textContent = s.target[1];
      badge.className = 'badge' + (s.badge[1] ? ' ' + s.badge[1] : ''); badge.textContent = s.badge[0];
      rt.textContent = s.title; rd.textContent = s.desc; log.textContent = s.log;
      var step = 280;
      order.forEach(function (k, i) {
        var t = reduce ? 0 : i * step;
        timers.push(setTimeout(function () {
          order.forEach(function (o) { items[o].classList.remove('cur'); });
          if (s.stop === k) { items[k].classList.add('stop'); return; }
          if (s.stop && order.indexOf(k) > order.indexOf(s.stop)) return;
          items[k].classList.add('pass');
          if (k === 'target' && s.special) items[k].classList.add('special');
          if (!reduce) items[k].classList.add('cur');
        }, t));
      });
    }
    $$('.sc', flow).forEach(function (b) {
      b.addEventListener('click', function () {
        played = true;
        $$('.sc', flow).forEach(function (x) { x.classList.toggle('on', x === b); });
        play(b.getAttribute('data-sc'));
      });
    });
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (es, o) { es.forEach(function (e) { if (e.isIntersecting && !played) { played = true; play('normal'); o.disconnect(); } }); }, { threshold: .35 }).observe(flow);
    } else play('normal');
  }

  /* ── scroll story (internal apps) ── */
  var story = $('[data-story]');
  if (story) {
    var chapters = $$('.chapter', story);
    function setStep(n) {
      for (var i = 1; i <= 7; i++) story.classList.toggle('on-' + i, i <= n);
      story.setAttribute('data-step', n);
      chapters.forEach(function (c) { c.classList.toggle('active', +c.getAttribute('data-ch') === n); });
    }
    if ('IntersectionObserver' in window && !reduce) {
      setStep(1);
      var io = new IntersectionObserver(function (es) {
        es.forEach(function (e) { if (e.isIntersecting) setStep(+e.target.getAttribute('data-ch')); });
      }, { rootMargin: window.innerWidth <= 1180 ? '-58% 0px -30% 0px' : '-45% 0px -45% 0px', threshold: 0 });   // narrow screens: the band sits below the sticky diagram
      chapters.forEach(function (c) { io.observe(c); });
    } else {
      story.setAttribute('data-nojs', ''); chapters.forEach(function (c) { c.classList.add('active'); });
    }
  }

  /* ── bars, counters and reveal ── */
  var bars = $('[data-bars]');
  if (bars) {
    if ('IntersectionObserver' in window && !reduce) {
      new IntersectionObserver(function (es, o) { es.forEach(function (e) { if (e.isIntersecting) { bars.classList.add('in'); o.disconnect(); } }); }, { threshold: .3 }).observe(bars);
    } else bars.classList.add('in');
  }
  $$('.sec h2, .card, .step, .path, .stat, .faq details, .swap-col, .code, .term.big, .oss-badge').forEach(function (el) { el.classList.add('rv'); });
  if ('IntersectionObserver' in window && !reduce) {
    var ro = new IntersectionObserver(function (es) { es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add('in'); ro.unobserve(e.target); } }); }, { threshold: .12, rootMargin: '0px 0px -40px 0px' });
    $$('.rv').forEach(function (el) { ro.observe(el); });
  } else $$('.rv').forEach(function (el) { el.classList.add('in'); });

  /* ── "Today" tools drift into the single card as it scrolls in ── */
  var swap = $('#swap');
  if (swap && 'IntersectionObserver' in window && !reduce) {
    var lis = $$('.before li', swap);
    new IntersectionObserver(function (es, o) {
      es.forEach(function (e) { if (e.isIntersecting) { lis.forEach(function (li, i) { setTimeout(function () { li.style.transform = 'translateX(14px)'; li.style.opacity = '.55'; }, 400 + i * 90); }); o.disconnect(); } });
    }, { threshold: .5 }).observe(swap);
  }

  /* ── SafeExpose terminal: type the command once ── */
  var typed = $('[data-typing] .typed');
  if (typed && 'IntersectionObserver' in window && !reduce) {
    var full = typed.getAttribute('data-text'), term = typed.closest('[data-typing]'), lines = $$('.line', term);
    typed.textContent = ''; lines.forEach(function (l) { l.style.visibility = 'hidden'; });
    new IntersectionObserver(function (es, o) {
      es.forEach(function (e) {
        if (!e.isIntersecting) return; o.disconnect();
        var i = 0; var t = setInterval(function () {
          typed.textContent = full.slice(0, ++i);
          if (i >= full.length) { clearInterval(t); lines.forEach(function (l, k) { setTimeout(function () { l.style.visibility = 'visible'; }, 350 + k * 450); }); }
        }, 55);
      });
    }, { threshold: .5 }).observe(term);
  }


  /* ── high availability: break things and watch what happens (numbers are the measured ones from docs/CLUSTER.md) ── */
  var ha = $('[data-ha]');
  if (ha) {
    var ids = ['n1', 'n2', 'n3', 'd1', 'd2'], names = { n1: 'node-1', n2: 'node-2', n3: 'node-3', d1: 'node-5', d2: 'node-6' };
    var els = {}; ids.forEach(function (k) { els[k] = $('[data-n="' + k + '"]', ha); });
    var st, term, hbtimers = [], tick = null, electing = '';
    var elServing = $('#ha-serving'), elMaster = $('#ha-master'), elChanges = $('#ha-changes'), mT = $('#ha-title'), mD = $('#ha-desc');
    var bPromote = $('[data-hs="promote"]', ha), bReturn = $('[data-hs="return"]', ha);
    function H(ms, fn) { hbtimers.push(setTimeout(fn, reduce ? 0 : ms)); }
    function stopTimers() { hbtimers.forEach(clearTimeout); hbtimers = []; if (tick) { clearInterval(tick); tick = null; } electing = ''; }
    function able(btn, on) { btn.disabled = !on; btn.setAttribute('aria-disabled', on ? 'false' : 'true'); }
    function reset() { st = { n1: 'master', n2: 'follower', n3: 'follower', d1: 'standby', d2: 'standby' }; term = 7; electing = ''; }
    function say(t, d) { mT.textContent = t; mD.textContent = d; }
    function render() {
      var up = 0, master = null, anyCand = false;
      ids.forEach(function (k) {
        var s = st[k], r = els[k]; r.setAttribute('data-state', s);
        $('.role', r).textContent = s === 'master' ? '\u2605 master' : s === 'candidate' ? 'electing\u2026' : s;
        if (s !== 'down') up++; if (s === 'master') master = k; if (s === 'candidate') anyCand = true;
      });
      elServing.textContent = up + ' of 5 nodes';
      elServing.className = up === 5 ? 'ok' : up >= 2 ? 'warn' : 'bad';
      if (master) { elMaster.textContent = names[master] + ' \u00b7 term ' + term; elChanges.textContent = 'accepted'; elChanges.className = 'ok'; }
      else if (anyCand) { elMaster.textContent = electing || 'electing\u2026'; elChanges.textContent = 'paused until elected'; elChanges.className = 'warn'; }
      else { elMaster.textContent = 'none'; elChanges.textContent = 'refused (503): promote the DR site'; elChanges.className = 'bad'; }
    }
    function countdown(secs, done) {
      var left = secs, t0 = Date.now();
      electing = 'electing\u2026 ' + secs.toFixed(1) + ' s'; render();
      if (reduce) { done(); return; }
      tick = setInterval(function () {
        left = Math.max(0, secs - (Date.now() - t0) / 1000); electing = 'electing\u2026 ' + left.toFixed(1) + ' s'; render();
        if (left <= 0) { clearInterval(tick); tick = null; done(); }
      }, 100);
    }
    var S = {
      healthy: function () {
        reset(); able(bPromote, false); able(bReturn, false); render();
        say('Everything is healthy.', 'Three voting nodes share the work. The master applies configuration changes and the others copy them within milliseconds. Two DR nodes copy every change and also serve traffic, but never vote.');
      },
      follower: function () {
        reset(); able(bPromote, false); able(bReturn, false); st.n3 = 'down'; render();
        say('One node fails. Nothing else changes.', 'The other four nodes keep serving traffic and the master keeps accepting changes: two of three voters is still a majority. When node-3 returns it fetches what it missed from the ledger and verifies every link.');
      },
      master: function () {
        reset(); able(bPromote, false); able(bReturn, false); st.n1 = 'down'; render();
        say('The master dies. The others elect a new one.', 'Traffic never stops: every node keeps proxying. After a randomised 3 to 6 heartbeat wait the survivors hold a vote, and the one with the most complete ledger wins. Measured: a new master 2.9 to 3.8 s after kill -9.');
        H(350, function () { st.n2 = 'candidate'; st.n3 = 'candidate'; countdown(3.4, function () { st.n2 = 'master'; st.n3 = 'follower'; term = 8; render(); say('node-2 is the new master (term 8).', 'Configuration changes are accepted again. When node-1 restarts it hears the newer term, follows node-2 and catches up on the changes it missed.'); }); });
      },
      site: function () {
        reset(); st.n1 = st.n2 = st.n3 = 'down'; able(bPromote, true); able(bReturn, false); render();
        say('The whole primary site is lost.', 'The DR site keeps serving traffic on the configuration it already has. It will not guess: with no master it refuses configuration changes with a clear "promote" hint, so the two sites can never diverge. Decide, then promote.');
      },
      promote: function () {
        able(bPromote, false); stopTimers();
        st.d1 = 'candidate'; st.d2 = 'candidate'; render();
        say('Promoting the DR site\u2026', 'POST /cluster/dr/promote makes every live DR node a voting primary node. They elect a master in a term 1000 above the old site, so a returning old node can never out-vote it. The promotion survives restarts.');
        countdown(3.7, function () { st.d1 = 'master'; st.d2 = 'follower'; term = 1007; render(); able(bReturn, true); say('The DR site is now the primary.', 'node-5 is the master (term 1007). Configuration changes are accepted again and nothing from the old site was lost. Traffic never stopped.'); });
      },
      'return': function () {
        able(bReturn, false); st.n1 = st.n2 = st.n3 = 'follower'; render();
        say('The old site returns as followers.', 'Its nodes hear the newer term and follow the new master instead of competing with it. To fail back later, restart them as standbys of the new primary and promote them the same way.');
      }
    };
    $$('.hb', ha).forEach(function (b) {
      b.addEventListener('click', function () {
        if (b.disabled) return;
        var k = b.getAttribute('data-hs');
        if (k !== 'promote' && k !== 'return') stopTimers();
        if (k === 'promote' || k === 'return') $$('.hb', ha).forEach(function (x) { x.classList.toggle('on', x === b); });
        else $$('.hb', ha).forEach(function (x) { x.classList.toggle('on', x === b); });
        S[k]();
      });
    });
    S.healthy();
  }


  /* ── hero 3D scene (three.js, vendored, loaded after first paint; the page works without it) ── */
  var stage = $('[data-stage]'), hero = $('.hero');
  if (stage && hero) {
    var glOk = (function () { try { var c = document.createElement('canvas'); return !!(window.WebGLRenderingContext && (c.getContext('webgl2') || c.getContext('webgl'))); } catch (e) { return false; } })();
    var saveData = navigator.connection && navigator.connection.saveData;
    if (glOk && !saveData) {
      hero.classList.add('has-3d'); stage.hidden = false;           // reserve the space now so nothing jumps when the scene arrives
      var fail = function () { hero.classList.remove('has-3d'); stage.hidden = true; };
      stage.addEventListener('hero3d:fail', fail);
      var boot = function () {
        import('/js/hero3d.js').then(function (m) { if (!m.start(stage, { reduce: reduce })) fail(); }).catch(fail);
      };
      if (window.requestIdleCallback) window.requestIdleCallback(boot, { timeout: 1500 }); else setTimeout(boot, 200);
    }
  }

  var nav = $('.nav');
  if (nav) { var onScroll = function () { nav.classList.toggle('scrolled', window.scrollY > 40); }; onScroll(); window.addEventListener('scroll', onScroll, { passive: true }); }
  var clock = $('#t-clock');
  if (clock) { var t0 = Date.now(); var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    setInterval(function () { var s = Math.floor((Date.now() - t0) / 1000); clock.textContent = pad(Math.floor(s / 3600)) + ':' + pad(Math.floor(s / 60) % 60) + ':' + pad(s % 60); }, 1000); }

  var y = $('#year'); if (y) y.textContent = new Date().getFullYear();
})();
