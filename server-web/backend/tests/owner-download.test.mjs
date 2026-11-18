globalThis.localStorage = {getItem: () => 'zh-CN'};
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';

const source = await readFile(new URL('../web/js/owner-download.js', import.meta.url), 'utf8');
const {createOwnerDownload} = await import('../web/js/owner-download.js');
const content = Buffer.from('a synthetic APK payload for resume and integrity testing');
const release = {url:'/downloads/KOne-owner-b10031.apk', bytes:content.length,
  sha256:createHash('sha256').update(content).digest('hex')};
function reply(options, data = content, etag = `"${release.sha256}"`) {
  const [,a,b] = /^bytes=(\d+)-(\d+)$/.exec(options.headers.Range);
  return new Response(data.subarray(+a,+b+1), {status:206,
    headers:{'Content-Range':`bytes ${a}-${b}/${content.length}`,ETag:etag}});
}
const config = request => ({request,chunkBytes:8,retryDelayMs:0,timeoutMs:100});

test('same-session chunk download verifies content and reuses completed blob', async () => {
  const seen=[];
  const transfer=createOwnerDownload(release,config(async (url,options)=>{
    assert.equal(url,release.url);assert.equal(options.credentials,'same-origin');
    assert.equal(options.redirect,'error');seen.push(options.headers.Range);return reply(options);
  }));
  const result=await transfer.run(new AbortController().signal);
  assert.deepEqual(Buffer.from(await result.arrayBuffer()),content);
  const count=seen.length;assert.equal(await transfer.run(new AbortController().signal),result);
  assert.equal(seen.length,count);assert.equal(count,Math.ceil(content.length/8));
});

test('failed chunk resumes without downloading committed chunks again',async()=>{
  let failing=true;const seen=[];
  const transfer=createOwnerDownload(release,config(async (url,options)=>{
    seen.push(options.headers.Range);
    if(failing&&options.headers.Range.startsWith('bytes=8-'))throw new TypeError('network lost');
    return reply(options);
  }));
  await assert.rejects(transfer.run(new AbortController().signal),/连接中断/);
  assert.equal(seen.filter(s=>s.startsWith('bytes=0-')).length,1);
  failing=false;
  const result=await transfer.run(new AbortController().signal);
  assert.deepEqual(Buffer.from(await result.arrayBuffer()),content);
  assert.equal(seen.filter(s=>s.startsWith('bytes=0-')).length,1);
});

test('authentication failure does not retry or save an HTML error page',async()=>{
  let calls=0;
  const transfer=createOwnerDownload(release,config(async()=>{calls++;return new Response('login',{status:401});}));
  await assert.rejects(transfer.run(new AbortController().signal),/登录已过期/);assert.equal(calls,1);
});

test('changed release validator is rejected before mixing data',async()=>{
  const transfer=createOwnerDownload(release,config(async(url,options)=>reply(options,content,'"changed"')));
  await assert.rejects(transfer.run(new AbortController().signal),/下载响应不完整/);
});

test('successful HTTP responses still need a matching APK hash',async()=>{
  const bad=Buffer.from(content);bad[3]^=1;
  const transfer=createOwnerDownload(release,config(async(url,options)=>reply(options,bad)));
  await assert.rejects(transfer.run(new AbortController().signal),/校验未通过/);
});

test('cancelled download can continue from its last completed chunk',async()=>{
  const controller=new AbortController();let seen=[];
  const transfer=createOwnerDownload(release,config(async(url,options)=>{seen.push(options.headers.Range);return reply(options);}));
  await assert.rejects(transfer.run(controller.signal,state=>{if(state.received===8)controller.abort();}),{name:'AbortError'});
  const result=await transfer.run(new AbortController().signal);
  assert.deepEqual(Buffer.from(await result.arrayBuffer()),content);
});

test('stalled response is aborted instead of waiting forever at zero',async()=>{
  let calls=0;
  const transfer=createOwnerDownload(release,config(async(url,options)=>{
    calls++;return new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new DOMException('timeout','AbortError'))));
  }));
  await assert.rejects(transfer.run(new AbortController().signal),/连接中断/);assert.equal(calls,3);
});
