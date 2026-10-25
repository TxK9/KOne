/* NYC art-preview sky v5. UTC ephemeris + explicit timezone representative city.
 * Timezone selection is not GPS: the NYC art remains fixed; only its sky proxy changes.
 * SunCalc 2.1.1: angles are DEGREES, azimuth clockwise from NORTH.
 * The skyline is artwork: its camera azimuth is approximate; vertical scale is
 * an art-direction setting, never a claim of a calibrated panorama.
 */
(function (root, factory) {
  const api = factory(typeof module === 'object' && module.exports ? require('./vendor/suncalc-2.1.1.js') : root.SunCalc);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.NycSkyV5 = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function (SunCalc) {
  'use strict';
  if (!SunCalc) throw new Error('Load vendor/suncalc-2.1.1.js before sky-v5.js');
  const NYC = Object.freeze({ lat: 40.7128, lon: -74.0060, zone: 'America/New_York' });
  const DEMO_DATE = '2026-10-17';
  const rad = d => d * Math.PI / 180;
  const wrap = d => ((d + 180) % 360 + 360) % 360 - 180;
  const clamp = (n, min = 0, max = 1) => Math.max(min, Math.min(max, n));
  const smooth = (a, b, value) => { const t = clamp((value - a) / (b - a)); return t * t * (3 - 2 * t); };
  const pad = n => String(n).padStart(2, '0');
  const REPRESENTATIVES = Object.freeze({
    'America/New_York': Object.freeze({ lat: 40.7128, lon: -74.0060, label: '纽约' }),
    'America/Los_Angeles': Object.freeze({ lat: 34.0522, lon: -118.2437, label: '洛杉矶' }),
    'Asia/Shanghai': Object.freeze({ lat: 31.2304, lon: 121.4737, label: '上海' }),
    'Asia/Tokyo': Object.freeze({ lat: 35.6762, lon: 139.6503, label: '东京' }),
    'Europe/London': Object.freeze({ lat: 51.5074, lon: -.1278, label: '伦敦' }),
    'Europe/Paris': Object.freeze({ lat: 48.8566, lon: 2.3522, label: '巴黎' }),
    'Australia/Sydney': Object.freeze({ lat: -33.8688, lon: 151.2093, label: '悉尼' }),
    'UTC': Object.freeze({ lat: 0, lon: 0, label: 'UTC 零度经线近似（赤道）', approximate: true }),
  });
  const ALIASES = Object.freeze({ NY: 'America/New_York', NYC: 'America/New_York', LA: 'America/Los_Angeles', Shanghai: 'Asia/Shanghai', Tokyo: 'Asia/Tokyo', London: 'Europe/London', Paris: 'Europe/Paris', Sydney: 'Australia/Sydney' });
  const zoneOptions = Object.freeze([
    Object.freeze({ value: 'DEVICE', label: '设备时区' }),
    ...Object.entries(REPRESENTATIVES).map(([value, entry]) => Object.freeze({ value, label: value === 'UTC' ? 'UTC（近似）' : entry.label })),
  ]);
  const formatterCache = new Map(), demoCache = new Map(), dayCache = new Map(), locationCache = new Map();
  function instantMs(instant) {
    const ms = instant instanceof Date ? instant.getTime() : typeof instant === 'string' ? Date.parse(instant) : Number(instant);
    if (!Number.isFinite(ms) || !Number.isFinite(new Date(ms).getTime())) throw new RangeError('Invalid UTC instant');
    return ms;
  }
  function zoneName(input = NYC.zone) {
    const raw = typeof input === 'object' && input ? input.zone || input.timeZone || NYC.zone : input || NYC.zone;
    const selected = raw === 'DEVICE' ? Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' : ALIASES[raw] || raw;
    try { return new Intl.DateTimeFormat('en-US', { timeZone: selected }).resolvedOptions().timeZone; }
    catch (_) { throw new RangeError(`Unsupported IANA timezone: ${selected}`); }
  }
  function formatter(zone) {
    if (!formatterCache.has(zone)) {
      if (formatterCache.size > 64) formatterCache.clear();
      formatterCache.set(zone, new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }));
    }
    return formatterCache.get(zone);
  }
  function rawParts(ms, zone) {
    return Object.fromEntries(formatter(zone).formatToParts(new Date(ms)).filter(x => x.type !== 'literal').map(x => [x.type, Number(x.value)]));
  }
  function partsUTC(p) {
    const date = new Date(0); date.setUTCFullYear(p.year, p.month - 1, p.day); date.setUTCHours(p.hour || 0, p.minute || 0, p.second || 0, 0); return date.getTime();
  }
  function offsetAt(ms, zone) { return Math.round((partsUTC(rawParts(ms, zone)) - Math.floor(ms / 1000) * 1000) / 60000); }
  function parts(instant, input = NYC.zone) {
    const ms = instantMs(instant), zone = zoneName(input), p = rawParts(ms, zone);
    const date = `${p.year}-${pad(p.month)}-${pad(p.day)}`, time = `${pad(p.hour)}:${pad(p.minute)}`;
    const offsetMinutes = offsetAt(ms, zone), abs = Math.abs(offsetMinutes);
    return { ...p, date, time, dateTime: `${date} ${time}`, zone, offsetMinutes, offsetLabel: `UTC${offsetMinutes < 0 ? '−' : '+'}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}` };
  }
  function localParts(instant, zone = NYC.zone) { return parts(instant, zone); }
  function resolveLocation(input = NYC.zone, instant = Date.now()) {
    const zone = zoneName(input), requestedZone = typeof input === 'object' && input ? input.zone || input.timeZone || zone : input || NYC.zone;
    if (typeof input === 'object' && input && ('lat' in input || 'lon' in input)) {
      const lat = Number(input.lat), lon = Number(input.lon);
      if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new RangeError('Explicit latitude/longitude required together, within valid bounds');
      return { zone, requestedZone, lat, lon, label: input.label || '指定地点', approximate: false, source: 'explicit-coordinates', isRepresentative: false, isDeviceZone: requestedZone === 'DEVICE' };
    }
    const known = REPRESENTATIVES[zone];
    if (known) return { ...known, zone, requestedZone, approximate: Boolean(known.approximate), source: known.approximate ? 'timezone-meridian' : 'timezone-representative-city', isRepresentative: true, isDeviceZone: requestedZone === 'DEVICE' };
    const year = new Date(instantMs(instant)).getUTCFullYear(), key = `${zone}|${year}`;
    if (!locationCache.has(key)) {
      // Timezones are regions, not positions. For an unlisted valid zone use
      // its standard-offset meridian at the equator, openly labelled approximate.
      // Taking the smaller January/July offset avoids moving the proxy longitude
      // when daylight saving starts. No geolocation or network request is used.
      const standardOffset = Math.min(offsetAt(Date.UTC(year, 0, 15, 12), zone), offsetAt(Date.UTC(year, 6, 15, 12), zone));
      locationCache.set(key, { zone, lat: 0, lon: wrap(standardOffset / 4), label: `${zone} 时区中线近似（赤道，非定位）`, approximate: true, source: 'timezone-meridian' });
    }
    return { ...locationCache.get(key), requestedZone, isRepresentative: true, isDeviceZone: requestedZone === 'DEVICE' };
  }
  function parseLocal(date, time = '00:00') {
    const d = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date), t = /^(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(time);
    if (!d || !t) throw new RangeError('Expected YYYY-MM-DD and HH:mm[:ss]');
    const p = { year: +d[1], month: +d[2], day: +d[3], hour: +t[1], minute: +t[2], second: +(t[3] || 0) };
    const target = partsUTC(p), canonical = new Date(target);
    if (canonical.getUTCFullYear() !== p.year || canonical.getUTCMonth() + 1 !== p.month || canonical.getUTCDate() !== p.day || p.hour > 23 || p.minute > 59 || p.second > 59) throw new RangeError('Invalid local date/time');
    return target;
  }
  function localCandidates(target, zone) {
    const offsets = new Set([-172800000, -86400000, 0, 86400000, 172800000].map(delta => offsetAt(target + delta, zone) * 60000));
    return [...offsets].map(offset => target - offset).filter(ms => partsUTC(rawParts(ms, zone)) === target).sort((a, b) => a - b);
  }
  function utcFromLocal(date, time, input = NYC.zone) {
    const zone = zoneName(input), candidates = localCandidates(parseLocal(date, time), zone);
    if (candidates.length !== 1) throw new RangeError(candidates.length ? `Ambiguous local time in ${zone}` : `Nonexistent local time in ${zone}`);
    return candidates[0];
  }
  function addDays(date, days) { return new Date(parseLocal(date, '12:00') + days * 86400000).toISOString().slice(0, 10); }
  function firstOfDate(date, zone) {
    const target = parseLocal(date, '00:00'), candidates = localCandidates(target, zone);
    if (candidates.length) return candidates[0];
    // Some IANA zones change offset at midnight. Find the first existing
    // instant of that civil date rather than silently normalizing wall time.
    let lo = target - 36 * 3600000, hi = target + 36 * 3600000;
    while (hi - lo > 1) { const mid = Math.floor((lo + hi) / 2), p = rawParts(mid, zone), localDate = `${p.year}-${pad(p.month)}-${pad(p.day)}`; if (localDate < date) lo = mid; else hi = mid; }
    const p = rawParts(hi, zone);
    if (`${p.year}-${pad(p.month)}-${pad(p.day)}` !== date) throw new RangeError(`Nonexistent local date in ${zone}`);
    return hi;
  }
  function localDayRange(date, input = NYC.zone) {
    const zone = zoneName(input), key = `${date}|${zone}`;
    if (!dayCache.has(key)) {
      const start = firstOfDate(date, zone), end = firstOfDate(addDays(date, 1), zone), durationMs = end - start;
      if (dayCache.size > 256) dayCache.clear();
      dayCache.set(key, { date, zone, start, end, durationMs, durationHours: durationMs / 3600000 });
    }
    return { ...dayCache.get(key) };
  }
  function normalizedScene(scene = {}) {
    return {
      id: scene.id || 'original', cameraBearing: Number(scene.cameraBearing ?? scene.bearingDeg ?? 180),
      horizontalFov: Number(scene.horizontalFov ?? 100), horizonY: Number(scene.horizonY ?? .29),
      verticalScale: Number(scene.verticalScale ?? .12),
      // Normalized relative to the complete 9:19.5 wallpaper/phone, not a crop.
      skySafe: { left: .10, right: .90, top: .13, bottom: .28, ...scene.skySafe },
      skyExclusions: scene.skyExclusions || [], aspect: Number(scene.aspect ?? 9 / 19.5),
      demoDate: scene.demoDate || DEMO_DATE, sunDemoDates: scene.sunDemoDates || [],
      maxMoonDays: clamp(Number(scene.maxMoonDays ?? 8), 0, 14),
    };
  }
  function project(position, scene) {
    const s = normalizedScene(scene), delta = rad(wrap(position.azimuth - s.cameraBearing));
    const altitude = rad(position.altitude), forward = Math.cos(altitude) * Math.cos(delta);
    if (forward <= 0) return { x: NaN, y: NaN, forward, inFront: false, inFrame: false };
    const x = .5 + Math.tan(delta) / (2 * Math.tan(rad(s.horizontalFov / 2)));
    const y = s.horizonY - s.verticalScale * Math.tan(altitude) / Math.cos(delta);
    return { x, y, forward, inFront: true, inFrame: x >= 0 && x <= 1 && y >= 0 && y <= 1 };
  }
  function vector(position) { const a = rad(position.azimuth), h = rad(position.altitude); return [Math.cos(h) * Math.sin(a), Math.cos(h) * Math.cos(a), Math.sin(h)]; }
  const dot = (a, b) => a.reduce((sum, x, i) => sum + x * b[i], 0);
  function lightDirection(sun, moon, scene) {
    // Project the celestial tangent pointing from the Moon towards the Sun.
    // This handles northern/southern sky orientations without a fixed waxing
    // crescent icon or an arbitrary screen-left/screen-right convention.
    const s = normalizedScene(scene), m = vector(moon), v = vector(sun), b = rad(s.cameraBearing);
    const tangent = v.map((n, i) => n - dot(v, m) * m[i]);
    const right = [Math.cos(b), -Math.sin(b), 0], forward = [Math.sin(b), Math.cos(b), 0];
    const mf = dot(m, forward), mr = dot(m, right), tf = dot(tangent, forward);
    const dx = (dot(tangent, right) * mf - mr * tf) / (2 * Math.tan(rad(s.horizontalFov / 2)) * mf * mf);
    const dy = -s.verticalScale * (tangent[2] * mf - m[2] * tf) / (mf * mf);
    // dx/dy use normalized wallpaper coordinates; canvas rotates in pixels.
    // Account for height / width so the lit limb points towards the Sun in a
    // tall wallpaper instead of flattening its vertical direction.
    return { dx, dy, angle: Math.atan2(dy / s.aspect, dx) };
  }
  function phaseName(phase) {
    return ['新月附近', '盈月牙', '上弦附近', '盈凸月', '满月附近', '亏凸月', '下弦附近', '残月'][Math.round((((phase % 1) + 1) % 1) * 8) % 8];
  }
  function isSafe(p, s, radiusX) {
    const rY = radiusX * s.aspect, a = s.skySafe;
    if (p.x - radiusX < a.left || p.x + radiusX > a.right || p.y - rY < a.top || p.y + rY > a.bottom) return false;
    return !s.skyExclusions.some(b => p.x + radiusX > b.left && p.x - radiusX < b.right && p.y + rY > b.top && p.y - rY < b.bottom);
  }
  function solarLight(sun, scene, dark) {
    const s = normalizedScene(scene), bearing = rad(s.cameraBearing), v = vector(sun), right = [Math.cos(bearing), -Math.sin(bearing), 0];
    const px = dot(v, right), py = -v[2] / s.aspect, length = Math.hypot(px, py) || 1;
    const direct = smooth(0, 12, sun.altitude), intensity = dark ? 0 : direct;
    return {
      screenX: Number.isFinite(sun.x) ? sun.x : null, screenY: Number.isFinite(sun.y) ? sun.y : null,
      screenDirectionX: px / length, screenDirectionY: py / length,
      rayDirectionX: -px / length, rayDirectionY: -py / length,
      direction: { x: px / length, y: py / length },
      side: Math.abs(px) < .035 ? 'center' : px < 0 ? 'left' : 'right',
      altitude: sun.altitude, azimuth: sun.azimuth, intensity, solarIntensity: direct,
      daylight: smooth(-6, 12, sun.altitude), offscreen: !sun.inFrame, behind: !sun.inFront,
      warmth: 1 - smooth(0, 32, sun.altitude), source: 'solar-position',
    };
  }
  function sample(scene, theme, instant, input = NYC.zone) {
    const s = normalizedScene(scene), ms = instantMs(instant), date = new Date(ms), dark = theme === 'night' || theme === 'dark';
    const location = resolveLocation(input, ms), sunPosition = SunCalc.getPosition(date, location.lat, location.lon), moonPosition = SunCalc.getMoonPosition(date, location.lat, location.lon);
    const illumination = SunCalc.getMoonIllumination(date), sunProjection = project(sunPosition, s), moonProjection = project(moonPosition, s);
    const sunSafe = sunProjection.inFront && sunProjection.inFrame && isSafe(sunProjection, s, .030), moonSafe = moonProjection.inFront && moonProjection.inFrame && isSafe(moonProjection, s, .032);
    const sun = { ...sunPosition, ...sunProjection, radiusX: .030, aboveHorizon: sunPosition.altitude > 0, safe: sunSafe, physicallyVisible: sunPosition.altitude > 0 && sunSafe };
    const moon = { ...moonPosition, ...moonProjection, radiusX: .032, aboveHorizon: moonPosition.altitude > 0, safe: moonSafe, physicallyVisible: moonPosition.altitude > 0 && moonSafe };
    sun.visible = !dark && sun.physicallyVisible;
    moon.visible = dark && sun.altitude < -6 && moon.physicallyVisible;
    const body = dark ? moon : sun, themeMatches = dark ? sun.altitude < -6 : sun.altitude > 0;
    const brightDirection = lightDirection(sunPosition, moonPosition, s), local = parts(ms, location.zone);
    let reason = '';
    if (!body.aboveHorizon) reason = `${dark ? '月亮' : '太阳'}在地平线下`;
    else if (!themeMatches) reason = dark ? '此刻尚未入夜' : '此刻尚未日出';
    else if (!body.inFront || !body.inFrame) reason = `${dark ? '月亮' : '太阳'}在画面之外`;
    else if (!body.safe) reason = `${dark ? '月亮' : '太阳'}避让建筑与界面`;
    const light = solarLight(sun, s, dark);
    return {
      ...body, instant: ms, iso: date.toISOString(), zone: location.zone, requestedZone: location.requestedZone,
      localDate: local.date, localTime: local.time, localDateTime: local.dateTime, offsetMinutes: local.offsetMinutes, offsetLabel: local.offsetLabel,
      location, locationLabel: location.label, label: `${local.dateTime} · ${location.label}`,
      theme: dark ? 'night' : 'day', body: dark ? 'moon' : 'sun',
      visible: body.visible, themeMatches, reason, sun, moon, daylight: light.daylight, light,
      sunAltitude: sun.altitude, illumination: illumination.fraction, phase: illumination.phase, phaseName: phaseName(illumination.phase),
      brightDirection, tilt: brightDirection.angle, radiusX: body.radiusX, cameraBearing: s.cameraBearing,
      approximation: `${location.label}天体方位估算 · 艺术构图投影；${location.isRepresentative ? '时区代表地点，不是设备定位' : '指定经纬度'}`,
    };
  }
  function bestOnDate(scene, theme, date, input) {
    const s = normalizedScene(scene), location = resolveLocation(input), range = localDayRange(date, location.zone), dark = theme === 'night' || theme === 'dark';
    let best = null;
    // Scan UTC elapsed time, not 1440 assumed wall-clock minutes. This visits
    // both occurrences on a 25-hour day and omits the missing spring hour.
    function evaluate(instant) {
      const sun = SunCalc.getPosition(new Date(instant), location.lat, location.lon);
      if (dark ? sun.altitude >= -6 : sun.altitude <= 0) return;
      const body = dark ? SunCalc.getMoonPosition(new Date(instant), location.lat, location.lon) : sun, p = project(body, s), radius = dark ? .032 : .030;
      if (body.altitude <= 0 || !p.inFront || !p.inFrame || !isSafe(p, s, radius)) return;
      if(dark&&SunCalc.getMoonIllumination(new Date(instant)).fraction<.18)return;
      const targetY = (s.skySafe.top + s.skySafe.bottom) / 2;
      const score = -Math.abs(p.x - .71) * .55 - Math.abs(p.y - targetY) * 2 + Math.min(body.altitude, 20) * .0005;
      if (!best || score > best.score) best = { instant, score };
    }
    for (let instant = range.start; instant < range.end; instant += 5 * 60000) evaluate(instant);
    if (!best) return null;
    const roughInstant = best.instant;
    for (let instant = Math.max(range.start, roughInstant - 10 * 60000); instant < Math.min(range.end, roughInstant + 10 * 60000); instant += 60000) evaluate(instant);
    return { ...sample(s, theme, best.instant, input), score: best.score };
  }
  function chooseDemo(scene, theme, input = NYC.zone, date) {
    const s = normalizedScene(scene), location = resolveLocation(input), mode = theme === 'dark' || theme === 'night' ? 'night' : 'day';
    const requestedDate = date || parts(Date.now(), location.zone).date;
    parseLocal(requestedDate, '12:00');
    const key = JSON.stringify([s, mode, location, requestedDate]);
    if (demoCache.has(key)) return { ...demoCache.get(key) };
    let state = bestOnDate(s, mode, requestedDate, input);
    // Bounded search, no synchronous 45-day sweep. Configured seasonal dates
    // remain explicit fallback examples, never disguised as today's sky.
    const maxDays = mode === 'night' ? s.maxMoonDays : 3;
    for (let days = 1; !state && days <= maxDays; days++) state = bestOnDate(s, mode, addDays(requestedDate, days), input);
    if (!state && mode === 'day') {
      for (const fallbackDate of s.sunDemoDates.slice(0, 4)) { state = bestOnDate(s, mode, fallbackDate, input); if (state) break; }
    }
    // The original paintings are not calibrated panoramas. When the real
    // camera projection cannot show a body, use an explicitly labelled art
    // composition. Actual-time sample() never moves a body into the frame.
    if (!state) state = compositionDemo(s, mode, input, requestedDate);
    state.alternateDate = state.localDate !== requestedDate;
    state.demoRequestedDate = requestedDate;
    state.label = `演示 · ${state.localDateTime} · ${location.label}${state.alternateDate ? '（另选日期）' : ''}${state.composition?' · 日月艺术构图':''}`;
    if (demoCache.size > 1024) demoCache.clear();
    demoCache.set(key, state);
    return { ...state };
  }
  function compositionPoint(scene,theme){
    const s=normalizedScene(scene),radius=theme==='night'?.032:.030;
    let best=null;
    for(let yi=0;yi<=16;yi++)for(let xi=0;xi<=48;xi++){
      const p={x:s.skySafe.left+(s.skySafe.right-s.skySafe.left)*xi/48,y:s.skySafe.top+(s.skySafe.bottom-s.skySafe.top)*yi/16};
      if(!isSafe(p,s,radius))continue;
      const score=Math.abs(p.x-.71)+Math.abs(p.y-(s.skySafe.top+s.skySafe.bottom)/2)*2;
      if(!best||score<best.score)best={...p,score};
    }
    return best;
  }
  function composeState(scene,theme,state){
    const point=compositionPoint(scene,theme);if(!point)return {...state,composition:false};
    const dark=theme==='night',body=dark?'moon':'sun',radiusX=dark?.032:.030;
    if(!state.themeMatches||!state[body]?.aboveHorizon)return {...state,composition:false};
    const displayed={...state[body],x:point.x,y:point.y,radiusX,inFront:true,inFrame:true,safe:true,visible:true,projection:'art-composition'};
    const next={...state,...displayed,[body]:displayed,visible:true,composition:true,reason:'',approximation:state.approximation+' · 日月位置为艺术构图'};
    if(!dark){
      const dx=(.5-point.x)*.85,dy=.35,length=Math.hypot(dx,dy);
      next.light={...state.light,screenX:point.x,screenY:point.y,rayDirectionX:dx/length,rayDirectionY:dy/length,source:'art-composition'};
    }
    return next;
  }
  function compositionDemo(scene,theme,input,requestedDate){
    const location=resolveLocation(input),dark=theme==='night';let best=null;
    // Prefer the selected date and a visible lunar phase. Polar day/night can
    // require a seasonal example, which remains explicit in the date readout.
    const offsets=dark?[0,3,7,11,15,19,23,27,90,180]:[0,30,60,90,120,180];
    for(const offset of offsets){
      const date=addDays(requestedDate,offset),range=localDayRange(date,location.zone);
      for(let instant=range.start;instant<range.end;instant+=20*60000){
        const value=sample(scene,theme,instant,input);
        if(dark?value.sun.altitude>=-6||value.moon.altitude<=0||value.illumination<.18:value.sun.altitude<=4)continue;
        const score=dark?value.moon.altitude*.02+value.illumination:Math.min(value.sun.altitude,35)*.03;
        if(!best||score>best.score)best={...value,score};
      }
      if(best)break;
    }
    const fallback=best||sample(scene,theme,utcFromLocal(requestedDate,dark?'21:00':'12:00',location.zone),input);
    return composeState(scene,theme,fallback);
  }
  function litBoundaryX(y, fraction) { return -(2 * clamp(fraction) - 1) * Math.sqrt(Math.max(0, 1 - y * y)); }
  function drawSun(ctx, x, y, r) {
    // Flat, quiet pigment. The only gradient is the very faint OUTSIDE haze;
    // the disc has no specular highlight or a shaded metal-ball circumference.
    const haze = ctx.createRadialGradient(x, y, r, x, y, r * 3.4);
    haze.addColorStop(0, 'rgba(230,186,125,.13)'); haze.addColorStop(1, 'rgba(230,186,125,0)');
    ctx.fillStyle = haze; ctx.fillRect(x - 3.4 * r, y - 3.4 * r, 6.8 * r, 6.8 * r);
    // Soft pigment edge, with a flat interior: no shaded sphere or hard sticker rim.
    for (let edge = 4; edge >= 1; edge--) {
      ctx.fillStyle = 'rgba(199,164,123,.025)'; ctx.beginPath(); ctx.arc(x, y, r * (1 + edge * .022), 0, Math.PI * 2); ctx.fill();
    }
    ctx.fillStyle = 'rgba(218,169,102,.88)'; ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
  }
  function drawMoon(ctx, x, y, r, state) {
    const haze = ctx.createRadialGradient(x, y, r, x, y, 3 * r);
    haze.addColorStop(0, `rgba(199,216,224,${.12 * state.illumination})`); haze.addColorStop(1, 'rgba(199,216,224,0)');
    ctx.fillStyle = haze; ctx.fillRect(x - 3 * r, y - 3 * r, 6 * r, 6 * r);
    ctx.save(); ctx.translate(x, y); ctx.rotate(state.tilt);
    ctx.fillStyle = 'rgba(173,191,204,.035)'; ctx.beginPath(); ctx.arc(0, 0, r, 0, Math.PI * 2); ctx.fill();
    // A disk's illuminated area equals fraction. Changing phase changes the
    // terminator rather than merely painting a full circle at lower opacity.
    ctx.beginPath();
    const steps = 160;
    for (let i = 0; i <= steps; i++) {
      const yy = -1 + i * 2 / steps, xx = Math.sqrt(Math.max(0, 1 - yy * yy));
      i ? ctx.lineTo(xx * r, yy * r) : ctx.moveTo(xx * r, yy * r);
    }
    for (let i = steps; i >= 0; i--) { const yy = -1 + i * 2 / steps; ctx.lineTo(litBoundaryX(yy, state.illumination) * r, yy * r); }
    ctx.closePath(); ctx.fillStyle = 'rgba(218,227,224,.84)'; ctx.fill();
    // Very faint flat surface mottling. The same phase path clips all marks;
    // nothing can illuminate the dark side or change the terminator.
    ctx.save(); ctx.clip(); ctx.fillStyle = 'rgba(108,128,140,.055)';
    for (const [mx, my, mr] of [[.25,-.3,.19],[-.2,.1,.27],[.4,.35,.14],[-.3,-.5,.11]]) {
      ctx.beginPath(); ctx.arc(mx*r,my*r,mr*r,0,Math.PI*2); ctx.fill();
    }
    ctx.restore();
    ctx.restore();
  }
  function attach(container, scene = {}, theme = 'day', options = {}) {
    if (!container || !container.ownerDocument) throw new TypeError('A sky layer DOM container is required');
    const canvas = container.ownerDocument.createElement('canvas');
    canvas.className = 'nyc-sky-canvas'; canvas.setAttribute('aria-hidden', 'true'); container.append(canvas);
    let currentScene = scene, currentTheme = theme, currentZone = options.zone || NYC.zone, destroyed = false, state,currentPresentation=null;
    // Only the parent preview owns time. Resize/setScene cannot start or advance
    // a clock and cannot discard an explicitly rendered UTC instant.
    let currentInstant = options.instant === undefined ? Date.now() : instantMs(options.instant);
    function render(instant = currentInstant, zone = currentZone,presentation=null) {
      if (destroyed) return null;
      if(arguments.length)currentPresentation=presentation;
      currentInstant = instantMs(instant); currentZone = zone;
      state = sample(currentScene, currentTheme, currentInstant, currentZone);
      if(currentPresentation?.composition&&currentPresentation.body===state.body)state=composeState(currentScene,currentTheme,state);
      state.demoRequestedDate=currentPresentation?.demoRequestedDate||null;
      const rect = container.getBoundingClientRect(), ratio = Math.min(2, globalThis.devicePixelRatio || 1,540/Math.max(1,rect.width));
      const w = Math.max(1, Math.round(rect.width * ratio)), h = Math.max(1, Math.round(rect.height * ratio));
      canvas.width = w; canvas.height = h; const ctx = canvas.getContext('2d'); ctx.clearRect(0, 0, w, h);
      if (state.visible) {
        // Glows respect the same sky and landmark masks as the actual disc.
        const safe=normalizedScene(currentScene);ctx.save();ctx.beginPath();ctx.rect(safe.skySafe.left*w,safe.skySafe.top*h,(safe.skySafe.right-safe.skySafe.left)*w,(safe.skySafe.bottom-safe.skySafe.top)*h);ctx.clip();
        for(const box of safe.skyExclusions){ctx.beginPath();ctx.rect(0,0,w,h);ctx.rect(box.left*w,box.top*h,(box.right-box.left)*w,(box.bottom-box.top)*h);ctx.clip('evenodd');}
        if (state.body === 'moon') drawMoon(ctx, state.x * w, state.y * h, state.radiusX * w, state);
        else drawSun(ctx, state.x * w, state.y * h, state.radiusX * w);
        ctx.restore();
      }
      container.dataset.skyVisible = String(state.visible); container.dataset.skyBody = state.body;
      container.dataset.skyInstant = state.iso; container.dataset.skyZone = state.zone; container.dataset.skyPhase = state.phase.toFixed(5);
      options.onState?.(state); return state;
    }
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(() => render()) : null;
    observer?.observe(container); render();
    return {
      render, get state() { return state; },
      setScene(nextScene, nextTheme = currentTheme) { currentScene = nextScene; currentTheme = nextTheme;currentPresentation=null; return render(); },
      destroy() { destroyed = true; observer?.disconnect(); canvas.remove(); },
    };
  }
  return { NYC, DEMO_DATE, REPRESENTATIVES, zoneOptions, resolveLocation, zoneName, parts, localDayRange, attach, sample, chooseDemo, project, normalizedScene, utcFromLocal, localParts, addDays, phaseName, litBoundaryX, lightDirection,compositionPoint,composeState };
});
