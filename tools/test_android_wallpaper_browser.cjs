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
