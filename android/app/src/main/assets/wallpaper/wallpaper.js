/* The approved artwork runtime, behind the native interface. All resources are bundled. */
(() => {
  'use strict';
  const params = new URLSearchParams(location.search), layers = [], stage = document.getElementById('stage');
  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  const motionAllowed = () => params.get('motion') !== 'off' && !reducedMotion?.matches;
  // Browser visibility can hide the page, but only the host can resume its own pause.
  let hostActive = true, visible = !document.hidden, running = motionAllowed() && visible, elapsed = 0, anchor = performance.now();
  document.documentElement.dataset.motion = motionAllowed() ? 'on' : 'off';
  let sky = null, scene = null, timer = null, ready = false, error = null;
  const clock = () => elapsed + (running ? (performance.now() - anchor) / 1000 : 0);
  const time = () => {
    if (params.get('time') === 'day' || params.get('time') === 'night') {
      const date = NycSkyV5.parts(Date.now(), scene.cityZone).date;
      return NycSkyV5.chooseDemo(scene, params.get('time'), scene.cityLocation, date).instant;
    }
    return Date.now();
  };
  function updateSky() {
    if (!sky) return;
    const sample = sky.render(time(), scene.cityLocation);
    const daylight = params.get('time') === 'day' ? 1 : params.get('time') === 'night' ? 0 : sample.daylight;
    const visual = {...sample, daylight};
    stage.style.setProperty('--daylight', String(daylight));
    for (const layer of layers) { layer.setSky?.(visual); layer.setLighting?.(visual); }
  }
  function schedule() {
    clearTimeout(timer);
    if (ready && visible && !document.hidden) timer = setTimeout(() => { updateSky(); schedule(); }, 30000);
  }
  function syncActivity() {
    visible = hostActive && !document.hidden && error === null;
    const next = visible && motionAllowed();
    if (next !== running) { elapsed = clock(); anchor = performance.now(); running = next; }
    document.documentElement.dataset.motion = motionAllowed() ? 'on' : 'off';
    for (const layer of layers) { layer.setVisible?.(visible); layer.setPlaying?.(next); }
    if (visible) updateSky();
    schedule();
  }
  function active(value) { hostActive = Boolean(value); syncActivity(); }
  // No JavaScript-to-native bridge. Android only supplies visibility to this local page.
  let linked = false;
  // The app says when KOne connects; the city answers with a sweep of light (see pulse-v1.js).
  function connected(value) {
    linked = Boolean(value);
    for (const layer of layers) layer.setConnected?.(linked);
  }
  window.KOneWallpaper = {
    active,
    connected,
    get state() { return {ready, error, scene: scene?.id, running, time:clock(), layers:layers.map(layer => layer.state)}; },
  };
  document.addEventListener('visibilitychange', syncActivity);
  reducedMotion?.addEventListener?.('change', syncActivity);
  const read = name => fetch(name).then(response => { if (!response.ok) throw Error('Missing bundled resource'); return response.json(); });
  Promise.all([read('catalog.json'), read('motion.json'), read('life.json')]).then(async ([catalog, motion, life]) => {
    scene = catalog.find(item => item.id === params.get('scene')) || catalog[0];
    // The stage keeps the artwork's original coordinates on every screen aspect ratio.
    const resize = () => {
      const aspect = scene.crop.aspect, w = innerWidth, h = w / aspect;
      const scale = Math.max(1, innerHeight / h);
      stage.style.width = w + 'px'; stage.style.height = h + 'px';
      stage.style.transform = `translate(${(innerWidth - w * scale) / 2}px,${(innerHeight - h * scale) / 2}px) scale(${scale})`;
    };
    resize(); window.addEventListener('resize', resize);
    const day = document.getElementById('day'), night = document.getElementById('night');
    const load = (image, path) => new Promise((resolve, reject) => { image.onload = resolve; image.onerror = () => reject(Error('Unreadable bundled image')); image.src = path; });
    await Promise.all([load(day, scene.assets.day), load(night, scene.assets.night)]);
    const model = KOneWeatherModelV11.create(scene, {mode:params.get('weather') || 'story', date:Date.now()});
    KOneEnvironmentV13.bind(scene.id, model);
    // The city at full life: its own traffic, water and lights, as the artwork was reviewed for.
    const options = {config:motion, playing:running, intensity:'expressive', maxFps:24, environmentTime:clock, getWeather:t => model.sample(t)};
    layers.push(NycMotionV13.attach(document.getElementById('motion'), scene, 'day', day, {...options, blendImage:night}));
    layers.push(NycMarineV13.attach(document.getElementById('marine'), scene, 'day', day, options));
    layers.push(NycLightV13.attach(document.getElementById('lighting'), scene, 'day', day, options));
    layers.push(KOneCityLifeV13.attach(document.getElementById('life'), scene, 'day', day, {...options, config:life, blendImage:night}));
    layers.push(KOneWeatherV13.attach(document.getElementById('weather'), scene, 'day', day, options));
    // The city's own sky is an extra: if it cannot start, the artwork still runs.
    try { if (window.KOnePulseV1) layers.push(KOnePulseV1.attach(document.getElementById('pulse'), scene, options)); } catch (_) {}
    sky = NycSkyV5.attach(document.getElementById('sky'), scene, 'day', {zone:scene.cityLocation, instant:time()});
    ready = true; updateSky(); syncActivity(); connected(linked);
    document.documentElement.dataset.ready = 'true';
  }).catch(failure => {
    error = failure.message; ready = false; active(false);
    // Whatever did load stays visible rather than behind the fade.
    document.documentElement.dataset.ready = 'true';
    document.documentElement.dataset.error = 'wallpaper-unavailable';
  });
})();
