// Runs Codex's own code (country-preview/motion-v13.js, environment-v10.js, lighting-v13.js,
// weather-v13.js and weather-model-v11.js) on a few scenes and writes what it works out, for
// MotionTest and WeatherTest to hold the Kotlin port to:
//
//     node desktop/tools/motion_reference.mjs 484a556 > desktop/src/test/resources/city/motion-reference.json
//
// Only the parts that need no browser are run: seeds, wind, cloud pigment, water rows, the
// search for lit windows and the facade mask (on a made-up picture the tests make the same way),
// where rain, snow and ripples fall, and how an Open-Meteo answer reads.
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const ref = process.argv[2];
const show = path => execFileSync('git', ['show', `${ref}:country-preview/${path}`], {encoding: 'utf8', maxBuffer: 1 << 26});
const motion = show('motion-v13.js');
const environment = show('environment-v10.js');
const lighting = show('lighting-v13.js');
const weather = show('weather-v13.js');
const weatherModel = show('weather-model-v11.js');
const marineCode = show('marine-v13.js');
const lifeCode = show('city-life-v13.js');
const audioCode = show('ambient-audio-v11.js');
const catalog = JSON.parse(readFileSync(new URL('../src/main/resources/scenes/catalog.json', import.meta.url), 'utf8'));

// The helpers at the top of Codex's motion file, and its environment.
const helpers = motion.slice(motion.indexOf("const TAU"), motion.indexOf("function attach("));
const context = {Math, Number, Array, Set, String};
vm.createContext(context);
vm.runInContext(helpers + `;this.h = {seedOf, unitHash, random, validPolygons, horizontalSpans, intersectSpans, subtractSpans, sweptBoundarySpans, inside, bounds, clamp, smooth};`, context);
const window = {};
vm.runInNewContext(environment, {window, Math, Number, String, Map});
const h = context.h;

// The cloud pigment's pixel loop, lifted from createCloudTexture.
const pigmentBody = motion.slice(motion.indexOf("const rnd = random(seed), color"), motion.indexOf("pigmentCache.set(pigmentKey, pixels);"));
function pigment(seed, paletteNight) {
  const texture = {width: 192, height: 96};
  const ctx = {createImageData: (w, hh) => ({data: new Uint8ClampedArray(w * hh * 4)})};
  return new Function('seed', 'paletteNight', 'texture', 'ctx', 'random', 'clamp', pigmentBody + '; return pixels.data;')(
    seed, paletteNight, texture, ctx, h.random, h.clamp);
}

// The search for lit windows, lifted from sampleLights.
const lightsBody = motion.slice(motion.indexOf("const luminanceAt = (x, y) =>"), motion.indexOf("samplingStatus = 'sampled';"));
function lights(sceneId, lightPolygons, water, data, width, height) {
  const sampledLights = [];
  const sample = {width, height};
  new Function('lights', 'water', 'data', 'sample', 'sampledLights', 'inside', 'bounds', 'seedOf', 'unitHash', 'clamp', 'sceneId', lightsBody)(
    lightPolygons, water, data, sample, sampledLights, h.inside, h.bounds, h.seedOf, h.unitHash, h.clamp, sceneId);
  return sampledLights.map(l => ({x: l.x, y: l.y, r: l.r, g: l.g, b: l.b, region: l.region, seed: l.seed | 0, period: l.period, transition: l.transition, offset: l.offset, variable: l.variable}));
}

// The made-up picture: a dark street with warm, bright dots, the same in MotionTest.
function picture(width, height) {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const o = (y * width + x) * 4, lit = (x * 7 + y * 13) % 29 === 0;
    data[o] = lit ? 200 + (x % 50) : 40 + (x + y) % 30;
    data[o + 1] = lit ? 150 + (y % 40) : 38 + (x * y) % 20;
    data[o + 2] = lit ? 60 + (x % 30) : 45;
    data[o + 3] = 255;
  }
  return data;
}

// The water rows as motion-v13's sourceWaterRows cuts them, on a fixed cover.
function waterRows(water, exclusions, rowHeight, pixelX, pixelY) {
  return water.map(polygon => {
    const box = h.bounds(polygon), rows = [];
    const critical = Array.from(new Set([polygon, ...exclusions].flatMap(shape => shape.map(point => point[1])))).sort((a, b) => a - b);
    for (let top = box.top; top < box.bottom; top += rowHeight) {
      const bottom = Math.min(top + rowHeight, box.bottom);
      const low = top - pixelY, high = bottom + pixelY;
      const levels = [low, (top + bottom) / 2, high];
      const epsilon = Math.min(1e-7, (high - low) * 1e-4);
      for (const y of critical) if (y >= low && y <= high) levels.push(Math.max(low, y - epsilon), y, Math.min(high, y + epsilon));
      let spans = null;
      for (const level of levels) {
        let allowed = h.horizontalSpans(polygon, level);
        for (const exclusion of exclusions) allowed = h.subtractSpans(allowed, h.horizontalSpans(exclusion, level));
        spans = spans === null ? allowed : h.intersectSpans(spans, allowed);
        if (!spans.length) break;
      }
      spans = h.subtractSpans(spans || [], h.sweptBoundarySpans(polygon, low, high));
      for (const exclusion of exclusions) spans = h.subtractSpans(spans, h.sweptBoundarySpans(exclusion, low, high));
      spans = spans.map(([l, r]) => [l + pixelX, r - pixelX]).filter(([l, r]) => r - l > 1e-10);
      if (spans.length) rows.push({top, spans});
    }
    return rows;
  });
}

// The facade mask, lifted from lighting-v13's sampleFacades.
const maskBody = lighting.slice(lighting.indexOf("const output = maskContext.createImageData"), lighting.indexOf("maskContext.putImageData(output, 0, 0);"));
const lightingHelpers = lighting.slice(lighting.indexOf("const clamp ="), lighting.indexOf("const valid ="));
function facades(data, width, height) {
  const mask = {width, height};
  const maskContext = {createImageData: (w, hh) => ({data: new Uint8ClampedArray(w * hh * 4)})};
  return new Function('original', 'mask', 'maskContext', lightingHelpers + maskBody + '; return output.data;')({data}, mask, maskContext);
}

// Where rain, snow and ripples fall, lifted from weather-v13's attach.
const weatherHelpers = weather.slice(weather.indexOf("const TAU"), weather.indexOf("const normalizeProfile"));
const fallBody = weather.slice(weather.indexOf("const rnd = random(seedOf(sceneId + ':weather-v11'));"), weather.indexOf("const strength = () =>"));
function fall(sceneId, water, holes) {
  return new Function('sceneId', 'water', 'holes', weatherHelpers + fallBody + '; return {drops, flakes, ringPoints};')(sceneId, water, holes);
}

const model = {};
vm.runInNewContext(weatherModel, {window: model, Math, Number, Array, Object, Error, Date});

const out = {ref, seeds: {}, unitHash: [], random: [], wind: [], pigments: [], water: {}, lights: {}};
for (const id of ['tokyo-bay', 'harbor', 'berlin-spree', 'KOne']) out.seeds[id] = h.seedOf(id) | 0;
for (const [seed, cell] of [[1, 2], [-5, 7], [123456789, -3], [h.seedOf('tokyo-bay'), 4]]) out.unitHash.push({seed: seed | 0, cell, value: h.unitHash(seed, cell)});
const next = h.random(h.seedOf('tokyo-bay') ^ 0x71ac49);
for (let i = 0; i < 6; i++) out.random.push(next());
for (const t of [0, 1.5, 37, 600]) out.wind.push({id: 'harbor', seconds: t, ...window.KOneEnvironmentV10.sample('harbor', t)});
for (const seed of [11, 229]) for (const night of [false, true]) {
  const data = pigment(seed, night);
  let sum = 0;
  for (let i = 3; i < data.length; i += 4) sum += data[i];
  out.pigments.push({seed, night, alphaSum: sum, sample: [data[(40 * 192 + 60) * 4 + 3], data[(48 * 192 + 96) * 4 + 3], data[(30 * 192 + 130) * 4 + 3]]});
}
for (const id of ['tokyo-bay', 'harbor', 'berlin-spree']) {
  const scene = catalog.find(s => s.id === id);
  const water = h.validPolygons(scene.motion.waterPolygons);
  const exclusions = h.validPolygons([...(scene.motion.waterExclusions || []), ...(scene.motion.marine?.exclusionPolygons || [])]);
  out.water[id] = waterRows(water, exclusions, .004, 1 / 852, 1 / 1852);
  const regions = h.validPolygons(scene.motion.lightRegions);
  const lightPolygons = regions.length ? regions : Array.from({length: 8}, (_, i) => {
    const x = .04 + (i % 4) * .23, y = .30 + Math.floor(i / 4) * .135;
    return [[x, y], [x + .22, y], [x + .22, y + .125], [x, y + .125]];
  });
  out.lights[id] = lights(id, lightPolygons, water, picture(256, 512), 256, 512);
}
{
  const data = picture(120, 260);
  const mask = facades(data, 120, 260);
  let sum = 0, lit = 0, weighted = 0;
  for (let i = 3; i < mask.length; i += 4) { sum += mask[i]; weighted += mask[i] * ((i >> 2) % 997 + 1); if (mask[i]) lit++; }
  out.facades = {width: 120, height: 260, alphaSum: sum, lit, weighted};
}
out.fall = {};
for (const id of ['tokyo-bay', 'berlin-spree']) {
  const scene = catalog.find(s => s.id === id);
  const water = h.validPolygons(scene.motion.waterPolygons);
  const holes = h.validPolygons([...(scene.motion.waterExclusions || []), ...(scene.motion.marine?.exclusionPolygons || [])]);
  const f = fall(id, water, holes);
  out.fall[id] = {drops: f.drops.slice(0, 4), lastDrop: f.drops[209], flakes: f.flakes.slice(0, 3), lastFlake: f.flakes[124], rings: f.ringPoints.length, firstRings: f.ringPoints.slice(0, 3), lastRing: f.ringPoints.at(-1)};
}
const answer = {current: {time: 1792224000, interval: 900, weather_code: 63, temperature_2m: 12.4, cloud_cover: 96, wind_speed_10m: 6.2, rain: 1.1, showers: .3, snowfall: 0, visibility: 4200},
  current_units: {time: 'unixtime', interval: 'seconds', weather_code: 'wmo code', temperature_2m: '°C', cloud_cover: '%', wind_speed_10m: 'm/s', rain: 'mm', showers: 'mm', snowfall: 'cm', visibility: 'm'}};
const snowy = {current: {...answer.current, weather_code: 73, temperature_2m: -4, rain: 0, showers: 0, snowfall: .4, visibility: 900}, current_units: answer.current_units};
const foggy = {current: {...answer.current, weather_code: 45, rain: 0, showers: 0, cloud_cover: 40, visibility: 300}, current_units: answer.current_units};
out.openMeteo = [answer, snowy, foggy].map(a => ({answer: a, profile: model.KOneWeatherModelV11.fromCurrent(a).profile}));
// Codex's marine and city-life layers, attached in a make-believe page that draws nothing.
function page() {
  class Element { appendChild() {} getBoundingClientRect() { return {width: 300, height: 650}; } }
  class HTMLImageElement extends Element {}
  const context = new Proxy({}, {get: (target, key) => key in target ? target[key] : () => ({addColorStop() {}})});
  const canvas = () => Object.assign(new Element(), {style: {}, setAttribute() {}, getContext: () => context, remove() {}});
  const window = {devicePixelRatio: 1, addEventListener() {}, removeEventListener() {}};
  const globals = {window, Element, HTMLImageElement, Math, Number, Array, Object, String, Map, Set, TypeError, Promise,
    document: {createElement: canvas, addEventListener() {}, removeEventListener() {}, hidden: false},
    matchMedia: () => ({matches: false, addEventListener() {}, removeEventListener() {}}),
    getComputedStyle: () => ({objectPosition: '50% 50%'}), requestAnimationFrame: () => 0, cancelAnimationFrame() {}};
  globals.window.matchMedia = globals.matchMedia;
  return globals;
}
function image(globals, aspect) {
  return Object.assign(new globals.HTMLImageElement(), {naturalWidth: 852, naturalHeight: Math.round(852 / aspect), complete: true, classList: {contains: () => false}, addEventListener() {}, removeEventListener() {}});
}
const marineHelpers = marineCode.slice(marineCode.indexOf("const TAU"), marineCode.indexOf("function attach("));
const wavesBody = marineCode.slice(marineCode.indexOf("const seed = [...sceneId]"), marineCode.indexOf("const skyBounds = scene?.skySafe"));
out.marine = {};
out.life = {};
for (const id of ['sydney-harbour', 'venice-lagoon', 'amsterdam-canals', 'istanbul-bosphorus', 'tokyo-bay', 'rio-bay', 'las-vegas-strip', 'east-river', 'luoyang-yingtian-gate', 'honolulu-waikiki']) {
  const scene = catalog.find(s => s.id === id);
  const aspect = scene.crop.aspect;
  const config = {scenes: {[id]: scene.motion}};
  const g = page();
  vm.runInNewContext(marineCode, g);
  const sceneJson = {...scene, skySafe: scene.skySafe, cloudSkyBand: scene.cloudSkyBand, skyExclusions: scene.skyExclusions};
  const controller = g.window.NycMarineV13.attach(new g.Element(), sceneJson, 'day', image(g, aspect), {config, playing: false});
  controller.renderAt(73.5);
  const state = controller.state;
  const marine = scene.motion.marine && typeof scene.motion.marine === 'object' ? scene.motion.marine : null;
  const water = h.validPolygons(scene.motion.waterPolygons);
  const exclusions = h.validPolygons(marine?.exclusionPolygons);
  const waveType = marine && ['coast', 'harbour', 'bay', 'river', 'canal', 'lagoon', 'marina', 'strait', 'lake'].includes(marine.type) ? marine.type : null;
  const styles = marineCode.slice(marineCode.indexOf("const waterStyle = ({"), marineCode.indexOf("const canvas = document.createElement('canvas');"));
  const made = new Function('sceneId', 'water', 'exclusions', 'boxes', 'waveType', 'marine', 'config', 'TAU', 'clamp', 'finite', 'inside', 'random',
    styles + wavesBody + '; return {seed, wavePoints, birds, flocks};')(id, water, exclusions, water.map(h.bounds), waveType, marine, scene.motion,
    Math.PI * 2, h.clamp, Number.isFinite, h.inside, h.random);
  out.marine[id] = {
    seed: made.seed, waves: made.wavePoints.map(p => p.length), firstWave: made.wavePoints.flat()[0] || null, lastWave: made.wavePoints.flat().at(-1) || null,
    birds: made.birds.map(b => ({phase: b.phase, lane: b.lane, size: b.size, direction: b.direction, beatRate: b.beatRate})),
    flight: state.birdSkyBounds, accepted: state.routes.map(r => ({id: r.id, progress: r.progress, speed: r.speed})), rejected: state.rejectedRoutes,
  };
  const lg = page();
  vm.runInNewContext(lifeCode, lg);
  const life = lg.window.KOneCityLifeV13.attach(new lg.Element(), {id}, 'day', image(lg, aspect), {config: {scenes: {[id]: {...scene.life, reviewed: true}}}, playing: false});
  const ls = life.state;
  out.life[id] = {traffic: ls.acceptedTraffic, walkers: ls.acceptedWalkers, rejected: ls.rejectedRoutes.map(r => r.id), trees: ls.canopyAnchors, shores: ls.shorelineRegions};
}
// The soundscape's noise, lifted from ambient-audio-v11's noiseBuffer.
{
  const body = audioCode.slice(audioCode.indexOf("function noiseBuffer(kind, seed) {") + "function noiseBuffer(kind, seed) {".length, audioCode.indexOf("    function build() {"));
  const audioHelpers = audioCode.slice(audioCode.indexOf("const clamp ="), audioCode.indexOf("const seaWeights"));
  const context = {sampleRate: 44100, createBuffer: (channels, length) => { const data = new Float32Array(length); return {getChannelData: () => data}; }};
  const noise = (kind, seed) => new Function('context', 'kind', 'seed', audioHelpers + body.slice(0, body.lastIndexOf('}')))(context, kind, seed).getChannelData(0);
  out.noise = {};
  for (const [kind, seed] of [['wind', 913], ['rain', 419], ['sea', 2671]]) {
    const data = noise(kind, seed);
    let sum = 0;
    for (const v of data) sum += Math.abs(v);
    out.noise[kind] = {length: data.length, first: Array.from(data.slice(0, 4)), seam: Array.from(data.slice(data.length - 3)), sum};
  }
}

// Every scene in short: boats Codex lets sail, and the cars and walkers it lets go.
out.everyScene = {};
for (const scene of catalog) {
  const g = page();
  vm.runInNewContext(marineCode, g);
  const marine = g.window.NycMarineV13.attach(new g.Element(), scene, 'day', image(g, scene.crop.aspect), {config: {scenes: {[scene.id]: scene.motion}}, playing: false});
  const lg = page();
  vm.runInNewContext(lifeCode, lg);
  const life = lg.window.KOneCityLifeV13.attach(new lg.Element(), {id: scene.id}, 'day', image(lg, scene.crop.aspect), {config: {scenes: {[scene.id]: {...scene.life, reviewed: true}}}, playing: false});
  out.everyScene[scene.id] = [marine.state.acceptedRoutes, marine.state.birdCount, life.state.acceptedTraffic, life.state.acceptedWalkers];
}
process.stdout.write(JSON.stringify(out) + '\n');
