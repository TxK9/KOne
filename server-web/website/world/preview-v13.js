(() => {
  'use strict';

  const VERSION = 'world-cities-v13';
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const assetUrl = path => `${path}?v=${VERSION}`;
  const icon = (name, cls = '') => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><use href="#i-${name}"/></svg>`;
  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');
  let motionConfig = null;
  const state = {intensity: 'bold', country: 'ALL', search: '', loading: false, zone: 'CITY', clockMode: 'pair', instant: Date.now(), clockDate: null, motion: !reduceMotion.matches && !new URLSearchParams(location.search).has('embedded'), scene: null, layout: 'compare', theme: 'day', overlay: true, compact: {day: false, night: false}};
  const availableScenes = [];
  const skyControllers = [];
  const motionControllers = [];
  const marineControllers = [];
  const lightControllers = [];
  const weatherControllers = [],lifeControllers=[];
  let lifeConfig={scenes:{}},weatherModel=null,audio=null,weatherRequest=0,weatherAbort=null;
  let weatherMode=paramsMode(),weatherSeason='auto',weatherMessage='',weatherBusy=false,weatherReadoutTimer=null;
  const weatherCache=new Map();
  function paramsMode(){const p=new URLSearchParams(location.search).get('weather');return ['clear','cloudy','shower','storm','mist','snow','after','story'].includes(p)?p:'story';}
  let effectClock={time:0,anchor:performance.now(),running:!reduceMotion.matches};
  function effectTime(){return effectClock.time+(effectClock.running?Math.max(0,performance.now()-effectClock.anchor)/1000:0);}
  function syncEffectClock(){const running=state.motion&&!document.hidden;if(running!==effectClock.running){effectClock.time=effectTime();effectClock.anchor=performance.now();effectClock.running=running;}}
  function profile(time=effectTime()){return weatherModel?.sample(time)||window.KOneWeatherModelV11.normalized();}
  const rememberedScenes = new Map();
  let bodyDemo=null;
  let sceneRequest = 0, clockInitialized = false;
  let clockTimer = null, clockLast = null, lastDeviceZone = null, controlsKey = '';
  const params = new URLSearchParams(location.search);

  function cityId(scene) {
    if (scene?.cityId) return scene.cityId;
    if (scene?.id === 'sydney-harbour') return 'sydney';
    if (scene?.id === 'san-francisco-bay') return 'san-francisco';
    if (scene?.id === 'london-thames') return 'london';
    return 'new-york';
  }
  function cityLabel(scene) { return scene?.cityLabel || '纽约'; }
  function countryLabel(scene) { return scene?.countryLabel || '美国'; }
  function cityFlag(scene) { return scene?.flag || '🇺🇸'; }
  function attachRenderer(name, factory) {
    try { return factory(); }
    catch (error) { console.warn(`${name} preview unavailable:`, state.scene?.id, error); return null; }
  }
  function escapeHtml(value) { return String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
  function resolveSkyZone() {
    if (state.zone !== 'CITY') return state.zone;
    const location = state.scene?.cityLocation;
    if (location && Number.isFinite(location.lat) && Number.isFinite(location.lon)) {
      return {...location, zone: state.scene.cityZone || location.zone || 'America/New_York', label: cityLabel(state.scene)};
    }
    return state.scene?.cityZone || 'America/New_York';
  }

  function phone(theme, scene) {
    const label = theme === 'day' ? '日间' : '夜间';
    return `<article class="phone-group ${theme === state.theme ? 'single-active' : ''}" data-mode="${theme}">
      <div class="caption"><h2>${label}</h2><span>${theme === 'day' ? '薄雾 · 日光' : '城市 · 微光'}</span></div>
      <div class="phone ${state.compact[theme] ? 'compact' : ''}" data-theme="${theme}" role="img" aria-label="${escapeHtml(scene.title)}，${escapeHtml(cityLabel(scene))}${label}背景与 KOne 示例首页">
        <img class="wallpaper wallpaper-night" ${theme==='night'?'data-source="true"':''} src="${assetUrl(scene.assets.night)}" alt="${escapeHtml(scene.title)}夜间背景">
        <img class="wallpaper wallpaper-day" ${theme==='day'?'data-source="true"':''} src="${assetUrl(scene.assets.day)}" alt="${escapeHtml(scene.title)}日间背景">
        <div class="motion-layer" aria-hidden="true"></div>
        <div class="marine-layer" aria-hidden="true"></div>
        <div class="lighting-layer" aria-hidden="true"></div>
        <div class="sky-layer" aria-hidden="true"></div>
        <div class="citylife-layer" aria-hidden="true"></div>
        <div class="weather-layer" aria-hidden="true"></div>
        <div class="ui" aria-hidden="true">
          <div class="statusbar"><span class="status-time">${theme === 'day' ? '09:41' : '21:41'}</span><div class="status-icons">${icon('signal')}${icon('wifi')}${icon('battery')}</div></div>
          <div class="app-top"><div><div class="app-name">KOne</div><div class="app-subtitle">仪表</div></div><div class="menu">${icon('more')}</div></div>
          <div class="dashboard">
            <div class="panel"><button class="fold-handle" type="button" tabindex="-1" title="收起面板"></button>
              <div class="statusline"><i class="status-dot"></i><span>已连接 · 00:26:08</span><span class="home">${icon('home')}</span>${icon('down', 'chevron')}</div>
              <div class="node-summary"><span class="node-name">${escapeHtml(cityLabel(scene))} · 示例节点</span>${icon('fold')}<span class="traffic">↓ 0 B/s &nbsp; ↑ 0 B/s</span></div>
              <div class="rule"></div><div class="categories"><span class="selected">${escapeHtml(countryLabel(scene))}</span><span>本地</span></div>
              <div class="node-row"><span class="flag">${escapeHtml(cityFlag(scene))}</span><span class="row-name">${escapeHtml(cityLabel(scene))}</span><span class="latency">${icon('sun')}18 ms</span>${icon('check', 'row-check')}<div class="row-menu">${icon('more-h')}</div></div>
            </div>
            <div class="action-row"><div class="compact-summary"><span class="flag">${escapeHtml(cityFlag(scene))}</span><span class="latency">${icon('sun')}18 ms</span>${icon('home')}</div><div class="power">${icon('power')}<span>关闭</span></div></div>
          </div>
          <div class="bottom-nav"><div class="nav-item active">${icon('dashboard')}<span>仪表</span></div><div class="nav-item">${icon('log')}<span>连接</span></div><div class="nav-item">${icon('terminal')}<span>工具</span></div><div class="nav-item">${icon('gear')}<span>设置</span></div></div><div class="home-indicator"></div>
        </div>
      </div>
      <div class="demo-time" aria-live="polite"></div>
      <div class="under-phone"><a href="${assetUrl(scene.assets[theme])}" target="_blank" rel="noopener">背景原图 ↗</a><button type="button" class="fold-button" aria-expanded="${!state.compact[theme]}">${state.compact[theme] ? '展开面板' : '收起面板'}</button></div>
    </article>`;
  }

  function syncControls() {
    syncEffectClock();
    document.body.classList.toggle('single', state.layout === 'single');
    document.body.classList.toggle('pure', !state.overlay);
    $('#compare').setAttribute('aria-pressed', state.layout === 'compare');
    $('#single').setAttribute('aria-pressed', state.layout === 'single');
    $('#day').setAttribute('aria-pressed', state.theme === 'day');
    $('#night').setAttribute('aria-pressed', state.theme === 'night');
    $('#overlay').setAttribute('aria-pressed', state.overlay);
    $('#motion').setAttribute('aria-pressed', state.motion);
    $('#soft').setAttribute('aria-pressed',state.intensity==='soft');
    $('#expressive').setAttribute('aria-pressed',state.intensity==='expressive');
    $('#bold').setAttribute('aria-pressed',state.intensity==='bold');
    const notes = {soft: '轻云细波 · 光照随行', expressive: '云雾流动 · 水光铺开 · 日照随行', bold: '流云穿行 · 水浪起伏 · 船行留痕 · 日照流转'};
    $('#motion-note').textContent = state.motion ? (state.scene?.motionCaption || notes[state.intensity]) : (reduceMotion.matches ? '已定格 · 减少动态效果' : '已定格');
    $$('.phone-group').forEach(group => group.classList.toggle('single-active', group.dataset.mode === state.theme));
    const nextControlsKey=[state.layout,state.theme,state.motion,state.intensity].join('|');
    if(nextControlsKey!==controlsKey){
    for (const {controller, theme} of motionControllers) {
      controller.setVisible(state.layout === 'compare' || state.theme === theme);
      controller.setPlaying(state.motion);
      controller.setIntensity?.(state.intensity);
    }
    for(const {controller,theme} of lightControllers){controller.setVisible(state.layout==='compare'||state.theme===theme);controller.setPlaying(state.motion);controller.setIntensity(state.intensity);}
    for(const {controller,theme} of marineControllers){controller.setVisible(state.layout==='compare'||state.theme===theme);controller.setPlaying(state.motion);controller.setIntensity(state.intensity);}
    for(const {controller,theme} of [...weatherControllers,...lifeControllers]){controller.setVisible(state.layout==='compare'||state.theme===theme);controller.setPlaying(state.motion);controller.setIntensity(state.intensity);}
    audio?.setPlaying(state.motion);
    controlsKey=nextControlsKey;
    }
    $$('.scene-button').forEach(button => button.setAttribute('aria-pressed', button.dataset.scene === state.scene?.id));
    $$('.city-button').forEach(button => button.setAttribute('aria-pressed', button.dataset.city === cityId(state.scene)));
    $$('.country-button').forEach(button => button.setAttribute('aria-pressed', button.dataset.country === state.country));
    scheduleWeatherReadout();
  }

  function setCompact(theme, value) {
    state.compact[theme] = value;
    const group = $(`.phone-group[data-mode="${theme}"]`);
    if (!group) return;
    $('.phone', group).classList.toggle('compact', value);
    const button = $('.fold-button', group);
    button.textContent = value ? '展开面板' : '收起面板';
    button.setAttribute('aria-expanded', !value);
  }

  async function renderScene(scene) {
    if (!scene) return;
    const request = ++sceneRequest;
    state.loading = true;
    $('#gallery').setAttribute('aria-busy', 'true');
    $('#catalog-feedback').textContent = `正在载入 ${cityLabel(scene)}…`;
    try {
      await preloadScene(scene);
      if (request !== sceneRequest) return;
      commitScene(scene);
      $('#catalog-feedback').textContent = '';
      scheduleClock();
    } catch (error) {
      if (request === sceneRequest) $('#catalog-feedback').textContent = `${cityLabel(scene)}未载入，请再试一次`;
      console.warn('City pair unavailable', scene.id, error);
    } finally {
      if (request === sceneRequest) {state.loading = false; $('#gallery').setAttribute('aria-busy', 'false');}
    }
  }

  function commitScene(scene) {
    controlsKey='';
    skyControllers.splice(0).forEach(controller => controller.destroy());
    motionControllers.splice(0).forEach(({controller}) => controller.destroy());
    marineControllers.splice(0).forEach(({controller}) => controller.destroy());
    lightControllers.splice(0).forEach(({controller})=>controller.destroy());
    weatherControllers.splice(0).forEach(({controller})=>controller.destroy());
    lifeControllers.splice(0).forEach(({controller})=>controller.destroy());
    weatherRequest++;weatherAbort?.abort();weatherBusy=false;weatherMessage='';
    const previousCity = state.scene ? cityId(state.scene) : null;
    state.scene = scene;
    effectClock={time:0,anchor:performance.now(),running:state.motion&&!document.hidden};
    if(weatherMode==='live'){weatherMode='story';weatherMessage='已切换城市，天气故事演示中；可读取这座城市的天气';}
    weatherModel=window.KOneWeatherModelV11.create(scene,{mode:weatherMode,season:weatherSeason,date:state.instant});
    window.KOneEnvironmentV13.bind(scene.id,weatherModel);
    if(weatherMode==='snow'&&!weatherModel.status.snowEligible)weatherMode='cloudy';
    if (state.country !== 'ALL' && state.country !== countryLabel(scene)) state.country = 'ALL';
    rememberedScenes.set(cityId(scene), scene.id);
    if (state.clockMode === 'live') state.instant = Date.now();
    if (state.zone === 'CITY' && previousCity !== cityId(scene)) {
      state.clockDate = partDate(localParts(state.clockMode === 'pair' ? Date.now() : state.instant));
    }
    $('#gallery').innerHTML = phone('day', scene) + phone('night', scene);
    $('#scene-title').textContent = `${scene.number} · ${scene.title}`;
    $('#scene-description').textContent = scene.description;
    $('#gallery').setAttribute('aria-label', `${cityLabel(scene)}日间与夜间背景预览`);
    renderSceneButtons();
    $$('.phone-group').forEach(group => {
      const theme = group.dataset.mode;
      const environmentTime=()=>effectTime();
      const getWeather=time=>weatherModel.sample(time);
      const fold = () => setCompact(theme, !state.compact[theme]);
      $('.fold-button', group).addEventListener('click', fold);
      $('.fold-handle', group).addEventListener('click', fold);
      if (window.NycMotionV13?.attach && motionConfig) {
        const controller=attachRenderer('Motion',()=>window.NycMotionV13.attach($('.motion-layer',group),scene,theme,$('.wallpaper[data-source]',group),{config:motionConfig,blendImage:$('.wallpaper:not([data-source])',group),playing:state.motion,allowReducedMotion:true,maxFps:30,intensity:state.intensity,environmentTime}));
        if(controller) motionControllers.push({controller,theme});
      }
      if(window.NycLightV13?.attach&&motionConfig){
        const controller=attachRenderer('Lighting',()=>window.NycLightV13.attach($('.lighting-layer',group),scene,theme,$('.wallpaper[data-source]',group),{config:motionConfig,playing:state.motion,intensity:state.intensity,allowReducedMotion:true,environmentTime}));
        if(controller) lightControllers.push({controller,theme});
      }
      if (window.NycMarineV13?.attach && motionConfig) {
        const controller = attachRenderer('Marine',()=>window.NycMarineV13.attach($('.marine-layer',group),scene,theme,$('.wallpaper[data-source]',group),{config:motionConfig,playing:state.motion,intensity:state.intensity,maxFps:30,allowReducedMotion:true,environmentTime}));
        if(controller) marineControllers.push({controller,theme});
      }
      const weather=attachRenderer('Weather',()=>window.KOneWeatherV13.attach($('.weather-layer',group),scene,theme,$('.wallpaper[data-source]',group),{config:motionConfig,playing:state.motion,intensity:state.intensity,environmentTime,getWeather,allowReducedMotion:true}));
      if(weather)weatherControllers.push({controller:weather,theme});
      const life=attachRenderer('City life',()=>window.KOneCityLifeV13.attach($('.citylife-layer',group),scene,theme,$('.wallpaper[data-source]',group),{config:lifeConfig,blendImage:$('.wallpaper:not([data-source])',group),playing:state.motion,intensity:state.intensity,environmentTime,getWeather,allowReducedMotion:true}));
      if(life)lifeControllers.push({controller:life,theme});
      $('.phone',group).addEventListener('pointerdown',event=>{
        if(!state.motion||event.target.closest('button,a'))return;
        const r=event.currentTarget.getBoundingClientRect(),tr=life?.state.imageTransform;
        if(!tr)return;life.interact({x:(event.clientX-r.left-tr.x)/tr.width,y:(event.clientY-r.top-tr.y)/tr.height});
      });
      if(window.NycSkyV5?.attach){
        const skyScene=skySceneFor(scene);
        const controller=attachRenderer('Sky',()=>window.NycSkyV5.attach($('.sky-layer',group),skyScene,theme,{zone:resolveSkyZone(),onState:sky=>{
          $('.status-time',group).textContent=sky.localTime||'';
          const mode=state.clockMode==='pair'?'演示':state.clockMode==='live'?'此刻':state.clockMode==='play'?'推演':'预览';
          $('.demo-time',group).textContent=`${mode} · ${sky.localDate||''} ${sky.localTime||''} · ${sky.locationLabel||sky.location?.label||''}${sky.demoRequestedDate&&sky.localDate!==sky.demoRequestedDate?'（另选日期）':''}${sky.composition?' · 日月艺术构图':!sky.visible&&sky.reason?' · '+sky.reason:''}`;
          const light=lightControllers.find(c=>c.theme===theme)?.controller;
          const motion=motionControllers.find(c=>c.theme===theme)?.controller;
          const marine=marineControllers.find(c=>c.theme===theme)?.controller;
          const dayMix=state.clockMode==='pair'?(theme==='day'?1:0):Math.max(0,Math.min(1,sky.daylight??(sky.sunAltitude>0?1:0)));
          // The water source must use the exact same day/night blend as its
          // wallpaper, including the deliberately pure paired comparison.
          const visualSky={...sky,daylight:dayMix};
          light?.setSky(visualSky);motion?.setLighting(visualSky);
          marine?.setSky(visualSky);
          weatherControllers.find(c=>c.theme===theme)?.controller.setSky(visualSky);
          lifeControllers.find(c=>c.theme===theme)?.controller.setSky(visualSky);
          group.querySelector('.phone').style.setProperty('--daylight',dayMix);
        }}));
        if(controller) skyControllers.push(controller);
      }
    });
    updateClock();
    syncControls();
    if (!clockInitialized && skyControllers.length) initClock();
    syncWeather();scheduleWeatherReadout();
  }

  function renderSceneButtons() {
    const cities = [...new Map(availableScenes.map(scene => [cityId(scene), scene])).entries()];
    const countries = [...new Map(cities.map(([, scene]) => [countryLabel(scene), scene])).entries()];
    $('#country-selector').innerHTML = `<button type="button" class="country-button" data-country="ALL" aria-pressed="${state.country === 'ALL'}">全部国家</button>` + countries.map(([country]) => `<button type="button" class="country-button" data-country="${escapeHtml(country)}" aria-pressed="${state.country === country}">${escapeHtml(country)}</button>`).join('');
    $$('.country-button').forEach(button => button.addEventListener('click', () => setCountry(button.dataset.country)));
    const countrySizes = new Map(countries.map(([country]) => [country, availableScenes.filter(scene => countryLabel(scene) === country).length * 2]));
    const countryOptions = countries.slice().sort(([a],[b]) => countrySizes.get(b)-countrySizes.get(a));
    const imageCount=availableScenes.length*2, us=countrySizes.get('美国')||0, china=countrySizes.get('中国')||0;
    const done=countries.length===31&&us===60&&china===18&&countries.every(([c])=>c==='美国'||c==='中国'||countrySizes.get(c)===20);
    $('#catalog-progress').value=imageCount;
    $('#catalog-status').textContent=`已核对 ${imageCount} / 658 张 · ${done?'比例目标已达成':'扩充已收住'}`;
    $('#catalog-balance').textContent=`美国 ${us} 张 · ${(us/imageCount*100).toFixed(2)}%　中国 ${china} 张 · ${(china/imageCount*100).toFixed(2)}%`;
    $('#country-filter').innerHTML = '<option value="ALL">全部国家</option>' + countryOptions.map(([country]) => `<option value="${escapeHtml(country)}">${escapeHtml(country)} · ${countrySizes.get(country)}图</option>`).join('');
    $('#country-filter').value = state.country;
    const query = state.search.trim().toLocaleLowerCase();
    const visibleCities = cities.filter(([id, scene]) => (state.country === 'ALL' || countryLabel(scene) === state.country) && (!query || [id,cityLabel(scene),countryLabel(scene),scene.searchTerms || ''].join(' ').toLocaleLowerCase().includes(query)));
    $('#city-selector').innerHTML = visibleCities.map(([id, scene]) => `<button type="button" class="city-button" data-city="${escapeHtml(id)}" aria-label="${escapeHtml(countryLabel(scene))} · ${escapeHtml(cityLabel(scene))}" aria-pressed="${id === cityId(state.scene)}"><span class="city-flag" aria-hidden="true">${escapeHtml(cityFlag(scene))}</span><span>${escapeHtml(cityLabel(scene))}</span></button>`).join('');
    if (!visibleCities.length) $('#city-selector').innerHTML = '<span class="empty-cities">没有匹配的城市</span>';
    $$('.city-button').forEach(button => button.addEventListener('click', () => {
      const scenes = availableScenes.filter(scene => cityId(scene) === button.dataset.city);
      const next = scenes.find(scene => scene.id === rememberedScenes.get(button.dataset.city)) || scenes[0];
      renderScene(next); scheduleClock();
    }));
    const filtered = availableScenes.filter(scene => cityId(scene) === cityId(state.scene));
    $('#scene-selector').innerHTML = filtered.map(scene => `<button type="button" class="scene-button" data-scene="${escapeHtml(scene.id)}" aria-pressed="${scene.id === state.scene?.id}"><img src="${assetUrl(scene.assets.day)}" alt="" loading="lazy" decoding="async"><span><b>${escapeHtml(scene.number)}</b>${escapeHtml(scene.title)}</span></button>`).join('');
    $$('.scene-button').forEach(button => button.addEventListener('click', () => renderScene(availableScenes.find(scene => scene.id === button.dataset.scene))));
    $('#scene-selector').classList.toggle('one-view', filtered.length === 1);
    $('#scene-count').textContent = `${cities.length} 座城市 · ${countries.length} 个国家 · ${availableScenes.length} 个视角`;
  }

  function setCountry(country) {
    if (country !== 'ALL' && !availableScenes.some(scene => countryLabel(scene) === country)) return;
    state.country = country;
    if (country !== 'ALL' && countryLabel(state.scene) !== country) {
      const scene = availableScenes.find(scene => countryLabel(scene) === country);
      renderScene(scene); scheduleClock();
    } else {
      // A filter selection invalidates an older pair still decoding.
      sceneRequest++;
      state.loading = false;
      $('#gallery').setAttribute('aria-busy', 'false');
      $('#catalog-feedback').textContent = '';
      renderSceneButtons();
    }
  }

  const loadedPairs = new Map();
  function syncWeather(){
    if(!weatherModel||!$('#weather-mode'))return;
    const p=profile(),s=weatherModel.status;
    $('#weather-mode').value=s.mode==='live'?'live':weatherMode;
    $('#weather-season').value=weatherSeason;
    $('#weather-mode [value="snow"]').disabled=!s.snowEligible;
    $('#weather-live').disabled=weatherBusy;
    $('#weather-live').textContent=weatherBusy?'读取中…':'读取城市天气';
    const stamp=s.live?.validAt;
    const stale=stamp&&Math.abs(Date.now()-stamp)>45*60000;
    const label=s.mode==='live'?`城市天气 · 模型估计${stale?' · 数据较旧':''}`:s.mode==='story'?'艺术天气 · 120秒天气故事':'艺术天气 · 手动演示';
    $('#weather-readout').textContent=`${p.label} · ${label}`;
    $('#weather-detail').textContent=s.mode==='live'&&stamp?`${new Intl.DateTimeFormat('zh-CN',{timeZone:state.scene.cityZone,month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(stamp)} · ${s.live.temperature.toFixed(1)}°C · 风 ${s.live.windMs.toFixed(1)} m/s`:
      '晴 → 云 → 阵雨 → 风雨 → 雨后；云、水、船和日照同步变化';
    const life=lifeConfig.scenes?.[state.scene.id];
    $('#weather-coverage').textContent=life?.reviewed?(life.effectCaption||life.localIdentity||'已适配本视角的城市细节'):'本视角启用天气与水域联动';
    $('#weather-feedback').textContent=weatherMessage;
    const sound=audio?.state;
    $('#ambient-audio').setAttribute('aria-pressed',String(Boolean(sound?.enabled)));
    $('#ambient-audio').textContent=sound?.enabled?'关闭环境声音':'开启环境声音';
    for(const group of $$('.phone-group')){
      const dark=group.dataset.mode==='night';
      const transmission=Math.max(.02,1-p.cloudCover*.88-p.rain*.40-p.fog*.25-p.snow*.2);
      // Non-fog presets retain readable celestial discs; dense fog may hide
      // them. This does not move true-time bodies above the horizon.
      const fogFade=window.KOneEnvironmentV13.visibility(p).fogVisibility;
      $('.sky-layer',group).style.opacity=String(Math.max(dark?.62:.48,Math.pow(transmission,dark?.35:.5))*fogFade);
    }
  }
  function scheduleWeatherReadout(){
    if(!weatherModel)return;
    if(document.hidden||!state.motion){clearTimeout(weatherReadoutTimer);weatherReadoutTimer=null;syncWeather();return;}
    if(weatherReadoutTimer)return;
    weatherReadoutTimer=setTimeout(()=>{weatherReadoutTimer=null;syncWeather();scheduleWeatherReadout();},250);
  }
  function refreshEffects(){
    for(const {controller} of [...motionControllers,...lightControllers,...marineControllers,...weatherControllers,...lifeControllers])controller.setIntensity(state.intensity);
    syncWeather();scheduleWeatherReadout();
  }
  function leaveLiveWeatherForDemo(){
    if(!weatherModel||!weatherBusy&&weatherModel.status.mode!=='live')return;
    weatherRequest++;weatherAbort?.abort();weatherBusy=false;
    if(weatherModel.status.mode==='live'){
      weatherModel.setMode('story',effectTime(),{immediate:!state.motion});weatherMode='story';
    }
    weatherMessage='已切换演示时刻，使用艺术天气；读取城市天气可回到此刻';
    refreshEffects();
  }
  function setWeatherMode(mode){
    if(!weatherModel)return false;
    weatherModel.setDate(state.clockDate||new Date(state.instant).toISOString().slice(0,10),effectTime());
    const ok=weatherModel.setMode(mode,effectTime(),{immediate:!state.motion});
    if(!ok){weatherMessage='这个城市与季节未启用飘雪，可切换冬季演示';syncWeather();return false;}
    weatherRequest++;weatherAbort?.abort();weatherBusy=false;weatherMode=mode;weatherMessage='';refreshEffects();return true;
  }
  async function readCityWeather(){
    if(!state.scene||!weatherModel)return false;
    weatherAbort?.abort();const controller=new AbortController();weatherAbort=controller;
    const request=++weatherRequest,scene=state.scene,model=weatherModel;
    weatherBusy=true;weatherMessage='';syncWeather();
    const timeout=setTimeout(()=>controller.abort(),12000);
    try{
      const cached=weatherCache.get(cityId(scene));let result;
      if(cached&&Date.now()-cached.fetchedAt<10*60000)result=cached;
      else{
        const variables='temperature_2m,relative_humidity_2m,is_day,precipitation,rain,showers,snowfall,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility';
        const q=new URLSearchParams({latitude:scene.cityLocation.lat,longitude:scene.cityLocation.lon,current:variables,wind_speed_unit:'ms',temperature_unit:'celsius',precipitation_unit:'mm',timeformat:'unixtime',timezone:'UTC'});
        const response=await fetch(`https://api.open-meteo.com/v1/forecast?${q}`,{signal:controller.signal,credentials:'omit',referrerPolicy:'no-referrer'});
        if(!response.ok)throw Error(`HTTP ${response.status}`);
        const parsed=window.KOneWeatherModelV11.fromCurrent(await response.json());result={...parsed,fetchedAt:Date.now()};
        if(weatherCache.size>=32)weatherCache.delete(weatherCache.keys().next().value);
        weatherCache.set(cityId(scene),result);
      }
      if(request!==weatherRequest||state.scene.id!==scene.id||model!==weatherModel)return false;
      weatherModel.setLive(result.profile,{...result.meta,fetchedAt:result.fetchedAt},effectTime());
      weatherMode='live';state.clockMode='live';state.layout='single';state.instant=Date.now();state.zone='CITY';updateClock();scheduleClock();
      weatherMessage='已按画面城市读取；未使用设备定位';refreshEffects();return true;
    }catch(error){
      if(request===weatherRequest){weatherMessage=controller.signal.aborted?'城市天气读取超时，保留当前天气演示':'城市天气暂时不可用，保留当前天气演示';syncWeather();}
      return false;
    }finally{clearTimeout(timeout);if(request===weatherRequest){weatherBusy=false;syncWeather();}}
  }
  async function preloadScene(scene) {
    const key=[scene.id,scene.assets.day,scene.assets.night].join('|');
    if (loadedPairs.has(key)) return loadedPairs.get(key);
    const loading = decodePair(scene).catch(error => {loadedPairs.delete(key);throw error;});
    loadedPairs.set(key, loading);
    if (loadedPairs.size > 4) loadedPairs.delete(loadedPairs.keys().next().value);
    return loading;
  }
  async function decodePair(scene) {
    await Promise.all(['day', 'night'].map(async theme => {
      const image = new Image();
      image.src = assetUrl(scene.assets[theme]);
      await image.decode();
      if (!image.naturalWidth || !image.naturalHeight) throw new Error(`${scene.id} ${theme} is empty`);
    }));
    return scene;
  }

  $('#catalog-refresh').onclick=async()=>{
    const button=$('#catalog-refresh');button.disabled=true;
    const sceneAtRequest=state.scene?.id;
    try {
      const [catalogResponse,configResponse]=await Promise.all([fetch(`scenes-v9.json?v=${VERSION}`,{cache:'no-store'}),fetch(`motion-scenes-v9.json?v=${VERSION}`,{cache:'no-store'})]);
      if(!catalogResponse.ok||!configResponse.ok)throw new Error('Catalog unavailable');
      const [nextScenes,nextConfig]=await Promise.all([catalogResponse.json(),configResponse.json()]);
      if(!Array.isArray(nextScenes)||!nextScenes.length||!nextConfig.scenes)throw new Error('Invalid catalog');
      const current=nextScenes.find(s=>s.id===state.scene?.id)||nextScenes[0];
      motionConfig=nextConfig;availableScenes.splice(0,availableScenes.length,...nextScenes);
      if(!state.loading&&state.scene?.id===sceneAtRequest)await renderScene(current);
      renderSceneButtons();
    }catch(error){$('#catalog-feedback').textContent='新增背景暂未载入，请稍后再试';console.warn('Catalog refresh failed',error);}
    finally{button.disabled=false;}
  };

  $('#country-filter').onchange = event => setCountry(event.target.value);
  $('#city-search').oninput = event => {state.search = event.target.value; renderSceneButtons();};

  $('#compare').addEventListener('click',()=>{leaveLiveWeatherForDemo();state.layout='compare';state.clockMode='pair';updateClock();syncControls();scheduleClock();});
  $('#single').addEventListener('click', () => {state.layout = 'single'; syncControls();});
  $('#day').addEventListener('click',()=>showBody('day'));
  $('#night').addEventListener('click',()=>showBody('night'));
  $('#motion').addEventListener('click',()=>{state.motion=!state.motion;if(!state.motion&&state.clockMode==='play')state.clockMode='preview';syncControls();updateClock();scheduleClock();});
  reduceMotion.addEventListener('change',event=>{if(event.matches){state.motion=false;if(state.clockMode==='play')state.clockMode='preview';syncControls();updateClock();scheduleClock();}});
  $('#overlay').addEventListener('click', () => {state.overlay = !state.overlay; syncControls();});
  state.layout = params.get('layout') === 'single' ? 'single' : 'compare';
  state.theme = params.get('theme') === 'night' ? 'night' : 'day';
  state.overlay = params.get('ui') !== 'off';
  if (params.get('motion') === 'off') state.motion = false;
  if (['soft', 'expressive', 'bold'].includes(params.get('intensity'))) state.intensity = params.get('intensity');
  if (params.get('panel') === 'compact') state.compact = {day: true, night: true};
  syncControls();

  function skySceneFor(scene) {
    const cfg=motionConfig?.scenes?.[scene?.id]||{};
    const declared=[scene?.skySafe,cfg.skySafe].filter(Boolean);
    const values=(key,fallback)=>declared.map(a=>Number(a[key])).filter(Number.isFinite).concat(fallback);
    const skySafe={left:Math.max(...values('left',0)),right:Math.min(...values('right',1)),
      top:Math.max(...values('top',.13)),bottom:Math.min(...values('bottom',.28))};
    const rect=item=>{
      if(Array.isArray(item)&&item.length>=3&&item.every(p=>Array.isArray(p)&&p.length===2&&p.every(Number.isFinite)))
        return {left:Math.min(...item.map(p=>p[0])),right:Math.max(...item.map(p=>p[0])),top:Math.min(...item.map(p=>p[1])),bottom:Math.max(...item.map(p=>p[1]))};
      if(item&&['left','right','top','bottom'].every(k=>Number.isFinite(item[k])))return item;
      return null;
    };
    const skyExclusions=[...(scene?.skyExclusions||[]),...(cfg.skyExclusions||[])].map(rect).filter(Boolean);
    return {...scene,skySafe,skyExclusions};
  }
  function skyScene() {return skySceneFor(state.scene);}
  function localParts(instant=state.instant){
    if(window.NycSkyV5?.parts) return window.NycSkyV5.parts(instant,resolveSkyZone());
    const requested = resolveSkyZone();
    const zone = typeof requested === 'object' ? requested.zone : requested === 'DEVICE' ? Intl.DateTimeFormat().resolvedOptions().timeZone : requested;
    const values = Object.fromEntries(new Intl.DateTimeFormat('en-CA',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(instant).filter(part=>part.type!=='literal').map(part=>[part.type,part.value]));
    return {...values,date:`${values.year}-${values.month}-${values.day}`,time:`${values.hour}:${values.minute}`};
  }
  function partDate(p){return p.date||`${p.year}-${String(p.month).padStart(2,'0')}-${String(p.day).padStart(2,'0')}`;}
  function syncClockReadout(){
    if(!window.NycSkyV5||!state.scene)return;
    const p=localParts(),loc=window.NycSkyV5.resolveLocation(resolveSkyZone(),state.instant);
    if(!state.clockDate)state.clockDate=partDate(p);
    const mode=state.clockMode==='pair'?'日夜各选可见时刻':state.clockMode==='live'?'此刻':state.clockMode==='play'?'推演一天':'时刻预览';
    $('#clock-readout').textContent=state.clockMode==='pair'?mode:`${mode} · ${p.time||`${p.hour}:${String(p.minute).padStart(2,'0')}`}`;
    const source = state.zone === 'CITY' ? `画面城市 · ${cityLabel(state.scene)}` : state.zone === 'DEVICE' ? `设备时区 · ${loc.label||loc.zone}` : loc.label||loc.zone;
    $('#sky-source').textContent=`${source} · ${loc.isRepresentative ? '代表地点 · ' : ''}艺术光照`;
    $('#sky-date').value=state.clockDate;
    $('#sky-zone').value=state.zone;
    $('#sky-zone').querySelector('[value="CITY"]').textContent=`画面地点 · ${cityLabel(state.scene)}`;
    const day=window.NycSkyV5.localDayRange(state.clockDate,resolveSkyZone());
    $('#sky-time').value=Math.round(Math.max(0,Math.min(1,(state.instant-day.start)/(day.end-day.start)))*1000);
    $('#sky-play').setAttribute('aria-pressed',state.clockMode==='play');
    $('#sky-play').textContent=state.clockMode==='play'?'停止推演':'推演一天';
    $('#sky-now').setAttribute('aria-pressed',state.clockMode==='live');
    const utc = instant => new Date(instant).toISOString().slice(0,16).replace('T',' ');
    $('#utc-readout').textContent = state.clockMode === 'pair'
      ? `UTC · ${skyControllers.map((controller,index)=>`${index===0?'日间':'夜间'} ${utc(controller.state.instant)}`).join(' · ')}`
      : `UTC · ${utc(state.instant)}`;
  }
  function updateClock(){
    if(!window.NycSkyV5||!state.scene||!skyControllers.length)return;
    if(state.clockMode==='pair'){
      const date=state.clockDate||partDate(localParts(Date.now()));
      skyControllers.forEach((controller,i)=>{
        const demo=window.NycSkyV5.chooseDemo(skyScene(),i===0?'day':'night',resolveSkyZone(),date);
        controller.render(demo.instant,resolveSkyZone(),demo);
      });
    }else{
      if(state.clockMode==='live')state.instant=Date.now();
      const sample=window.NycSkyV5.sample(skyScene(),'day',state.instant,resolveSkyZone());
      state.theme=sample.sun.altitude> -3?'day':'night';
      state.clockDate=partDate(localParts());
      const presentation=bodyDemo?.scene===state.scene.id&&bodyDemo.instant===state.instant&&bodyDemo.requestedZone===JSON.stringify(resolveSkyZone())&&state.clockMode==='preview'?bodyDemo:null;
      skyControllers.forEach(controller=>controller.render(state.instant,resolveSkyZone(),presentation));
    }
    const weatherDate=state.clockDate||partDate(localParts());
    if(weatherModel&&weatherModel.status.date!==weatherDate){
      const revision=weatherModel.revision;
      weatherModel.setDate(weatherDate,effectTime(),{immediate:!state.motion});
      weatherMode=weatherModel.status.mode;
      if(weatherModel.revision!==revision)refreshEffects();else syncWeather();
    }
    syncControls();syncClockReadout();
  }
  function scheduleClock(){
    clearTimeout(clockTimer);clockTimer=null;
    if(!window.NycSkyV5||!skyControllers.length)return;
    if(document.hidden||!state.motion){clockLast=null;return;}
    const delay=state.clockMode==='play'?125:state.clockMode==='live'?10000:state.zone==='DEVICE'?15000:0;
    if(!delay)return;
    clockTimer=setTimeout(()=>{
      const now=performance.now();
      if(state.clockMode==='play'){
        const date=state.clockDate||partDate(localParts());
        const day=window.NycSkyV5.localDayRange(date,resolveSkyZone());
        const delta=clockLast===null?125:Math.max(0,Math.min(500,now-clockLast));
        state.instant+=delta*(day.end-day.start)/72000;
        if(state.instant>=day.end){state.instant=day.end-1;state.clockMode='preview';}
        clockLast=now;updateClock();
      }else if(state.clockMode==='live')updateClock();
      else if(state.zone==='DEVICE'){
        const next=Intl.DateTimeFormat().resolvedOptions().timeZone;
        if(next!==lastDeviceZone){lastDeviceZone=next;updateClock();}
      }
      scheduleClock();
    },delay);
  }
  function showBody(theme){
    if(!state.scene||!window.NycSkyV5)return;
    leaveLiveWeatherForDemo();
    const demo=window.NycSkyV5.chooseDemo(skyScene(),theme,resolveSkyZone(),state.clockDate||partDate(localParts(Date.now())));
    bodyDemo={...demo,scene:state.scene.id,requestedZone:JSON.stringify(resolveSkyZone())};
    state.instant=demo.instant;state.clockMode='preview';state.layout='single';state.clockDate=partDate(localParts());
    updateClock();state.theme=theme;syncControls();scheduleClock();
  }
  function initClock(){
    if (clockInitialized) return;
    if(!window.NycSkyV5?.attach||!skyControllers.length){
      $$('.clock-panel button,.clock-panel select,.clock-panel input').forEach(control=>control.disabled=true);
      $('#clock-readout').textContent='日夜静态预览';
      $('#sky-source').textContent='';
      return;
    }
    const options=[{value:'DEVICE',label:'设备时区'},{value:'CITY',label:'画面城市'},...window.NycSkyV5.zoneOptions.filter(o=>o.value!=='DEVICE')];
    $('#sky-zone').replaceChildren();
    for(const o of options){const e=document.createElement('option');e.value=o.value;e.textContent=o.label;$('#sky-zone').append(e);}
    clockInitialized = true;
    $$('.clock-panel button,.clock-panel select,.clock-panel input').forEach(control=>control.disabled=false);
    lastDeviceZone=Intl.DateTimeFormat().resolvedOptions().timeZone;
    updateClock();scheduleClock();
  }
  $('#soft').onclick=()=>{state.intensity='soft';syncControls();};
  $('#expressive').onclick=()=>{state.intensity='expressive';syncControls();};
  $('#bold').onclick=()=>{state.intensity='bold';syncControls();};
  $('#sky-zone').onchange=e=>{if(e.target.value!=='CITY'){leaveLiveWeatherForDemo();if(state.clockMode==='live')state.clockMode='preview';}state.zone=e.target.value;if(state.clockMode==='live')state.instant=Date.now();state.clockDate=partDate(localParts(state.clockMode==='pair'?Date.now():state.instant));clockLast=null;updateClock();scheduleClock();};
  $('#sky-now').onclick=()=>{state.clockMode='live';state.layout='single';state.instant=Date.now();clockLast=null;updateClock();scheduleClock();};
  $('#sky-sun').onclick=()=>showBody('day');$('#sky-moon').onclick=()=>showBody('night');
  $('#sky-play').onclick=()=>{
    if(state.clockMode==='play')state.clockMode='preview';
    else{
      if(!state.motion){$('#motion-note').textContent='先开启动态，再推演一天';return;}
      leaveLiveWeatherForDemo();
      state.layout='single';state.clockMode='play';clockLast=null;
      const date=state.clockDate||partDate(localParts());const day=window.NycSkyV5.localDayRange(date,resolveSkyZone());
      state.instant=day.start;
    }
    updateClock();scheduleClock();
  };
  $('#sky-time').oninput=e=>{
    leaveLiveWeatherForDemo();
    const day=window.NycSkyV5.localDayRange($('#sky-date').value||partDate(localParts()),resolveSkyZone());
    state.instant=day.start+Math.min(.999999,Number(e.target.value)/1000)*(day.end-day.start);
    state.layout='single';state.clockMode='preview';clockLast=null;updateClock();scheduleClock();
  };
  $('#sky-date').onchange=e=>{
    if(!e.target.value)return;state.clockDate=e.target.value;
    leaveLiveWeatherForDemo();
    const day=window.NycSkyV5.localDayRange(state.clockDate,resolveSkyZone());
    state.instant=day.start+Math.min(.999999,Number($('#sky-time').value)/1000)*(day.end-day.start);
    state.layout='single';state.clockMode='preview';updateClock();scheduleClock();
  };
  document.addEventListener('visibilitychange',()=>{clockLast=null;if(!document.hidden&&state.clockMode==='live')updateClock();scheduleClock();});
  document.addEventListener('visibilitychange',()=>{syncEffectClock();scheduleWeatherReadout();});
  $('#weather-mode').onchange=e=>{if(e.target.value==='live')readCityWeather();else setWeatherMode(e.target.value);};
  $('#weather-season').onchange=e=>{weatherSeason=e.target.value;weatherModel?.setSeason(weatherSeason,effectTime(),{immediate:!state.motion});weatherMode=weatherModel?.status.mode||weatherMode;refreshEffects();};
  $('#weather-live').onclick=readCityWeather;
  $('#ambient-audio').onclick=async()=>{
    if(!audio)audio=window.KOneAmbientV11.create({getProfile:()=>profile(),getScene:()=>state.scene,isPlaying:()=>state.motion});
    if(audio.state.enabled)audio.disable();else{const result=await audio.enable();if(result===false)weatherMessage='当前浏览器暂不能播放环境声音';}
    syncWeather();
  };

  const ready = (async () => {
    const [response, motionResult,lifeResult] = await Promise.all([
      fetch(`scenes-v9.json?v=${VERSION}`, {cache: 'no-store'}),
      fetch(`motion-scenes-v9.json?v=${VERSION}`, {cache: 'no-store'}).then(async result => result.ok ? await result.json() : null).catch(() => null),
      fetch(`city-life-scenes-v12.json?v=${VERSION}`, {cache:'no-store'}).then(r=>r.ok?r.json():{scenes:{}}).catch(()=>({scenes:{}}))
    ]);
    if (!response.ok) throw new Error('Scene configuration unavailable');
    const scenes = await response.json();
    motionConfig = motionResult;
    lifeConfig=lifeResult;
    if (!motionConfig || ![window.NycMotionV13, window.NycLightV13, window.NycMarineV13].some(renderer => renderer?.attach)) {
      state.motion = false;
      $('#motion').disabled = true;
      $('#motion').title = '动态暂未载入，可查看静态背景';
    }
    availableScenes.push(...scenes);
    const requested = availableScenes.find(scene => scene.id === params.get('scene')) || scenes[0];
    if (params.get('country') && availableScenes.some(scene => countryLabel(scene) === params.get('country'))) state.country = params.get('country');
    const initial = state.country !== 'ALL' && countryLabel(requested) !== state.country ? availableScenes.find(scene => countryLabel(scene) === state.country) : requested;
    await renderScene(initial);
    renderSceneButtons();
    initClock();
    return availableScenes.map(scene => scene.id);
  })().catch(() => {
    $('#gallery').innerHTML = '<p class="load-note">背景尚未载入，请刷新预览。</p>';
    return [];
  });

  window.KOnePreview = {
    ready,
    getState: () => ({version: VERSION, loading: state.loading, search: state.search, cachedPairs: loadedPairs.size, country: state.country, cities: [...new Set(availableScenes.map(cityId))], countries: [...new Set(availableScenes.map(countryLabel))], motion: state.motion, weather:{...weatherModel?.status,profile:profile(),time:effectTime(),busy:weatherBusy,message:weatherMessage},weatherLayers:weatherControllers.map(({controller})=>controller.state),cityLife:lifeControllers.map(({controller})=>controller.state),audio:audio?.state||{enabled:false}, skyConfiguration: {skySafe:skyScene().skySafe,skyExclusions:skyScene().skyExclusions}, motionStates: motionControllers.map(({controller}) => controller.state), marine:marineControllers.map(({controller})=>controller.state),lighting:lightControllers.map(({controller})=>controller.state),clock:{mode:state.clockMode,zone:state.zone,resolvedZone:resolveSkyZone(),instant:state.instant,date:state.clockDate},intensity:state.intensity, city:cityId(state.scene),scene: state.scene?.id, layout: state.layout, theme: state.theme, overlay: state.overlay, compact: {...state.compact}, availableScenes: availableScenes.map(scene => scene.id), sky: skyControllers.map(controller => controller.state)}),
    setInstant: (instant,zone=state.zone)=>{leaveLiveWeatherForDemo();state.zone=zone;state.instant=instant;state.clockDate=partDate(localParts());state.clockMode='preview';state.layout='single';updateClock();syncControls();scheduleClock();},
    setScene: id => renderScene(availableScenes.find(scene => scene.id === id)),
    setCountry,
    setIntensity: value => {if (['soft', 'expressive', 'bold'].includes(value)) {state.intensity = value; syncControls();}},
    setLayout: layout => {state.layout = layout === 'single' ? 'single' : 'compare'; syncControls();},
    setTheme: theme => {state.theme = theme === 'night' ? 'night' : 'day'; syncControls();},
    setOverlay: value => {state.overlay = Boolean(value); syncControls();},
    setMotion: value => {state.motion = Boolean(value);if(!state.motion&&state.clockMode==='play')state.clockMode='preview';syncControls();updateClock();scheduleClock();},
    setWeather:setWeatherMode,readCityWeather,
    setWeatherSeason:value=>{weatherSeason=value;weatherModel?.setSeason(value,effectTime(),{immediate:!state.motion});weatherMode=weatherModel?.status.mode||weatherMode;refreshEffects();},
    setCompact
  };
})();
