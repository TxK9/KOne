const browserTools = require('./browser-tools.cjs');
const {chromium} = browserTools.playwright();
const assert=require('assert/strict'),fs=require('fs');
(async()=>{
 const browser=await chromium.launch(browserTools.launchOptions());
 const context=await browser.newContext({viewport:{width:1280,height:450},locale:'en',reducedMotion:'reduce'});
 const page=await context.newPage();await page.goto(process.env.KONE_TEST_ORIGIN||'http://127.0.0.1:18791');
 await page.locator('.scenery').waitFor();
 await page.evaluate(async()=>{
  const {scenery}=await import('/js/ink.js');const now=Date.now;
  const gallery=document.createElement('div');gallery.style='display:flex;position:fixed;inset:0;z-index:10000;background:var(--paper)';
  for(let i=0;i<4;i++){
   Date.now=()=>i*86400000;const art=scenery();art.style='position:relative;width:320px;height:426px;flex:none;--scene-right:0%';gallery.append(art);
  }
  Date.now=now;document.getElementById('kone').replaceChildren(gallery);
  await Promise.all([...gallery.querySelectorAll('img')].map(img=>img.decode()));
 });
 fs.mkdirSync(__dirname+'/screenshots',{recursive:true});
 for(const mode of ['light','dark']){
  await page.evaluate(mode=>document.documentElement.dataset.theme=mode,mode);
  const states=await page.locator('.scene-'+mode).evaluateAll(nodes=>nodes.map(el=>({loaded:el.complete&&el.naturalWidth>0,display:getComputedStyle(el).display,clip:getComputedStyle(el).clipPath,animation:getComputedStyle(el).animationName})));
  assert.equal(states.length,4);for(const s of states){assert.ok(s.loaded);assert.notEqual(s.display,'none');assert.ok(s.clip.startsWith('polygon'));assert.equal(s.animation,'none');}
  await page.screenshot({path:__dirname+'/screenshots/art-en-'+mode+'.png'});
 }
 await browser.close();console.log('PASS all 4 paintings clipped in English, both themes loaded, reduced motion respected');
})();
