/* Host checks for the wallpaper's clock and lifecycle; no browser or Android device. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const root = path.resolve(__dirname, '../android/app/src/main/assets/wallpaper');
const catalog = JSON.parse(fs.readFileSync(path.join(root, 'catalog.json')));
const sky = require(path.join(root, 'sky-v5.js'));
const weather = require(path.join(root, 'weather-model-v11.js'));

test('all bundled cities have finite daylight and deterministic weather', () => {
  for (const scene of catalog) {
    for (const instant of [Date.UTC(2026, 0, 15, 0), Date.UTC(2026, 6, 15, 12)]) {
      const sample = sky.sample(scene, 'day', instant, scene.cityLocation);
      assert.ok(Number.isFinite(sample.daylight), scene.id);
      assert.ok(sample.daylight >= 0 && sample.daylight <= 1, scene.id);
      const model = weather.create(scene, {date:instant});
      for (const time of [0, 60, 119, 180]) {
        const sample = model.sample(time);
        for (const field of ['cloudCover', 'rain', 'snow', 'fog', 'wind']) assert.ok(Number.isFinite(sample[field]), scene.id + ':' + field);
      }
    }
  }
});

async function page(query, reduced = false, {failRead = null, failAttach = null, failSky = false} = {}) {
  let now = 0;
  const events = {}, controllers = [], timers = new Map();
  const media = {matches:reduced, addEventListener(name, handler) { this.handler = handler; },
    change(value) { this.matches = value; this.handler?.(); }};
  const style = () => ({setProperty(key, value) { this[key] = value; }});
  const elements = new Map();
  for (const id of ['stage','day','night','motion','marine','lighting','sky','life','weather']) {
    elements.set(id, {style:style(), set src(value) {this.source = value; queueMicrotask(() => this.onload?.());}});
  }
  function attach() {
    if (controllers.length === failAttach) throw new Error('Synthetic layer initialization failure');
    const controller = {visible:true, playing:true, failSky:false,
      setVisible(value) {this.visible=value;}, setPlaying(value) {this.playing=value;},
      setSky() {if (this.failSky) throw new Error('Synthetic layer sky update failure');},
      setLighting() {}, get state() {return {running:this.visible && this.playing};}};
    controllers.push(controller); return controller;
  }
  const document = {hidden:false, documentElement:{dataset:{}}, getElementById:id => elements.get(id), addEventListener:(name, handler) => events[name]=handler};
  const context = {URLSearchParams, console, performance:{now:() => now}, innerWidth:412, innerHeight:892,
    location:{search:query}, document, matchMedia:() => media, NycSkyV5:{...sky,attach:() => ({render:(instant, zone) => {
      if (failSky) throw new Error('Synthetic sky render failure');
      return sky.sample(catalog[0], 'day', instant, zone);
    }})},
    KOneWeatherModelV11:weather, KOneEnvironmentV13:{bind() {}},
    NycMotionV13:{attach}, NycMarineV13:{attach}, NycLightV13:{attach}, KOneCityLifeV13:{attach}, KOneWeatherV13:{attach},
    addEventListener() {}, setTimeout:handler => {const id=timers.size+1;timers.set(id,handler);return id;}, clearTimeout:id => timers.delete(id),
    fetch:async file => ({ok:file !== failRead,json:async () => JSON.parse(fs.readFileSync(path.join(root,file)))})};
  context.window=context;
  vm.runInNewContext(fs.readFileSync(path.join(root,'wallpaper.js'),'utf8'),context);
  const settle = async () => {for(let i=0;i<4;i++) await new Promise(setImmediate);};
  return {context,document,controllers,timers,events,elements,media,settle,advance:ms => now+=ms,
    failSky() {failSky=true;}, fireTimer() {
      assert.equal(timers.size,1);
      const [id,callback]=timers.entries().next().value;timers.delete(id);callback();
    }};
}

async function failedPageStaysStopped(failure, expectedLayers) {
  const p = await page('?scene=original&time=city&motion=on', false, failure);
  await p.settle();
  assert.equal(p.document.documentElement.dataset.error, 'wallpaper-unavailable');
  assert.equal(p.controllers.length, expectedLayers);
  p.context.KOneWallpaper.active(true);
  p.document.hidden = true; p.events.visibilitychange();
  p.document.hidden = false; p.events.visibilitychange();
  p.media.change(true); p.media.change(false);
  const before = p.context.KOneWallpaper.state.time;
  p.advance(60000);
  assert.equal(p.context.KOneWallpaper.state.ready, false);
  assert.equal(p.context.KOneWallpaper.state.running, false);
  assert.equal(p.context.KOneWallpaper.state.time, before);
  assert.ok(p.controllers.every(controller => !controller.state.running));
  assert.equal(p.timers.size, 0);
}

test('missing bundled metadata remains stopped after host resume', () => failedPageStaysStopped({failRead:'catalog.json'}, 0));
test('partial layer initialization cannot restart after failure', () => failedPageStaysStopped({failAttach:2}, 2));
test('sky render failure cannot leave a ready or running wallpaper', () => failedPageStaysStopped({failSky:true}, 5));

for (const source of ['sky-render', 'layer-setSky']) {
  for (const trigger of ['timer', 'host-resume']) {
    test(`post-ready ${source} failure on ${trigger} stops the controller and remains observable`, async () => {
      const p=await page('?scene=original&time=city&motion=on');
      await p.settle();
      assert.equal(p.context.KOneWallpaper.state.ready,true);
      assert.equal(p.context.KOneWallpaper.state.error,null);
      assert.ok(p.controllers.every(controller => controller.state.running));
      if (trigger === 'host-resume') p.context.KOneWallpaper.active(false);
      p.advance(30000);
      if (source === 'sky-render') p.failSky(); else p.controllers[1].failSky=true;
      let thrown=null;
      try {if (trigger === 'timer') p.fireTimer(); else p.context.KOneWallpaper.active(true);} catch (failure) {thrown=failure.message;}
      const failed=p.context.KOneWallpaper.state;
      assert.equal(failed.ready,false,JSON.stringify({ready:failed.ready,error:failed.error,running:failed.running,thrown}));
      assert.equal(thrown,null);
      assert.match(failed.error,/Synthetic .* (render|update) failure/);
      assert.equal(failed.running,false);
      assert.equal(p.document.documentElement.dataset.error,'wallpaper-unavailable');
      assert.equal(p.document.documentElement.dataset.ready,'true'); // Retain the static painting.
      assert.ok(p.controllers.every(controller => !controller.state.running));
      assert.equal(p.timers.size,0);
      p.context.KOneWallpaper.active(true);
      p.document.hidden=true;p.events.visibilitychange();
      p.document.hidden=false;p.events.visibilitychange();
      p.media.change(true);p.media.change(false);
      p.advance(60000);
      assert.equal(p.context.KOneWallpaper.state.error,failed.error);
      assert.equal(p.context.KOneWallpaper.state.time,failed.time);
      assert.ok(p.controllers.every(controller => !controller.state.running));
      assert.equal(p.timers.size,0);
    });
  }
}

test('browser visibility cannot override an explicit Android pause', async () => {
  const p = await page('?scene=original&motion=on');
  await p.settle();
  p.context.KOneWallpaper.active(false);
  p.document.hidden = true; p.events.visibilitychange();
  p.advance(3600000);
  p.document.hidden = false; p.events.visibilitychange();
  assert.equal(p.context.KOneWallpaper.state.running, false);
  assert.equal(p.context.KOneWallpaper.state.time, 0);
  assert.equal(p.timers.size, 0);
  assert.ok(p.controllers.every(c => !c.state.running));
  p.context.KOneWallpaper.active(true);
  p.advance(1000);
  assert.equal(p.context.KOneWallpaper.state.time, 1);
});

test('system reduced motion freezes the shared clock and resumes without catching up', async () => {
  const p = await page('?scene=original&motion=on', true);
  await p.settle();
  assert.equal(p.document.documentElement.dataset.motion, 'off');
  assert.ok(p.controllers.every(c => !c.playing));
  p.advance(3600000);
  assert.equal(p.context.KOneWallpaper.state.time, 0);
  p.media.change(false);
  p.advance(1000);
  assert.equal(p.context.KOneWallpaper.state.time, 1);
  p.media.change(true);
  p.advance(3600000);
  assert.equal(p.context.KOneWallpaper.state.time, 1);
  assert.equal(p.document.documentElement.dataset.motion, 'off');
  assert.ok(p.controllers.every(c => !c.playing));
});

test('a page finishing after Android pauses stays paused, then resumes without a time jump', async () => {
  const p=await page('?scene=original&time=city&weather=story');
  p.context.KOneWallpaper.active(false);
  await p.settle();
  assert.equal(p.document.documentElement.dataset.ready,'true');
  assert.equal(p.controllers.length,5);
  assert.ok(p.controllers.every(c => !c.state.running));
  p.advance(3600000);
  assert.equal(p.context.KOneWallpaper.state.time,0);
  p.context.KOneWallpaper.active(true);
  p.advance(1000);
  assert.equal(p.context.KOneWallpaper.state.time,1);
  assert.ok(p.controllers.every(c => c.state.running));
  p.context.KOneWallpaper.active(false);
  assert.equal(p.timers.size,0);
});

test('motion off leaves the painting and clock visible while animation stays stopped', async () => {
  const p=await page('?scene=original&time=night&weather=clear&motion=off');
  await p.settle();
  assert.equal(p.document.documentElement.dataset.ready,'true');
  assert.ok(p.controllers.every(c => c.visible && !c.playing));
  assert.equal(p.elements.get('stage').style['--daylight'],'0');
  assert.equal(p.timers.size,1);
  p.document.hidden=true; p.events.visibilitychange();
  assert.equal(p.timers.size,0);
  assert.ok(p.controllers.every(c => !c.visible));
});

class Container {
  appendChild() {}
  getBoundingClientRect() { return {width: 320, height: 640}; }
}

function pulseFixture(cityId, reduced, {maxFps = 24} = {}) {
  const frames = new Map();
  let next = 0, wallNow = 0;
  const gradient = {addColorStop() {}};
  const drawing = new Proxy({}, {
    get(target, method) {
      if (method in target) return target[method];
      return (...args) => {
        for (const value of args) {
          if (typeof value === 'number') assert.ok(Number.isFinite(value), `${cityId}: ${String(method)} received ${value}`);
        }
        return gradient;
      };
    },
  });
  const context = {
    Element: Container,
    document: {
      hidden: false,
      createElement() { return {style: {}, setAttribute() {}, getContext() { return drawing; }, remove() {}}; },
      addEventListener() {}, removeEventListener() {},
    },
    matchMedia() { return {matches: reduced, addEventListener() {}, removeEventListener() {}}; },
    requestAnimationFrame(fn) { const id = ++next; frames.set(id, fn); return id; },
    cancelAnimationFrame(id) { frames.delete(id); },
    ResizeObserver: class { observe() {} disconnect() {} },
    devicePixelRatio: 1,
    performance: {now:() => wallNow},
    Intl, Date, Math,
  };
  context.window = context;
  vm.runInNewContext(fs.readFileSync(path.join(root, 'pulse-v1.js'), 'utf8'), context);
  const pulse = context.KOnePulseV1.attach(new Container(), {
    id: cityId + '-test', cityId, cityZone: 'America/New_York', horizonY: .3,
  }, {maxFps});
  assert.equal(pulse.state.running, !reduced);
  pulse.setSky({daylight: 0});
  return {pulse, frames, setNow(value) { wallNow = value; }, frame(now, timestamp = now) {
    wallNow = now;
    const queued = [...frames.values()]; frames.clear();
    for (const callback of queued) callback(timestamp);
  }};
}

function checkPulse(cityId, reduced, {step = 100, duration = 12000, connectAt = 0, maxFps = 24} = {}) {
  const fixture = pulseFixture(cityId, reduced, {maxFps}), {pulse, frames} = fixture;
  let connected = false;
  for (let frame = 0; frame * step < duration; frame++) {
    fixture.setNow(frame * step);
    if (!connected && frame * step >= connectAt) { pulse.setConnected(true); connected = true; }
    fixture.frame(frame * step);
  }
  const state = pulse.state;
  pulse.setVisible(false);
  assert.equal(pulse.state.running, false);
  pulse.destroy();
  assert.equal(frames.size, 0);
  return state;
}


test('city pulse keeps finite canvas coordinates and stops for reduced motion and hidden views', () => {
  for (const reduced of [false, true]) {
    for (const city of ['new-york', 'los-angeles', 'las-vegas', 'london', 'tokyo', 'unknown']) checkPulse(city, reduced);
  }
});

test('connection glow settles consistently at different frame cadences', () => {
  const fast = checkPulse('tokyo', false, {step:1000 / 60, duration:1000});
  const slow = checkPulse('tokyo', false, {step:1000 / 12, duration:1000});
  assert.ok(Number.isFinite(fast.connectionGlow) && Number.isFinite(slow.connectionGlow));
  assert.ok(fast.connectionGlow > .98 && slow.connectionGlow > .98);
  assert.ok(Math.abs(fast.connectionGlow - slow.connectionGlow) < .02);
});

test('a connection cannot consume time from before it was requested', () => {
  const late = checkPulse('tokyo', false, {step:100, duration:1100, connectAt:900, maxFps:1});
  assert.ok(late.connectionGlow > 0 && late.connectionGlow < .8);
});

test('a delayed callback includes only time after the connection event', () => {
  const fixture = pulseFixture('tokyo', false);
  fixture.frame(0);
  fixture.setNow(999);
  fixture.pulse.setConnected(true);
  assert.equal(fixture.pulse.state.connectionGlow, 0);
  fixture.frame(1000);
  const glow = fixture.pulse.state.connectionGlow;
  assert.ok(glow > 0 && glow < .02, `one post-event millisecond produced ${glow}`);
  fixture.pulse.destroy();
  assert.equal(fixture.frames.size, 0);
});

test('an older queued callback cannot backdate the connection clock', () => {
  const fixture = pulseFixture('tokyo', false);
  fixture.frame(0);
  fixture.setNow(999);
  fixture.pulse.setConnected(true);
  fixture.frame(1000, 998);
  assert.equal(fixture.pulse.state.connectionGlow, 0);
  fixture.frame(1010);
  assert.equal(fixture.pulse.state.connectionGlow, 0); // The paint cadence has not admitted another draw.
  fixture.frame(1050);
  assert.ok(fixture.pulse.state.connectionGlow > 0 && fixture.pulse.state.connectionGlow < .3);
  fixture.pulse.destroy();
});

test('paused and reduced-motion connections settle without starting a clock', () => {
  for (const reduced of [false, true]) {
    const fixture = pulseFixture('tokyo', reduced);
    if (!reduced) { fixture.frame(0); fixture.pulse.setVisible(false); }
    fixture.setNow(3600000);
    fixture.pulse.setConnected(true);
    assert.equal(fixture.pulse.state.connectionGlow, 1);
    fixture.pulse.setConnected(false);
    assert.equal(fixture.pulse.state.connectionGlow, 0);
    assert.equal(fixture.frames.size, 0);
    fixture.pulse.destroy();
  }
});
