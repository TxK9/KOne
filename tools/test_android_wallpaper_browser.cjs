/* Real-browser checks for bundled Android wallpaper, using synthetic local state only. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const modules = process.env.KONE_TEST_NODE_MODULES;
const {chromium} = modules ? require(path.join(modules, 'playwright')) : require('playwright');
const root = path.resolve(process.argv[2] || path.join(__dirname, '../android/app/src/main/assets/wallpaper'));
const output = process.env.KONE_WALLPAPER_EVIDENCE_DIR;
const results = [];
const mime = {'.html':'text/html', '.js':'application/javascript', '.json':'application/json',
  '.css':'text/css', '.webp':'image/webp', '.png':'image/png'};

async function main() {
  const server = http.createServer(async (req, res) => {
    try {
      const target = path.resolve(root, '.' + decodeURIComponent(new URL(req.url, 'http://fixture').pathname));
      if (!target.startsWith(root + path.sep)) { res.writeHead(403).end(); return; }
      const content = await fs.readFile(target);
      res.writeHead(200, {'Content-Type':mime[path.extname(target)] || 'application/octet-stream',
        'X-Content-Type-Options':'nosniff'}).end(content);
    } catch { res.writeHead(404).end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  let browser;
  async function check(name, body) {
    try { await body(); results.push({name, passed:true}); console.log('PASS ' + name); }
    catch (error) { results.push({name, passed:false, error:error.message}); console.log('FAIL ' + name + ': ' + error.message); }
  }
  try {
    browser = await chromium.launch({headless:true, executablePath:process.env.KONE_TEST_BROWSER || '/usr/bin/chromium'});
    const context = await browser.newContext({viewport:{width:320, height:692}, deviceScaleFactor:1});
    const external = [];
    await context.route('**/*', route => {
      if (route.request().url().startsWith(origin + '/')) return route.continue();
      external.push(route.request().url()); return route.abort();
    });
    await context.addInitScript(() => {
      const now = Date.UTC(2026, 10, 1, 12);
      const OriginalDate = Date;
      window.Date = class extends OriginalDate {
        constructor(...args) { super(...(args.length ? args : [now])); }
        static now() { return now; }
      };
    });
    if (output) await fs.mkdir(output, {recursive:true});
    for (const scene of ['original', 'tokyo-bay', 'las-vegas-strip']) {
      for (const time of ['day', 'night']) {
        await check(scene + '-' + time + '-320', async () => {
          const page = await context.newPage(), errors = [];
          page.on('pageerror', error => errors.push(error.message));
          try {
            await page.goto(origin + '/index.html?scene=' + scene + '&time=' + time + '&weather=clear');
            await page.waitForFunction(() => window.KOneWallpaper?.state.ready, {timeout:30000});
            await page.waitForFunction(() => window.KOneWallpaper.state.layers.some(layer => layer.frames >= 3));
            const state = await page.evaluate(() => {
              window.KOneWallpaper.connected(true);
              const result = window.KOneWallpaper.state;
              window.KOneWallpaper.active(false);
              return result;
            });
            assert.equal(state.error, null);
            assert.equal(state.scene, scene);
            assert.ok(state.layers.length >= 5);
            assert.ok(state.layers.every(layer => layer.frames === undefined || Number.isFinite(layer.frames)));
            const images = await page.locator('img.wallpaper').evaluateAll(items => items.map(image => image.complete && image.naturalWidth > 0));
            assert.deepEqual(images, [true, true]);
            if (output) {
              await page.screenshot({path:path.join(output, scene + '-' + time + '-320.png'), animations:'disabled'});
              await fs.writeFile(path.join(output, scene + '-' + time + '-320.json'), JSON.stringify(state, null, 2) + '\n');
            }
            assert.deepEqual(errors, []);
          } finally { await page.close(); }
        });
      }
    }
    await check('host-pause-survives-browser-visibility', async () => {
      const page = await context.newPage();
      try {
        await page.goto(origin + '/index.html?scene=original&time=day');
        await page.waitForFunction(() => window.KOneWallpaper?.state.ready);
        const state = await page.evaluate(() => {
          window.KOneWallpaper.active(false);
          Object.defineProperty(document, 'hidden', {configurable:true, value:true});
          document.dispatchEvent(new Event('visibilitychange'));
          Object.defineProperty(document, 'hidden', {configurable:true, value:false});
          document.dispatchEvent(new Event('visibilitychange'));
          return window.KOneWallpaper.state;
        });
        assert.equal(state.running, false);
        assert.ok(state.layers.every(layer => !layer.running));
      } finally { await page.close(); }
    });
    for (const mode of ['query', 'system']) {
      await check('reduced-motion-' + mode, async () => {
        const page = await context.newPage();
        try {
          if (mode === 'system') await page.emulateMedia({reducedMotion:'reduce'});
          await page.goto(origin + '/index.html?scene=original&time=night&motion=' + (mode === 'query' ? 'off' : 'on'));
          await page.waitForFunction(() => window.KOneWallpaper?.state.ready);
          const value = await page.evaluate(() => ({state:window.KOneWallpaper.state,
            transition:getComputedStyle(document.getElementById('stage')).transitionDuration}));
          assert.equal(value.state.running, false);
          assert.ok(value.state.layers.every(layer => !layer.running));
          assert.ok(value.transition.split(',').every(duration => parseFloat(duration) === 0), value.transition);
        } finally { await page.close(); }
      });
    }
    const progression = {};
    await check('opening-fade-intermediate-and-completion', async () => {
      const page = await context.newPage();
      try {
        await page.addInitScript(() => {
          new MutationObserver(() => {
            if (document.documentElement.dataset.ready !== 'true' || window.__koneFade) return;
            const stage = document.getElementById('stage');
            getComputedStyle(stage).opacity;
            const fade = stage.getAnimations().find(animation => animation.transitionProperty === 'opacity');
            if (fade) { fade.pause(); window.__koneFade = fade; }
          }).observe(document, {attributes:true, subtree:true, attributeFilter:['data-ready']});
        });
        await page.goto(origin + '/index.html?scene=original&time=day&motion=on');
        await page.waitForFunction(() => Boolean(window.__koneFade));
        const values = await page.evaluate(() => {
          const fade = window.__koneFade, stage = document.getElementById('stage');
          const duration = Number(fade.effect.getTiming().duration);
          const sample = time => { fade.currentTime = time; return Number(getComputedStyle(stage).opacity); };
          return {duration, initial:sample(0), intermediate:sample(duration / 3), complete:sample(duration)};
        });
        assert.equal(values.duration, 480);
        assert.ok(values.initial <= .01);
        assert.ok(values.intermediate > values.initial + .05 && values.intermediate < .999);
        assert.ok(values.complete >= .999);
        progression.fade = values;
      } finally { await page.close(); }
    });
    await check('connection-glow-progress-pause-and-completion', async () => {
      const page = await context.newPage();
      try {
        await page.goto(origin + '/index.html?scene=original&time=night&motion=on');
        await page.waitForFunction(() => window.KOneWallpaper?.state.ready);
        const samples = await page.evaluate(async () => {
          const pulse = () => window.KOneWallpaper.state.layers.find(layer => 'connectionGlow' in layer);
          const snapshot = () => ({glow:pulse().connectionGlow, frames:pulse().frames, time:window.KOneWallpaper.state.time, wallMs:performance.now()});
          window.KOneWallpaper.connected(true);
          const initial = snapshot();
          const intermediate = await new Promise((resolve, reject) => {
            let callbacks = 0;
            function poll() {
              const value = snapshot();
              if (value.glow > 0) { resolve(value); return; }
              if (++callbacks > 240) { reject(new Error('Connection glow did not advance')); return; }
              requestAnimationFrame(poll);
            }
            requestAnimationFrame(poll);
          });
          window.KOneWallpaper.active(false);
          const pauseStart = snapshot();
          await new Promise(resolve => setTimeout(resolve, 180));
          const pauseEnd = snapshot();
          window.KOneWallpaper.active(true);
          return {initial, intermediate, pauseStart, pauseEnd};
        });
        assert.ok(samples.initial.glow <= .01);
        assert.ok(samples.intermediate.glow > 0 && samples.intermediate.glow < .98);
        assert.equal(samples.pauseStart.frames, samples.pauseEnd.frames);
        assert.ok(Math.abs(samples.pauseStart.glow - samples.pauseEnd.glow) < .00001);
        assert.ok(Math.abs(samples.pauseStart.time - samples.pauseEnd.time) < .00001);
        await page.waitForFunction(() => window.KOneWallpaper.state.layers.some(layer => layer.connectionGlow > .98));
        const complete = await page.evaluate(() => {
          const layer = window.KOneWallpaper.state.layers.find(value => 'connectionGlow' in value);
          return {glow:layer.connectionGlow, frames:layer.frames};
        });
        assert.ok(complete.frames > samples.pauseEnd.frames);
        progression.connectionGlow = {...samples, complete};
      } finally { await page.close(); }
    });
    await check('delayed-callback-excludes-pre-connection-time', async () => {
      const page = await context.newPage();
      try {
        await page.addInitScript(() => {
          let now = 0, next = 0;
          const callbacks = new Map();
          Object.defineProperty(performance, 'now', {value:() => now});
          window.requestAnimationFrame = callback => { const id = ++next; callbacks.set(id, callback); return id; };
          window.cancelAnimationFrame = id => callbacks.delete(id);
          window.__koneFrames = {
            now(value) { now = value; },
            step(value, timestamp = value) {
              now = value;
              const queued = [...callbacks.values()]; callbacks.clear();
              for (const callback of queued) callback(timestamp);
            },
          };
        });
        await page.goto(origin + '/index.html?scene=original&time=night&motion=on');
        await page.waitForFunction(() => window.KOneWallpaper?.state.ready, undefined, {polling:20});
        const values = await page.evaluate(() => {
          const pulse = () => window.KOneWallpaper.state.layers.find(layer => 'connectionGlow' in layer);
          const clock = window.__koneFrames;
          clock.step(0);
          clock.now(999);
          window.KOneWallpaper.connected(true);
          const initial = pulse().connectionGlow;
          clock.step(1000);
          return {initial, oneMillisecond:pulse().connectionGlow,
            eventTimeMs:999, callbackTimeMs:1000};
        });
        assert.equal(values.initial, 0);
        assert.ok(values.oneMillisecond > 0 && values.oneMillisecond < .02);
        progression.delayedCallback = values;
      } finally { await page.close(); }
    });
    if (output) await fs.writeFile(path.join(output, 'motion-progression.json'), JSON.stringify(progression, null, 2) + '\n');
    await check('bundled-assets-only', async () => assert.deepEqual(external, []));
    await context.close();
  } finally {
    if (browser) await browser.close();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
  if (output) await fs.writeFile(path.join(output, 'checks.json'), JSON.stringify(results, null, 2) + '\n');
  console.log(JSON.stringify({run:results.length, passed:results.filter(item => item.passed).length,
    failed:results.filter(item => !item.passed).length}));
  if (results.some(item => !item.passed)) process.exitCode = 1;
}
main().catch(error => { console.error(error); process.exitCode = 1; });
