/* The public preview surface uses the original reviewed v13 rendering engines. */
(() => {
'use strict';
const L=window.KOneLocale,$=id=>document.getElementById(id),params=new URLSearchParams(location.search);
const reduce=matchMedia('(prefers-reduced-motion: reduce)');
const lowPower=Boolean(navigator.connection?.saveData||(navigator.hardwareConcurrency&&navigator.hardwareConcurrency<=4)||(navigator.deviceMemory&&navigator.deviceMemory<=2));
let requestedMotion=!reduce.matches&&!lowPower,visible=true,scene=null,config=null,lifeConfig=null,scenes=[],controllers=[],skies=[],model=null,requestId=0,weather=params.get('weather')||'clear',loading=false,degraded=false,failed=false;
let clock={time:0,anchor:performance.now(),running:false};
function effectTime(){return clock.time+(clock.running?(performance.now()-clock.anchor)/1000:0);}
function sync(){const running=requestedMotion&&visible&&!document.hidden&&!lowPower&&!reduce.matches;if(clock.running!==running){clock.time=effectTime();clock.anchor=performance.now();clock.running=running;}for(const c of controllers){c.setVisible?.(visible);c.setPlaying?.(running);} $('motion-toggle').textContent=L.t(requestedMotion?'pause':'play');$('motion-toggle').setAttribute('aria-pressed',String(requestedMotion));$('motion-toggle').disabled=lowPower||reduce.matches;$('motion-toggle').title=lowPower||reduce.matches?L.t('staticNote'):'';}
function localize(){$('preview-status').textContent=failed?L.t('previewError'):loading&&!scene?L.t('loadingCity'):'';document.title=L.t('previewTitle');L.apply();if(scene){$('city-select').value=scene.id;document.querySelectorAll('.phone-group').forEach(group=>{const period=L.t(group.dataset.mode);group.querySelector('.caption').textContent=period;const label=L.t('sceneImage',{city:L.city(scene.id).name,period});group.querySelector('.phone').setAttribute('aria-label',label);group.querySelectorAll('img').forEach(img=>img.alt='');});}sync();}
function listen(query,fn){if(query.addEventListener)query.addEventListener('change',fn);else query.addListener?.(fn);}
async function fetchJson(path){const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);try{const r=await fetch(path,{signal:controller.signal});if(!r.ok)throw Error(path);return await r.json();}finally{clearTimeout(timer);}}
async function decode(src){const image=new Image();image.src=src;await Promise.race([image.decode?image.decode():new Promise((res,rej)=>{image.onload=res;image.onerror=rej;}),new Promise((_,rej)=>setTimeout(()=>rej(Error('image timeout')),12000))]);if(!image.naturalWidth)throw Error('empty image');}
function attach(name,factory){try{const c=factory();if(c)controllers.push(c);return c;}catch(error){degraded=true;console.warn('City layer unavailable:',name,error);return null;}}
function clear(){for(const c of controllers)c.destroy?.();for(const c of skies)c.destroy?.();controllers=[];skies=[];}
function phone(theme){const group=document.createElement('figure');group.className='phone-group';group.dataset.mode=theme;group.innerHTML='<figcaption class="caption"></figcaption><div class="phone" role="img" data-theme="'+theme+'"><img class="wallpaper wallpaper-night" alt=""><img class="wallpaper wallpaper-day" alt=""><div class="motion-layer" aria-hidden="true"></div><div class="marine-layer" aria-hidden="true"></div><div class="lighting-layer" aria-hidden="true"></div><div class="sky-layer" aria-hidden="true"></div><div class="citylife-layer" aria-hidden="true"></div><div class="weather-layer" aria-hidden="true"></div></div>';for(const kind of ['day','night'])group.querySelector('.wallpaper-'+kind).src=scene.assets[kind];return group;}
function commit(next){
 clear();scene=next;degraded=false;clock={time:0,anchor:performance.now(),running:false};
 model=window.KOneWeatherModelV11?.create(scene,{mode:weather,date:Date.now()});window.KOneEnvironmentV13?.bind(scene.id,model);
 const groups=['day','night'].map(phone);$('gallery').replaceChildren(...groups);
 if(!lowPower&&config&&window.ResizeObserver&&window.IntersectionObserver){for(const group of groups){
  const theme=group.dataset.mode,source=group.querySelector('.wallpaper-'+theme),blend=group.querySelector('.wallpaper-'+(theme==='day'?'night':'day'));
  const getWeather=time=>model?.sample(time)||{};const options={config,playing:false,intensity:'expressive',allowReducedMotion:false,maxFps:24,environmentTime:effectTime,getWeather,blendImage:blend};
  const layer=name=>group.querySelector('.'+name+'-layer');
  const motion=attach('motion',()=>window.NycMotionV13?.attach(layer('motion'),scene,theme,source,options));
  const light=attach('lighting',()=>window.NycLightV13?.attach(layer('lighting'),scene,theme,source,options));
  const marine=attach('marine',()=>window.NycMarineV13?.attach(layer('marine'),scene,theme,source,options));
  const rainfall=attach('weather',()=>window.KOneWeatherV13?.attach(layer('weather'),scene,theme,source,options));
  const life=attach('city life',()=>window.KOneCityLifeV13?.attach(layer('citylife'),scene,theme,source,{...options,config:lifeConfig}));
  if(window.NycSkyV5){try{
   const zone=scene.cityLocation?{...scene.cityLocation,zone:scene.cityZone,label:L.city(scene.id).name}:scene.cityZone;
   const sky=window.NycSkyV5.attach(layer('sky'),scene,theme,{zone,onState:state=>{const s={...state,daylight:theme==='day'?1:0};light?.setSky(s);motion?.setLighting(s);marine?.setSky(s);rainfall?.setSky(s);life?.setSky(s);}});
   skies.push(sky);const date=new Intl.DateTimeFormat('en-CA',{timeZone:scene.cityZone,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());const parts=Object.fromEntries(date.map(p=>[p.type,p.value]));const demo=window.NycSkyV5.chooseDemo(scene,theme,zone,`${parts.year}-${parts.month}-${parts.day}`);sky.render(demo.instant,zone,demo);
  }catch(error){degraded=true;console.warn('City sky unavailable',error);}}
  group.querySelector('.phone').addEventListener('pointerdown',event=>{if(!clock.running)return;const r=event.currentTarget.getBoundingClientRect(),tr=life?.state.imageTransform;if(tr)life.interact({x:(event.clientX-r.left-tr.x)/tr.width,y:(event.clientY-r.top-tr.y)/tr.height});});
 }}else degraded=true;
 localize();sync();
}
async function setScene(id){
 const next=scenes.find(s=>s.id===id);if(!next)throw Error('unknown city');const seq=++requestId;loading=true;failed=false;$('gallery').classList.add('changing');$('gallery').setAttribute('aria-busy','true');$('preview-status').textContent=scene?'':L.t('loadingCity');$('preview-retry').hidden=true;
 try{await Promise.all([decode(next.assets.day),decode(next.assets.night)]);if(seq!==requestId)return false;commit(next);$('preview-status').textContent='';return true;}
 catch(error){if(seq===requestId){failed=true;$('preview-status').textContent=L.t('previewError');$('preview-retry').hidden=false;}throw error;}
 finally{if(seq===requestId){loading=false;$('gallery').classList.remove('changing');$('gallery').setAttribute('aria-busy','false');}}
}
function setMotion(value){requestedMotion=Boolean(value)&&!reduce.matches;sync();}
function setWeather(value){if(!['clear','cloudy','storm','mist'].includes(value))return;weather=value;model?.setMode(value,effectTime(),{immediate:!clock.running});$('weather-select').value=value;}
const ready=(async()=>{try{scenes=await fetchJson('scenes-v9.json');[config,lifeConfig]=await Promise.all([fetchJson('motion-scenes-v9.json').catch(()=>null),fetchJson('city-life-scenes-v12.json').catch(()=>null)]);await setScene(scenes.find(s=>s.id===params.get('scene'))?.id||scenes[0].id);return scenes.map(s=>s.id);}catch(error){failed=true;$('preview-status').textContent=L.t('previewError');$('preview-retry').hidden=false;return [];}})();
$('preview-retry').addEventListener('click',()=>location.reload());$('city-select').addEventListener('change',e=>setScene(e.target.value).catch(()=>{}));$('weather-select').addEventListener('change',e=>setWeather(e.target.value));$('motion-toggle').addEventListener('click',()=>setMotion(!requestedMotion));
window.addEventListener('kone:locale',localize);document.addEventListener('visibilitychange',sync);listen(reduce,()=>{if(reduce.matches)requestedMotion=false;sync();});
window.addEventListener('pagehide',clear);
window.KOnePreview={ready,setScene,setMotion,setWeather,setLocale:value=>L.setLocale(value,{persist:false}),setVisible:value=>{visible=Boolean(value);sync();},getState:()=>({scene:scene?.id,motion:clock.running,requestedMotion,lowPower,degraded,loading,weather,locale:L.locale,layers:controllers.map(c=>c.state),sky:skies.map(c=>c.state),effectTime:effectTime()}),setOverlay:()=>{},setLayout:()=>{},setIntensity:()=>{}};
})();
