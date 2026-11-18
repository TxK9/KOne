const browserTools = require('./browser-tools.cjs');
const {chromium} = browserTools.playwright();
const assert = require('node:assert/strict');
const fs = require('node:fs');
const origin = process.env.KONE_TEST_ORIGIN || 'http://127.0.0.1:18791';
(async()=>{
 const browser = await chromium.launch(browserTools.launchOptions());
 const results=[], errors=[];
 async function check(page, name, en){
  const problems=await page.evaluate(en=>{
   const problems=[];
   for(const el of document.querySelectorAll('body *')){
    if(el.closest('select, script, style, noscript')||!el.getClientRects().length||getComputedStyle(el).visibility==='hidden')continue;
    for(const attr of ['aria-label','title','placeholder','alt']){
     const value=el.getAttribute(attr)||'';if(en&&/[\u3400-\u9fff\u3000-\u303f\uff01-\uff60]/u.test(value))problems.push(attr+': '+value);
    }
    for(const node of el.childNodes)if(node.nodeType===Node.TEXT_NODE&&en&&/[\u3400-\u9fff\u3000-\u303f\uff01-\uff60]/u.test(node.textContent))problems.push('text: '+node.textContent);
   }
   if(document.documentElement.scrollWidth>innerWidth+1)problems.push('horizontal overflow '+document.documentElement.scrollWidth+'/'+innerWidth);
   return problems;
  },en);
  assert.deepEqual(problems,[],name+' language/layout');results.push(name);console.log('OK '+name);
 }
 async function close(page){const btn=page.locator('.sheet-head button');if(await btn.count()){await btn.click();await page.locator('.sheet-layer').waitFor({state:'detached'});}}
 async function visit(page,id){if(new URL(page.url()).hash==='#/'+id) await page.reload({waitUntil:'domcontentloaded'}); else await page.goto(origin+'/#/'+id,{waitUntil:'domcontentloaded'});await page.locator('.app[data-page="'+id+'"]').waitFor();await page.waitForTimeout(150);}
 for(const locale of ['en','zh-CN']){
  const context=await browser.newContext({locale,viewport:{width:1440,height:1000},reducedMotion:'reduce'});
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  const en=locale==='en';
  await page.goto(origin,{waitUntil:'domcontentloaded'});await page.locator('.login-form').waitFor();
  await check(page,locale+' login',en);
  await page.locator('.login-go').click();assert.equal(await page.locator('.login-error').textContent(),en?'Enter your password':'请输入密码');
  await page.locator('#login-password').fill('wrong-password');await page.locator('.login-go').click();await page.waitForFunction(()=>document.querySelector('.login-error')?.textContent);
  assert.equal(await page.locator('.login-error').textContent(),en?'Incorrect password':'密码不正确');
  await page.locator('#login-password').fill('ink-demo-2026');await page.locator('.login-go').click();await page.locator('.app').waitFor();
  for(const id of ['overview','servers','routes','customers','configs','records','feedback']){await visit(page,id);await check(page,locale+' desktop '+id,en);}
  // Populated feedback fixture exercises localized system fields and unmodified free text.
  const report={id:1,number:'FB-TEST-1',revision:1,status:'new',description:'Connection drops after switching nodes.',reply:'Investigation in progress.',fixedVersion:'',appVersion:'1.0.0',versionCode:10041,androidVersion:'15',manufacturer:'Test',model:'Phone',network:'cellular',createdAt:new Date().toISOString(),images:[]};
  await page.route('**/api/feedback?*',r=>r.fulfill({json:{items:[report],nextBefore:null}}));
  await visit(page,'feedback');await page.locator('.feedback-card').waitFor();await check(page,locale+' populated feedback',en);
  report.status='future_unknown';await visit(page,'feedback');await page.locator('.feedback-card').waitFor();
  assert.equal(await page.locator('.feedback-card .pill').textContent(),en?'Unknown':'未知');
  report.status='new';await visit(page,'feedback');await page.locator('.feedback-card').waitFor();
  await page.getByRole('button',{name:en?'Review':'处理',exact:true}).click();await check(page,locale+' feedback sheet',en);
  await page.locator('.sheet select').selectOption('resolved');await page.locator('.sheet .button-primary').click();
  assert.equal(await page.locator('.sheet-error').textContent(),en?'Enter the released version containing the fix':'请填写已发布的修复版本');await close(page);
  const originalDescription=report.description;report.description='<img src=x onerror="window.untrustedExecuted=true"> 用户反馈原文';
  await visit(page,'feedback');await page.locator('.feedback-card').waitFor();
  assert.equal(await page.locator('.feedback-description').first().textContent(),report.description);
  assert.equal(await page.locator('.feedback-card img').count(),0);assert.equal(await page.evaluate(()=>window.untrustedExecuted),undefined);
  results.push(locale+' preserves untrusted customer text');report.description=originalDescription;
  await visit(page,'feedback');await page.locator('.feedback-card').waitFor();
  // Unknown server errors are localized; arbitrary server strings are never translated as data.
  await page.route('**/api/owner-release',r=>r.fulfill({status:503,json:{error:'unrecognized backend detail 中文'}}));
  await page.locator('.rail-foot button').filter({hasText:en?'Owner edition':'自用版'}).click();
  await page.waitForFunction(()=>document.querySelector('.toast')?.textContent);
  assert.equal(await page.locator('.toast').textContent(),en?'Action could not be completed':'操作没有完成');
  await page.unroute('**/api/owner-release');
  const release={state:'ready',version:'1.0.0',versionCode:10041,url:'/downloads/KOne-owner-b10041.apk',bytes:48,sha256:'a'.repeat(64)};
  await page.route('**/api/owner-release',r=>r.fulfill({json:{release,devices:[{id:'test-device',name:'Test phone'}]}}));
  await page.route('**/downloads/KOne-owner-b10041.apk',r=>r.fulfill({status:404,body:''}));
  await page.locator('.rail-foot button').filter({hasText:en?'Owner edition':'自用版'}).click();await check(page,locale+' owner download sheet',en);
  await page.locator('.sheet .button-primary').click();await page.waitForFunction(()=>document.querySelector('.sheet-error')?.textContent);
  assert.equal(await page.locator('.sheet-error').textContent(),en?'Package updated. Close this dialog and check again.':'安装包已更新，请关闭后重新检查版本');await check(page,locale+' download failure',en);await close(page);
  await page.unroute('**/api/owner-release');await page.unroute('**/downloads/KOne-owner-b10041.apk');
  await visit(page,'servers');await page.getByRole('button',{name:en?'Enroll server':'接入服务器',exact:true}).first().click();await check(page,locale+' enrollment sheet',en);await close(page);
  await page.locator('.server-row').first().click();await page.getByRole('button',{name:en?'Deploy protocol':'部署协议',exact:true}).first().click();await check(page,locale+' deployment sheet',en);await close(page);
  await visit(page,'routes');await page.getByRole('button',{name:en?'Add route':'新增线路',exact:true}).first().click();await check(page,locale+' route sheet',en);
  await page.locator('.sheet .choice-trigger').first().click();await check(page,locale+' node choices',en);await page.keyboard.press('Escape');await close(page);
  await page.getByRole('button',{name:en?'Add exit':'添加出口',exact:true}).click();await check(page,locale+' exit sheet',en);await close(page);
  await visit(page,'configs');await page.getByRole('button',{name:en?'Import configuration':'导入配置',exact:true}).first().click();await check(page,locale+' import sheet',en);
  await page.locator('.sheet .button-primary').click();assert.equal(await page.locator('.sheet-error').textContent(),en?'Enter a name':'请填写名称');await close(page);
  await visit(page,'customers');await page.getByRole('button',{name:en?'Add customer':'新增客户',exact:true}).first().click();await check(page,locale+' customer sheet',en);await close(page);
  await page.locator('.customer-row').first().click();
  for(const tab of en?['Configurations','Devices','Usage','Diagnostics']:['配置','设备','流量','诊断']){await page.locator('.inspector [role=tab]').filter({hasText:tab}).click();await page.waitForTimeout(100);await check(page,locale+' customer '+tab,en);}
  await page.locator('.inspector [role=tab]').first().click();
  const keyButton=page.getByRole('button',{name:en?/^(Generate|Regenerate) key$/:/^(生成密钥|重新生成密钥)$/});
  if(await keyButton.count()){
    await keyButton.click();await page.locator('.key-slip').waitFor();await check(page,locale+' issued activation key',en);
    const secret=await page.locator('.key-slip code').textContent();assert.ok(secret.startsWith('kone://claim'));
  }
  await page.getByRole('button',{name:en?'Add group':'新增分组',exact:true}).click();await check(page,locale+' group sheet',en);await close(page);
  // Protect unsaved inline configuration changes before a locale-triggered reload.
  const originalGroup=await page.locator('.inspector .choice').first().evaluate(el=>el.value);
  await page.locator('.inspector .choice').first().evaluate(el=>{el.value='';el.dispatchEvent(new Event('change',{bubbles:true}));});
  await page.locator('.language-select').selectOption(en?'zh-CN':'en');await page.locator('.sheet').waitFor();
  assert.equal(await page.locator('.sheet-body p').textContent(),en?'Unsaved changes will be lost.':'未保存的修改会丢失。');
  await check(page,locale+' unsaved language guard',en);await close(page);
  assert.equal(await page.locator('.language-select').inputValue(),locale);
  await page.locator('.inspector .choice').first().evaluate((el,value)=>{el.value=value;el.dispatchEvent(new Event('change',{bubbles:true}));},originalGroup);
  // Switching uses a dedicated preference and a reload, retaining authentication and route.
  await page.locator('.language-select').selectOption(en?'zh-CN':'en');await page.waitForLoadState('networkidle');await page.locator('.app').waitFor();
  assert.equal(await page.locator('html').getAttribute('lang'),en?'zh-CN':'en');
  assert.equal(await page.evaluate(()=>localStorage.getItem('kone.admin.locale')),en?'zh-CN':'en');
  await page.reload({waitUntil:'domcontentloaded'});await page.locator('.app').waitFor();assert.equal(await page.locator('html').getAttribute('lang'),en?'zh-CN':'en');
  await page.locator('.language-select').selectOption(locale);await page.waitForLoadState('networkidle');await page.locator('.app').waitFor();
  for(const width of [390,320]){
   await page.setViewportSize({width,height:844});
   for(const id of ['overview','servers','routes','customers','configs','records','feedback']){await visit(page,id);await check(page,locale+' '+width+' '+id,en);}
   await visit(page,'customers');await page.getByRole('button',{name:en?'Add customer':'新增客户',exact:true}).first().click();await check(page,locale+' '+width+' modal',en);await close(page);
  }
  fs.mkdirSync(__dirname+'/screenshots',{recursive:true});await page.screenshot({path:__dirname+'/screenshots/'+locale+'-mobile.png',fullPage:true});
  await page.setViewportSize({width:1440,height:1000});await visit(page,'overview');await page.screenshot({path:__dirname+'/screenshots/'+locale+'-desktop.png',fullPage:true});
  await context.close();
 }
 assert.deepEqual(errors,[]);await browser.close();fs.writeFileSync(__dirname+'/ui-results.json',JSON.stringify({checks:results.length,results,errors},null,2));console.log('PASS '+results.length+' bilingual UI checks');
})().catch(e=>{console.error(e);process.exit(1)});
