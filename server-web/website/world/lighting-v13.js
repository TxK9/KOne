(() => {
  'use strict';

  const clamp = (value, minimum = 0, maximum = 1) => Math.max(minimum, Math.min(maximum, value));
  const smooth = (a, b, value) => {
    const t = clamp((value - a) / (b - a));
    return t * t * (3 - 2 * t);
  };
  const valid = value => typeof value === 'number' && Number.isFinite(value);
  const inside = (x, y, polygon) => {
    let result = false;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      const [xi, yi] = polygon[i], [xj, yj] = polygon[j];
      if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) result = !result;
    }
    return result;
  };
  const safePolygons = value => Array.isArray(value) ? value.filter(polygon =>
    Array.isArray(polygon) && polygon.length > 2 && polygon.every(point =>
      Array.isArray(point) && point.length === 2 && point.every(n => valid(n) && n >= 0 && n <= 1))) : [];

  // Body positions come from the same sky sample that paints the sun and moon.
  // Angles are degrees, x/y are normalized viewport coordinates. No date or
  // separate astronomical approximation is evaluated in this renderer.
  function solarParameters(sky, theme) {
    const sun = sky?.sun || {};
    const altitude = valid(sun.altitudeDegrees) ? sun.altitudeDegrees : Number(sun.altitude);
    const daylight = valid(sky?.daylight) ? clamp(sky.daylight) : 0;
    const above = Number.isFinite(altitude) && altitude > 0;
    const strength = !above ? 0 : daylight *
      smooth(0, 4, altitude) * (0.44 + 0.56 * Math.sin(clamp(altitude, 0, 90) * Math.PI / 180));
    return {
      sun, altitude: Number.isFinite(altitude) ? altitude : null, daylight, strength,
      warm: above ? 1 - smooth(8, 48, altitude) : 0,
      inFrame: above && (sun.physicallyVisible === true || sky?.composition === true && sun.projection === 'art-composition') && sun.safe === true && sun.inFront === true && sun.inFrame === true && valid(sun.x) && valid(sun.y)
    };
  }

  function attach(container, scene, theme, image, options = {}) {
    if (!(container instanceof Element) || !(image instanceof HTMLImageElement)) {
      throw new TypeError('NycLightV13.attach needs a container and its source image');
    }
    const sceneId = scene?.id || '';
    const config = options.config?.scenes?.[sceneId] || options.config || {};
    const water = safePolygons(config.waterPolygons);
    const waterExclusions=safePolygons([...(config.waterExclusions||[]),...(config.islandPolygons||[]),...(config.occlusionPolygons||[]),...(config.marine?.exclusionPolygons||[])]);
    const canvas = document.createElement('canvas');
    canvas.className = 'nyc-lighting-canvas';
    canvas.setAttribute('aria-hidden', 'true');
    container.appendChild(canvas);
    const context = canvas.getContext('2d', {alpha: true});
    const surface = document.createElement('canvas');
    const surfaceContext = surface.getContext('2d', {alpha: true});
    const mask = document.createElement('canvas');
    const maskContext = mask.getContext('2d', {willReadFrequently: true});
    const reducedQuery = matchMedia('(prefers-reduced-motion: reduce)');
    const maxFps = clamp(Number(options.maxFps) || 24, 1, 24);
    const normalIntensity = value => value === 'soft' || value === 'subtle' ? 'soft' : value === 'expressive' ? 'expressive' : 'bold';
    let intensity = normalIntensity(options.intensity);
    let playing = options.playing !== false;
    let visible = true, intersecting = true, destroyed = false;
    let skyState = null, solar = solarParameters(null, theme);
    let environment = {wind:1, advection:0, gust:.5, cloudCover:.48, waveGroup:.5, shimmer:.5};
    let moonReflectionStrength = 0;
    let renderedSunStrength=0,renderedMoonStrength=0;
    let width = 0, height = 0, ratio = 1, cover = null;
    let time = 0, frames = 0, raf = 0, lastClock = null, lastPaint = null;
    let samplingStatus = 'pending', maskSource = null;
    let resizeObserver = null, intersectionObserver = null;

    function shouldRun() {
      return !destroyed && Boolean(context) && playing && visible && intersecting &&
        !document.hidden && (!reducedQuery.matches || options.allowReducedMotion === true) && Boolean(cover) && (solar.strength > 0 || moonReflectionStrength > 0);
    }
    function stop() {
      if (raf) cancelAnimationFrame(raf);
      raf = 0; lastClock = null; lastPaint = null;
    }
    function updateLoop() {
      if (!shouldRun()) { stop(); return; }
      if (!raf) raf = requestAnimationFrame(tick);
    }
    function tick(timestamp) {
      raf = 0;
      if (!shouldRun()) { lastClock = lastPaint = null; return; }
      if (lastClock !== null) time += Math.min(100, Math.max(0, timestamp - lastClock)) / 1000;
      lastClock = timestamp;
      if (lastPaint === null || timestamp - lastPaint >= 1000 / maxFps) {
        render(time);
        lastPaint = timestamp;
      }
      raf = requestAnimationFrame(tick);
    }

    function trace(polygon, ctx = context) {
      ctx.beginPath();
      polygon.forEach(([x, y], i) => {
        if (i === 0) ctx.moveTo(cover.x + x * cover.width, cover.y + y * cover.height);
        else ctx.lineTo(cover.x + x * cover.width, cover.y + y * cover.height);
      });
      ctx.closePath();
    }
    function clipWater(polygon){
      trace(polygon);context.clip();
      for(const hole of waterExclusions){
        context.beginPath();context.rect(0,0,width,height);
        hole.forEach(([x,y],i)=>{const px=cover.x+x*cover.width,py=cover.y+y*cover.height;i?context.lineTo(px,py):context.moveTo(px,py);});context.closePath();context.clip('evenodd');
      }
    }

    function sampleFacades() {
      if (!maskContext || !image.naturalWidth || theme === 'night') {
        samplingStatus = theme === 'night' ? 'nighttime' : 'unavailable'; return;
      }
      if (maskSource === (image.currentSrc || image.src) && samplingStatus !== 'pending') return;
      maskSource = image.currentSrc || image.src;
      mask.width = Math.min(480, image.naturalWidth);
      mask.height = Math.max(1, Math.round(mask.width * image.naturalHeight / image.naturalWidth));
      try {
        maskContext.drawImage(image, 0, 0, mask.width, mask.height);
        const original = maskContext.getImageData(0, 0, mask.width, mask.height);
        const output = maskContext.createImageData(mask.width, mask.height);
        const luminance = (x, y) => {
          const i = (y * mask.width + clamp(x, 0, mask.width - 1)) * 4;
          return (original.data[i] * 0.2126 + original.data[i + 1] * 0.7152 + original.data[i + 2] * 0.0722) / 255;
        };
        for (let y = 0; y < mask.height; y++) {
          const ny = (y + 0.5) / mask.height;
          const band = smooth(0.282, 0.324, ny) * (1 - smooth(0.61, 0.67, ny));
          if (band === 0) continue;
          for (let x = 0; x < mask.width; x++) {
            const nx = (x + 0.5) / mask.width;
            const lightness = luminance(x, y);
            // Midtones receive the wash, paper-white sky and dark creases do
            // not. Local contrast keeps fine facade texture visible.
            const midtone = smooth(0.28, 0.54, lightness) * (1 - smooth(0.90, 0.985, lightness));
            // A water polygon is a safe reflection region, not a complete
            // water segmentation. Cutting that polygon out of the wash made
            // hard unlit holes at twilight. Let image texture softly select
            // architectural edges instead of exposing polygon boundaries.
            const texture = smooth(0.006, 0.055, Math.abs(luminance(x + 2, y) - luminance(x - 2, y)));
            const i = (y * mask.width + x) * 4;
            output.data[i] = output.data[i + 1] = output.data[i + 2] = 255;
            output.data[i + 3] = Math.round(255 * band * midtone * texture);
          }
        }
        maskContext.putImageData(output, 0, 0);
        samplingStatus = 'sampled';
      } catch (_) {
        // A tainted source gets no facade wash, rather than a broad substitute.
        maskContext.clearRect(0, 0, mask.width, mask.height);
        samplingStatus = 'unavailable';
      }
    }

    function drawFacades(power, warm, seconds) {
      if (samplingStatus !== 'sampled' || !surfaceContext) return;
      const ctx = surfaceContext;
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      ctx.clearRect(0, 0, width, height);
      const azimuth = Number(solar.sun.azimuth);
      const bearing = Number(scene?.cameraBearing) || 0;
      const side = skyState?.composition&&solar.sun.projection==='art-composition'&&valid(solar.sun.x)?clamp((solar.sun.x-.5)*2,-1,1):Number.isFinite(azimuth) ? Math.sin((azimuth - bearing) * Math.PI / 180) :
        valid(solar.sun.x) ? (solar.sun.x - 0.5) * 2 : 0;
      const gradient = ctx.createLinearGradient(0, 0, width, 0);
      const r = 255, g = Math.round(249 - warm * 14), b = Math.round(228 - warm * 27);
      const base = power * 0.48;
      const left = base * (0.72 - side * 0.28), right = base * (0.72 + side * 0.28);
      gradient.addColorStop(0, `rgba(${r},${g},${b},${left})`);
      gradient.addColorStop(1, `rgba(${r},${g},${b},${right})`);
      ctx.fillStyle = gradient;
      ctx.fillRect(0, height * 0.25, width, height * 0.45);
      // Moving soft gaps in the warm wash suggest passing cloud cover.
      // The original texture shows through; this is not a reconstructed 3D shadow.
      ctx.globalCompositeOperation = 'destination-out';
      for (let i=0;i<3;i++) {
        const period = width*1.8, drift = environment.advection*width*.009*(.6+i*.13);
        const cx = ((i*.52*width+drift)%period)-width*.3;
        const cy = height*(.39+i*.075), rx = width*(.27+i*.035), ry = height*.11;
        for(const center of [cx-period,cx,cx+period]){
          ctx.save(); ctx.translate(center,cy); ctx.scale(rx,ry);
          const shadow = ctx.createRadialGradient(0,0,0,0,0,1);
          shadow.addColorStop(0,`rgba(0,0,0,${(.18+environment.cloudCover*.30)*(intensity==='bold'?1:.6)})`);
          shadow.addColorStop(1,'rgba(0,0,0,0)');ctx.fillStyle=shadow;ctx.fillRect(-1,-1,2,2);ctx.restore();
        }
      }
      ctx.globalCompositeOperation = 'destination-in';
      ctx.drawImage(mask, cover.x, cover.y, cover.width, cover.height);
      ctx.globalCompositeOperation = 'source-over';
      context.drawImage(surface, 0, 0, surface.width, surface.height, 0, 0, width, height);
    }

    function drawScattering(seconds, power, warm) {
      if (!solar.inFrame) return;
      const sx = solar.sun.x * width, sy = solar.sun.y * height;
      // These are broad, soft overlapping fans, rooted only at the real sun.
      // Light breathes with haze; the sun itself never drifts with the animation.
      const breathe = .76+.24*(1-environment.cloudCover);
      for (let i = 0; i < 2; i++) {
        const targetX = width * (0.5 + (0.5 - solar.sun.x) * 0.36 + (i - 0.5) * 0.16 + (intensity==='bold'?.04*Math.sin(environment.advection*.067+i*.7):0));
        const targetY = height * (0.54 + i * 0.035);
        const spread = width * (0.19 + i * 0.095);
        context.save();
        context.filter = `blur(${Math.max(5, width * 0.025)}px)`;
        context.beginPath();
        context.moveTo(sx - width * 0.012, sy);
        context.bezierCurveTo(sx - width * 0.035, sy + height * 0.08,
          targetX - spread, targetY - height * 0.11, targetX - spread, targetY);
        context.quadraticCurveTo(targetX, targetY + height * 0.04, targetX + spread, targetY);
        context.bezierCurveTo(targetX + spread, targetY - height * 0.11,
          sx + width * 0.035, sy + height * 0.08, sx + width * 0.012, sy);
        context.closePath();
        const gradient = context.createLinearGradient(sx, sy, targetX, targetY);
        const opacity = power * breathe * (0.12 + warm * 0.035);
        gradient.addColorStop(0, 'rgba(255,248,223,0)');
        gradient.addColorStop(0.22, `rgba(255,241,207,${opacity * 0.65})`);
        gradient.addColorStop(0.62, `rgba(255,236,195,${opacity})`);
        gradient.addColorStop(1, 'rgba(255,237,207,0)');
        context.fillStyle = gradient;
        context.fill();
        context.restore();
      }
    }

    function drawReflection(seconds, power, warm) {
      // Out-of-frame or behind-camera suns cannot acquire an invented reflection.
      if (!solar.inFrame || !water.length) return;
      const sx = solar.sun.x * width;
      water.forEach((polygon, index) => {
        const top = Math.max(height * 0.32, cover.y + Math.min(...polygon.map(p => p[1])) * cover.height);
        const bottom = Math.min(height * 0.675, cover.y + Math.max(...polygon.map(p => p[1])) * cover.height);
        if (bottom <= top) return;
        context.save(); clipWater(polygon);
        for (let y = top + 1; y < bottom; y += Math.max(2, width * 0.009)) {
          const distance = clamp((y - height * 0.3) / (height * 0.36));
          const phase = y * 0.17 + index * 2.1;
          const drift = Math.sin(phase + environment.advection * 0.8) * width * 0.008 * (.8+environment.waveGroup*.4);
          const halfWidth = width * (0.012 + distance * 0.072) * (0.7 + 0.3 * Math.sin(phase * 1.7));
          const x = sx + drift;
          const alpha = power * (0.17 + warm * 0.055) * (0.55 + 0.45 * Math.sin(phase + environment.advection * 0.7) ** 2);
          const gradient = context.createLinearGradient(x - halfWidth, y, x + halfWidth, y);
          gradient.addColorStop(0, 'rgba(255,245,212,0)');
          gradient.addColorStop(0.5, `rgba(255,246,216,${alpha})`);
          gradient.addColorStop(1, 'rgba(255,245,212,0)');
          context.strokeStyle = gradient;
          context.lineWidth = Math.max(0.5, width * 0.0025);
          context.beginPath(); context.moveTo(x - halfWidth, y); context.lineTo(x + halfWidth, y); context.stroke();
        }
        context.restore();
      });
    }

    function updateMoonStrength() {
      const moon=skyState?.moon;
      const dark=valid(skyState?.daylight)?1-clamp(skyState.daylight):theme==='night'?1:0;
      moonReflectionStrength = moon?.visible === true && (moon.physicallyVisible === true || skyState?.composition === true && moon.projection === 'art-composition') && moon.safe === true &&
        valid(moon.x) && valid(moon.y) && water.length && solar.altitude < -6
        ? dark * clamp(Number(skyState.illumination)||0) * smooth(0,14,Number(moon.altitude)||0) * .075 : 0;
    }
    function drawMoonReflection(seconds) {
      if (!(moonReflectionStrength>0)) return;
      const sx=skyState.moon.x*width, gain=intensity==='bold'?1.95:intensity==='expressive'?1:.5;
      context.save();context.globalCompositeOperation='screen';
      for (const [index,polygon] of water.entries()) {
        const top=Math.max(height*.3,cover.y+Math.min(...polygon.map(p=>p[1]))*cover.height);
        const bottom=Math.min(height*.68,cover.y+Math.max(...polygon.map(p=>p[1]))*cover.height);
        context.save();clipWater(polygon);
        for(let y=top+1;y<bottom;y+=Math.max(2.5,width*.011)) {
          const depth=clamp((y-top)/Math.max(1,bottom-top)), phase=y*.19+index*1.2;
          const x=sx+Math.sin(phase+environment.advection*.72)*width*.012;
          const half=width*(.012+depth*.055)*(.8+.2*Math.sin(phase*1.7));
          const transmission=valid(environment.transmission)?clamp(environment.transmission):1;
          const a=moonReflectionStrength*transmission*(valid(environment.fogVisibility)?environment.fogVisibility:1)*gain*(.32+.68*Math.sin(phase+environment.advection*.58)**2);
          const g=context.createLinearGradient(x-half,y,x+half,y);
          g.addColorStop(0,'rgba(181,210,225,0)');g.addColorStop(.5,`rgba(195,220,231,${a})`);g.addColorStop(1,'rgba(181,210,225,0)');
          context.strokeStyle=g;context.lineWidth=Math.max(.6,width*.0025);context.beginPath();context.moveTo(x-half,y);context.lineTo(x+half,y);context.stroke();
        }
        context.restore();
      }
      context.restore();
    }

    function render(seconds) {
      if (destroyed || !context || !cover || width <= 0 || height <= 0 ||
          !visible || !intersecting || document.hidden) return;
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      context.clearRect(0, 0, width, height);
      const suppliedTime=options.environmentTime?.();
      const envTime=valid(suppliedTime)?suppliedTime:seconds;
      environment=window.KOneEnvironmentV10?.sample(sceneId,envTime)||{wind:1,advection:envTime,gust:.5,cloudCover:.48,waveGroup:.5,shimmer:.5};
      const transmission=valid(environment.transmission)?clamp(environment.transmission):(.58+.42*(1-environment.cloudCover));
      const fogVisibility=valid(environment.fogVisibility)?environment.fogVisibility:1;
      const power = solar.strength * transmission * fogVisibility * (intensity === 'bold' ? 2.25 : intensity === 'expressive' ? 1 : 0.48);
      renderedSunStrength=power;renderedMoonStrength=moonReflectionStrength*transmission*fogVisibility;
      if (power > 0 || moonReflectionStrength > 0) {
        context.save();
        context.beginPath(); context.rect(0, height * 0.13, width, height * 0.55); context.clip();
        if(power>0){drawScattering(seconds, power, solar.warm);drawFacades(power, solar.warm, seconds);drawReflection(seconds, power, solar.warm);}
        if(fogVisibility>0)drawMoonReflection(seconds);
        context.restore();
        // Feather both edges into the paper and leave UI areas fully clear.
        context.globalCompositeOperation = 'destination-in';
        const edge = context.createLinearGradient(0, height * 0.13, 0, height * 0.68);
        edge.addColorStop(0, 'rgba(0,0,0,0)'); edge.addColorStop(0.06, '#000');
        edge.addColorStop(0.88, '#000'); edge.addColorStop(1, 'rgba(0,0,0,0)');
        context.fillStyle = edge; context.fillRect(0, height * 0.13, width, height * 0.55);
        context.globalCompositeOperation = 'source-over';
      }
      frames += 1;
    }

    function updateSize() {
      if (destroyed) return;
      const rect = container.getBoundingClientRect();
      width = Math.max(0, rect.width); height = Math.max(0, rect.height);
      ratio = Math.min(2, window.devicePixelRatio || 1, 720 / Math.max(width, 1));
      const pixelWidth = Math.max(1, Math.round(width * ratio)), pixelHeight = Math.max(1, Math.round(height * ratio));
      if (canvas.width !== pixelWidth) canvas.width = pixelWidth;
      if (canvas.height !== pixelHeight) canvas.height = pixelHeight;
      if (surface.width !== pixelWidth) surface.width = pixelWidth;
      if (surface.height !== pixelHeight) surface.height = pixelHeight;
      if (image.naturalWidth && image.naturalHeight && width && height) {
        const scale = Math.max(width / image.naturalWidth, height / image.naturalHeight);
        const position = getComputedStyle(image).objectPosition.split(/\s+/);
        const fraction = (part, fallback) => /^-?[\d.]+%$/.test(part || '') ? parseFloat(part) / 100 :
          part === 'left' || part === 'top' ? 0 : part === 'right' || part === 'bottom' ? 1 : fallback;
        const iw = image.naturalWidth * scale, ih = image.naturalHeight * scale;
        cover = {scale, width: iw, height: ih, x: (width - iw) * fraction(position[0], 0.5),
          y: (height - ih) * fraction(position[1], 0.5)};
      } else cover = null;
      render(time); updateLoop();
    }
    function onLoad() {
      maskSource = null; samplingStatus = 'pending'; sampleFacades(); updateSize();
    }
    function onError() {
      cover = null; samplingStatus = 'image-error'; stop();
      if (context) { context.setTransform(1, 0, 0, 1, 0, 0); context.clearRect(0, 0, canvas.width, canvas.height); }
    }
    const onVisibility = () => { if (!document.hidden) render(time); updateLoop(); };
    const onReduced = () => { render(time); updateLoop(); };
    image.addEventListener('load', onLoad); image.addEventListener('error', onError);
    document.addEventListener('visibilitychange', onVisibility);
    (reducedQuery.addEventListener?reducedQuery.addEventListener('change',onReduced):reducedQuery.addListener(onReduced));
    window.addEventListener('resize', updateSize, {passive: true});
    if ('ResizeObserver' in window) { resizeObserver = new ResizeObserver(updateSize); resizeObserver.observe(container); }
    if ('IntersectionObserver' in window) {
      intersecting = false;
      intersectionObserver = new IntersectionObserver(entries => {
        intersecting = entries.some(entry => entry.isIntersecting);
        if (intersecting) render(time);
        updateLoop();
      }, {threshold: 0});
      intersectionObserver.observe(container);
    }
    if (image.complete && image.naturalWidth) onLoad(); else updateSize();

    return {
      setSky(value) { skyState = value || null; solar = solarParameters(skyState, theme); updateMoonStrength(); render(time); updateLoop(); },
      setIntensity(value) { intensity = normalIntensity(value); render(time); },
      setPlaying(value) { playing = Boolean(value); updateLoop(); },
      setVisible(value) { visible = Boolean(value); if (visible) updateSize(); else updateLoop(); },
      renderAt(seconds) {
        if (destroyed || !valid(seconds) || seconds < 0) return;
        stop(); playing = false; time = seconds; render(time);
      },
      destroy() {
        if (destroyed) return;
        destroyed = true; stop(); resizeObserver?.disconnect(); intersectionObserver?.disconnect();
        image.removeEventListener('load', onLoad); image.removeEventListener('error', onError);
        document.removeEventListener('visibilitychange', onVisibility); (reducedQuery.removeEventListener?reducedQuery.removeEventListener('change',onReduced):reducedQuery.removeListener(onReduced));
        window.removeEventListener('resize', updateSize); canvas.remove();
        mask.width = mask.height = surface.width = surface.height = 1; cover = null; skyState = null;
      },
      get state() {
        return {sceneId, theme, intensity, playing, visible, intersecting, destroyed,
          pageVisible: !document.hidden, reducedMotion: reducedQuery.matches, running: Boolean(raf) && shouldRun(),
          time, frames, maxFps, width, height, pixelWidth: canvas.width, pixelHeight: canvas.height, dpr: ratio,
          directSunStrength: solar.strength,renderedSunStrength,renderedMoonStrength,sunAltitudeDegrees: solar.altitude, sunInFrame: solar.inFrame,
          moonReflectionStrength, environment:{...environment}, effects:['cloud-filtered-sunlight','soft-moving-light-patches','phase-aware-moon-reflection'],
          waterPolygonCount: water.length, samplingStatus, imageTransform: cover ? {...cover} : null,
          limitation: 'Painterly directional illumination is an artistic 2D approximation; it does not reconstruct building geometry or cast physical shadows.'};
      }
    };
  }

  window.NycLightV13 = {attach, solarParameters};
})();
