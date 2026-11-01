/* City presentation only. Download initialization lives in downloads.js. */
(() => {
'use strict';
if(document.documentElement.dataset.site!=='product')return;
const L=window.KOneLocale,$=id=>document.getElementById(id),frame=$('world-frame'),reduce=matchMedia('(prefers-reduced-motion: reduce)');
const coords=['37.8° N / 122.4° W','31.2° N / 121.5° E','35.7° N / 139.8° E','51.5° N / 0.1° W','48.9° N / 2.4° E','33.9° S / 151.2° E'];
const lowPower=Boolean(navigator.connection?.saveData||(navigator.hardwareConcurrency&&navigator.hardwareConcurrency<=4)||(navigator.deviceMemory&&navigator.deviceMemory<=2));
let current='san-francisco-bay',requested='san-francisco-bay',requestedMotion=!reduce.matches&&!lowPower,visible=false,api=null,sequence=0,started=false,timer=null,error=false;
function labels(){const city=L.city(current);$('world-city').textContent=city.name;$('world-country').textContent=city.country;$('world-story').textContent=city.story;$('world-coordinate').textContent=coords[L.ids.indexOf(current)];document.querySelectorAll('[data-country-scene]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.countryScene===current)));$('world-motion').textContent=L.t(requestedMotion?'pause':'play');$('world-motion').setAttribute('aria-pressed',String(requestedMotion));$('world-motion').disabled=lowPower||reduce.matches;$('world-motion').title=lowPower||reduce.matches?L.t('staticNote'):'';$('world-note').textContent=L.t(lowPower||reduce.matches?'staticNote':'artNote');if(!$('world-loading').hidden)$('world-loading').textContent=L.t(error?'worldError':'loadingCity');}
function play(){api?.setVisible(visible&&!document.hidden);api?.setMotion(requestedMotion&&visible&&!document.hidden);labels();}
function fail(){error=true;$('world-loading').hidden=false;$('world-loading').textContent=L.t('worldError');$('world-retry').hidden=false;clearTimeout(timer);}
async function select(scene){if(!L.ids.includes(scene))return false;requested=scene;if(!started)start();if(!api)return false;const seq=++sequence;error=false;$('world-loading').hidden=false;$('world-loading').textContent=L.t('loadingCity');$('world-retry').hidden=true;clearTimeout(timer);timer=setTimeout(fail,18000);
 try{const done=await api.setScene(scene);if(seq!==sequence)return false;if(done===false)throw Error('scene unavailable');current=scene;api.setWeather($('world-weather').value);clearTimeout(timer);$('world-loading').hidden=true;play();return true;}catch(_){if(seq===sequence)fail();return false;}}
function start(){if(started)return;started=true;error=false;const url=new URL(frame.dataset.src,location.href);url.searchParams.set('lang',L.locale);frame.src=url.href;timer=setTimeout(fail,22000);}
frame.addEventListener('load',async()=>{if(!started)return;try{const next=frame.contentWindow.KOnePreview;if(!next)throw Error('missing preview');const ids=await next.ready;if(!ids.length)throw Error('missing scenes');api=next;api.setLocale(L.locale);await select(requested);}catch(_){fail();}});
frame.addEventListener('error',fail);
if(window.IntersectionObserver){const preload=new IntersectionObserver(entries=>{if(entries.some(e=>e.isIntersecting)){start();preload.disconnect();}},{rootMargin:'250px'});preload.observe($('world-preview'));const activity=new IntersectionObserver(entries=>{visible=entries.some(e=>e.isIntersecting);play();},{threshold:.03});activity.observe($('world-preview'));}else{visible=true;start();}
document.querySelectorAll('[data-country-scene]').forEach(n=>n.addEventListener('click',()=>select(n.dataset.countryScene)));
$('world-motion').addEventListener('click',()=>{requestedMotion=!requestedMotion;if(reduce.matches||lowPower)requestedMotion=false;play();});
$('world-weather').addEventListener('change',e=>api?.setWeather(e.target.value));$('world-retry').addEventListener('click',()=>{if(api)select(requested);else{started=false;start();}});
document.addEventListener('visibilitychange',play);const onReduced=()=>{if(reduce.matches)requestedMotion=false;play();};if(reduce.addEventListener)reduce.addEventListener('change',onReduced);else reduce.addListener?.(onReduced);
window.addEventListener('kone:locale',()=>{api?.setLocale(L.locale);labels();});labels();
window.KOneSitePreview={select,getState:()=>({scene:current,requested,requestedMotion,visible,started,error,rendering:api?.getState()||null})};
})();
