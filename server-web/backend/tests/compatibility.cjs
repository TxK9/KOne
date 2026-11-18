const browserTools = require('./browser-tools.cjs');
const {chromium} = browserTools.playwright();
const assert=require('assert/strict');
(async()=>{
 const browser=await chromium.launch(browserTools.launchOptions());
 const errors=[];
 for(const locale of ['en','zh-CN']){
  const context=await browser.newContext({locale,reducedMotion:'reduce'});
  await context.addInitScript(()=>{
   Object.hasOwn=undefined;Intl.ListFormat=undefined;Intl.PluralRules=undefined;
   const original=window.matchMedia.bind(window);
   window.matchMedia=query=>{const media=original(query);media.addEventListener=undefined;media.removeEventListener=undefined;return media;};
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto(process.env.KONE_TEST_ORIGIN||'http://127.0.0.1:18791');await page.locator('.login-form').waitFor();
  await page.locator('#login-password').fill('ink-demo-2026');await page.locator('.login-go').click();await page.locator('.app').waitFor();
  await page.locator('[data-page="configs"]').click();await page.locator('.config-card').first().waitFor();
  const output=await page.evaluate(async()=>{const i=await import('/js/i18n.js');return {one:i.count(1,'device'),many:i.count(2,'device'),list:i.listText(['A','B']),error:i.systemText('密码不正确')};});
  assert.equal(output.one,locale==='en'?'1 device':'1 台设备');assert.equal(output.many,locale==='en'?'2 devices':'2 台设备');assert.equal(output.list,locale==='en'?'A, B':'A、B');assert.equal(output.error,locale==='en'?'Incorrect password':'密码不正确');
  await context.close();
 }
 const nojs=await browser.newContext({javaScriptEnabled:false});const nojsPage=await nojs.newPage();
 await nojsPage.goto(process.env.KONE_TEST_ORIGIN||'http://127.0.0.1:18791');
 assert.equal(await nojsPage.locator('html').getAttribute('lang'),'en');
 assert.ok((await nojsPage.locator('body').innerText()).includes('Enable JavaScript'));
 assert.ok(!/[\u3400-\u9fff]/u.test(await nojsPage.locator('body').innerText()));await nojs.close();
 assert.deepEqual(errors,[]);await browser.close();console.log('PASS both locales without Object.hasOwn, Intl.ListFormat, Intl.PluralRules or modern MediaQueryList listeners');
})();
