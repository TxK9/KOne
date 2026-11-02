/* This module has no dependency on the gallery, matchMedia, or city renderers. */
(() => {
'use strict';
if(document.documentElement.dataset.site!=='product')return;
const $=id=>document.getElementById(id), L=window.KOneLocale;
const t=k=>L?L.t(k):({checkingRelease:'Checking the release…',releaseError:'Release details unavailable. The published link remains available.',checkingFile:'Checking the file…',fileError:'File unavailable. Please try again.',downloadStarted:'Download requested. Check your browser’s downloads.',timedOut:'Request timed out. Try again.',notApproved:'This release is not available.',fileMismatch:'File size mismatch. Download stopped.',unsigned:'Unsigned installer. Windows may show a security warning.',continueDownload:'Continue download?',sizeUnit:'MB',byteUnit:'bytes',sourceError:'Source file unavailable.',gplError:'License unavailable.'}[k]||k);
const messages={},retryFiles={},busy=new Set();let architecture='x64',archChosen=false,pending=null;
// Pinned, previously verified release fallback; metadata is refreshed independently.
let android={version:'1.0.0',versionCode:10041,url:$('download-button').getAttribute('href'),bytes:102530593,sha256:$('android-sha').textContent,sourceUrl:$('source-download').getAttribute('href'),sourceBytes:119622416,sourceSha256:$('android-source-sha').textContent};
let windows={"version":"1.0.71","signed":false,"installers":{"x64":{"url":"downloads/windows/KOne-1.0.71.msi","bytes":144985187,"sha256":"97c19cb6d536ddb228e115d9c38d2d9d3003cce3193e6ffb65bb0eedb7dd0cac"},"arm64":{"url":"downloads/windows/KOne-1.0.71-arm64.msi","bytes":140931158,"sha256":"47d88224597abf54d5c0fecb5c359fd572166f17a29cf5159a0b109641a45762"}},"source":{"url":"downloads/windows/KOne-1.0.71-source.tar.gz","bytes":187635592,"sha256":"0f28650dca45e077e7d8241e1f4bbdc8d846779a91147ce510196cb27042bf10"}};
let sourceAllowed=true;let allowed={android:true,windows:true},sequence={android:0,windows:0};
function report(id,key=''){messages[id]=key;$(id+'-status').textContent=key?t(key):'';}
async function request(url,options={},json=false){
 const controller=typeof AbortController==='function'?new AbortController():null;let timer;
 try{return await Promise.race([(async()=>{const r=await fetch(url,{...options,cache:'no-store',...(controller?{signal:controller.signal}:{})});if(json){if(!r.ok)throw Error('releaseError');return r.json();}return r;})(),new Promise((_,reject)=>{timer=setTimeout(()=>{controller?.abort();reject(Error('timedOut'));},8000);})]);}
 catch(error){if(error.name==='AbortError')throw Error('timedOut');throw error;}
 finally{clearTimeout(timer);}
}
const fileValid=f=>f&&Number.isSafeInteger(f.bytes)&&f.bytes>0&&/^[a-f0-9]{64}$/i.test(f.sha256||'');
const sourceAndroid=d=>({url:d.sourceUrl,bytes:d.sourceBytes,sha256:d.sourceSha256});
const safeAndroid=d=>d&&d.state==='ready'&&d.channel==='invite-client'&&d.approvedForDownload===true&&d.activationRequired===true&&['pending','available'].includes(d.activationService)&&/^\d+\.\d+\.\d+$/.test(d.version||'')&&Number.isSafeInteger(d.versionCode)&&d.versionCode>0&&fileValid(d)&&d.architecture==='arm64-v8a'&&d.url===`downloads/KOne-client-${d.version}-b${d.versionCode}-arm64-v8a.apk`&&d.sourceUrl===`downloads/KOne-client-${d.version}-b${d.versionCode}-source.tar.gz`&&fileValid(sourceAndroid(d))&&typeof d.sourcePublicationApproved==='boolean';
const safeWindows=d=>d&&d.state==='ready'&&d.approvedForDownload===true&&/^\d+\.\d+\.\d+$/.test(d.version||'')&&fileValid(d.installers?.x64)&&fileValid(d.installers?.arm64)&&fileValid(d.source)&&d.installers.x64.url===`downloads/windows/KOne-${d.version}.msi`&&d.installers.arm64.url===`downloads/windows/KOne-${d.version}-arm64.msi`&&d.source.url===`downloads/windows/KOne-${d.version}-source.tar.gz`;
function link(id,file){$(id).href=file.url;$(id).download=file.url.split('/').pop();}
function chooseArchitecture(value){
 architecture=value==='arm64'?'arm64':'x64';const f=windows.installers[architecture];link('windows-download',f);
 $('windows-meta').textContent=new Intl.NumberFormat(L?.locale||'en',{minimumFractionDigits:1,maximumFractionDigits:1}).format(f.bytes/1048576)+' '+t('sizeUnit');
 $('windows-sha').textContent=f.sha256;$('windows-bytes').textContent=f.bytes+' '+t('byteUnit');
 document.querySelectorAll('[data-windows-arch]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.windowsArch===architecture)));
}
function render(){link('download-button',android);link('source-download',sourceAndroid(android));link('windows-source',windows.source);$('release-summary').textContent=android.version+' · b'+android.versionCode;$('windows-summary').textContent=windows.version;$('release-meta').textContent=new Intl.NumberFormat(L?.locale||'en',{minimumFractionDigits:1,maximumFractionDigits:1}).format(android.bytes/1048576)+' '+t('sizeUnit');$('android-sha').textContent=android.sha256;$('android-bytes').textContent=android.bytes+' '+t('byteUnit');$('android-source-sha').textContent=android.sourceSha256;$('windows-source-sha').textContent=windows.source.sha256;chooseArchitecture(architecture);for(const[id,key]of Object.entries(messages))report(id,key);}
async function refresh(platform){
 const seq=++sequence[platform];report(platform,'checkingRelease');$(platform+'-retry').hidden=true;
 try{const d=await request(platform==='android'?'release.json':'windows-release.json',{},true);if(seq!==sequence[platform])return;
 if(d?.approvedForDownload===false||['withdrawn','revoked'].includes(d?.state)){allowed[platform]=false;throw Error('notApproved');}
 if(!(platform==='android'?safeAndroid(d):safeWindows(d)))throw Error('releaseError');
 allowed[platform]=true;if(platform==='android'){android=d;sourceAllowed=d.sourcePublicationApproved;}else windows=d;render();report(platform);
 }catch(e){if(seq!==sequence[platform])return;report(platform,['timedOut','notApproved'].includes(e.message)?e.message:'releaseError');$(platform+'-retry').hidden=false;}
 $(platform==='android'?'download-button':'windows-download').setAttribute('aria-disabled',String(!allowed[platform]));
}
async function checkFile(file){
 let response=await request(file.url,{method:'HEAD'});let size;
 if([405,501].includes(response.status)){
  response=await request(file.url,{headers:{Range:'bytes=0-0'}});
  const range=response.headers.get('content-range');
  size=response.status===206?Number(range?.split('/').pop()):Number(response.headers.get('content-length'));
  response.body?.cancel().catch(()=>{});
 }else size=Number(response.headers.get('content-length'));
 if(!response.ok)throw Error('fileError');
 // Missing Content-Length is permitted; a supplied incorrect length is not.
 if(size>0&&Number.isSafeInteger(file.bytes)&&size!==file.bytes)throw Error('fileMismatch');
}
function nativeDownload(file){const a=document.createElement('a');a.href=file.url;a.download=file.url.split('/').pop();document.body.appendChild(a);a.click();a.remove();}
async function start(platform,file){
 if(busy.has(platform))return;if(!allowed[platform]&&platform!=='source'){report(platform,'notApproved');return;}
 busy.add(platform);report(platform,'checkingFile');if(platform!=='source')$(platform+'-retry').hidden=true;
 try{await checkFile(file);delete retryFiles[platform];nativeDownload(file);report(platform,'downloadStarted');}
 catch(e){retryFiles[platform]=file;report(platform,['timedOut','fileMismatch'].includes(e.message)?e.message:platform==='source'?'sourceError':'fileError');if(platform!=='source')$(platform+'-retry').hidden=false;}
 finally{busy.delete(platform);}
}
$('download-button').addEventListener('click',e=>{e.preventDefault();start('android',android);});
$('windows-download').addEventListener('click',e=>{e.preventDefault();if(!allowed.windows){report('windows','notApproved');return;}if(busy.has('windows'))return;pending={...windows.installers[architecture]};if(windows.signed===true){start('windows',pending);pending=null;return;}const dialog=$('windows-warning');if(typeof dialog.showModal==='function')dialog.showModal();else{if(confirm(t('unsigned')+'\n'+t('continueDownload')))start('windows',pending);pending=null;}});
$('windows-cancel').addEventListener('click',()=>{$('windows-warning').close();pending=null;});
$('windows-confirm').addEventListener('click',()=>{const f=pending;pending=null;$('windows-warning').close();if(f)start('windows',f);});
$('windows-warning').addEventListener('close',()=>{pending=null;});
for(const[id,getFile]of [['source-download',()=>sourceAndroid(android)],['windows-source',()=>windows.source],['gpl-download',()=>({url:'downloads/GPL-3.0.txt'})]])$(id).addEventListener('click',e=>{e.preventDefault();if(id==='source-download'&&!sourceAllowed){report('source','notApproved');return;}start('source',getFile());});
for(const platform of ['android','windows'])$(platform+'-retry').addEventListener('click',async()=>{const file=retryFiles[platform];await refresh(platform);if(file&&allowed[platform])return start(platform,platform==='android'?android:windows.installers[architecture]);});
document.querySelectorAll('[data-windows-arch]').forEach(n=>n.addEventListener('click',()=>{archChosen=true;chooseArchitecture(n.dataset.windowsArch);}));
try{navigator.userAgentData?.getHighEntropyValues?.(['architecture']).then(v=>{if(!archChosen&&v.architecture==='arm')chooseArchitecture('arm64');}).catch(()=>{});}catch(_){}
window.addEventListener('kone:locale',render);
// The skip link always escapes the opening, independently of app.js.
document.querySelectorAll('.skip,.intro-skip').forEach(n=>n.addEventListener('click',()=>{$('intro').close?.();document.body.classList.remove('modal-opening');}));
render();const androidReady=refresh('android'),windowsReady=refresh('windows');
window.KOneDownloads={androidReady,windowsReady,refresh,checkFile,getState:()=>({architecture,allowed:{...allowed},busy:[...busy],messages:{...messages}})};
})();
