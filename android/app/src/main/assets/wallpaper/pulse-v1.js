/* KOne city pulse v1: each city's own sky traffic and night light over the artwork.
 * Art direction, not live data: how busy the sky is, the helicopters, searchlights and the
 * colour of the city's glow follow the city's character. Everything stays in the sky band
 * and the horizon, so it never lands on a building. Coordinates are normalised to the stage,
 * which keeps the artwork's own aspect.
 */
(() => {
  'use strict';
  const TAU = Math.PI * 2;
  const clamp = (n, a = 0, b = 1) => Math.max(a, Math.min(b, n));
  const finite = Number.isFinite;
  const smooth = (a, b, n) => { const t = clamp((n - a) / (b - a)); return t * t * (3 - 2 * t); };

  // air: aircraft a minute; heli: helicopters a minute; glow: night light [r, g, b, strength];
  // beams: sweeping searchlights; luxor: one fixed beam straight up; tribute: two beams on 11 September.
  const WARM = [255, 178, 104], GOLD = [255, 204, 140], COOL = [206, 222, 255], NEON = [214, 186, 255];
  const CITIES = {
    'new-york': {air: 5, heli: 1.4, glow: [255, 176, 98, .62], tribute: true},
    'los-angeles': {air: 5, heli: 1, glow: [255, 170, 110, .58], beams: 2},
    'las-vegas': {air: 3, heli: .6, glow: [255, 198, 124, .82], beams: 3, luxor: true},
    'chicago': {air: 5, heli: .3, glow: [...WARM, .56]},
    'atlanta': {air: 6, glow: [...WARM, .5]},
    'dallas': {air: 4.5, glow: [...WARM, .5]},
    'houston': {air: 3.5, heli: .4, glow: [...WARM, .5]},
    'miami': {air: 3.5, heli: .4, glow: [255, 160, 196, .52]},
    'san-francisco': {air: 3.5, glow: [...GOLD, .44]},
    'seattle': {air: 3, glow: [...GOLD, .4]},
    'denver': {air: 3.5, glow: [...WARM, .42]},
    'washington-dc': {air: 3, heli: .8, glow: [...GOLD, .44]},
    'boston': {air: 3.2, glow: [...WARM, .44]},
    'philadelphia': {air: 3, glow: [...WARM, .44]},
    'san-diego': {air: 2.6, low: true, glow: [...GOLD, .4]},
    'honolulu': {air: 2, glow: [...GOLD, .34]},
    'detroit': {air: 2.6, glow: [...WARM, .42]},
    'minneapolis': {air: 2.8, glow: [...WARM, .4]},
    'new-orleans': {air: 2, glow: [...GOLD, .42]},
    'austin': {air: 2.4, glow: [...WARM, .4]},
    'san-antonio': {air: 2, glow: [...WARM, .38]},
    'salt-lake-city': {air: 2.4, glow: [...WARM, .36]},
    'portland': {air: 2.2, glow: [...GOLD, .36]},
    'pittsburgh': {air: 1.8, glow: [...WARM, .38]},
    'st-louis': {air: 2, glow: [...WARM, .38]},
    'baltimore': {air: 2, glow: [...WARM, .38]},
    'savannah': {air: .9, glow: [...GOLD, .3]},
    'charleston': {air: .9, glow: [...GOLD, .3]},
    'london': {air: 6, heli: .5, glow: [255, 172, 104, .5]},
    'paris': {air: 4, glow: [...GOLD, .5]},
    'tokyo': {air: 5, heli: .4, glow: [...COOL, .6]},
    'hong-kong': {air: 4, heli: .6, glow: [...NEON, .64]},
    'singapore': {air: 4, glow: [255, 234, 206, .54]},
    'dubai': {air: 6, heli: .5, glow: [255, 226, 176, .64]},
    'abu-dhabi': {air: 3, glow: [255, 226, 176, .5]},
    'shanghai': {air: 4, glow: [...COOL, .6]},
    'shenzhen': {air: 3.5, glow: [...COOL, .55]},
    'chongqing': {air: 2.5, glow: [...NEON, .55]},
    'seoul': {air: 3.5, glow: [...COOL, .55]},
    'osaka': {air: 3, glow: [...COOL, .5]},
    'yokohama': {air: 3, glow: [...COOL, .5]},
    'sydney': {air: 3.5, glow: [...GOLD, .44]},
    'melbourne': {air: 3, glow: [...GOLD, .42]},
    'istanbul': {air: 4, glow: [...WARM, .5]},
    'toronto': {air: 4, glow: [...WARM, .48]},
    'vancouver': {air: 2.6, glow: [...GOLD, .4]},
    'montreal': {air: 2.6, glow: [...WARM, .42]},
    'rio': {air: 2.8, heli: .4, glow: [...WARM, .46]},
    'mexico-city': {air: 4.5, heli: .4, glow: [255, 170, 96, .55]},
    'bangkok': {air: 3.5, glow: [255, 214, 170, .52]},
    'kuala-lumpur': {air: 3, glow: [255, 226, 190, .52]},
    'ho-chi-minh': {air: 2.6, glow: [...WARM, .48]},
    'jakarta': {air: 3, glow: [...WARM, .5]},
    'mumbai': {air: 3.5, glow: [...WARM, .52]},
    'cairo': {air: 2.6, glow: [...WARM, .48]},
    'johannesburg': {air: 2.6, glow: [...WARM, .44]},
    'cape-town': {air: 2, glow: [...GOLD, .38]},
    'berlin': {air: 3, glow: [...WARM, .44]},
    'frankfurt': {air: 5, glow: [...WARM, .46]},
    'amsterdam': {air: 4, glow: [...WARM, .42]},
    'rotterdam': {air: 2, glow: [...WARM, .42]},
    'barcelona': {air: 3, glow: [...WARM, .44]},
    'madrid': {air: 3.5, glow: [...WARM, .46]},
    'lisbon': {air: 3, low: true, glow: [...GOLD, .42]},
    'milan': {air: 3, glow: [...WARM, .44]},
    'rome': {air: 3, glow: [...GOLD, .44]},
  };
  const QUIET = {air: .8, heli: 0, glow: [...WARM, .3]};

  // A small seeded generator, so a city's sky does not repeat in lockstep across reloads.
  function random(seed) {
    let s = seed >>> 0 || 1;
    return () => { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; return (s >>> 0) / 4294967296; };
  }
  function hash(text) { let h = 2166136261; for (const c of String(text)) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return h >>> 0; }

  function attach(container, scene, options = {}) {
    if (!(container instanceof Element)) throw new TypeError('KOnePulseV1 requires a container');
    const profile = CITIES[scene?.cityId] || QUIET;
    const canvas = document.createElement('canvas');
    canvas.setAttribute('aria-hidden', 'true');
    Object.assign(canvas.style, {position: 'absolute', inset: '0', width: '100%', height: '100%', pointerEvents: 'none'});
    container.appendChild(canvas);
    const ctx = canvas.getContext('2d', {alpha: true});
    const reducedQuery = matchMedia('(prefers-reduced-motion: reduce)');
    const maxFps = clamp(Number(options.maxFps) || 24, 1, 30);
    const safe = scene?.skySafe || {left: .1, right: .9, top: .15, bottom: .25};
    const horizon = clamp(finite(scene?.horizonY) ? scene.horizonY : .3, .12, .7);
    // Aircraft keep below the status bar and above the skyline.
    const skyTop = clamp(Math.min(safe.top, horizon - .12), .07, .4), skyBottom = clamp(Math.max(safe.bottom, skyTop + .05), skyTop + .04, horizon - .02);
    const rand = random(hash(scene?.id) ^ Math.floor(Date.now() / 60000));
    let playing = options.playing !== false, visible = true, destroyed = false;
    let width = 0, height = 0, dpr = 1, raf = 0, last = null, lastPaint = null, seconds = 0, frames = 0;
    let daylight = 1, craft = [], nextAir = 0, nextHeli = 0;
    // Connecting sends one sweep of light along the skyline; while connected the city glows a little warmer.
    let connected = false, lift = 0, liftTime = 0, sweepAt = null;

    const zone = scene?.cityZone || 'UTC';
    const localDate = () => {
      try { return new Intl.DateTimeFormat('en-US', {timeZone: zone, month: 'numeric', day: 'numeric'}).format(new Date()); } catch (_) { return ''; }
    };
    const tribute = profile.tribute && localDate() === '9/11';

    function spawnAir(prefill) {
      const rightward = rand() < .5, low = profile.low ? rand() < .7 : rand() < .25;
      const y = low ? skyBottom - rand() * .03 : skyTop + rand() * (skyBottom - skyTop) * .8;
      // Higher aircraft are farther away: smaller and slower across the screen.
      const depth = low ? .85 + rand() * .15 : .35 + rand() * .4;
      const duration = (low ? 26 : 44) / (.6 + depth * .5) * (.85 + rand() * .3);
      craft.push({
        kind: 'air', born: seconds - (prefill ? rand() * duration : 0), duration, rightward, y,
        drift: (low ? .018 : -.006 + rand() * .012) * (rand() < .5 ? 1 : -1), depth,
        landing: low && rand() < .6, phase: rand() * 10,
      });
    }
    function spawnHeli(prefill) {
      const y = horizon - .015 - rand() * .045, duration = 50 + rand() * 40;
      craft.push({kind: 'heli', born: seconds - (prefill ? rand() * duration : 0), duration, rightward: rand() < .5, y, drift: 0, phase: rand() * 10, depth: .9, light: rand() < .35});
    }
    const gap = rate => rate > 0 ? 60 / rate * (.5 + rand()) : Infinity;
    function seed() {
      craft = [];
      for (let i = 0; i < Math.round(profile.air * .6); i++) spawnAir(true);
      if ((profile.heli || 0) > .5) spawnHeli(true);
      nextAir = seconds + gap(profile.air); nextHeli = seconds + gap(profile.heli || 0);
    }

    const at = (x, y) => [x * width, y * height];
    function dot(x, y, r, color, alpha) {
      if (alpha <= 0.01) return;
      ctx.globalAlpha = alpha; ctx.fillStyle = color;
      ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill();
    }
    function halo(x, y, r, rgb, alpha) {
      if (alpha <= 0.01) return;
      const g = ctx.createRadialGradient(x, y, 0, x, y, r);
      g.addColorStop(0, `rgba(${rgb},${alpha})`); g.addColorStop(1, `rgba(${rgb},0)`);
      ctx.globalAlpha = 1; ctx.fillStyle = g; ctx.fillRect(x - r, y - r, r * 2, r * 2);
    }

    function drawGlow(night) {
      const [r, g, b, strength] = profile.glow;
      const breath = 1 + .06 * Math.sin(seconds / 47 * TAU);
      const a = strength * night * breath * (1 + .3 * lift);
      if (a <= .01) return;
      const [, hy] = at(0, horizon), band = height * .16;
      const grad = ctx.createLinearGradient(0, hy - band, 0, hy + band * .5);
      grad.addColorStop(0, `rgba(${r},${g},${b},0)`);
      grad.addColorStop(.62, `rgba(${r},${g},${b},${a * .34})`);
      grad.addColorStop(1, `rgba(${r},${g},${b},0)`);
      ctx.globalCompositeOperation = 'screen'; ctx.globalAlpha = 1;
      ctx.fillStyle = grad; ctx.fillRect(0, hy - band, width, band * 1.5);
      ctx.globalCompositeOperation = 'source-over';
    }

    function beam(baseX, angle, length, spread, rgb, alpha) {
      if (alpha <= .01) return;
      const [bx, by] = at(baseX, horizon + .01);
      const tipX = bx + Math.sin(angle) * length, tipY = by - Math.cos(angle) * length;
      const half = Math.tan(spread) * length;
      const nx = Math.cos(angle) * half, ny = Math.sin(angle) * half;
      const grad = ctx.createLinearGradient(bx, by, tipX, tipY);
      grad.addColorStop(0, `rgba(${rgb},0)`);
      grad.addColorStop(.12, `rgba(${rgb},${alpha})`);
      grad.addColorStop(1, `rgba(${rgb},0)`);
      ctx.globalCompositeOperation = 'screen'; ctx.globalAlpha = 1; ctx.fillStyle = grad;
      ctx.beginPath(); ctx.moveTo(bx - 1, by); ctx.lineTo(tipX - nx, tipY - ny); ctx.lineTo(tipX + nx, tipY + ny); ctx.lineTo(bx + 1, by); ctx.closePath(); ctx.fill();
      ctx.globalCompositeOperation = 'source-over';
    }
    function drawBeams(night) {
      if (night <= .02) return;
      const length = height * (horizon + .02);
      for (let i = 0; i < (profile.beams || 0); i++) {
        const base = .2 + i * (.6 / Math.max(1, profile.beams - 1 || 1));
        const swing = Math.sin(seconds / (16 + i * 3.7) * TAU + i * 2.1) * .32;
        beam(base, swing, length, .018, '236,240,255', .13 * night);
      }
      if (profile.luxor) beam(.42, 0, length, .006, '255,248,226', .3 * night);
      if (tribute) { beam(.47, -.01, length, .01, '214,228,255', .26 * night); beam(.53, .01, length, .01, '214,228,255', .26 * night); }
    }

    function drawCraft(c, night, day) {
      const p = (seconds - c.born) / c.duration;
      if (p < 0 || p > 1) return false;
      const x = c.rightward ? -.04 + p * 1.08 : 1.04 - p * 1.08;
      const y = c.y + c.drift * p;
      const [px, py] = at(x, y);
      const fade = smooth(0, .06, p) * (1 - smooth(.94, 1, p));
      const s = Math.max(.6, Math.min(width, 420) / 420) * c.depth;
      if (c.kind === 'heli') {
        const bob = Math.sin((seconds + c.phase) * 1.3) * 1.2 * s;
        if (night > .02) {
          const beacon = (Math.sin((seconds + c.phase) * TAU * .9) > .55) ? 1 : .15;
          dot(px, py + bob, 1.3 * s, '#ff5a4a', fade * night * beacon);
          const strobe = ((seconds + c.phase) % 1.6) < .06 ? 1 : 0;
          dot(px + 2 * s, py + bob, 1.1 * s, '#ffffff', fade * night * strobe);
          if (c.light) {
            const g = ctx.createLinearGradient(px, py + bob, px, py + bob + height * .05);
            g.addColorStop(0, `rgba(255,248,230,${.16 * fade * night})`); g.addColorStop(1, 'rgba(255,248,230,0)');
            ctx.globalAlpha = 1; ctx.fillStyle = g;
            ctx.beginPath(); ctx.moveTo(px - 1, py + bob); ctx.lineTo(px - 9 * s, py + bob + height * .05); ctx.lineTo(px + 9 * s, py + bob + height * .05); ctx.lineTo(px + 1, py + bob); ctx.closePath(); ctx.fill();
          }
        }
        if (day > .02) {
          ctx.globalAlpha = .55 * fade * day; ctx.fillStyle = '#2a2e33';
          ctx.fillRect(px - 2.4 * s, py + bob - 1 * s, 4.8 * s, 2 * s);
          ctx.fillRect(px - 4.5 * s, py + bob - 2 * s, 9 * s, .6 * s);
        }
        return true;
      }
      if (day > .02) {
        // Contrails from the high, far aircraft; low ones fly clean.
        if (!c.landing && c.depth < .7) {
          const [tx, ty] = at(c.rightward ? x - .22 : x + .22, y - c.drift * .2);
          const g = ctx.createLinearGradient(px, py, tx, ty);
          g.addColorStop(0, `rgba(255,255,255,${.5 * fade * day})`); g.addColorStop(1, 'rgba(255,255,255,0)');
          ctx.globalAlpha = 1; ctx.strokeStyle = g; ctx.lineWidth = 1.4 * s; ctx.lineCap = 'round';
          ctx.beginPath(); ctx.moveTo(px, py); ctx.lineTo(tx, ty); ctx.stroke();
        }
        const dir = c.rightward ? 1 : -1;
        ctx.globalAlpha = .6 * fade * day; ctx.fillStyle = '#30343a';
        ctx.beginPath();
        ctx.moveTo(px + 4 * s * dir, py); ctx.lineTo(px - 4 * s * dir, py - .5 * s); ctx.lineTo(px - 4 * s * dir, py + .5 * s); ctx.closePath(); ctx.fill();
        ctx.fillRect(px - 1.2 * s, py - 2.6 * s, 1.6 * s, 5.2 * s);
      }
      if (night > .02) {
        const t = seconds + c.phase;
        if (c.landing) halo(px, py, 7 * s, '255,250,236', .55 * fade * night);
        dot(px, py, (c.landing ? 1.6 : 1) * s, '#fffaf0', (c.landing ? .95 : .55) * fade * night);
        const beacon = (Math.sin(t * TAU * .8) > .6) ? 1 : .1;
        dot(px - (c.rightward ? 3 : -3) * s, py + .6 * s, 1.1 * s, '#ff4d3d', beacon * fade * night);
        const strobe = (t % 1.3) < .05 || ((t % 1.3) > .16 && (t % 1.3) < .21) ? 1 : 0;
        dot(px + (c.rightward ? 2.2 : -2.2) * s, py, 1.2 * s, '#ffffff', strobe * fade * night);
      }
      return true;
    }

    function drawSweep(night) {
      if (sweepAt === null) return;
      const t = (seconds - sweepAt) / 2.2;
      if (t < 0 || t > 1) { if (t > 1) sweepAt = null; return; }
      const [r, g, b] = profile.glow;
      const x = (-.2 + t * 1.4) * width, [, hy] = at(0, horizon), band = width * .22;
      const strength = (.2 + .35 * night) * Math.sin(t * Math.PI);
      const grad = ctx.createRadialGradient(x, hy, 0, x, hy, band);
      grad.addColorStop(0, `rgba(${r},${g},${b},${strength})`);
      grad.addColorStop(1, `rgba(${r},${g},${b},0)`);
      ctx.globalCompositeOperation = 'screen'; ctx.globalAlpha = 1; ctx.fillStyle = grad;
      ctx.fillRect(x - band, hy - band * .6, band * 2, band * 1.2);
      ctx.globalCompositeOperation = 'source-over';
    }

    function render() {
      if (!ctx || !width || !height) return;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, width, height);
      const night = clamp(1 - daylight), day = clamp(daylight);
      // Settle in about half a second at any frame rate; resize/sky redraws consume no motion.
      const liftDelta = Math.max(0, seconds - liftTime); liftTime = seconds;
      lift += ((connected ? 1 : 0) - lift) * -Math.expm1(-liftDelta / .18);
      drawGlow(night);
      drawSweep(night);
      drawBeams(night);
      craft = craft.filter(c => drawCraft(c, night, day));
      ctx.globalAlpha = 1;
    }

    const allowed = () => !reducedQuery.matches;
    const shouldRun = () => !destroyed && playing && visible && !document.hidden && allowed() && ctx && width > 0;
    function frame(now) {
      raf = 0;
      if (!shouldRun()) { last = null; return; }
      if (last !== null) seconds += Math.min(.25, (now - last) / 1000);
      last = now;
      if (seconds >= nextAir && profile.air > 0) { spawnAir(false); nextAir = seconds + gap(profile.air); }
      if (seconds >= nextHeli && (profile.heli || 0) > 0) { spawnHeli(false); nextHeli = seconds + gap(profile.heli); }
      if (lastPaint === null || now - lastPaint >= 1000 / maxFps) { lastPaint = now; frames++; render(); }
      raf = requestAnimationFrame(frame);
    }
    function updateLoop() {
      if (shouldRun()) { if (!raf) raf = requestAnimationFrame(frame); }
      else { if (raf) cancelAnimationFrame(raf); raf = 0; last = null; render(); }
    }
    function resize() {
      if (destroyed) return;
      const rect = container.getBoundingClientRect();
      width = Math.max(0, rect.width); height = Math.max(0, rect.height);
      dpr = Math.min(2, window.devicePixelRatio || 1);
      canvas.width = Math.max(1, Math.round(width * dpr)); canvas.height = Math.max(1, Math.round(height * dpr));
      render(); updateLoop();
    }
    const onChange = () => updateLoop();
    document.addEventListener('visibilitychange', onChange);
    reducedQuery.addEventListener?.('change', onChange);
    const observer = 'ResizeObserver' in window ? new ResizeObserver(resize) : null;
    observer?.observe(container);
    seed(); resize();
    return {
      setPlaying(value) { if (destroyed) return; playing = Boolean(value); updateLoop(); },
      setVisible(value) { if (destroyed) return; visible = Boolean(value); updateLoop(); },
      setSky(value) { if (destroyed) return; daylight = finite(value?.daylight) ? clamp(value.daylight) : daylight; if (!raf) render(); },
      setConnected(value) {
        if (destroyed) return;
        const next = Boolean(value);
        if (next !== connected) liftTime = seconds;
        if (next && !connected) sweepAt = seconds;
        connected = next; if (!raf) { lift = next ? 1 : 0; render(); }
      },
      destroy() {
        if (destroyed) return; destroyed = true; if (raf) cancelAnimationFrame(raf); raf = 0;
        observer?.disconnect(); document.removeEventListener('visibilitychange', onChange); reducedQuery.removeEventListener?.('change', onChange); canvas.remove();
      },
      get state() { return {city: scene?.cityId, profiled: Boolean(CITIES[scene?.cityId]), playing, visible, running: Boolean(raf), craft: craft.length, frames, daylight, tribute, connectionGlow: lift}; },
    };
  }
  window.KOnePulseV1 = {attach};
})();
