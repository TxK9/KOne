/* KOne city marine v10 — coherent wave packets, route-history wakes and varied bird flight.
 * These are art direction, never live weather / traffic. Shoreline and bridge
 * exclusion masks belong to the reviewed artwork configuration.
 */
(() => {
  'use strict';

  const TAU = Math.PI * 2;
  const VESSEL_PACE = {rowboat: .60, gondola: .55, sailboat: .82, canalboat: .78, ferry: .95, yacht: 1.08};
  const clamp = (n, a = 0, b = 1) => Math.max(a, Math.min(b, n));
  const smooth = (a, b, n) => { const t = clamp((n - a) / (b - a)); return t * t * (3 - 2 * t); };
  const finite = n => typeof n === 'number' && Number.isFinite(n);
  const point = p => Array.isArray(p) && p.length === 2 && p.every(n => finite(n) && n >= 0 && n <= 1);
  const polygons = list => Array.isArray(list) ? list.filter(p => Array.isArray(p) && p.length >= 3 && p.every(point)) : [];
  const inside = (x, y, polygon) => {
    let hit = false;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      const [xi, yi] = polygon[i], [xj, yj] = polygon[j];
      if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) hit = !hit;
    }
    return hit;
  };
  const bounds = polygon => ({left: Math.min(...polygon.map(p => p[0])), right: Math.max(...polygon.map(p => p[0])),
    top: Math.min(...polygon.map(p => p[1])), bottom: Math.max(...polygon.map(p => p[1]))});
  function random(seed) {
    let value = seed >>> 0;
    return () => { value = (Math.imul(value, 1664525) + 1013904223) >>> 0; return value / 4294967296; };
  }

  function attach(container, scene, theme, image, options = {}) {
    if (!(container instanceof Element) || !(image instanceof HTMLImageElement)) {
      throw new TypeError('NycMarineV13.attach needs a container and its source image');
    }
    const sceneId = scene?.id || '';
    const config = options.config?.scenes?.[sceneId] || {};
    const marine = config.marine && typeof config.marine === 'object' ? config.marine : null;
    const water = polygons(config.waterPolygons);
    const exclusions = polygons(marine?.exclusionPolygons);
    const boxes = water.map(bounds);
    const waveType = marine && ['coast', 'harbour', 'bay', 'river', 'canal', 'lagoon', 'marina', 'strait', 'lake'].includes(marine.type) ? marine.type : null;
    const waterStyle = ({
      lake: {count: 64, scale: .16, crest: 0, tempo: .58, drift: .0038, ripple: .38, stretch: 1.1},
      bay: {count: 132, scale: 1, crest: .32, tempo: 1, drift: .008, ripple: .9, stretch: 1.2},
      coast: {count: 112, scale: .72, crest: .15, tempo: .88, drift: .007, ripple: .76, stretch: 1.15},
      strait: {count: 96, scale: .85, crest: .2, tempo: 1.08, drift: .01, ripple: .8, stretch: 1.4},
      harbour: {count: 84, scale: .65, crest: .06, tempo: .7, drift: .0048, ripple: .62, stretch: 1},
      lagoon: {count: 60, scale: .46, crest: 0, tempo: .52, drift: .003, ripple: .38, stretch: 1.15},
      marina: {count: 48, scale: .25, crest: 0, tempo: .5, drift: .0028, ripple: .3, stretch: .9},
      river: {count: 64, scale: .24, crest: 0, tempo: .84, drift: .0075, ripple: .46, stretch: 1.45},
      canal: {count: 40, scale: .12, crest: 0, tempo: .42, drift: .0022, ripple: .22, stretch: .9}
    })[waveType] || null;
    const canvas = document.createElement('canvas');
    canvas.className = 'nyc-marine-canvas';
    canvas.setAttribute('aria-hidden', 'true');
    container.appendChild(canvas);
    const ctx = canvas.getContext('2d', {alpha: true});
    const reducedQuery = matchMedia('(prefers-reduced-motion: reduce)');
    const maxFps = clamp(Number(options.maxFps) || 30, 1, 30);
    const interval = 1000 / maxFps;
    const nightTheme = theme === 'night' || theme === 'dark';
    let sky = null;
    let daylight = nightTheme ? 0 : 1;
    let illumination = nightTheme ? .35 : 1;
    let width = 0, height = 0, dpr = 1, cover = null;
    let playing = options.playing !== false, visible = true, intersecting = true, destroyed = false;
    let intensity = ['soft', 'expressive', 'bold'].includes(options.intensity) ? options.intensity : 'bold';
    const dynamics = () => intensity === 'bold' ? {power: 2.6, pace: 2.35, boatPace: 3.4} : intensity === 'expressive' ? {power: 1, pace: 1, boatPace: 1.2} : {power: .48, pace: .58, boatPace: .65};
    let raf = 0, clock = null, lastPaint = null, seconds = 0, frames = 0;
    let acceptedRoutes = [], rejectedRoutes = [], routeSignature = '';
    let visibleBoats = 0, visibleWaves = 0, visibleBirds = 0;
    let environment = {wind: 1, advection: 0, gust: 0, cloudCover: .5, waveGroup: .5, shimmer: .5};
    const environmentCache = new Map();
    let resizeObserver = null, intersectionObserver = null;
    const seed = [...sceneId].reduce((n, char) => Math.imul(n, 31) + char.charCodeAt(0), 437);
    const rnd = random(seed);
    const wavePoints = water.map((polygon, region) => {
      if (!waveType) return [];
      const box = boxes[region];
      const count = waterStyle.count;
      const result = [];
      for (let attempts = 0; result.length < count && attempts < count * 35; attempts++) {
        const x = box.left + rnd() * (box.right - box.left);
        const y = box.top + rnd() * (box.bottom - box.top);
        if (!inside(x, y, polygon) || exclusions.some(p => inside(x, y, p))) continue;
        result.push({x, y, phase: rnd() * TAU, speed: .48 + rnd() * .72,
          length: .01 + rnd() * (.014 + .045 * waterStyle.scale),
          crest: rnd() < waterStyle.crest, weight: .45 + rnd() * .55, drift: rnd(), bend: .6 + rnd() * 1.4,
          packet: rnd() * TAU, layer: result.length % 3, glint: rnd()});
      }
      return result;
    });

    const birdConfig = marine?.birds === false || config.birds === false ? {count: 0} : (marine?.birds || config.birds || {});
    const birdBand = Array.isArray(birdConfig.skyBand) && birdConfig.skyBand.length === 2 && birdConfig.skyBand.every(finite)
      ? [clamp(Math.min(...birdConfig.skyBand), .145, .255), clamp(Math.max(...birdConfig.skyBand), .16, .275)] : [.16, .255];
    if (birdBand[1] < birdBand[0]) birdBand[1] = birdBand[0];
    const birdCount = clamp(Number.isFinite(birdConfig.count) ? Math.round(birdConfig.count) : ['harbour','bay','strait','lagoon','marina'].includes(waveType) ? 5 : 3, 0, 10);
    const flocks = Array.from({length: Math.ceil(birdCount/3)}, () => ({phase:rnd(),lane:.15+rnd()*.5,period:49+rnd()*19,direction:rnd()<.27?-1:1}));
    const birds = Array.from({length: birdCount}, (_, i) => {
      const flock=flocks[Math.floor(i/3)],member=i%3;
      return {phase:(flock.phase+member*.036+rnd()*.009)%1,lane:clamp(flock.lane+member*.065+rnd()*.02),
        size:.006+rnd()*.0065,period:flock.period,flap:rnd()*TAU,index:i,direction:flock.direction,
        beatPeriod: 4.1+rnd()*3.8, beatDuty: .16+rnd()*.15, beatRate: 5.2+rnd()*2.4, bank: .08+rnd()*.12};
    });
    const skyBounds = scene?.skySafe || {};
    const cloudBand = Array.isArray(scene?.cloudSkyBand) && scene.cloudSkyBand.length===2 && scene.cloudSkyBand.every(finite)
      ? scene.cloudSkyBand : birdBand;
    const flightBounds = {
      left: finite(skyBounds.left) ? clamp(skyBounds.left) : .08,
      right: finite(skyBounds.right) ? clamp(skyBounds.right) : .92,
      top: Math.max(birdBand[0], Math.min(...cloudBand), finite(skyBounds.top) ? clamp(skyBounds.top) : birdBand[0]),
      bottom: Math.min(birdBand[1], Math.max(...cloudBand), finite(skyBounds.bottom) ? clamp(skyBounds.bottom) : birdBand[1])
    };
    const skyExclusions = [...(Array.isArray(scene?.skyExclusions) ? scene.skyExclusions : []),
      ...(Array.isArray(config.skyExclusions) ? config.skyExclusions : [])].flatMap(raw => {
      if (Array.isArray(raw)) return polygons([raw]);
      if (!raw || ![raw.left,raw.right,raw.top,raw.bottom].every(finite)) return [];
      const l=clamp(raw.left), r=clamp(raw.right), t=clamp(raw.top), b=clamp(raw.bottom);
      return l<r&&t<b ? [[[l,t],[r,t],[r,b],[l,b]]] : [];
    });

    // The shared sampler is deterministic art direction, not live weather.
    // The caller's matching image-layer clock is used only for the shared
    // environment. Vessel travel and its past route samples keep this
    // controller's own pause-aware clock.
    function environmentAt(time) {
      if (environmentCache.has(time)) return environmentCache.get(time);
      let value = null;
      let sharedTime=time;
      try {
        const supplied=options.environmentTime?.();
        if(finite(supplied))sharedTime=supplied+(time-seconds);
        value = window.KOneEnvironmentV10?.sample(sceneId, sharedTime);
      } catch (_) { /* neutral fallback */ }
      const env = {
        wind: finite(value?.wind) ? clamp(value.wind,.5,1.6) : 1,
        advection: finite(value?.advection) ? value.advection : sharedTime,
        gust: finite(value?.gust) ? clamp(value.gust) : 0,
        cloudCover: finite(value?.cloudCover) ? clamp(value.cloudCover) : .5,
        waveGroup: finite(value?.waveGroup) ? clamp(value.waveGroup) : .5,
        shimmer: finite(value?.shimmer) ? clamp(value.shimmer) : .5,
        activity:finite(value?.activity)?clamp(value.activity,.025,1):1,
        rain:finite(value?.rain)?clamp(value.rain):0,snow:finite(value?.snow)?clamp(value.snow):0
      };
      environmentCache.set(time,env);
      return env;
    }
    function routeTravel(route, time) {
      const motor = ['yacht','ferry','canalboat'].includes(route.kind);
      const amplitude = motor ? .055 : route.kind==='sailboat' ? .105 : .13;
      const phase = route.phase*TAU, frequency = motor ? .043 : .068;
      const secondary = .017+route.phase*.006;
      // Analytic integral: continuous speed changes, positive derivative and
      // reproducible history, independent of shared image-layer clock skew.
      const varied = time + amplitude/frequency*(Math.sin(time*frequency+phase)-Math.sin(phase)) +
        .035/secondary*(Math.sin(time*secondary+phase*1.7)-Math.sin(phase*1.7));
      return route.phase + varied*dynamics().boatPace*(VESSEL_PACE[route.kind]||1)/route.duration;
    }
    function routeProgress(route,time) {
      const travel=routeTravel(route,time);
      return {travel, progress:((travel%1)+1)%1};
    }
    function routeSpeed(route,time) {
      const motor=['yacht','ferry','canalboat'].includes(route.kind);
      const amplitude=motor?.055:route.kind==='sailboat'?.105:.13;
      const frequency=motor?.043:.068, phase=route.phase*TAU;
      return (VESSEL_PACE[route.kind]||1)*(1+amplitude*Math.cos(time*frequency+phase)+.035*Math.cos(time*(.017+route.phase*.006)+phase*1.7));
    }

    function normalizedPosition(route, progress) {
      const target = clamp(progress) * route.length;
      let index = 0;
      while (index < route.segments.length - 1 && target > route.segments[index].end) index++;
      const segment = route.segments[index];
      const t = segment.length > 0 ? clamp((target - segment.start) / segment.length) : 0;
      return {x: segment.a[0] + (segment.b[0] - segment.a[0]) * t,
        y: segment.a[1] + (segment.b[1] - segment.a[1]) * t,
        ux: segment.ux, uy: segment.uy};
    }
    function touchesRectangle(polygon, left, top, right, bottom) {
      const inRect = (x, y) => x >= left && x <= right && y >= top && y <= bottom;
      for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
        const a = polygon[j], b = polygon[i];
        if (inRect(a[0], a[1]) || inRect(b[0], b[1])) return true;
        const dx = b[0] - a[0], dy = b[1] - a[1];
        for (const x of [left, right]) {
          const t = dx ? (x - a[0]) / dx : -1;
          if (t >= 0 && t <= 1 && a[1] + t * dy >= top && a[1] + t * dy <= bottom) return true;
        }
        for (const y of [top, bottom]) {
          const t = dy ? (y - a[1]) / dy : -1;
          if (t >= 0 && t <= 1 && a[0] + t * dx >= left && a[0] + t * dx <= right) return true;
        }
      }
      return false;
    }
    function safeRectangle(x, y, mx, my) {
      const left = x - mx, top = y - my, right = x + mx, bottom = y + my;
      const corners = [[left,top],[right,top],[right,bottom],[left,bottom]];
      // A single simple polygon must contain the whole box. Rejecting seam
      // crossings is intentionally conservative; a concave bay cannot sneak
      // a shoreline through a box whose corner probes happen to lie in water.
      const contained = water.some(p => corners.every(([cx,cy]) => inside(cx,cy,p)) &&
        !touchesRectangle(p,left,top,right,bottom));
      return contained && !exclusions.some(p => corners.some(([cx,cy]) => inside(cx,cy,p)) ||
        touchesRectangle(p,left,top,right,bottom));
    }
    function routesForImage() {
      const aspect = image.naturalWidth / image.naturalHeight;
      const signature = `${image.naturalWidth}x${image.naturalHeight}`;
      if (!marine || !finite(aspect) || aspect <= 0 || routeSignature === signature) return;
      routeSignature = signature;
      acceptedRoutes = []; rejectedRoutes = [];
      for (const [index, raw] of (Array.isArray(marine.routes) ? marine.routes : []).entries()) {
        const id = raw?.id || `route-${index + 1}`;
        const reject = reason => rejectedRoutes.push({id, reason});
        if (!raw || !Array.isArray(raw.points) || raw.points.length < 2 || !raw.points.every(point)) {
          reject('invalid-points'); continue;
        }
        if (!['sailboat', 'ferry', 'gondola', 'canalboat', 'yacht', 'rowboat'].includes(raw.kind)) { reject('unsupported-vessel'); continue; }
        if (['river', 'canal', 'marina'].includes(waveType) && raw.kind === 'sailboat') { reject('sailboat-disallowed-on-city-river'); continue; }
        const route = {id, kind: raw.kind, width: clamp(Number(raw.width) || .028, .009, .065),
          duration: clamp(Number(raw.duration) || 100, 35, 600), phase: ((Number(raw.phase) || 0) % 1 + 1) % 1,
          night: raw.night === true || (['ferry','canalboat','yacht'].includes(raw.kind) && raw.night !== false), segments: [], length: 0};
        // Round yacht turns inside the input corner. The derived curve still
        // undergoes the same dense full-vessel shoreline/bridge audit.
        const routePoints = [];
        if (raw.kind === 'yacht' && raw.points.length > 2) {
          routePoints.push(raw.points[0]);
          for (let i = 1; i < raw.points.length - 1; i++) {
            const a = raw.points[i-1], b = raw.points[i], c = raw.points[i+1];
            const blend = .2;
            const entry = [b[0] + (a[0]-b[0])*blend, b[1] + (a[1]-b[1])*blend];
            const exit = [b[0] + (c[0]-b[0])*blend, b[1] + (c[1]-b[1])*blend];
            routePoints.push(entry);
            for (let j = 1; j <= 16; j++) {
              const t = j/16, u = 1-t;
              routePoints.push([u*u*entry[0]+2*u*t*b[0]+t*t*exit[0], u*u*entry[1]+2*u*t*b[1]+t*t*exit[1]]);
            }
          }
          routePoints.push(raw.points.at(-1));
        } else routePoints.push(...raw.points);
        for (let i = 1; i < routePoints.length; i++) {
          const a = routePoints[i - 1], b = routePoints[i];
          const dx = b[0] - a[0], dy = (b[1] - a[1]) / aspect;
          const length = Math.hypot(dx, dy);
          if (length < .00001) continue;
          route.segments.push({a, b, length, start: route.length, end: route.length + length, ux: dx / length, uy: dy / length});
          route.length += length;
        }
        if (!route.length) { reject('zero-length'); continue; }
        // Every segment is checked in physical image space, with a conservative
        // rectangle containing hull, mast, sails and bobbing at any heading.
        // The polygon union may have gaps: sparse endpoints alone are unsafe.
        const envelope = raw.kind === 'sailboat' ? {x: .72, y: 1.16} : ['gondola','rowboat'].includes(raw.kind) ? {x: .87, y: .85} : {x: .66, y: .66};
        const mx = route.width * envelope.x + .0008, my = (route.width * envelope.y + .0008) * aspect;
        const samples = Math.ceil(route.length / Math.min(.0015, route.width / 5));
        let safe = true;
        for (let s = 0; safe && s <= samples; s++) {
          const p = normalizedPosition(route, s / samples);
          safe = safeRectangle(p.x,p.y,mx,my);
        }
        if (!safe) { reject('vessel-bounds-outside-reviewed-water'); continue; }
        acceptedRoutes.push(route);
      }
    }
    function allowed() { return !reducedQuery.matches || options.allowReducedMotion === true; }
    function shouldRun() {
      return !destroyed && !!ctx && (!!waveType && water.length > 0 || birds.length > 0 && daylight > .17) && !!cover && width > 0 && height > 0 &&
        playing && visible && intersecting && !document.hidden && allowed();
    }
    function stop() {
      if (raf) cancelAnimationFrame(raf);
      raf = 0; clock = lastPaint = null;
    }
    function updateLoop() {
      if (!shouldRun()) { stop(); return; }
      if (!raf) raf = requestAnimationFrame(tick);
    }
    function tick(timestamp) {
      raf = 0;
      if (!shouldRun()) { clock = lastPaint = null; return; }
      if (clock !== null) seconds += clamp(timestamp - clock, 0, 100) / 1000;
      clock = timestamp;
      if (lastPaint === null || timestamp - lastPaint + .01 >= interval) {
        render(seconds); lastPaint = timestamp;
      }
      raf = requestAnimationFrame(tick);
    }
    function waterClip() {
      ctx.beginPath();
      for (const polygon of water) {
        const signedArea = polygon.reduce((sum, p, i) => {
          const next=polygon[(i+1)%polygon.length];return sum+p[0]*next[1]-next[0]*p[1];
        },0);
        const loop=signedArea<0?[...polygon].reverse():polygon;
        loop.forEach(([x,y],i)=>{
          const px=cover.x+x*cover.width,py=cover.y+y*cover.height;
          if(i)ctx.lineTo(px,py);else ctx.moveTo(px,py);
        });ctx.closePath();
      }
      ctx.clip();
      // Subtract each hole separately; overlapping water masks stay a union,
      // and overlapping exclusions never accidentally restore visible pixels.
      for(const polygon of exclusions){
        ctx.beginPath();ctx.rect(-1,-1,width+2,height+2);
        polygon.forEach(([x,y],i)=>{
          const px=cover.x+x*cover.width,py=cover.y+y*cover.height;
          if(i)ctx.lineTo(px,py);else ctx.moveTo(px,py);
        });ctx.closePath();ctx.clip('evenodd');
      }
    }
    function drawWaves(time) {
      if (!waveType || !waterStyle) return;
      const {power, pace} = dynamics();
      const sea = waterStyle.scale > .7, quiet = waterStyle.scale < .3;
      const env = (.2 + illumination * .23) * (1-daylight) + daylight;
      const windX = finite(marine?.windScreen?.[0]) ? Math.sign(marine.windScreen[0]) || 1 : 1;
      const windY = finite(marine?.windScreen?.[1]) ? clamp(marine.windScreen[1],-.35,.35) : -.08;
      const advected = environment.advection * pace;
      const windEnergy = .78 + environment.wind * .22 + environment.gust * .12;
      const light = (.55 + (1-environment.cloudCover)*.45) * (.8+environment.shimmer*.2);
      visibleWaves = 0;
      ctx.save(); waterClip(); ctx.lineCap = 'round';
      wavePoints.forEach((points, region) => {
        const box = boxes[region], span = box.right - box.left;
        for (const wave of points) {
          // Two carrier frequencies travel through a much slower group.
          // Neighbouring marks share the packet field rather than blinking
          // independently. Short wind ripples ride on the longer swell.
          const phase = advected * wave.speed * waterStyle.tempo + wave.phase;
          const travel = ((wave.drift + advected * waterStyle.drift * windX) % 1 + 1) % 1;
          const px = box.left + travel * span;
          const groupPhase=advected*waterStyle.tempo*.18 + px*15 + wave.y*29 + region*.73 + seed*.001;
          const packet=.28+.72*Math.sin(groupPhase*.5)**2;
          const group=packet*(.76+environment.waveGroup*.24);
          const cycle=(Math.sin(phase + px*11)+1)*.5;
          const depth=clamp((wave.y-box.top)/Math.max(.02,box.bottom-box.top));
          const x=cover.x+px*cover.width;
          const y=cover.y+wave.y*cover.height+
            Math.sin(phase*.72+px*8)*(.25+waterStyle.scale*1.3)*Math.min(power,1.9)*group+
            Math.sin(phase*.31)*windY*1.6;
          if(x < -50 || x > width+50 || y < -8 || y > height+8)continue;
          const length=wave.length*cover.width*(.65+depth*.6)*waterStyle.stretch*(.85+group*.28);
          const alpha=Math.min(.5,(quiet?.09:sea?.17:.125)*power*env*wave.weight*windEnergy*group);
          const gradient=ctx.createLinearGradient(x-length*.5,y,x+length*.5,y);
          gradient.addColorStop(0,'rgba(239,242,232,0)');
          gradient.addColorStop(.25,`rgba(239,242,232,${alpha*.35})`);
          gradient.addColorStop(.48,`rgba(241,243,235,${alpha*light})`);
          gradient.addColorStop(.75,`rgba(239,242,232,${alpha*.45})`);
          gradient.addColorStop(1,'rgba(239,242,232,0)');
          const swell=(.15+waterStyle.scale*1.7)*Math.min(power,1.9)*
            Math.sin(phase+px*19)*wave.bend*group;
          ctx.strokeStyle=gradient;
          ctx.lineWidth=clamp(cover.width/490,.6,1.4)*(wave.crest?1.15:.75);
          ctx.beginPath();ctx.moveTo(x-length*.5,y);
          ctx.bezierCurveTo(x-length*.18,y+swell,x+length*.15,y-swell*.55,x+length*.5,y+swell*.18);ctx.stroke();
          if(!quiet || wave.layer===0){
            ctx.strokeStyle=`rgba(45,76,85,${alpha*(quiet?.18:.28)})`;
            ctx.lineWidth=clamp(cover.width/610,.4,1);
            ctx.beginPath();ctx.moveTo(x-length*.4,y+1.2);
            ctx.quadraticCurveTo(x,y+swell*.45+1.4,x+length*.36,y+1.1);ctx.stroke();
          }
          // Fine capillary ripples have shorter lifetimes and lengths than
          // the swell; their highlight slides along each crest as it turns.
          if(wave.layer!==2){
            const ripplePhase=phase*2.6+wave.glint*TAU;
            const shimmer=Math.sin(ripplePhase+environment.shimmer*2.1)**2;
            const offset=Math.sin(phase*.61+wave.packet)*length*.19;
            const short=length*(.18+waterStyle.ripple*.21);
            const ry=y+Math.cos(ripplePhase*.53)*(1+depth*1.3);
            ctx.strokeStyle=`rgba(239,245,235,${alpha*waterStyle.ripple*(.25+shimmer*.75)*light})`;
            ctx.lineWidth=clamp(cover.width/780,.4,.85);
            ctx.beginPath();ctx.moveTo(x+offset-short*.5,ry);
            ctx.quadraticCurveTo(x+offset,ry+Math.sin(ripplePhase)*.65,x+offset+short*.5,ry);ctx.stroke();
          }
          if(wave.crest && cycle>.72){
            const burst=smooth(.72,.94,cycle)*group;
            ctx.strokeStyle=`rgba(249,249,239,${Math.min(.42,burst*.27*power*env*windEnergy)})`;
            ctx.lineWidth=clamp(cover.width/520,.7,1.35);
            ctx.beginPath();ctx.moveTo(x-length*.2,y+swell*.18);
            ctx.bezierCurveTo(x-length*.05,y+swell*.45-.5,x+length*.1,y-swell*.22,x+length*.24,y+swell*.15);ctx.stroke();
            // Only open sea/strait crests get a few tiny broken foam beads.
            if(sea && burst>.65){
              ctx.fillStyle=`rgba(245,248,235,${burst*.16*env})`;
              for(let j=0;j<2;j++){
                ctx.beginPath();ctx.ellipse(x+length*(j*.14-.07),y+1.2+j*.35,Math.max(.45,length*.015),.4,0,0,TAU);ctx.fill();
              }
            }
          }
          visibleWaves++;
        }
      });
      ctx.restore();
    }
    function screenPosition(route, progress) {
      const p = normalizedPosition(route, progress);
      return {...p, x: cover.x + p.x * cover.width, y: cover.y + p.y * cover.height};
    }
    function vesselPose(route, progress, time) {
      const p=screenPosition(route,progress),length=route.width*cover.width;
      const {power:rawPower,pace}=dynamics();
      // These are the same reviewed v9 limits, shared by hull and bow wake.
      const power=Math.min(rawPower,1.8),roughness=.25+(waterStyle?.scale||.2)*.75;
      const roll=Math.sin(time*(waveType==='bay'?1.2:.83)*pace+route.phase*TAU)*.042*power*roughness;
      const bob=Math.sin(time*1.13*pace+route.phase*4)*Math.min(1.4,length*.04)*power*roughness;
      const turn=roll*(route.kind==='sailboat'?.8:.4);
      return {...p,ux:p.ux*Math.cos(turn)-p.uy*Math.sin(turn),
        uy:p.uy*Math.cos(turn)+p.ux*Math.sin(turn),y:p.y+bob,roll};
    }
    function drawWake(route, progress, opacity, time) {
      const {power} = dynamics();
      const motor = ['yacht','ferry','canalboat'].includes(route.kind);
      const lookBack = motor ? 8 : ['gondola','rowboat'].includes(route.kind) ? 3.2 : 5;
      const cycle=Math.floor(routeTravel(route,time)), speed=routeSpeed(route,time);
      const length = route.width * cover.width;
      const halfBeam = length * (motor ? .15 : .085);
      const boat = vesselPose(route, progress, time), count = 23, paths = [[], []];
      for (let i = 0; i <= count; i++) {
        const age = i / count * lookBack;
        if(age>time)break;
        const past=routeProgress(route,time-age);
        // Do not connect the previous route cycle to the new entry point.
        if(Math.floor(past.travel)!==cycle)break;
        const p = vesselPose(route, past.progress, time-age);
        const spread = halfBeam + age * length * (route.kind === 'yacht' ? .105 : motor ? .073 : .038);
        const wobble = Math.sin(time * 1.8 - age * 2.7 + route.phase * TAU) * (.25 + power * .22);
        for (let side=0;side<2;side++) {
          const sign = side ? 1 : -1;
          paths[side].push({x:p.x-p.ux*halfBeam-p.uy*(spread+wobble)*sign,
            y:p.y-p.uy*halfBeam+p.ux*(spread+wobble)*sign,age:i/count});
        }
      }
      ctx.save();waterClip();ctx.lineCap='round';
      const strength = (route.kind === 'yacht' ? .53 : motor ? .39 : .23)*(.75+speed*.25);
      for (const path of paths) for (let i=1;i<path.length;i++) {
        const a=path[i-1],b=path[i];
        ctx.strokeStyle=`rgba(241,244,234,${opacity*strength*(.3+daylight*.7)*(1-b.age)**1.6})`;
        ctx.lineWidth=clamp(cover.width/550,.65,1.4)*(motor?1.2:.8);
        ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(b.x,b.y);ctx.stroke();
      }
      if (motor) {
        // Broken churn follows the curved path history, including yacht turns.
        for(let i=1;i<16;i++){
          const age=i/16*lookBack*.8;
          if(age>time)continue;
          const past=routeProgress(route,time-age);
          if(Math.floor(past.travel)!==cycle)continue;
          const p=vesselPose(route,past.progress,time-age),f=1-i/16;
          const side=Math.sin(time*3.1-i*1.7)*halfBeam*(.35+i*.025);
          ctx.fillStyle=`rgba(246,247,236,${opacity*.25*f*(.35+daylight*.65)})`;
          ctx.beginPath();ctx.ellipse(p.x-p.uy*side,p.y+p.ux*side,Math.max(.4,halfBeam*(.5+i*.03)),Math.max(.35,halfBeam*.25),Math.atan2(p.uy,p.ux),0,TAU);ctx.fill();
        }
      }
      // Dispersing cross-ripples stay on the actual older path and inside
      // the same reviewed water clip, without widening the vessel envelope.
      if(motor)for(let i=1;i<=5;i++){
        const age=lookBack*(.18+i*.13);
        if(age>time)continue;
        const past=routeProgress(route,time-age);
        if(Math.floor(past.travel)!==cycle)continue;
        const p=vesselPose(route,past.progress,time-age),fade=(1-age/lookBack)**2;
        const spread=halfBeam*(1.5+age*.15);
        ctx.strokeStyle=`rgba(229,240,230,${opacity*.16*fade*(.35+daylight*.65)})`;
        ctx.lineWidth=.55;ctx.beginPath();
        ctx.moveTo(p.x-p.uy*spread-p.ux*length*.09,p.y+p.ux*spread-p.uy*length*.09);
        ctx.quadraticCurveTo(p.x-p.ux*length*.13,p.y-p.uy*length*.13,p.x+p.uy*spread-p.ux*length*.09,p.y-p.ux*spread-p.uy*length*.09);ctx.stroke();
      }
      // Gentle bow wave, strongest for the motor yacht.
      ctx.strokeStyle=`rgba(245,247,238,${opacity*(motor?.4:.2)*(.4+daylight*.6)})`;
      ctx.lineWidth=clamp(cover.width/600,.5,1.1);
      const bowX=boat.x+boat.ux*length*.48,bowY=boat.y+boat.uy*length*.48;
      ctx.beginPath();ctx.moveTo(bowX-boat.uy*halfBeam*1.6,bowY+boat.ux*halfBeam*1.6);
      ctx.quadraticCurveTo(bowX+boat.ux*length*.12,bowY+boat.uy*length*.12,bowX+boat.uy*halfBeam*1.6,bowY-boat.ux*halfBeam*1.6);ctx.stroke();
      ctx.restore();
    }
    function drawVessel(route, progress, opacity, time) {
      const p = vesselPose(route, progress, time), {roll}=p;
      const length = route.width * cover.width, {pace} = dynamics();
      const x = p.x, y = p.y;
      const beam = ['ferry','canalboat','yacht'].includes(route.kind) ? length * .165 : length * (route.kind === 'gondola' ? .085 : .115);
      const at = (along, across, lift = 0) => [x + p.ux * along - p.uy * across,
        y + p.uy * along + p.ux * across - lift];
      const path = points => { ctx.beginPath(); points.forEach(([px,py], i) => i ? ctx.lineTo(px,py) : ctx.moveTo(px,py)); ctx.closePath(); };
      const brightness = (.21 + illumination * .12) * (1-daylight) + daylight;
      const ink = Math.round(68 + daylight * 40);
      const opacityBase = opacity * (.72 + brightness * .28);
      ctx.save(); ctx.globalAlpha = opacityBase; ctx.lineJoin = 'round';
      // All boats retain an upright mast / cabin while the hull follows its
      // projected course. There is no canvas scale(-1,1) or mirrored sail.
      const hull = [at(length*.5,0),at(length*.18,beam),at(-length*.43,beam*.8),
        at(-length*.5,0),at(-length*.41,-beam*.8),at(length*.2,-beam)];
      path(hull); ctx.fillStyle = route.kind === 'yacht' ? `rgb(${Math.round(157+daylight*86)},${Math.round(161+daylight*82)},${Math.round(154+daylight*83)})` : route.kind === 'gondola' ? `rgb(${Math.round(35+daylight*18)},${Math.round(42+daylight*17)},${Math.round(42+daylight*15)})` : `rgb(${ink},${ink + 5},${ink + 3})`; ctx.fill();
      ctx.strokeStyle = `rgba(224,225,211,${.38 + daylight*.26})`; ctx.lineWidth = Math.max(.36, length * .028); ctx.stroke();
      if (route.kind === 'sailboat') {
        const mast = at(-length * .055, 0, length * .055);
        const top = [mast[0] + roll * length, mast[1] - length * .81];
        const windX = finite(marine?.windScreen?.[0]) ? marine.windScreen[0] : 1;
        const windSide = windX < 0 ? -1 : 1;
        const sheet = length * .43 * windSide;
        const cream = Math.round(215 + daylight * 26);
        // Two separate taut sails share the mast; the pale sail panel has a
        // single shaded seam and avoids a white triangular sticker silhouette.
        path([[top[0] + windSide * length * .025, top[1] + length * .07],
          [mast[0] + sheet, mast[1] - length * .06],
          [mast[0] + windSide * length * .025, mast[1] - length * .035]]);
        ctx.fillStyle = `rgb(${cream},${cream + 1},${cream - 9})`; ctx.fill();
        ctx.strokeStyle = `rgba(126,134,128,${.25 + daylight * .14})`; ctx.lineWidth = Math.max(.32,length*.018); ctx.stroke();
        path([[top[0] - windSide * length * .025, top[1] + length * .19],
          [mast[0] - sheet * .64, mast[1] - length * .08],
          [mast[0] - windSide * length * .026, mast[1] - length * .045]]);
        ctx.fillStyle = `rgb(${cream-12},${cream-8},${cream-17})`; ctx.fill(); ctx.stroke();
        ctx.strokeStyle = `rgba(83,94,90,${.66 + daylight * .23})`; ctx.lineWidth = Math.max(.4,length*.025);
        ctx.beginPath(); ctx.moveTo(mast[0],mast[1]);ctx.lineTo(top[0],top[1]);ctx.stroke();
        ctx.strokeStyle = `rgba(92,100,93,${.28+daylight*.12})`;ctx.lineWidth = Math.max(.3,length*.013);
        const bow=at(length*.42,0);ctx.beginPath();ctx.moveTo(top[0],top[1]+length*.2);ctx.lineTo(bow[0],bow[1]);ctx.stroke();
      } else if (route.kind === 'gondola') {
        // One standing gondolier rows from the stern with a single long oar.
        // The recovery lifts the blade; only the power stroke leaves a ring.
        ctx.strokeStyle=`rgba(40,47,46,${.8+daylight*.15})`;
        ctx.lineWidth=Math.max(.6,length*.055);
        ctx.beginPath();ctx.moveTo(...at(length*.46,0));
        ctx.quadraticCurveTo(...at(length*.57,0,length*.04),...at(length*.54,0,length*.13));ctx.stroke();
        ctx.beginPath();ctx.moveTo(...at(-length*.45,0));
        ctx.quadraticCurveTo(...at(-length*.55,0,length*.04),...at(-length*.51,0,length*.09));ctx.stroke();
        const stroke = (time * .34 * pace + route.phase * 2) % 1;
        const drive = stroke < .62;
        const swing = drive ? -.2 + stroke/.62*.38 : .18 - (stroke-.62)/.38*.38;
        const hand=at(-length*.27,length*.025,length*.24);
        const blade=at((-length*.24)+swing*length,length*.61,drive?0:length*.09*Math.sin((stroke-.62)/.38*Math.PI));
        const shoulder=at(-length*.29+Math.sin(stroke*TAU)*length*.018,0,length*.34);
        const hip=at(-length*.32,0,length*.15),head=at(-length*.29,0,length*.42);
        ctx.strokeStyle=`rgba(41,49,48,${.8+daylight*.2})`;ctx.lineCap='round';ctx.lineWidth=Math.max(.55,length*.047);
        ctx.beginPath();ctx.moveTo(...hip);ctx.lineTo(...shoulder);ctx.lineTo(...hand);ctx.stroke();
        ctx.beginPath();ctx.moveTo(...hip);ctx.lineTo(...at(-length*.35,length*.04));ctx.moveTo(...hip);ctx.lineTo(...at(-length*.29,-length*.04));ctx.stroke();
        ctx.fillStyle=`rgba(42,49,47,${.85+daylight*.15})`;ctx.beginPath();ctx.arc(head[0],head[1],Math.max(.5,length*.041),0,TAU);ctx.fill();
        ctx.strokeStyle=`rgba(102,90,69,${.7+daylight*.27})`;ctx.lineWidth=Math.max(.42,length*.025);
        ctx.beginPath();ctx.moveTo(...hand);ctx.lineTo(...blade);ctx.stroke();
        ctx.lineWidth=Math.max(.7,length*.047);ctx.beginPath();ctx.moveTo(...blade);ctx.lineTo(...at((-length*.24)+swing*length+length*.045,length*.66,drive?0:length*.09));ctx.stroke();
        if(drive){
          ctx.save();waterClip();
          const ringAge=(stroke/.62+.1)%1, ring=at((-length*.24)+swing*length,length*.66);
          ctx.strokeStyle=`rgba(243,245,234,${opacity*.38*(1-ringAge)*(.4+daylight*.6)})`;ctx.lineWidth=.6;
          ctx.beginPath();ctx.ellipse(ring[0],ring[1],length*(.025+ringAge*.065),length*(.012+ringAge*.025),0,0,TAU);ctx.stroke();ctx.restore();
        }
      } else if (route.kind === 'rowboat') {
        const stroke=(time*.42*pace+route.phase*2)%1;
        const sweep=Math.sin(stroke*TAU)*length*.22;
        const seat=at(-length*.06,0,length*.08);
        ctx.strokeStyle=`rgba(116,96,69,${.7+daylight*.25})`;
        ctx.lineWidth=Math.max(.45,length*.032);ctx.lineCap='round';
        for(const side of [-1,1]) {
          const hand=at(-length*.08,beam*side*.5,length*.15);
          const blade=at(length*.03+sweep,side*length*.5);
          ctx.beginPath();ctx.moveTo(...hand);ctx.lineTo(...blade);ctx.stroke();
          if(stroke<.5){ctx.save();waterClip();ctx.strokeStyle=`rgba(240,243,232,${opacity*.22*Math.sin(stroke*Math.PI*2)})`;ctx.lineWidth=.55;ctx.beginPath();ctx.ellipse(blade[0],blade[1],length*.05,length*.022,0,0,TAU);ctx.stroke();ctx.restore();}
        }
        ctx.fillStyle=`rgba(88,106,103,${.8+daylight*.18})`;
        ctx.beginPath();ctx.ellipse(seat[0],seat[1]-length*.065,length*.065,length*.09,0,0,TAU);ctx.fill();
        const head=at(-length*.07,0,length*.23);
        ctx.fillStyle=`rgba(123,110,90,${.8+daylight*.18})`;ctx.beginPath();ctx.arc(head[0],head[1],Math.max(.5,length*.046),0,TAU);ctx.fill();
      } else if (route.kind === 'yacht') {
        const cabinHeight=length*.13;
        path([at(length*.24,-beam*.67,cabinHeight*.7),at(length*.13,beam*.65,cabinHeight),at(-length*.28,beam*.65,cabinHeight),at(-length*.35,-beam*.65,cabinHeight)]);
        ctx.fillStyle=`rgb(${Math.round(158+daylight*85)},${Math.round(162+daylight*81)},${Math.round(159+daylight*77)})`;ctx.fill();
        ctx.strokeStyle=`rgba(207,222,219,${.45+daylight*.35})`;ctx.lineWidth=Math.max(.5,length*.035);ctx.stroke();
        path([at(length*.2,-beam*.58,cabinHeight*.75),at(length*.095,beam*.58,cabinHeight*.92),at(-length*.07,beam*.58,cabinHeight*.92),at(-length*.02,-beam*.58,cabinHeight*.75)]);
        ctx.fillStyle=`rgba(69,110,122,${.5+daylight*.16})`;ctx.fill();
        ctx.strokeStyle=`rgba(231,236,221,${.6+daylight*.3})`;ctx.lineWidth=Math.max(.4,length*.025);
        ctx.beginPath();ctx.moveTo(...at(length*.32,-beam*.55,length*.03));ctx.lineTo(...at(length*.43,0,length*.03));ctx.lineTo(...at(length*.28,beam*.62,length*.03));ctx.stroke();
        if(daylight<.45){ctx.fillStyle=`rgba(245,215,155,${.8*(1-daylight/.45)})`;ctx.beginPath();const lamp=at(-length*.14,beam*.6,length*.09);ctx.arc(lamp[0],lamp[1],Math.max(.45,length*.025),0,TAU);ctx.fill();}
      } else {
        const cabinHeight = length * (route.kind === 'canalboat' ? .105 : .16);
        path([at(length*.22,-beam*.6,cabinHeight),at(length*.22,beam*.6,cabinHeight),
          at(-length*.28,beam*.6,cabinHeight),at(-length*.28,-beam*.6,cabinHeight)]);
        ctx.fillStyle = `rgb(${Math.round(150+daylight*70)},${Math.round(154+daylight*68)},${Math.round(145+daylight*65)})`;ctx.fill();
        ctx.strokeStyle = `rgba(95,108,105,${.4+daylight*.22})`;ctx.lineWidth=Math.max(.35,length*.025);ctx.stroke();
        if(route.kind==='canalboat'){
          // A low glazed sightseeing boat, not a tall ocean-going cabin.
          path([at(length*.19,-beam*.58,cabinHeight*1.02),at(length*.19,beam*.56,cabinHeight*1.02),at(-length*.26,beam*.56,cabinHeight*1.02),at(-length*.26,-beam*.58,cabinHeight*1.02)]);
          ctx.fillStyle=`rgba(89,128,132,${.36+daylight*.23})`;ctx.fill();
          ctx.strokeStyle=`rgba(224,231,218,${.42+daylight*.25})`;ctx.lineWidth=Math.max(.35,length*.018);
          for(let j=0;j<4;j++){const along=-length*.24+j*length*.135;ctx.beginPath();ctx.moveTo(...at(along,-beam*.6,cabinHeight*1.05));ctx.lineTo(...at(along,beam*.6,cabinHeight*1.05));ctx.stroke();}
        }
        const cabinA=at(-length*.25,beam*.65,cabinHeight*.62),cabinB=at(length*.2,beam*.65,cabinHeight*.62);
        ctx.strokeStyle=`rgba(66,89,94,${.28+daylight*.22})`;ctx.lineWidth=Math.max(.5,length*.041);
        ctx.beginPath();ctx.moveTo(...cabinA);ctx.lineTo(...cabinB);ctx.stroke();
        if (daylight < .55) {
          const lightAlpha = (1-smooth(.08,.55,daylight))*.84;
          ctx.fillStyle=`rgba(242,208,144,${lightAlpha})`;
          for(let j=0;j<3;j++){
            const lamp=at((-length*.18)+j*length*.13,beam*.68,cabinHeight*.6);
            ctx.beginPath();ctx.ellipse(lamp[0],lamp[1],Math.max(.4,length*.024),Math.max(.32,length*.02),0,0,TAU);ctx.fill();
          }
        }
      }
      ctx.restore();
    }
    function drawBoats(time) {
      visibleBoats = 0;
      for (const route of acceptedRoutes) {
        const {progress} = routeProgress(route,time);
        const entry = smooth(0,.075,progress), exit = 1-smooth(.925,1,progress);
        const leisure=['rowboat','gondola','sailboat','yacht'].includes(route.kind);
        const weatherOperation=leisure?environment.activity:Math.max(.5,environment.activity);
        const dayOperation = (!route.night ? smooth(.12,.4,daylight) : 1)*weatherOperation;
        const opacity = entry * exit * dayOperation;
        if (opacity < .002) continue;
        const p=screenPosition(route,progress), margin=route.width*cover.width;
        if(p.x < -margin || p.x > width+margin || p.y < -margin || p.y > height+margin) continue;
        drawWake(route,progress,opacity,time);drawVessel(route,progress,opacity,time);visibleBoats++;
      }
    }
    function drawBirds(time) {
      visibleBirds=0;
      const dayOpacity=smooth(.17,.4,daylight)*environment.activity;
      if(nightTheme || !birds.length || dayOpacity<.003)return;
      if(flightBounds.right<=flightBounds.left || flightBounds.bottom<=flightBounds.top)return;
      const {pace}=dynamics();
      const top=cover.y+flightBounds.top*cover.height, bottom=cover.y+flightBounds.bottom*cover.height;
      if(bottom<=0 || top>=height)return;
      ctx.save();
      ctx.beginPath();ctx.rect(cover.x+flightBounds.left*cover.width,top,
        (flightBounds.right-flightBounds.left)*cover.width,bottom-top);ctx.clip();
      // Subtract every reviewed tower/roof exclusion independently so holes
      // remain a union, including overlapping scene and motion exclusions.
      for(const polygon of skyExclusions){
        ctx.beginPath();ctx.rect(-1,-1,width+2,height+2);
        polygon.forEach(([x,y],i)=>i?ctx.lineTo(cover.x+x*cover.width,cover.y+y*cover.height):
          ctx.moveTo(cover.x+x*cover.width,cover.y+y*cover.height));
        ctx.closePath();ctx.clip('evenodd');
      }
      for(const bird of birds){
        const driftTime=time+.38*Math.sin(time*.14+bird.phase*TAU);
        const progress=((driftTime*pace/bird.period+bird.phase)%1+1)%1;
        const localX=bird.direction>0?-.08+progress*1.16:1.08-progress*1.16;
        const size=clamp(cover.width*bird.size,2,5.6);
        const marginX=size*1.5/cover.width, marginY=size*1.5/cover.height;
        const band=flightBounds.bottom-flightBounds.top;
        if(band<marginY*2)continue;
        const lane=flightBounds.top+(.2+bird.lane*.6)*band;
        const arc=(Math.sin(progress*Math.PI)*.08+Math.sin(time*.24+bird.phase*TAU)*.07)*band;
        const localY=clamp(lane+arc,flightBounds.top+marginY,flightBounds.bottom-marginY);
        if(localX-marginX<flightBounds.left || localX+marginX>flightBounds.right)continue;
        if(skyExclusions.some(poly=>inside(localX,localY,poly) ||
          touchesRectangle(poly,localX-marginX,localY-marginY,localX+marginX,localY+marginY)))continue;
        const fade=smooth(flightBounds.left+marginX,flightBounds.left+marginX+.025,localX)*
          (1-smooth(flightBounds.right-marginX-.025,flightBounds.right-marginX,localX));
        if(fade<.003)continue;
        const x=cover.x+localX*cover.width,y=cover.y+localY*cover.height;
        if(x < -size*3 || x > width+size*3)continue;
        // Each bird has its own short powered burst and longer gliding rest.
        // The wings ease into the burst; slow banking foreshortens one wing.
        const beatCycle=((time/bird.beatPeriod+bird.flap)%1+1)%1;
        const beatEnvelope=smooth(0,.045,beatCycle)*(1-smooth(bird.beatDuty-.055,bird.beatDuty,beatCycle));
        const gustLift=.85+environment.gust*.15;
        const flap=Math.sin(time*bird.beatRate+bird.flap*7)*beatEnvelope*gustLift;
        const bank=Math.sin(time*.31+bird.phase*TAU)*bird.bank+Math.sin(time*.1+bird.flap)*.035;
        const glide=.06*Math.sin(time*.8+bird.flap);
        const tipY=(-.13+flap*.5+glide*(1-beatEnvelope))*size;
        const near=1+bank*.8,far=1-bank*.8;
        ctx.save();ctx.translate(x,y);ctx.rotate(bank+bird.direction*.045);
        ctx.globalAlpha=dayOpacity*fade*(.38+bird.size*14);
        ctx.lineCap='round';ctx.lineJoin='round';
        ctx.strokeStyle='rgb(83,101,107)';ctx.lineWidth=Math.max(.55,size*.16);
        ctx.beginPath();ctx.moveTo(-size*near,tipY+bank*size*.14);
        ctx.bezierCurveTo(-size*.6*near,tipY-size*.16,-size*.28,-size*.22,0,0);
        ctx.bezierCurveTo(size*.27,-size*.17,size*.63*far,tipY-size*.12,
          size*far,tipY+size*.035-bank*size*.14);ctx.stroke();
        ctx.fillStyle='rgb(80,98,104)';ctx.beginPath();
        ctx.ellipse(0,size*.025,size*.18,size*.085,-bird.direction*.2,0,TAU);ctx.fill();
        ctx.restore();visibleBirds++;
      }
      ctx.restore();
    }
    function render(time) {
      if (destroyed || !ctx || !cover || !visible || !intersecting || document.hidden) return;
      ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,width,height);
      visibleBoats=visibleWaves=visibleBirds=0;
      environmentCache.clear();environment=environmentAt(time);
      if(waveType){drawWaves(time);drawBoats(time);}drawBirds(time);
      frames++;
    }
    function resize() {
      if(destroyed)return;
      const rect=container.getBoundingClientRect();
      width=Math.max(0,rect.width);height=Math.max(0,rect.height);
      dpr=Math.min(2,window.devicePixelRatio||1,720/Math.max(1,width));
      const pw=Math.max(1,Math.round(width*dpr)),ph=Math.max(1,Math.round(height*dpr));
      if(canvas.width!==pw)canvas.width=pw;if(canvas.height!==ph)canvas.height=ph;
      if(image.naturalWidth&&image.naturalHeight&&width&&height){
        const scale=Math.max(width/image.naturalWidth,height/image.naturalHeight);
        const cw=image.naturalWidth*scale,ch=image.naturalHeight*scale;
        const objectPosition=getComputedStyle(image).objectPosition.split(/\s+/);
        const fraction=(v,fallback)=>/^-?[\d.]+%$/.test(v||'')?parseFloat(v)/100:
          v==='left'||v==='top'?0:v==='right'||v==='bottom'?1:fallback;
        cover={width:cw,height:ch,x:(width-cw)*fraction(objectPosition[0],.5),y:(height-ch)*fraction(objectPosition[1],.5),scale};
        routesForImage();render(seconds);
      }else cover=null;
      updateLoop();
    }
    function onLoad(){if(destroyed)return;routeSignature='';resize();}
    function onError(){cover=null;stop();if(ctx){ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,canvas.width,canvas.height);}}
    const onVisibility=()=>{if(!document.hidden)render(seconds);updateLoop();},onReduced=()=>updateLoop();
    image.addEventListener('load',onLoad);image.addEventListener('error',onError);
    document.addEventListener('visibilitychange',onVisibility);reducedQuery.addEventListener('change',onReduced);
    window.addEventListener('resize',resize,{passive:true});
    if('ResizeObserver'in window){resizeObserver=new ResizeObserver(resize);resizeObserver.observe(container);}
    if('IntersectionObserver'in window){
      intersecting=false;intersectionObserver=new IntersectionObserver(entries=>{
        const wasIntersecting=intersecting;intersecting=entries.some(e=>e.isIntersecting);
        if(intersecting&&!wasIntersecting)render(seconds);updateLoop();
      },{threshold:0});
      intersectionObserver.observe(container);
    }
    resize();
    return {
      setSky(value){
        if(destroyed)return;
        sky=value&&typeof value==='object'?value:null;
        daylight=finite(sky?.daylight)?clamp(sky.daylight):(nightTheme?0:1);
        illumination=finite(sky?.illumination)?clamp(sky.illumination):(nightTheme?.35:1);
        if(!raf)render(seconds);
        updateLoop();
      },
      setIntensity(value){if(destroyed)return;intensity=['soft','expressive','bold'].includes(value)?value:'bold';render(seconds);},
      setVisible(value){if(destroyed)return;visible=Boolean(value);if(visible)resize();else updateLoop();},
      setPlaying(value){if(destroyed)return;playing=Boolean(value);updateLoop();},
      renderAt(time){if(destroyed||!finite(time)||time<0)return;stop();playing=false;seconds=time;render(seconds);},
      destroy(){
        if(destroyed)return;destroyed=true;stop();resizeObserver?.disconnect();intersectionObserver?.disconnect();
        image.removeEventListener('load',onLoad);image.removeEventListener('error',onError);
        document.removeEventListener('visibilitychange',onVisibility);reducedQuery.removeEventListener('change',onReduced);
        window.removeEventListener('resize',resize);canvas.remove();canvas.width=canvas.height=1;cover=null;acceptedRoutes=[];
      },
      get state(){return {sceneId,theme,playing,visible,intersecting,pageVisible:!document.hidden,running:!!raf&&shouldRun(),
        destroyed,reducedMotion:reducedQuery.matches,motionAllowed:allowed(),intensity,maxFps,time:seconds,frames,
        width,height,dpr,pixelWidth:canvas.width,pixelHeight:canvas.height,waterRegions:water.length,
        exclusionRegions:exclusions.length,waveType,visibleWaves,birds:visibleBirds,birdCount:birds.length,boats:visibleBoats,acceptedRoutes:acceptedRoutes.length,
        routes:acceptedRoutes.map(r=>({id:r.id,kind:r.kind,width:r.width,duration:r.duration,night:r.night,progress:routeProgress(r,seconds).progress,speed:routeSpeed(r,seconds)})),
        rejectedRoutes:rejectedRoutes.map(r=>({...r})),daylight,illumination,
        dynamics:dynamics(),environment:{...environment},birdSkyBounds:{...flightBounds},birdSkyExclusions:skyExclusions.length,source:'artistic-water-traffic-and-birds',imageTransform:cover?{...cover}:null};}
    };
  }
  window.NycMarineV13={attach};
})();
