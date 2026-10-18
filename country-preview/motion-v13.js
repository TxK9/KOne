/* KOne motion v10: coherent weather, layered clouds, and independent room lights.
 * All water and window pixels are clipped to reviewed image-space polygons.
 * Clouds are procedural translucent pigment, never edited wallpaper pixels.
 */
(() => {
  'use strict';

  const TAU = Math.PI * 2;
  const MAX_DPR = 2;
  const MAX_CANVAS_WIDTH = 720;
  const clamp = (n, min = 0, max = 1) => Math.max(min, Math.min(max, n));
  const smooth = value => { const t = clamp(value); return t * t * (3 - 2 * t); };
  const wrap = (value, span) => ((value % span) + span) % span;
  const seedOf = value => {
    let seed = 2166136261;
    for (const char of String(value)) seed = Math.imul(seed ^ char.charCodeAt(0), 16777619);
    return seed >>> 0;
  };
  const unitHash = (seed, cell) => {
    let n = Math.imul(seed ^ Math.imul(cell, 374761393), 668265263);
    n = Math.imul(n ^ (n >>> 13), 1274126177);
    return ((n ^ (n >>> 16)) >>> 0) / 4294967295;
  };
  const inside = (x, y, polygon) => {
    let hit = false;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      const [xi, yi] = polygon[i], [xj, yj] = polygon[j];
      if (((yi > y) !== (yj > y)) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) hit = !hit;
    }
    return hit;
  };
  const validPolygons = value => Array.isArray(value) ? value.filter(polygon =>
    Array.isArray(polygon) && polygon.length >= 3 && polygon.every(point =>
      Array.isArray(point) && point.length === 2 && point.every(n => Number.isFinite(n) && n >= 0 && n <= 1)
    )
  ) : [];
  const bounds = polygon => ({
    left: Math.min(...polygon.map(p => p[0])), right: Math.max(...polygon.map(p => p[0])),
    top: Math.min(...polygon.map(p => p[1])), bottom: Math.max(...polygon.map(p => p[1]))
  });
  function horizontalSpans(polygon, y) {
    const crossings = [];
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      const [ax, ay] = polygon[j], [bx, by] = polygon[i];
      if ((ay <= y && y < by) || (by <= y && y < ay)) crossings.push(ax + (y - ay) * (bx - ax) / (by - ay));
    }
    crossings.sort((a, b) => a - b);
    const spans = [];
    for (let i = 0; i + 1 < crossings.length; i += 2) {
      if (crossings[i + 1] - crossings[i] > 1e-10) spans.push([crossings[i], crossings[i + 1]]);
    }
    return spans;
  }
  function intersectSpans(left, right) {
    const result = [];
    let a = 0, b = 0;
    while (a < left.length && b < right.length) {
      const start = Math.max(left[a][0], right[b][0]), end = Math.min(left[a][1], right[b][1]);
      if (end - start > 1e-10) result.push([start, end]);
      if (left[a][1] < right[b][1]) a++; else b++;
    }
    return result;
  }
  function subtractSpans(spans, cuts) {
    const result = [];
    for (const [start, end] of spans) {
      let cursor = start;
      for (const [cutStart, cutEnd] of cuts) {
        if (cutEnd <= cursor || cutStart >= end) continue;
        if (cutStart > cursor) result.push([cursor, Math.min(cutStart, end)]);
        cursor = Math.max(cursor, cutEnd);
        if (cursor >= end) break;
      }
      if (cursor < end) result.push([cursor, end]);
    }
    return result;
  }
  function sweptBoundarySpans(polygon, low, high) {
    const spans = [];
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      const [ax, ay] = polygon[j], [bx, by] = polygon[i];
      if (ay === by) {
        if (ay >= low && ay <= high) spans.push([Math.min(ax, bx), Math.max(ax, bx)]);
        continue;
      }
      const fromY = Math.max(low, Math.min(ay, by)), toY = Math.min(high, Math.max(ay, by));
      if (toY < fromY) continue;
      const fromX = ax + (fromY - ay) * (bx - ax) / (by - ay);
      const toX = ax + (toY - ay) * (bx - ax) / (by - ay);
      spans.push([Math.min(fromX, toX), Math.max(fromX, toX)]);
    }
    return spans.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  }
  function random(seed) {
    let n = seed >>> 0;
    return () => { n = (Math.imul(n, 1664525) + 1013904223) >>> 0; return n / 4294967296; };
  }

  function attach(container, scene, theme, sourceImage, options = {}) {
    if (!(container instanceof Element) || !(sourceImage instanceof HTMLImageElement)) {
      throw new TypeError('NycMotionV13.attach needs a container and its source image');
    }
    const sceneId = scene?.id || '';
    const config = options.config?.scenes?.[sceneId] || {};
    const water = validPolygons(config.waterPolygons);
    const waterExclusions = validPolygons([...(config.waterExclusions||[]),...(config.marine?.exclusionPolygons||[])]);
    const waterType = String(config.marine?.type || scene?.waterType || 'pool').toLowerCase();
    const waterNatures = {
      sea: [1,1], ocean: [1,1], coast: [1,1], bay: [1,1], strait: [1,1],
      harbour: [.54,.72], harbor: [.54,.72], estuary: [.55,.74], river: [.4,.65],
      canal: [.18,.4], lake: [.25,.46], lagoon: [.3,.5], marina: [.2,.42], pool: [.075,.22], pond: [.075,.22], land: [0,0],
    };
    const [waterEnergy, waterPace] = waterNatures[waterType] || waterNatures.pool;
    const cloudBands = [scene?.cloudSkyBand, config.cloudSkyBand].filter(band =>
      Array.isArray(band) && band.length === 2 && band.every(Number.isFinite) && band[1] > band[0]);
    const cloudBand = cloudBands.length ? [Math.max(.13, ...cloudBands.map(band => band[0])),
      Math.min(.267, ...cloudBands.map(band => band[1]))] : [.13,.267];
    const cloudExclusions = validPolygons([...(Array.isArray(scene?.skyExclusions) ? scene.skyExclusions : []),
      ...(Array.isArray(config.skyExclusions) ? config.skyExclusions : [])].map(shape =>
      Array.isArray(shape) ? shape : [[shape?.left, shape?.top], [shape?.right, shape?.top],
        [shape?.right, shape?.bottom], [shape?.left, shape?.bottom]]));
    const declaredLights = validPolygons(config.lightRegions);
    const autoLights = Array.from({length:8},(_,i)=>{const x=.04+(i%4)*.23,y=.30+Math.floor(i/4)*.135;return [[x,y],[x+.22,y],[x+.22,y+.125],[x,y+.125]];});
    const lights = declaredLights.length ? declaredLights : config.autoLights === false ? [] : autoLights;
    const mist = validPolygons(config.mistPolygons);
    const waterBoxes = water.map(bounds);
    const night = theme === 'night' || theme === 'dark';
    const blendImage = options.blendImage instanceof HTMLImageElement ? options.blendImage : null;
    const sourceIsNight = sourceImage.classList?.contains('wallpaper-night') ||
      (!sourceImage.classList?.contains('wallpaper-day') && night);
    const dayImage = sourceIsNight ? blendImage : sourceImage;
    const nightImage = sourceIsNight ? sourceImage : blendImage;
    const canvas = document.createElement('canvas');
    canvas.className = 'nyc-motion-canvas';
    canvas.setAttribute('aria-hidden', 'true');
    container.appendChild(canvas);
    const context = canvas.getContext('2d', {alpha: true});
    const reducedQuery = matchMedia('(prefers-reduced-motion: reduce)');
    const maxFps = clamp(Number(options.maxFps) || 30, 1, 30);
    const frameInterval = 1000 / maxFps;
    let intensity = ['soft','expressive'].includes(options.intensity) ? options.intensity : 'bold';
    let playing = options.playing !== false;
    let visible = true, intersecting = true, destroyed = false;
    let raf = 0, lastClock = null, lastPaint = null, time = 0, frames = 0;
    let width = 0, height = 0, ratio = 1, cover = null;
    let sampledLights = [], sampleSource = null;
    let samplingStatus = 'pending';
    let lightsDirty = true, waterBitmapDirty = true;
    let resizeObserver = null, intersectionObserver = null;
    let skyState = null;
    let waterBitmap = null, waterBlendCanvas = null, waterBlendKey = null;
    let waterSamplingKey = null, waterSamplingRows = [], waterSamplingBuilds = 0, waterSamplingSpanCount = 0;
    const waterCriticalYs = water.map(polygon => Array.from(new Set([polygon, ...waterExclusions]
      .flatMap(shape => shape.map(point => point[1])))).sort((a, b) => a - b));
    let waterBlendDaylight = night ? 0 : 1, waterBlendStatus = 'uninitialized';
    const sceneSeed = seedOf(sceneId);
    const cloudRandom = random(sceneSeed ^ 0x71ac49);
    const cloudBanks = Array.from({length: 4}, (_, index) => ({
      depth: .22 + index * .21,
      width: .39 + cloudRandom() * .35,
      height: .47 + cloudRandom() * .26,
      offset: cloudRandom() * 1.9 - .7,
      elevation: .02 + cloudRandom() * .25,
      phase: cloudRandom() * TAU,
      speed: .63 + cloudRandom() * .24 + index * .17,
    }));
    let environment = null, environmentSamples = 0, environmentStatus = 'uninitialized';

    function sampleEnvironment(seconds) {
      const shared=options.environmentTime?.();if(Number.isFinite(shared))seconds=shared;
      // Advection is integrated wind time. Multiplying elapsed time by an
      // instantaneous gust would teleport every cloud when the wind changes.
      let supplied = null;
      try { supplied = window.KOneEnvironmentV10?.sample(sceneId, seconds); } catch (_) { /* Use continuous local weather. */ }
      const phase = (sceneSeed % 6283) / 1000;
      const wind = 1 + .16 * Math.sin(seconds * .071 + phase) + .09 * Math.sin(seconds * .019 + phase * .7);
      const advection = seconds + .16 / .071 * (Math.cos(phase) - Math.cos(seconds * .071 + phase)) +
        .09 / .019 * (Math.cos(phase * .7) - Math.cos(seconds * .019 + phase * .7));
      const finite = (key, fallback, min, max) => Number.isFinite(supplied?.[key]) ?
        clamp(supplied[key], min, max) : fallback;
      environmentSamples += 1;
      environmentStatus = supplied && typeof supplied === 'object' ? 'provided' : 'fallback';
      return {
        wind: finite('wind', wind, .45, 1.65),
        advection: Number.isFinite(supplied?.advection) ? supplied.advection : advection,
        gust: finite('gust', .5 + .5 * Math.sin(seconds * .071 + phase), -1, 1),
        cloudCover: finite('cloudCover', .52 + .17 * Math.sin(seconds * .013 + phase), 0, 1),
        waveGroup: finite('waveGroup', .58 + .2 * Math.sin(seconds * .11 + phase), 0, 2),
        shimmer: finite('shimmer', .8 + .15 * Math.sin(seconds * .17 + phase), 0, 2),
        rain:finite('rain',0,0,1),snow:finite('snow',0,0,1),fog:finite('fog',0,0,1),
        fogVisibility:finite('fogVisibility',1,0,1),directVisibility:finite('directVisibility',1,0,1),
      };
    }

    // A porous noise field makes the edges curl and open into small gaps.
    // There is no horizontal band, opaque backing, or blue shadow colour.
    const pigmentSeeds = [11, 41, 83, 131, 173, 229];
    const createCloudTexture = (seed, paletteNight) => {
      const texture = document.createElement('canvas');
      texture.width = 192; texture.height = 96;
      const ctx = texture.getContext('2d');
      if (!ctx) return texture;
      // Reuse generated pigment between cards, keeping canvases independent so
      // disposing one preview never clears a sibling card's clouds.
      const pigmentCache = attach._cloudPigments || (attach._cloudPigments = new Map());
      const pigmentKey = `${paletteNight ? 'night' : 'day'}-${seed}`;
      if (pigmentCache.has(pigmentKey)) {
        ctx.putImageData(pigmentCache.get(pigmentKey), 0, 0);
        return texture;
      }
      const rnd = random(seed), color = paletteNight ? [121, 128, 132] : [183, 181, 174];
      const smooth = t => t * t * (3 - 2 * t);
      const lattice = (x, y) => {
        let n = Math.imul(x, 374761393) + Math.imul(y, 668265263) + seed * 1447;
        n = Math.imul(n ^ (n >>> 13), 1274126177);
        return ((n ^ (n >>> 16)) >>> 0) / 4294967295;
      };
      const noise = (x, y) => {
        const ix = Math.floor(x), iy = Math.floor(y), u = smooth(x - ix), v = smooth(y - iy);
        const a = lattice(ix, iy), b = lattice(ix + 1, iy), c = lattice(ix, iy + 1), d = lattice(ix + 1, iy + 1);
        return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
      };
      const lobeCount = 4 + seed % 4;
      const lobes = Array.from({length: lobeCount}, (_, i) => ({
        x: .17 + i * (.69 / (lobeCount - 1)) + (rnd() - .5) * .09,
        y: .39 + (rnd() - .5) * .2,
        rx: .095 + rnd() * .065, ry: .13 + rnd() * .13,
        strength: .65 + rnd() * .5,
      }));
      // Two uneven openings reveal the sky through the cloud, like dry pigment
      // breaking on paper. They are soft density depressions, never dark spots.
      const holes = Array.from({length: 1 + seed % 3}, () => ({x: .3 + rnd() * .4, y: .4 + rnd() * .2, rx: .045 + rnd() * .03, ry: .06 + rnd() * .05}));
      const pixels = ctx.createImageData(texture.width, texture.height);
      for (let py = 0; py < texture.height; py++) {
        for (let px = 0; px < texture.width; px++) {
          const x = px / texture.width, y = py / texture.height;
          // Warping the density field produces little rising folds and wisps;
          // a finer octave breaks up the edge without a smoky blurred rim.
          const warp = (noise(x * 4 + 2, y * 4 + 9) - .5) * .11;
          let mass = 0;
          for (const lobe of lobes) {
            const dx = (x - lobe.x) / lobe.rx, dy = (y + warp - lobe.y) / lobe.ry;
            mass = Math.max(mass, Math.exp(-(dx * dx + dy * dy) * 1.35) * lobe.strength);
          }
          let openings = 0;
          for (const hole of holes) {
            const dx = (x - hole.x) / hole.rx, dy = (y - hole.y) / hole.ry;
            openings += Math.exp(-(dx * dx + dy * dy) * 1.8) * .65;
          }
          const coarse = noise(x * 11 + 7, y * 7 + 3), fine = noise(x * 35 + 5, y * 23 + 17);
          const density = clamp((mass + (coarse - .5) * .47 + (fine - .5) * .12 - openings - .19) / .68);
          const edge = smooth(clamp(x / .1)) * smooth(clamp((1 - x) / .1)) *
            smooth(clamp(y / .12)) * smooth(clamp((1 - y) / .18));
          const alpha = smooth(density) * (.25 + coarse * .75) * edge * (paletteNight ? .27 : .38);
          const offset = (py * texture.width + px) * 4;
          pixels.data[offset] = color[0]; pixels.data[offset + 1] = color[1]; pixels.data[offset + 2] = color[2];
          pixels.data[offset + 3] = Math.round(alpha * 255);
        }
      }
      pigmentCache.set(pigmentKey, pixels);
      ctx.putImageData(pixels, 0, 0);
      return texture;
    };
    const cloudTextures = cloudBanks.map((_, index) => {
      const seed = pigmentSeeds[(sceneSeed % pigmentSeeds.length + index) % pigmentSeeds.length];
      return {day: createCloudTexture(seed, false), night: createCloudTexture(seed, true)};
    });
    const glintRandom = random(sceneSeed ^ 937);
    const waterGlints = water.map(() => Array.from({length: 48}, () => ({
      x: glintRandom(), y: glintRandom(), length: .012 + glintRandom() * .052,
      phase: glintRandom() * TAU, speed: .7 + glintRandom() * .8,
      period: 3.8 + glintRandom() * 5.2,
    })));

    function motionAllowed() { return !reducedQuery.matches || options.allowReducedMotion === true; }
    function shouldRun() {
      return !destroyed && Boolean(context) && playing && visible && intersecting &&
        !document.hidden && motionAllowed() && width > 0 && height > 0 && Boolean(cover);
    }
    function resetClock() { lastClock = null; lastPaint = null; }
    function stop() {
      if (raf) cancelAnimationFrame(raf);
      raf = 0; resetClock();
    }
    function updateLoop() {
      if (!shouldRun()) { stop(); return; }
      if (!raf) { resetClock(); raf = requestAnimationFrame(tick); }
    }
    function tick(timestamp) {
      raf = 0;
      if (!shouldRun()) { resetClock(); return; }
      // Resume without jumping; long stalls advance the art by at most 100 ms.
      if (lastClock !== null) time += clamp(timestamp - lastClock, 0, 100) / 1000;
      lastClock = timestamp;
      if (lastPaint === null || timestamp - lastPaint + .01 >= frameInterval) {
        render(time);
        // Use the actual presentation time. Floating-point remainder near an
        // exact interval can otherwise leave this clock one whole frame behind
        // and accidentally exceed the FPS ceiling on a 60 Hz display.
        lastPaint = timestamp;
      }
      raf = requestAnimationFrame(tick);
    }
    function appendPolygon(polygon) {
      polygon.forEach(([x, y], i) => {
        const px = cover.x + x * cover.width, py = cover.y + y * cover.height;
        if (i === 0) context.moveTo(px, py); else context.lineTo(px, py);
      });
      context.closePath();
    }
    function trace(polygon) { context.beginPath(); appendPolygon(polygon); }
    function excludePolygons(polygons) {
      if (!polygons.length) return;
      // Intersect the current clip with the complement of each exclusion.
      // Separate intersections also handle overlapping exclusion regions.
      polygons.forEach(polygon => {
        context.beginPath(); context.rect(0, 0, width, height); appendPolygon(polygon); context.clip('evenodd');
      });
    }
    function lightStrength() {
      if (!skyState) return .7 + waterBlendDaylight * .1;
      const nightStrength = clamp(.35 + Number(skyState.illumination || 0) * .65, .35, 1);
      const altitude = Number(skyState.sunAltitude ?? (skyState.body === 'sun' ? skyState.altitude : NaN));
      const dayStrength = Number.isFinite(altitude) ? clamp(Math.sin(altitude * Math.PI / 180) * .8 + .25, .12, 1) : .8;
      return nightStrength * (1 - waterBlendDaylight) + dayStrength * waterBlendDaylight;
    }
    function reflectionX() {
      const body=waterBlendDaylight>.5?skyState?.sun:skyState?.moon;
      return body?.visible&&body.safe&&body.aboveHorizon&&Number.isFinite(body.x)?clamp(body.x,-.2,1.2):null;
    }
    function imageCover(image) {
      if (!image?.naturalWidth || !image?.naturalHeight || !width || !height) return null;
      const scale = Math.max(width / image.naturalWidth, height / image.naturalHeight);
      const position = getComputedStyle(image).objectPosition.split(/\s+/);
      const fraction = value => /^-?[\d.]+%$/.test(value || '') ? parseFloat(value) / 100 :
        value === 'left' || value === 'top' ? 0 : value === 'right' || value === 'bottom' ? 1 : .5;
      const imageWidth = image.naturalWidth * scale, imageHeight = image.naturalHeight * scale;
      return {x: (width - imageWidth) * fraction(position[0]), y: (height - imageHeight) * fraction(position[1]),
        width: imageWidth, height: imageHeight};
    }
    function alignedImageRect(image, bitmapWidth, bitmapHeight) {
      const own = imageCover(image);
      if (!cover || !own) return null;
      // The geometry is authored against this controller's source image. Map
      // each counterpart's CSS cover into that reference, preserving its own
      // aspect and crop instead of stretching it to the reference PNG bounds.
      return {x: (own.x - cover.x) / cover.width * bitmapWidth,
        y: (own.y - cover.y) / cover.height * bitmapHeight,
        width: own.width / cover.width * bitmapWidth,
        height: own.height / cover.height * bitmapHeight};
    }
    function drawAlignedImage(ctx, image, bitmapWidth, bitmapHeight) {
      const rect = alignedImageRect(image, bitmapWidth, bitmapHeight);
      if (!rect) return false;
      ctx.drawImage(image, rect.x, rect.y, rect.width, rect.height); return true;
    }
    function coverSignature(image) {
      const own = imageCover(image);
      return own ? [own.x, own.y, own.width, own.height].join(',') : 'missing-cover';
    }
    function updateWaterBitmap() {
      if (destroyed || !visible || !intersecting || document.hidden) { waterBitmapDirty = true; return; }
      waterBitmapDirty = false;
      const supplied = Number(skyState?.daylight);
      const daylight = skyState && Number.isFinite(supplied) ? clamp(supplied) : (night ? 0 : 1);
      // Sky clock updates can be frequent; a static daylight value reuses one
      // composite. Time / animation frames never rebuild this pixel buffer.
      waterBlendDaylight = Math.round(daylight * 1000) / 1000;
      const ready = image => image && image.complete && image.naturalWidth > 0 && image.naturalHeight > 0;
      const signature = image => image ? `${image.currentSrc || image.src}|${image.naturalWidth}x${image.naturalHeight}|${image.complete}` : 'missing';
      const key = `${waterBlendDaylight}|${signature(dayImage)}|${signature(nightImage)}|${coverSignature(sourceImage)}|${coverSignature(dayImage)}|${coverSignature(nightImage)}`;
      if (key === waterBlendKey) return;
      waterBlendKey = key;
      waterBitmap = null;
      if (waterBlendDaylight === 0 || waterBlendDaylight === 1) {
        const image = waterBlendDaylight === 1 ? dayImage : nightImage;
        if (!ready(image)) { waterBlendStatus = 'endpoint-unavailable'; return; }
        if (coverSignature(image) === coverSignature(sourceImage)) {
          waterBitmap = image; waterBlendStatus = 'endpoint-source'; return;
        }
      }
      if (!ready(dayImage) || !ready(nightImage)) {
        // No bright source-image patch is allowed in a twilight wallpaper.
        // Until the matching artwork is ready, only procedural glints remain.
        waterBlendStatus = 'waiting-for-counterpart';
        return;
      }
      if (!waterBlendCanvas) waterBlendCanvas = document.createElement('canvas');
      const bitmapWidth = Math.min(MAX_CANVAS_WIDTH, sourceImage.naturalWidth);
      const bitmapHeight = Math.max(1, Math.round(bitmapWidth * sourceImage.naturalHeight / sourceImage.naturalWidth));
      if (waterBlendCanvas.width !== bitmapWidth) waterBlendCanvas.width = bitmapWidth;
      if (waterBlendCanvas.height !== bitmapHeight) waterBlendCanvas.height = bitmapHeight;
      const blendContext = waterBlendCanvas.getContext('2d', {alpha: true});
      if (!blendContext) { waterBlendStatus = 'context-unavailable'; return; }
      try {
        blendContext.setTransform(1, 0, 0, 1, 0, 0);
        blendContext.clearRect(0, 0, bitmapWidth, bitmapHeight);
        blendContext.globalAlpha = 1;
        blendContext.globalCompositeOperation = 'source-over';
        drawAlignedImage(blendContext, nightImage, bitmapWidth, bitmapHeight);
        blendContext.globalAlpha = waterBlendDaylight;
        drawAlignedImage(blendContext, dayImage, bitmapWidth, bitmapHeight);
        blendContext.globalAlpha = 1;
        waterBitmap = waterBlendCanvas;
        waterBlendStatus = waterBlendDaylight === 0 || waterBlendDaylight === 1 ? 'endpoint-aligned-cover' : 'blended';
      } catch (_) { waterBlendStatus = 'blend-unavailable'; }
    }
    function sourceWaterRows(bitmapWidth, bitmapHeight) {
      const key = [width, height, cover.x, cover.y, cover.width, cover.height, bitmapWidth, bitmapHeight].join('|');
      if (key === waterSamplingKey) return waterSamplingRows;
      waterSamplingKey = key; waterSamplingBuilds += 1; waterSamplingSpanCount = 0;
      // Geometry only: never read source pixels or rebuild these spans during
      // animation. A source-pixel inset protects nearest sampling at the bank.
      const guardX = 1 / bitmapWidth, guardY = 1 / bitmapHeight;
      waterSamplingRows = water.map((polygon, index) => {
        const box = waterBoxes[index], rows = [];
        const top = Math.max(0, cover.y + box.top * cover.height);
        const bottom = Math.min(height, cover.y + box.bottom * cover.height);
        for (let y = top; y < bottom; y += 1.5) {
          const stripHeight = Math.min(1.5, bottom - y);
          const imageTop = (y - cover.y) / cover.height, imageBottom = (y + stripHeight - cover.y) / cover.height;
          const low = imageTop - guardY, high = imageBottom + guardY;
          const levels = [low, (imageTop + imageBottom) / 2, high];
          // A very thin island/notch may fall between top/mid/bottom. Between
          // vertices polygon edges are linear, so also checking every critical
          // height makes the entire source rectangle conservatively water.
          const epsilon = Math.min(1e-7, (high - low) * 1e-4);
          for (const vertexY of waterCriticalYs[index]) {
            if (vertexY >= low && vertexY <= high) levels.push(
              Math.max(low, vertexY - epsilon), vertexY, Math.min(high, vertexY + epsilon));
          }
          let spans = null;
          for (const level of levels) {
            let allowed = horizontalSpans(polygon, level);
            for (const exclusion of waterExclusions) allowed = subtractSpans(allowed, horizontalSpans(exclusion, level));
            spans = spans === null ? allowed : intersectSpans(spans, allowed);
            if (!spans.length) break;
          }
          // A slanted thin island can move between the sample heights. Remove
          // every x touched by any boundary across the full source footprint:
          // a remaining vertical source column starts in water and cannot cross
          // a bank/hole boundary anywhere in this strip.
          spans = subtractSpans(spans || [], sweptBoundarySpans(polygon, low, high));
          for (const exclusion of waterExclusions) spans = subtractSpans(spans, sweptBoundarySpans(exclusion, low, high));
          spans = (spans || []).map(([left, right]) => [left + guardX, right - guardX])
            .filter(([left, right]) => right - left > 1e-10);
          if (!spans.length) continue;
          waterSamplingSpanCount += spans.length;
          rows.push({y, stripHeight, imageY: (imageTop + imageBottom) / 2,
            sourceY: imageTop * bitmapHeight, sourceHeight: (imageBottom - imageTop) * bitmapHeight, spans});
        }
        return rows;
      });
      return waterSamplingRows;
    }
    function drawWater(seconds) {
      if (!water.length) return;
      const bold = intensity === 'bold', expressive = intensity !== 'soft';
      const amplitude = (bold ? 6 : expressive ? 1.8 : .58) * waterEnergy;
      const weather = environment;
      const bitmapWidth = waterBitmap?.naturalWidth || waterBitmap?.width || 0;
      const bitmapHeight = waterBitmap?.naturalHeight || waterBitmap?.height || 0;
      const sourceRows = waterBitmap && bitmapWidth && bitmapHeight ? sourceWaterRows(bitmapWidth, bitmapHeight) : null;
      water.forEach((polygon, index) => {
        const box = waterBoxes[index];
        const top = Math.max(0, cover.y + box.top * cover.height);
        const bottom = Math.min(height, cover.y + box.bottom * cover.height);
        if (bottom <= top) return;
        context.save(); trace(polygon); context.clip(); excludePolygons(waterExclusions);
        context.globalAlpha = expressive ? .98 : .82;
        // Both source rectangles and destination clips stay inside the reviewed
        // water, including concave banks/islands. Nearest sampling plus the
        // source-pixel inset cannot filter neighbouring land into these spans.
        // Displacement leaves uncovered edges transparent over the static art.
        context.imageSmoothingEnabled = false;
        for (const row of sourceRows?.[index] || []) {
          const {y, stripHeight, sourceY, sourceHeight, imageY, spans} = row;
          const depth = clamp((imageY - box.top) / Math.max(.001, box.bottom - box.top));
          const pace = (bold ? 1 : expressive ? .62 : .3) * waterPace;
          const advected = weather.advection * pace;
          // Shared wave phases make neighbouring strips travel together.
          // Several wavelengths and a slow envelope avoid a single waving
          // sheet; perspective leaves distant water calmer than near water.
          const group = clamp(.72 + .18 * weather.waveGroup + .1 * Math.sin(seconds * .16 + imageY * 38 + index), .5, 1.15);
          const windGain = clamp(.55 + .35 * weather.wind + .1 * weather.waveGroup, .55, 1.25);
          const waves = .57 * Math.sin(advected * 1.65 + imageY * 190 + index * 1.7) +
            .28 * Math.sin(advected * .69 - imageY * 337 + index * .73) +
            .15 * Math.sin(advected * 2.73 + imageY * 743 + Math.sin(seconds * .13 + index) * .4);
          const dx = amplitude * clamp(waves * group * windGain * (.38 + .62 * depth), -1, 1);
          for (const [left, right] of spans) {
            context.drawImage(waterBitmap, left * bitmapWidth, sourceY, (right - left) * bitmapWidth, sourceHeight,
              cover.x + left * cover.width + dx, y, (right - left) * cover.width, stripHeight);
          }
        }
        context.globalAlpha = 1;
        context.globalCompositeOperation = 'screen';
        const glow = lightStrength();
        waterGlints[index].forEach((g, i) => {
          const span = box.right - box.left, depth = box.bottom - box.top;
          const drift = wrap(weather.advection * waterPace * (bold ? .014 : expressive ? .006 : .0025) * (.45 + .55 * g.speed) + g.x, 1);
          const nx = box.left + drift * span;
          const surfaceY = clamp(g.y + .008 * Math.sin(weather.advection * .7 + g.phase), .015, .985);
          const ny = box.top + surfaceY * depth;
          const x = cover.x + nx * cover.width, y = cover.y + ny * cover.height;
          const crest = .5 + .5 * Math.sin(ny * 260 - weather.advection * 1.3 + index * 1.7);
          const capillary = .5 + .5 * Math.sin(seconds * TAU / g.period + g.phase);
          const pulse = .25 + .75 * (.64 * crest + .36 * capillary);
          const bodyX=reflectionX(),reflection=bodyX===null?0:Math.exp(-Math.pow((nx-bodyX)/.19,2))*weather.directVisibility;
          const alpha = (bold ? .245 : expressive ? .105 : .032) * pulse * (.4 + .6 * glow) * (.6 + .4 * reflection) * (.28+.72*weather.fogVisibility)*
            clamp(weather.shimmer, .35, 1.5) * (1 - weather.cloudCover * .25) * (.15 + .85 * waterEnergy);
          const daylight = waterBlendDaylight;
          const color = [213 + daylight * 29, 197 + daylight * 38, 157 + daylight * 52].map(Math.round);
          context.strokeStyle = `rgba(${color[0]},${color[1]},${color[2]},${alpha})`;
          context.lineWidth = expressive ? .75 : .55;
          const length = Math.min(span * .3, g.length) * cover.width * (.55 + surfaceY * .8) *
            (.88 + .12 * Math.sin(weather.advection * .65 + g.phase));
          context.beginPath(); context.moveTo(x - length / 2, y);
          context.quadraticCurveTo(x, y + Math.sin(seconds * .9 + i) * .65, x + length / 2, y);
          context.stroke();
        });
        context.restore();
        // Feather the fixed shoreline clip so subpixel texture resampling
        // cannot reveal a hard polygon edge against the wallpaper blend.
        context.save();
        trace(polygon);
        context.globalCompositeOperation = 'destination-out';
        context.strokeStyle = 'rgba(0,0,0,.72)';
        context.lineWidth = 2.2;
        context.filter = 'blur(1.1px)';
        context.stroke();
        waterExclusions.forEach(exclusion => { trace(exclusion); context.stroke(); });
        context.restore();
      });
      context.globalAlpha = 1;
    }
    function drawClouds(seconds) {
      const bold = intensity === 'bold', expressive = intensity !== 'soft';
      // Per-view sky bands keep clouds above tall landmark tips.
      const [bandTop, bandBottom] = cloudBand;
      if (bandBottom <= bandTop) return;
      const span=bandBottom-bandTop;
      const top = cover.y + bandTop * cover.height, bottom = cover.y + bandBottom * cover.height;
      if (bottom <= 0 || top >= height) return;
      context.save(); context.beginPath();
      context.rect(0, Math.max(0, top), width, Math.min(height, bottom) - Math.max(0, top)); context.clip();
      excludePolygons(cloudExclusions);
      const daylight = waterBlendDaylight;
      const opacity = (bold ? .6 + .33 * daylight : expressive ? .38 + .34 * daylight : .16 + .14 * daylight) *
        (1+environment.rain*.35);
      cloudBanks.forEach((bank, index) => {
        const breathe = 1 + .018 * Math.sin(environment.advection * .018 + bank.phase);
        const cloudW = cover.width * bank.width * breathe;
        const cloudH = cover.height * span * bank.height / breathe;
        // Far banks drift slowly; nearer banks have more contour and speed.
        // Position uses continuous integrated wind, never a gust multiplied by
        // total elapsed time. Two porous pigments evolve over several minutes.
        const periodWidth = cover.width * 1.4 + cloudW;
        const speed = (bold ? 8.8 : expressive ? 2.2 : .48) * (.48 + bank.depth * .7) * bank.speed;
        const drift = environment.advection * speed;
        const x = wrap(bank.offset * cover.width + cloudW + drift, periodWidth) - cloudW;
        const y = cover.y + (bandTop + span * bank.elevation) * cover.height +
          Math.sin(seconds * .026 + bank.phase) * (bold ? 2.4 : expressive ? 1.4 : .6);
        const density = opacity * (.45 + bank.depth * .38) * (.65 + environment.cloudCover * .55) *
          (1 + environment.gust * .035);
        const morph = smooth(.5 + .5 * Math.sin(seconds * (.009 + index * .0017) + bank.phase));
        [cloudTextures[index], cloudTextures[(index + 1) % cloudTextures.length]].forEach((pigments, layer) => {
          const shapeOpacity = density * (layer ? morph : 1 - morph);
          [[pigments.day, daylight], [pigments.night, 1 - daylight]].forEach(([texture, weight]) => {
            if (weight <= 0) return;
            context.globalAlpha = clamp(shapeOpacity * weight);
            for (const offset of [-periodWidth, 0, periodWidth]) context.drawImage(texture, x + offset, y, cloudW, cloudH);
          });
        });
      });
      context.restore();
    }
    function drawMist(seconds) {
      mist.forEach((polygon, index) => {
        const box = bounds(polygon);
        const centerX = cover.x + (box.left + box.right) * cover.width / 2;
        const centerY = cover.y + (box.top + box.bottom) * cover.height / 2;
        const rx = Math.max(1, (box.right - box.left) * cover.width * .65);
        const ry = Math.max(1, (box.bottom - box.top) * cover.height * .55);
        const bold = intensity === 'bold', expressive = intensity !== 'soft';
        const drift = Math.sin(environment.advection * TAU / 29 + index * 2) *
          (bold ? 28 : expressive ? 13 : 3.5) * (.8 + environment.wind * .2);
        const breathe = .72 + .28 * Math.sin(seconds * TAU / 23 + index);
        context.save(); trace(polygon); context.clip();
        context.translate(centerX + drift, centerY); context.scale(rx, ry);
        const gradient = context.createRadialGradient(0, 0, 0, 0, 0, 1);
        const opacity = (.026 + waterBlendDaylight * .014) * breathe * (bold ? 2.5 : expressive ? 1.5 : 1);
        gradient.addColorStop(0, `rgba(221,227,224,${opacity})`);
        gradient.addColorStop(1, 'rgba(221,227,224,0)');
        context.fillStyle = gradient; context.fillRect(-1, -1, 2, 2); context.restore();
      });
    }
    function drawLights(seconds) {
      const darkness = smooth(1 - waterBlendDaylight);
      if (darkness <= .001) return;
      const bold = intensity === 'bold', expressive = intensity !== 'soft';
      context.save(); context.globalCompositeOperation = 'screen';
      sampledLights.forEach(light => {
        const x = cover.x + light.x * cover.width, y = cover.y + light.y * cover.height;
        // A room holds its level for tens of seconds, then changes gently.
        // Seeds, hold durations, offsets and target levels are independent;
        // no shared sine wave makes an entire facade blink together.
        const local = seconds + light.offset;
        const cell = Math.floor(local / light.period);
        const position = wrap(local, light.period);
        const level = step => {
          const value = unitHash(light.seed, step);
          return value < .12 ? .12 : .42 + value * .58;
        };
        const from = level(cell), to = level(cell + 1);
        const activity = light.variable ? from + (to - from) * smooth((position - light.period + light.transition) / light.transition) :
          .68 + unitHash(light.seed, 5) * .16;
        const alpha = (bold ? .035 + .34 * activity : expressive ? .025 + .2 * activity : .018 + .075 * activity) * darkness;
        const radius = clamp(width / (bold ? 160 : 260), .6, bold ? 2.4 : expressive ? 1.8 : 1.2);
        context.save(); trace(lights[light.region]); context.clip();
        const glow = context.createRadialGradient(x, y, 0, x, y, radius);
        glow.addColorStop(0, `rgba(${light.r},${light.g},${light.b},${alpha})`);
        glow.addColorStop(.4, `rgba(${light.r},${light.g},${light.b},${alpha * .52})`);
        glow.addColorStop(1, `rgba(${light.r},${light.g},${light.b},0)`);
        context.fillStyle = glow; context.fillRect(x - radius, y - radius, radius * 2, radius * 2); context.restore();
      });
      context.restore();
    }
    function render(seconds) {
      if (destroyed || !context || !cover || !width || !height || !visible || !intersecting || document.hidden) return;
      if (waterBitmapDirty) updateWaterBitmap();
      if (lightsDirty) { sampleLights(); lightsDirty = false; }
      // One snapshot per painted frame keeps every effect on the same wind
      // field, without extra samples per water strip, bank or room window.
      environment = sampleEnvironment(seconds);
      context.setTransform(ratio, 0, 0, ratio, 0, 0); context.clearRect(0, 0, width, height);
      drawWater(seconds); drawClouds(seconds); drawMist(seconds); drawLights(seconds); frames += 1;
    }
    function sampleLights() {
      // Both live cards use the decoded night counterpart as evidence of real
      // warm windows. A card originally mounted in daylight can then reach dusk
      // without inventing windows or waiting for a theme remount.
      const sampledImage = nightImage;
      if (!lights.length) { sampledLights = []; samplingStatus = 'disabled'; return; }
      if (!sampledImage?.complete || !sampledImage.naturalWidth || !sampledImage.naturalHeight) {
        sampledLights = []; sampleSource = null; samplingStatus = 'waiting-for-night'; return;
      }
      const signature = `${sampledImage.currentSrc || sampledImage.src}|${sampledImage.naturalWidth}x${sampledImage.naturalHeight}|${coverSignature(sourceImage)}|${coverSignature(sampledImage)}`;
      if (sampleSource === signature && samplingStatus === 'sampled') return;
      sampledLights = []; sampleSource = signature;
      const sample = document.createElement('canvas');
      sample.width = Math.min(768, sourceImage.naturalWidth);
      sample.height = Math.max(1, Math.round(sample.width * sourceImage.naturalHeight / sourceImage.naturalWidth));
      const sampleContext = sample.getContext('2d', {willReadFrequently: true});
      try {
        if (!sampleContext) throw new Error('Source sampling unavailable');
        if (!drawAlignedImage(sampleContext, sampledImage, sample.width, sample.height)) throw new Error('Source cover unavailable');
        const data = sampleContext.getImageData(0, 0, sample.width, sample.height).data;
        const luminanceAt = (x, y) => {
          const offset = (clamp(y, 0, sample.height - 1) * sample.width + clamp(x, 0, sample.width - 1)) * 4;
          return data[offset] * .3 + data[offset + 1] * .59 + data[offset + 2] * .11;
        };
        lights.forEach((polygon, region) => {
          const box = bounds(polygon), candidates = [];
          for (let sy = Math.ceil(box.top * sample.height); sy < Math.floor(box.bottom * sample.height); sy++) {
            for (let sx = Math.ceil(box.left * sample.width); sx < Math.floor(box.right * sample.width); sx++) {
              const x = (sx + .5) / sample.width, y = (sy + .5) / sample.height;
              if (!inside(x, y, polygon) || water.some(shape=>inside(x,y,shape))) continue;
              const offset = (sy * sample.width + sx) * 4;
              const r = data[offset], g = data[offset + 1], b = data[offset + 2];
              // Only reinforce already warm luminous pixels in the artwork.
              // Unlit facades never acquire arbitrary synthetic windows.
              if (r < 112 || g < 84 || r < b * 1.18 || g < b * 1.04 || g > r * 1.1) continue;
              const around = (luminanceAt(sx - 3, sy) + luminanceAt(sx + 3, sy) +
                luminanceAt(sx, sy - 3) + luminanceAt(sx, sy + 3)) * .25;
              if (luminanceAt(sx, sy) - around < 12) continue;
              candidates.push({x, y, r, g, b, region, score: r + g - b * .7});
            }
          }
          candidates.sort((a, b) => b.score - a.score);
          const chosen = [];
          for (const candidate of candidates) {
            if (chosen.every(p => Math.hypot((p.x - candidate.x) * sample.width, (p.y - candidate.y) * sample.height) >= 3.8)) {
              const seed = seedOf(`${sceneId}:${Math.round(candidate.x * sample.width)}:${Math.round(candidate.y * sample.height)}`);
              chosen.push({...candidate, seed, period: 54 + unitHash(seed, 1) * 104,
                transition: 9 + unitHash(seed, 2) * 13, offset: unitHash(seed, 3) * 158,
                variable: unitHash(seed, 4) < .28});
            }
            if (chosen.length === 10) break;
          }
          sampledLights.push(...chosen);
        });
        samplingStatus = 'sampled';
      } catch (_) { samplingStatus = 'unavailable'; }
      sample.width = sample.height = 1;
    }
    function updateSize() {
      if (destroyed) return;
      const rect = container.getBoundingClientRect();
      const nextWidth = Math.max(0, rect.width), nextHeight = Math.max(0, rect.height);
      const nextRatio = Math.min(MAX_DPR, window.devicePixelRatio || 1, MAX_CANVAS_WIDTH / Math.max(1, nextWidth));
      const pixelWidth = Math.max(1, Math.round(nextWidth * nextRatio));
      const pixelHeight = Math.max(1, Math.round(nextHeight * nextRatio));
      const changed = width !== nextWidth || height !== nextHeight || ratio !== nextRatio;
      width = nextWidth; height = nextHeight; ratio = nextRatio;
      if (canvas.width !== pixelWidth) canvas.width = pixelWidth;
      if (canvas.height !== pixelHeight) canvas.height = pixelHeight;
      if (sourceImage.naturalWidth && sourceImage.naturalHeight && width && height) {
        const scale = Math.max(width / sourceImage.naturalWidth, height / sourceImage.naturalHeight);
        const imageWidth = sourceImage.naturalWidth * scale, imageHeight = sourceImage.naturalHeight * scale;
        const position = getComputedStyle(sourceImage).objectPosition.split(/\s+/);
        const fraction = (part, fallback) => /^-?[\d.]+%$/.test(part || '') ? parseFloat(part) / 100 :
          part === 'left' || part === 'top' ? 0 : part === 'right' || part === 'bottom' ? 1 : fallback;
        cover = {scale, width: imageWidth, height: imageHeight,
          x: (width - imageWidth) * fraction(position[0], .5), y: (height - imageHeight) * fraction(position[1], .5)};
        if (changed) { lightsDirty = true; waterBitmapDirty = true; }
        if (changed) render(time);
      } else cover = null;
      updateLoop();
    }
    function onImageLoad() {
      if (destroyed) return;
      sampleSource = null; samplingStatus = 'pending';
      lightsDirty = true; waterBitmapDirty = true;
      const before = frames;
      updateSize();
      if (frames === before) render(time);
    }
    function onImageError() {
      cover = null; waterBitmap = null; sampledLights = []; samplingStatus = 'image-error'; stop();
      if (context) { context.setTransform(1, 0, 0, 1, 0, 0); context.clearRect(0, 0, canvas.width, canvas.height); }
    }
    function onBlendImageChange() {
      if (destroyed) return;
      // A failed counterpart must not retain its previous cached composite.
      waterBlendKey = null;
      lightsDirty = true; waterBitmapDirty = true;
      if (!raf) render(time);
    }
    const onVisibility = () => {
      if (!document.hidden) { waterBitmapDirty = true; render(time); }
      updateLoop();
    }, onReduced = () => updateLoop();
    sourceImage.addEventListener('load', onImageLoad); sourceImage.addEventListener('error', onImageError);
    blendImage?.addEventListener('load', onBlendImageChange); blendImage?.addEventListener('error', onBlendImageChange);
    document.addEventListener('visibilitychange', onVisibility); reducedQuery.addEventListener('change', onReduced);
    window.addEventListener('resize', updateSize, {passive: true});
    if ('ResizeObserver' in window) { resizeObserver = new ResizeObserver(updateSize); resizeObserver.observe(container); }
    if ('IntersectionObserver' in window) {
      intersecting = false;
      intersectionObserver = new IntersectionObserver(entries => {
        const wasIntersecting = intersecting;
        intersecting = entries.some(entry => entry.isIntersecting);
        if (intersecting && !wasIntersecting) { waterBitmapDirty = true; render(time); }
        updateLoop();
      }, {threshold: 0});
      intersectionObserver.observe(container);
    }
    if (sourceImage.complete && sourceImage.naturalWidth) onImageLoad(); else updateSize();

    return {
      setVisible(value) {
        if (destroyed) return;
        visible = Boolean(value);
        if (visible) {
          waterBitmapDirty = true;
          const before = frames;
          updateSize();
          if (frames === before) render(time);
        } else updateLoop();
      },
      setPlaying(value) { if (destroyed) return; playing = Boolean(value); updateLoop(); },
      setIntensity(value) {
        if (destroyed) return;
        intensity = ['soft','expressive'].includes(value) ? value : 'bold';
        render(time);
      },
      setLighting(value) {
        if (destroyed) return;
        skyState = value && typeof value === 'object' ? value : null;
        waterBitmapDirty = true;
        // Sky updates must never start an extra animation loop. If already
        // running, the next frame reads this state; paused views get one paint.
        if (!raf) render(time);
      },
      renderAt(seconds) {
        if (destroyed || !Number.isFinite(seconds) || seconds < 0) return;
        stop(); playing = false; time = seconds; render(time);
      },
      destroy() {
        if (destroyed) return;
        destroyed = true; stop(); resizeObserver?.disconnect(); intersectionObserver?.disconnect();
        sourceImage.removeEventListener('load', onImageLoad); sourceImage.removeEventListener('error', onImageError);
        blendImage?.removeEventListener('load', onBlendImageChange); blendImage?.removeEventListener('error', onBlendImageChange);
        document.removeEventListener('visibilitychange', onVisibility); reducedQuery.removeEventListener('change', onReduced);
        window.removeEventListener('resize', updateSize); canvas.remove(); sampledLights = []; cover = null;
        cloudTextures.forEach(pigments => Object.values(pigments).forEach(texture => { texture.width = texture.height = 1; }));
        if (waterBlendCanvas) waterBlendCanvas.width = waterBlendCanvas.height = 1;
        waterBitmap = null; waterBlendCanvas = null;
        waterSamplingRows = []; waterSamplingKey = null;
      },
      get state() {
        return {playing, visible, running: Boolean(raf) && shouldRun(), frames, time, sceneId, theme,
          intensity, intersecting, pageVisible: !document.hidden, destroyed,
          reducedMotion: reducedQuery.matches, motionAllowed: motionAllowed(), maxFps,
          width, height, pixelWidth: canvas.width, pixelHeight: canvas.height, dpr: ratio,
          waterRegions: water.length, waterExclusionRegions: waterExclusions.length,
          mistRegions: mist.length, cloudCount: cloudTextures.length, cloudExclusionRegions: cloudExclusions.length,
          cloudSpeedCssPx: intensity==='bold'?8.8:intensity==='expressive'?2.2:.48,
          cloudSkyBounds: [...cloudBand], lightCount: sampledLights.length, samplingStatus, lightSampleSource: sampleSource,
          roomLightModel: 'independent-hold-and-fade', lightPeriodSeconds: [54,158], lightTransitionSeconds: [9,22],
          variableLightCount: sampledLights.filter(light => light.variable).length,
          environmentStatus, environmentSamples, environment: environment ? {...environment} : null,
          waterType, waterPace, maxWaterShiftCssPx: (intensity === 'bold' ? 6 : intensity === 'expressive' ? 1.8 : .58) * waterEnergy,
          waterSamplingMode: 'source-safe-spans', waterSamplingBuilds, waterSamplingSpanCount,
          waterBlendStatus, waterBlendDaylight, sourceAlignment: 'independent-css-cover',
          sourceCovers: {day: imageCover(dayImage), night: imageCover(nightImage)},
          lighting: skyState ? {sunAltitude: skyState.sunAltitude, illumination: skyState.illumination,reflectionX:reflectionX(),reflectionBody:waterBlendDaylight>.5?'sun':'moon'} : null,
          imageTransform: cover ? {...cover} : null};
      }
    };
  }
  window.NycMotionV13 = {attach};
})();
