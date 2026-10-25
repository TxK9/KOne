/* KOne weather v13: deterministic atmospheric layers on the original artwork.
 * This renderer never edits or samples wallpaper pixels. Precipitation is
 * atmospheric; water impacts and architectural drips require reviewed geometry.
 * The caller owns weather transitions and labels simulated versus live data.
 */
(() => {
  'use strict';
  const TAU = Math.PI * 2;
  const finite = n => typeof n === 'number' && Number.isFinite(n);
  const clamp = (n, a = 0, b = 1) => Math.max(a, Math.min(b, n));
  const wrap = n => ((n % 1) + 1) % 1;
  const smooth = n => { const x = clamp(n); return x * x * (3 - 2 * x); };
  const point = p => Array.isArray(p) && p.length === 2 && p.every(n => finite(n) && n >= 0 && n <= 1);
  const polygons = list => Array.isArray(list) ? list.filter(p => Array.isArray(p) && p.length >= 3 && p.every(point)) : [];
  const seedOf = value => [...String(value)].reduce((n, c) => Math.imul(n ^ c.charCodeAt(0), 16777619), 2166136261) >>> 0;
  const random = seed => { let n = seed; return () => { n = (Math.imul(n, 1664525) + 1013904223) >>> 0; return n / 4294967296; }; };
  const inside = (x, y, shape) => {
    let yes = false;
    for (let i = 0, j = shape.length - 1; i < shape.length; j = i++) {
      const [a, b] = shape[i], [c, d] = shape[j];
      if ((b > y) !== (d > y) && x < (c - a) * (y - b) / (d - b) + a) yes = !yes;
    }
    return yes;
  };
  const normalizeProfile = raw => {
    const p = raw && typeof raw === 'object' ? raw : {};
    const amount = key => finite(p[key]) ? clamp(p[key]) : 0;
    return {id: String(p.id || 'clear'), source: String(p.source || 'manual'), cloudCover: amount('cloudCover'),
      rain: amount('rain'), snow: amount('snow'), fog: amount('fog'), wetness: amount('wetness'),
      rainbow: amount('rainbow'), lightning: amount('lightning'), wind: finite(p.wind) ? clamp(p.wind, .5, 2) : 1,
      temperature: finite(p.temperature) ? clamp(p.temperature, -70, 65) : null};
  };

  function attach(container, scene, theme, image, options = {}) {
    if (!(container instanceof Element) || !(image instanceof HTMLImageElement)) {
      throw new TypeError('KOneWeatherV13.attach needs a container and source image');
    }
    const sceneId = scene?.id || '';
    const config = options.config?.scenes?.[sceneId] || {};
    const weatherConfig = config.weather && typeof config.weather === 'object' ? config.weather : {};
    const water = polygons(config.waterPolygons);
    const holes = polygons([...(config.waterExclusions || []),...(config.islandPolygons||[]),...(config.occlusionPolygons||[]), ...(config.marine?.exclusionPolygons || [])]);
    const rainHoles = polygons(weatherConfig.precipitationExclusions);
    const fogMasks = polygons(weatherConfig.fogPolygons || config.mistPolygons);
    const dripAnchors = Array.isArray(weatherConfig.dripAnchors) ? weatherConfig.dripAnchors.filter(a => point(a?.point)) : [];
    const canvas = document.createElement('canvas');
    canvas.className = 'kone-weather-canvas'; canvas.setAttribute('aria-hidden', 'true');
    container.appendChild(canvas);
    const ctx = canvas.getContext('2d', {alpha: true});
    const media = matchMedia('(prefers-reduced-motion: reduce)');
    const maxFps = clamp(Number(options.maxFps) || 24, 1, 30), interval = 1000 / maxFps;
    let playing = options.playing !== false, visible = options.visible !== false, intersecting = true, destroyed = false;
    let intensity = ['soft', 'expressive'].includes(options.intensity) ? options.intensity : 'bold';
    let width = 0, height = 0, dpr = 1, cover = null, sky = null;
    let raf = 0, clock = null, lastPaint = null, seconds = 0, frames = 0;
    let resizeObserver = null, intersectionObserver = null;
    let effectiveProfile = normalizeProfile(null), rainCount = 0, snowCount = 0, rings = 0, drips = 0, fogStrength = 0;
    let sampleTime = 0, weatherStatus = 'uninitialized', lightningStrength = 0, rainbowStrength = 0;
    const rnd = random(seedOf(sceneId + ':weather-v11'));
    const drops = Array.from({length: 210}, (_, i) => ({x: rnd(), y: rnd(), phase: rnd() * TAU,
      depth: .15 + .85 * (i % 3) / 2, speed: .38 + rnd() * .38, drift: .015 + rnd() * .025, alpha: .4 + rnd() * .6}));
    const flakes = Array.from({length: 125}, () => ({x: rnd(), y: rnd(), phase: rnd() * TAU,
      depth: .2 + rnd() * .8, speed: .012 + rnd() * .025, drift: .007 + rnd() * .015, period: 3 + rnd() * 5}));
    const ringPoints = [];
    const waterBoxes = water.map(p => ({l: Math.min(...p.map(v => v[0])), r: Math.max(...p.map(v => v[0])),
      t: Math.min(...p.map(v => v[1])), b: Math.max(...p.map(v => v[1]))}));
    // Allocate across all water regions, with a strict global budget. Acceptance
    // checks are done once; per-frame rings remain clipped at every shoreline.
    for (let attempts = 0; water.length && ringPoints.length < 64 && attempts < 6400; attempts++) {
      const region = Math.floor(rnd() * water.length), box = waterBoxes[region];
      const x = box.l + rnd() * (box.r - box.l), y = box.t + rnd() * (box.b - box.t);
      if (inside(x, y, water[region]) && !holes.some(p => inside(x, y, p))) {
        ringPoints.push({x, y, phase: rnd(), period: .7 + rnd() * .85, scale: .6 + rnd() * .8});
      }
    }

    const strength = () => intensity === 'bold' ? 1 : intensity === 'expressive' ? .72 : .38;
    const motionAllowed = () => !media.matches || options.allowReducedMotion === true;
    const canPaint = () => !destroyed && Boolean(ctx) && visible && intersecting && !document.hidden && width > 0 && height > 0 && Boolean(cover);
    const shouldRun = () => canPaint() && playing && motionAllowed();
    function stop() { if (raf) cancelAnimationFrame(raf); raf = 0; clock = lastPaint = null; }
    function updateLoop() { if (!shouldRun()) stop(); else if (!raf) { clock = lastPaint = null; raf = requestAnimationFrame(tick); } }
    function tick(timestamp) {
      raf = 0;
      if (!shouldRun()) { clock = lastPaint = null; return; }
      if (clock !== null) seconds += clamp(timestamp - clock, 0, 100) / 1000;
      clock = timestamp;
      if (lastPaint === null || timestamp - lastPaint + .01 >= interval) { render(seconds); lastPaint = timestamp; }
      raf = requestAnimationFrame(tick);
    }
    function at(x, y) { return [cover.x + x * cover.width, cover.y + y * cover.height]; }
    function appendPolygon(polygon) {
      polygon.forEach(([x, y], i) => { const p = at(x, y); if (i) ctx.lineTo(...p); else ctx.moveTo(...p); }); ctx.closePath();
    }
    function exclude(shapes) {
      shapes.forEach(p => { ctx.beginPath(); ctx.rect(0, 0, width, height); appendPolygon(p); ctx.clip('evenodd'); });
    }
    function quietClip() { ctx.beginPath(); ctx.rect(0, height * .13, width, height * .57); ctx.clip(); }
    function sample(time) {
      let shared = time, raw = null;
      try { const supplied = options.environmentTime?.(); if (finite(supplied)) shared = supplied; } catch (_) { /* Local pause-aware clock. */ }
      try { raw = options.getWeather?.(shared); weatherStatus = raw && typeof raw === 'object' ? 'provided' : 'neutral'; }
      catch (_) { weatherStatus = 'unavailable'; }
      sampleTime = shared; effectiveProfile = normalizeProfile(raw); return effectiveProfile;
    }
    function daylight() { return finite(sky?.daylight) ? clamp(sky.daylight) : theme === 'night' || theme === 'dark' ? 0 : 1; }

    function drawAtmosphere(time, p, energy) {
      const day = daylight(), rainMass = p.rain * .6 + p.snow * .22;
      const darkness = p.cloudCover * (.035 + .20 * rainMass) * energy;
      // A transparent vertical wash keeps the actual watercolor texture visible.
      const shade = ctx.createLinearGradient(0, height * .13, 0, height * .64);
      shade.addColorStop(0, `rgba(67,79,85,${darkness})`); shade.addColorStop(.46, `rgba(86,99,105,${darkness * .6})`); shade.addColorStop(1, 'rgba(86,99,105,0)');
      ctx.fillStyle = shade; ctx.fillRect(0, height * .13, width, height * .53);
      fogStrength = clamp(p.fog * .27 + rainMass * .075 + p.wetness * (1 - p.rain) * .025) * energy;
      if (fogStrength > .0001) {
        ctx.save();
        if (fogMasks.length) { ctx.beginPath(); fogMasks.forEach(appendPolygon); ctx.clip(); }
        const cloudBottom = Array.isArray(scene?.cloudSkyBand) && finite(scene.cloudSkyBand[1]) ? scene.cloudSkyBand[1] : .267;
        const horizonY = at(.5, clamp(cloudBottom + .07, .27, .4))[1];
        const colors = day > .45 ? '217,220,214' : '118,134,143';
        for (let layer = 0; layer < 3; layer++) {
          const x = width * (.18 + layer * .34 + .07 * Math.sin(time * (.017 + layer * .004) + layer));
          const y = horizonY + height * (.012 * Math.sin(time * .025 + layer) + layer * .018);
          ctx.save(); ctx.translate(0, y); ctx.scale(1, .38); ctx.translate(0, -y);
          const haze = ctx.createRadialGradient(x, y, 0, x, y, width * (.49 + layer * .08));
          haze.addColorStop(0, `rgba(${colors},${fogStrength * (.58 - layer * .1)})`); haze.addColorStop(1, `rgba(${colors},0)`);
          ctx.fillStyle = haze;
          ctx.fillRect(0, y - width, width, width * 2); ctx.restore();
        }
        ctx.restore();
      }
      if (p.rain > .02) {
        // A localized, porous distant rain curtain; it does not blanket the city.
        const x = width * (.48 + .26 * Math.sin(time * .009 + (seedOf(sceneId) % 61)));
        ctx.save(); ctx.translate(x, height * .365); ctx.scale(.7, 1.35);
        const radius = width * .34;
        const curtain = ctx.createRadialGradient(0, 0, 0, 0, 0, radius);
        const opacity = p.rain * energy * (day > .3 ? .065 : .045);
        curtain.addColorStop(0, `rgba(111,133,143,${opacity})`); curtain.addColorStop(.6, `rgba(111,133,143,${opacity * .48})`); curtain.addColorStop(1, 'rgba(111,133,143,0)');
        ctx.fillStyle = curtain; ctx.fillRect(-radius, -radius, radius * 2, radius * 2); ctx.restore();
      }
    }
    function drawRain(time, p, energy) {
      rainCount = Math.round(210 * p.rain * energy);
      const day = daylight();
      for (let i = 0; i < rainCount; i++) {
        const drop = drops[i], x = (wrap(drop.x + time * drop.drift) * 1.18 - .09) * width;
        const y = (.1 + wrap(drop.y + time * drop.speed * (.6 + drop.depth * .8)) * .65) * height;
        const length = height * (.003 + drop.depth * .012) * (.6 + p.rain * .5)*(intensity==='bold'?1.18:1);
        const dx = length * (.18 + p.wind * .2 + .09 * Math.sin(time * .13 + drop.phase));
        const opacity = (.065 + drop.depth * .12) * drop.alpha * (.65 + p.rain * .35)*(intensity==='bold'?1.2:1);
        ctx.strokeStyle = day > .35 ? `rgba(110,135,148,${opacity})` : `rgba(187,204,207,${opacity * .95})`;
        ctx.lineWidth = .35 + drop.depth * .65; ctx.lineCap = 'round'; ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + dx, y + length); ctx.stroke();
      }
    }
    function drawSnow(time, p, energy) {
      snowCount = Math.round(125 * p.snow * energy);
      for (let i = 0; i < snowCount; i++) {
        const flake = flakes[i];
        const nx = wrap(flake.x + time * flake.drift + .023 * p.wind * Math.sin(time / flake.period + flake.phase));
        const y = (.1 + wrap(flake.y + time * flake.speed) * .65) * height;
        const radius = .45 + flake.depth * 1.4;
        ctx.fillStyle = `rgba(242,244,239,${.18 + flake.depth * .36})`; ctx.beginPath(); ctx.ellipse(nx * width, y, radius, radius * .85, 0, 0, TAU); ctx.fill();
      }
    }
    function drawRings(time, p, energy) {
      rings = 0;
      const amount = Math.round(ringPoints.length * p.rain * energy);
      if (!amount) return;
      ctx.save(); ctx.beginPath(); water.forEach(appendPolygon); ctx.clip(); exclude(holes);
      for (let i = 0; i < amount; i++) {
        const ring = ringPoints[i], life = wrap(time / ring.period + ring.phase);
        const [x, y] = at(ring.x, ring.y), radius = width * (.001 + .011 * life) * ring.scale;
        const alpha = .27 * (1 - life) * smooth(life * 9) * (.55 + .45 * daylight());
        ctx.strokeStyle = `rgba(220,232,229,${alpha})`; ctx.lineWidth = .5; ctx.beginPath(); ctx.ellipse(x, y, radius, radius * .29, -.035, 0, TAU); ctx.stroke();
        if (life < .2) { ctx.strokeStyle = `rgba(219,231,231,${(1 - life * 5) * .19})`; ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + radius * .1, y - width * .004 * (1 - life * 5)); ctx.stroke(); }
        if (y >= height * .13 && y <= height * .7) rings++;
      }
      ctx.restore();
    }
    function drawDrips(time, p, energy) {
      drips = 0;
      if (!dripAnchors.length || p.wetness < .02 || p.temperature!==null&&p.temperature<=0) return;
      dripAnchors.forEach((anchor, i) => {
        const life = wrap(time / (1.1 + i % 4 * .31) + (i * .618 % 1));
        if (life > p.wetness * .85) return;
        const [x, y] = at(...anchor.point);
        const length = finite(anchor.length) ? clamp(anchor.length, .003, .04) : .018;
        const fall = height * length * life * life;
        ctx.strokeStyle = `rgba(186,207,211,${.26 * energy * (1 - life * .5)})`; ctx.lineWidth = .65;
        ctx.beginPath(); ctx.moveTo(x, y + fall); ctx.lineTo(x + p.wind * fall * .08, y + fall + height * .002); ctx.stroke(); drips++;
      });
    }
    function drawSpecial(time, p, energy) {
      rainbowStrength = 0; lightningStrength = 0;
      const day = daylight();
      // A rainbow is an explicitly requested theme. Its placement is additionally
      // opt-in because the arc must be opposite the sun, not pasted arbitrarily.
      const arc = weatherConfig.rainbow;
      if (p.rainbow > 0 && day > .5 && point(arc?.center) && finite(arc?.radius) && arc.radius > 0) {
        const [x, y] = at(...arc.center), radius = cover.width * clamp(arc.radius, .05, .7);
        const hues = ['196,117,106', '208,169,111', '197,193,132', '149,181,158', '133,164,189', '165,148,183'];
        rainbowStrength = p.rainbow * day * energy * .09;
        hues.forEach((rgb, i) => { ctx.strokeStyle = `rgba(${rgb},${rainbowStrength})`; ctx.lineWidth = width * .0024;
          ctx.beginPath(); ctx.ellipse(x, y, radius - i * width * .0026, radius - i * width * .0026, 0, Math.PI, TAU); ctx.stroke(); });
      }
      if (p.lightning > 0 && p.rain > .15) {
        const phase = wrap(time / 37.7 + (seedOf(sceneId) % 100) / 100);
        const pulse = Math.max(0, 1 - Math.abs(phase - .025) / .004) + .45 * Math.max(0, 1 - Math.abs(phase - .035) / .002);
        lightningStrength = clamp(pulse * p.lightning * energy * .13, 0, .13);
        if (lightningStrength) { const flash = ctx.createLinearGradient(0, height * .13, 0, height * .52);
          flash.addColorStop(0, `rgba(229,235,241,${lightningStrength})`); flash.addColorStop(1, 'rgba(229,235,241,0)');
          ctx.fillStyle = flash; ctx.fillRect(0, height * .13, width, height * .39); }
      }
    }
    function render(time) {
      if (!canPaint()) return;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, width, height);
      const p = sample(time), energy = strength();
      ctx.save(); quietClip();
      drawAtmosphere(time, p, energy);
      ctx.save(); exclude(rainHoles); drawRain(time, p, energy); drawSnow(time, p, energy); ctx.restore();
      drawRings(time, p, energy); drawDrips(time, p, energy); drawSpecial(time, p, energy);
      ctx.restore();
      // Fade precipitation away before the dashboard and keep the logo quiet.
      ctx.save();ctx.globalCompositeOperation='destination-in';
      const edge=ctx.createLinearGradient(0,height*.13,0,height*.70);
      edge.addColorStop(0,'rgba(0,0,0,0)');edge.addColorStop(.07,'rgba(0,0,0,1)');
      edge.addColorStop(.73,'rgba(0,0,0,1)');edge.addColorStop(.91,'rgba(0,0,0,0)');edge.addColorStop(1,'rgba(0,0,0,0)');
      ctx.fillStyle=edge;ctx.fillRect(0,height*.13,width,height*.57);ctx.restore();frames++;
    }
    function updateSize() {
      if (destroyed) return;
      const rect = container.getBoundingClientRect(); width = Math.max(0, rect.width); height = Math.max(0, rect.height);
      dpr = Math.min(2, window.devicePixelRatio || 1, 540 / Math.max(1, width));
      const pw = Math.max(1, Math.round(width * dpr)), ph = Math.max(1, Math.round(height * dpr));
      if (canvas.width !== pw) canvas.width = pw; if (canvas.height !== ph) canvas.height = ph;
      if (image.naturalWidth && image.naturalHeight && width && height) {
        const scale = Math.max(width / image.naturalWidth, height / image.naturalHeight);
        const iw = image.naturalWidth * scale, ih = image.naturalHeight * scale;
        const position = getComputedStyle(image).objectPosition.split(/\s+/);
        const fraction = (part, fallback) => /^-?[\d.]+%$/.test(part || '') ? parseFloat(part) / 100 :
          part === 'left' || part === 'top' ? 0 : part === 'right' || part === 'bottom' ? 1 : fallback;
        cover = {x: (width - iw) * fraction(position[0], .5), y: (height - ih) * fraction(position[1], .5), width: iw, height: ih, scale};
      } else cover = null;
      render(seconds); updateLoop();
    }
    function imageError() { cover = null; stop(); if (ctx && visible && intersecting && !document.hidden) { ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.clearRect(0, 0, canvas.width, canvas.height); } }
    const onVisibility = () => { if (!document.hidden) render(seconds); updateLoop(); };
    const onReduced = () => updateLoop();
    image.addEventListener('load', updateSize); image.addEventListener('error', imageError);
    document.addEventListener('visibilitychange', onVisibility); media.addEventListener('change', onReduced);
    window.addEventListener('resize', updateSize, {passive: true});
    if ('ResizeObserver' in window) { resizeObserver = new ResizeObserver(updateSize); resizeObserver.observe(container); }
    if ('IntersectionObserver' in window) {
      intersecting = false; intersectionObserver = new IntersectionObserver(entries => {
        const was = intersecting; intersecting = entries.some(entry => entry.isIntersecting);
        if (intersecting && !was) render(seconds); updateLoop();
      }, {threshold: 0}); intersectionObserver.observe(container);
    }
    updateSize();
    return {
      setPlaying(value) { if (destroyed) return; playing = Boolean(value); updateLoop(); },
      setVisible(value) { if (destroyed) return; visible = Boolean(value); if (visible) updateSize(); else updateLoop(); },
      setIntensity(value) { if (destroyed) return; intensity = ['soft', 'expressive'].includes(value) ? value : 'bold'; if (!raf) render(seconds); },
      setSky(value) { if (destroyed) return; sky = value && typeof value === 'object' ? value : null; if (!raf) render(seconds); },
      renderAt(time) { if (destroyed || !finite(time) || time < 0) return; stop(); playing = false; seconds = time; render(seconds); },
      destroy() {
        if (destroyed) return; destroyed = true; stop(); resizeObserver?.disconnect(); intersectionObserver?.disconnect();
        image.removeEventListener('load', updateSize); image.removeEventListener('error', imageError);
        document.removeEventListener('visibilitychange', onVisibility); media.removeEventListener('change', onReduced);
        window.removeEventListener('resize', updateSize); canvas.remove(); cover = null;
      },
      get state() { return {sceneId, theme, playing, visible, intersecting, pageVisible: !document.hidden, destroyed,
        running: Boolean(raf) && shouldRun(), frames, time: seconds, sampleTime, intensity, maxFps,
        reducedMotion: media.matches, motionAllowed: motionAllowed(), width, height, dpr, pixelWidth: canvas.width, pixelHeight: canvas.height,
        effectiveProfile: {...effectiveProfile}, weatherStatus, rainCount, snowCount, rings, ringCandidates: ringPoints.length,
        waterRegions: water.length, waterExclusionRegions: holes.length, precipitationExclusions: rainHoles.length,
        dripAnchors: dripAnchors.length, drips, fogStrength, lightningStrength, rainbowStrength,
        quietBounds: [.13, .7], imageTransform: cover ? {...cover} : null}; }
    };
  }
  window.KOneWeatherV13 = {attach, normalizeProfile};
})();
