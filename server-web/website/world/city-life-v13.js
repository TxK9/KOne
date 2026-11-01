/* KOne city life v13: reviewed artwork anchors, pause-aware small events.
 * Art direction only: this layer never claims live pedestrian/traffic data.
 * Coordinates are in the original source image, not the cropped phone viewport.
 */
(() => {
  'use strict';
  const TAU = Math.PI * 2;
  const clamp = (n, a = 0, b = 1) => Math.max(a, Math.min(b, n));
  const finite = Number.isFinite;
  const smooth = (a, b, n) => { const t = clamp((n - a) / (b - a)); return t * t * (3 - 2 * t); };
  const validPoint = p => Array.isArray(p) && p.length === 2 && p.every(n => finite(n) && n >= 0 && n <= 1);
  const validPolygon = p => Array.isArray(p) && p.length >= 3 && p.every(validPoint);
  function inside(x, y, polygon) {
    let hit = false;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      const [xi, yi] = polygon[i], [xj, yj] = polygon[j];
      if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) hit = !hit;
    }
    return hit;
  }
  function routeAt(points, phase) {
    const lengths = points.slice(1).map((p, i) => Math.hypot(p[0] - points[i][0], p[1] - points[i][1]));
    let remaining = clamp(phase) * lengths.reduce((a, b) => a + b, 0), i = 0;
    while (i < lengths.length - 1 && remaining > lengths[i]) remaining -= lengths[i++];
    const a = points[i], b = points[i + 1], f = lengths[i] ? remaining / lengths[i] : 0;
    return {x: a[0] + (b[0] - a[0]) * f, y: a[1] + (b[1] - a[1]) * f,
      dx: b[0] - a[0], dy: b[1] - a[1]};
  }
  function attach(container, scene, theme, image, options = {}) {
    if (!(container instanceof Element) || !(image instanceof HTMLImageElement)) throw new TypeError('KOneCityLifeV13 requires container and source image');
    const sceneId = scene?.id || '', supplied = options.config?.scenes?.[sceneId] || options.config?.[sceneId];
    const config = supplied?.reviewed === true ? supplied : null;
    const canvas = document.createElement('canvas');
    canvas.className = 'kone-citylife-canvas'; canvas.setAttribute('aria-hidden', 'true');
    Object.assign(canvas.style, {position: 'absolute', inset: '0', width: '100%', height: '100%', pointerEvents: 'none'});
    container.appendChild(canvas);
    const ctx = canvas.getContext('2d', {alpha: true});
    const blendImage = options.blendImage instanceof HTMLImageElement && options.blendImage !== image ? options.blendImage : null;
    const sourceIsNight = image.classList?.contains('wallpaper-night') ||
      (!image.classList?.contains('wallpaper-day') && (theme === 'night' || theme === 'dark'));
    const dayImage = sourceIsNight ? blendImage : image, nightImage = sourceIsNight ? image : blendImage;
    let canopySourceBlend = 'pending';
    const sourceReady = value => Boolean(value && value.naturalWidth > 0 && value.naturalHeight > 0 && value.complete !== false);
    const reducedQuery = matchMedia('(prefers-reduced-motion: reduce)');
    let playing = options.playing !== false, visible = true, intersecting = true, destroyed = false;
    let width = 0, height = 0, dpr = 1, cover = null, raf = 0, lastTime = null, lastPaint = null, seconds = 0, frames = 0;
    let intensity = ['soft', 'expressive', 'bold'].includes(options.intensity) ? options.intensity : 'bold';
    let daylight = theme === 'night' || theme === 'dark' ? 0 : 1;
    let windTouch = null, weather = {}, counts = {}, events = [], ripples = [];
    let resizeObserver = null, intersectionObserver = null;
    const water = (config?.waterPolygons || []).filter(validPolygon);
    const waterExclusions = [...(config?.occlusionPolygons || []),...(config?.waterExclusions || []),...(config?.islandPolygons || [])].filter(validPolygon);
    const trees = (config?.trees || []).filter(t => validPoint(t.center) && finite(t.rx) && finite(t.ry) && t.rx > 0 && t.ry > 0);
    const facades = (config?.glassFacades || []).map(v=>Array.isArray(v)?{polygon:v}:v).filter(v=>validPolygon(v?.polygon));
    const shores = (config?.shorelines || []).filter(s => validPolygon(s.waterPolygon) && s.points?.length >= 2 && s.points.every(validPoint));
    const eaves = (config?.eaves || []).filter(e => validPoint(e.a) && validPoint(e.b));
    const accents=(config?.accentLights||[]).filter(a=>validPolygon(a?.polygon));
    const jets=(config?.waterJets||[]).filter(j=>validPoint(j?.base)&&validPoint(j?.top)&&j.base[1]>j.top[1]);
    const wheels=(config?.wheels||[]).filter(w=>validPoint(w?.center)&&finite(w.rx)&&finite(w.ry)&&w.rx>0&&w.ry>0);
    const grounds=(config?.dryWind||[]).filter(v=>validPolygon(v?.polygon)&&['sand','grass'].includes(v.kind));
    const heats=(config?.heatShimmer||[]).filter(v=>validPolygon(v?.polygon));
    const rejectedRoutes = [];
    const roads = (config?.roads || []).filter(validPolygon);
    const plazas = (config?.walkPolygons || []).filter(validPolygon);
    const hidden = (config?.occlusionPolygons || []).filter(validPolygon);
    const routeSafe = (route, polygons, pedestrian) => {
      if (!route?.points?.every(validPoint) || route.points.length < 2 || !polygons.length) return false;
      const size = route.size || .007, imageAspect = image.naturalWidth / Math.max(1,image.naturalHeight);
      const rx = pedestrian ? .0018 : Math.max(.0012,Math.hypot(size/2,size*.17));
      const top = pedestrian ? -(route.height||.004)*1.3 : -rx*imageAspect;
      const bottom = pedestrian ? .0003 : rx*imageAspect;
      for (let step = 0; step <= 100; step++) {
        const p = routeAt(route.points, step / 100);
        // An axis-aligned envelope is intentionally conservative for all headings.
        for (const [ox, oy] of [[-rx,top],[rx,top],[rx,bottom],[-rx,bottom],[0,0]]) {
          if (!polygons.some(poly => inside(p.x + ox, p.y + oy, poly))) return false;
        }
      }
      return true;
    };
    const routes = (config?.traffic || []).filter(r => {
      const valid = routeSafe(r, roads, false); if (!valid) rejectedRoutes.push({id:r.id, reason:'vehicle envelope outside reviewed road'}); return valid;
    });
    const walkers = (config?.walkers || []).filter(r => {
      const valid = routeSafe(r, plazas, true); if (!valid) rejectedRoutes.push({id:r.id, reason:'pedestrian envelope outside reviewed plaza'}); return valid;
    });
    const power = () => intensity === 'bold' ? 1.8 : intensity === 'expressive' ? 1 : .5;
    const allowed = () => !reducedQuery.matches || options.allowReducedMotion === true;
    const shouldRun = () => !destroyed && config && playing && visible && intersecting && !document.hidden && allowed() && ctx && cover;
    const map = (x,y) => ({x:cover.x+x*cover.width, y:cover.y+y*cover.height});
    function polyPath(poly) {ctx.beginPath();poly.forEach(([x,y],i)=>{const p=map(x,y);i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y);});ctx.closePath();}
    function clipPolygons(polys) {ctx.beginPath();for(const poly of polys){poly.forEach(([x,y],i)=>{const p=map(x,y);i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y);});ctx.closePath();}ctx.clip();}
    function readWeather(time) {
      let value = {}, env = {}, sharedTime = time;
      try { const supplied = options.environmentTime?.(); if (finite(supplied)) sharedTime = supplied; } catch (_) {}
      try { value = options.getWeather?.(sharedTime) || {}; } catch (_) { /* neutral art direction */ }
      try { env = window.KOneEnvironmentV10?.sample(sceneId, sharedTime) || {}; } catch (_) {}
      const rain = clamp(Number(value.rain ?? value.precipitation ?? value.rainIntensity) || 0);
      const wind = finite(value.wind) ? value.wind : finite(env.wind) ? env.wind : 1;
      const gust = clamp(finite(value.gust) ? value.gust : finite(env.gust) ? env.gust : .3);
      const fog=clamp(Number(value.fog)||0),snow=clamp(Number(value.snow)||0),visibility=window.KOneEnvironmentV13?.visibility(value)||{fogVisibility:1,directVisibility:1};
      return {rain,fog,snow,...visibility, wetness:clamp(finite(value.wetness)?value.wetness:rain*.9), wind:clamp(wind,0,3),
        windPhase:finite(env.advection)?env.advection:Math.max(0,sharedTime),
        windX:finite(value.windX)?clamp(value.windX,-2,2):.65, gust,
        cloudCover:clamp(finite(value.cloudCover)?value.cloudCover:env.cloudCover??.35),
        temperatureC:finite(value.temperatureC)?value.temperatureC:finite(value.temperature)?value.temperature:null};
    }
    function drawTrees(time) {
      const dayReady = sourceReady(dayImage), nightReady = sourceReady(nightImage);
      const aspectMatches = !dayReady || !nightReady || Math.abs(dayImage.naturalWidth/dayImage.naturalHeight - nightImage.naturalWidth/nightImage.naturalHeight) < .012;
      const canPaint = aspectMatches && (daylight >= .999 ? dayReady : daylight <= .001 ? nightReady : dayReady && nightReady);
      canopySourceBlend = !aspectMatches ? 'counterpart-aspect-mismatch' : !canPaint ? 'waiting-for-counterpart' : daylight >= .999 ? 'day' : daylight <= .001 ? 'night' : 'mixed';
      for (let i = 0; i < trees.length; i++) {
        const t = trees[i], p = map(...t.center), rx = t.rx*cover.width, ry = t.ry*cover.height;
        const interaction = windTouch ? Math.max(0,1-(time-windTouch.time)/5)*Math.exp(-Math.hypot(t.center[0]-windTouch.x,t.center[1]-windTouch.y)*9) : 0;
        const gust = (.34+weather.gust*.66+interaction)*power();
        const phase=finite(t.phase)?t.phase*TAU:i*1.73,rate=finite(t.period)?TAU/clamp(t.period,2,30):(t.kind==='palm'?1.4:1.08);
        const sway = Math.sin(time*rate+phase)*(.5+.5*Math.sin(time*.17+phase)) + Math.sin(time*2.6+phase)*.17;
        const dx = Math.min(rx*.17, cover.width*.0045)*sway*gust*weather.windX*clamp(t.gain??1,.2,2);
        const dy = Math.sin(time*1.1+i)*Math.min(ry*.075,cover.height*.0005)*gust;
        // Repaint only the reviewed inner canopy. The original trunk, buildings,
        // edges and shoreline remain in their exact source positions.
        if (canPaint) {
          ctx.save();ctx.beginPath();ctx.ellipse(p.x,p.y,rx,ry,0,0,TAU);ctx.clip();
          // Match the wallpaper exactly: opaque night base, then day with the
          // same daylight opacity. Never repaint a daytime patch over night.
          ctx.globalAlpha=1;
          if (daylight <= .001) ctx.drawImage(nightImage,cover.x+dx,cover.y+dy,cover.width,cover.height);
          else if (daylight >= .999) ctx.drawImage(dayImage,cover.x+dx,cover.y+dy,cover.width,cover.height);
          else {ctx.drawImage(nightImage,cover.x+dx,cover.y+dy,cover.width,cover.height);ctx.globalAlpha=daylight;ctx.drawImage(dayImage,cover.x+dx,cover.y+dy,cover.width,cover.height);}
          ctx.restore();counts.trees++;
        }
        // A few short leaf tips, anchored inside each real canopy, react faster
        // than the heavy crown. No new tree trunk or free-floating leaves.
        if (daylight>.15 && t.kind==='palm') {
          ctx.save();ctx.strokeStyle=`rgba(66,96,80,${.18*daylight})`;ctx.lineWidth=Math.max(.45,width*.0011);
          for(let k=0;k<5;k++){const a=(k/5)*TAU-.7;ctx.beginPath();ctx.moveTo(p.x+dx*.4,p.y);ctx.quadraticCurveTo(p.x+Math.cos(a)*rx*.55+dx,p.y+Math.sin(a)*ry*.3,p.x+Math.cos(a)*rx*.9+dx,p.y+Math.sin(a)*ry*.7+Math.abs(sway)*ry*.1);ctx.stroke();}ctx.restore();
        }
      }
    }
    function drawGlass(time) {
      if (daylight<.15||weather.directVisibility<=0) return;
      facades.forEach((facade,i)=>{
        const poly=facade.polygon;
        const xs=poly.map(p=>p[0]),ys=poly.map(p=>p[1]),left=Math.min(...xs),right=Math.max(...xs),top=Math.min(...ys),bottom=Math.max(...ys);
        const progress=(time/clamp(facade.period||31+i*7,12,180)+(facade.phase??i*.31))%1, bright=Math.sin(progress*Math.PI)**4;
        const p=map(left+(right-left)*progress,top),span=(right-left)*cover.width*.6;
        ctx.save();polyPath(poly);ctx.clip();
        const gradient=ctx.createLinearGradient(p.x-span,p.y,p.x+span,p.y);
        const alpha=daylight*weather.directVisibility*bright*.28*clamp(facade.gain??1,.2,2)*Math.sqrt(power());
        gradient.addColorStop(0,'rgba(255,250,222,0)');gradient.addColorStop(.5,`rgba(255,250,222,${alpha})`);gradient.addColorStop(1,'rgba(255,250,222,0)');
        ctx.fillStyle=gradient;const a=map(left,top),b=map(right,bottom);ctx.fillRect(a.x,a.y,b.x-a.x,b.y-a.y);ctx.restore();counts.glass++;
      });
    }
    function drawShore(time) {
      shores.forEach((shore,index)=>{
        ctx.save();polyPath(shore.waterPolygon);ctx.clip();
        const bands=intensity==='bold'?5:3,flow=validPoint(shore.flow)?shore.flow:Array.isArray(shore.flow)&&shore.flow.every(finite)?shore.flow:[0,1];
        for(let band=0;band<bands;band++){
          const phase=(time/clamp(shore.period||8+index*1.3,4,30)+band/bands+(shore.phase||0))%1, approach=(1-Math.cos(phase*TAU))/2;
          const alpha=Math.sin(phase*Math.PI)**2*(.18+weather.gust*.12)*Math.max(.3,daylight)*clamp(shore.gain??1,.2,1.8);
          ctx.beginPath();shore.points.forEach(([x,y],i)=>{const offset=(shore.reach||.006)*(1-approach),p=map(x+flow[0]*offset,y+flow[1]*offset);i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y);});
          ctx.strokeStyle=`rgba(246,251,247,${alpha})`;ctx.lineWidth=Math.max(.55,cover.height*.00065)*(1+weather.gust*.6);ctx.stroke();counts.foam++;
        }
        // Small caustic fragments stay in reviewed shallow water; broad ocean
        // waves and vessels belong to the existing marine renderer.
        if(shore.shallowPolygon&&validPolygon(shore.shallowPolygon)&&daylight>.25&&weather.directVisibility>.001){
          ctx.save();polyPath(shore.shallowPolygon);ctx.clip();
          const xs=shore.shallowPolygon.map(p=>p[0]),ys=shore.shallowPolygon.map(p=>p[1]),left=Math.min(...xs),right=Math.max(...xs),top=Math.min(...ys),bottom=Math.max(...ys);
          for(let k=0;k<22;k++){
            const f=((k*.61803398875+.07*Math.sin(time*.09+k))%1+1)%1,x=left+(right-left)*f,y=top+(bottom-top)*(.5+.43*Math.sin(k*2.8+time*.17));
            const p=map(x,y),a=.16*daylight*weather.directVisibility*(.5+.5*Math.sin(time*1.2+k));
            ctx.strokeStyle=`rgba(244,255,235,${a})`;ctx.lineWidth=.7;ctx.beginPath();ctx.ellipse(p.x,p.y,cover.width*(.006+.004*Math.sin(time*.5+k)),cover.height*.0008,0,0,Math.PI*1.5);ctx.stroke();counts.caustics++;
          }ctx.restore();
        }ctx.restore();
      });
    }
    function drawAccents(time){
      const night=1-daylight;if(night<.05)return;
      accents.forEach((a,i)=>{
        const xs=a.polygon.map(p=>p[0]),ys=a.polygon.map(p=>p[1]),left=Math.min(...xs),right=Math.max(...xs),top=Math.min(...ys),bottom=Math.max(...ys);
        const phase=time/clamp(a.period||37+i*5,18,180)+(a.phase??i*.29),pulse=.65+.35*Math.sin(phase*TAU);
        const color=Array.isArray(a.color)&&a.color.length===3?a.color.map(n=>clamp(Number(n)||0,0,255)):[247,208,155];
        const p=map(left,top),q=map(right,bottom),alpha=night*(.036+.085*pulse)*clamp(a.power??.7,.1,1.5)*Math.sqrt(power());
        ctx.save();polyPath(a.polygon);ctx.clip();const gradient=ctx.createLinearGradient(p.x,p.y,q.x,q.y);
        gradient.addColorStop(0,`rgba(${color.join(',')},${alpha*.3})`);gradient.addColorStop(.55,`rgba(${color.join(',')},${alpha})`);gradient.addColorStop(1,`rgba(${color.join(',')},${alpha*.4})`);
        ctx.fillStyle=gradient;ctx.fillRect(p.x,p.y,q.x-p.x,q.y-p.y);ctx.restore();counts.accents++;
      });
    }
    function paintSource(dx,dy){
      const ready=daylight>=.999?sourceReady(dayImage):daylight<=.001?sourceReady(nightImage):sourceReady(dayImage)&&sourceReady(nightImage);
      if(!ready)return false;
      if(daylight<=.001)ctx.drawImage(nightImage,cover.x+dx,cover.y+dy,cover.width,cover.height);
      else if(daylight>=.999)ctx.drawImage(dayImage,cover.x+dx,cover.y+dy,cover.width,cover.height);
      else{ctx.drawImage(nightImage,cover.x+dx,cover.y+dy,cover.width,cover.height);ctx.globalAlpha=daylight;ctx.drawImage(dayImage,cover.x+dx,cover.y+dy,cover.width,cover.height);}
      return true;
    }
    function drawGround(time){
      grounds.forEach((g,i)=>{
        if(g.kind==='grass'){
          ctx.save();polyPath(g.polygon);ctx.clip();const phase=time*TAU/clamp(g.period||7,3,30)+(g.phase||i)*TAU;
          if(paintSource(Math.sin(phase)*width*.0018*weather.wind*clamp(g.gain??1,.1,2),0))counts.ground++;
          ctx.restore();return;
        }
        if(weather.rain>.04||weather.snow>.02||weather.fog>.45||weather.wetness>.4||weather.wind<.85)return;
        const xs=g.polygon.map(p=>p[0]),ys=g.polygon.map(p=>p[1]),x0=Math.min(...xs),x1=Math.max(...xs),y0=Math.min(...ys),y1=Math.max(...ys);
        const flow=Array.isArray(g.flow)&&g.flow.every(finite)?g.flow:[1,0],phase=weather.windPhase/clamp(g.period||11,4,40)+(g.phase||0);
        ctx.save();polyPath(g.polygon);ctx.clip();ctx.strokeStyle=`rgba(${daylight>.3?'196,163,115':'157,155,142'},${(.07+.05*weather.gust)*clamp(g.gain??1,.1,2)})`;ctx.lineWidth=.55;
        for(let k=0;k<32;k++){const f=((phase+k*.618)%1+1)%1,y=y0+(y1-y0)*(.5+.47*Math.sin(k*2.37)),x=x0+(x1-x0)*(flow[0]>=0?f:1-f),p=map(x,y);ctx.beginPath();ctx.moveTo(p.x,p.y);ctx.lineTo(p.x+flow[0]*width*.014,p.y+flow[1]*height*.003);ctx.stroke();}ctx.restore();counts.ground++;
      });
      if(daylight<.7||weather.rain>.02||weather.snow>.02||weather.fog>.35||weather.cloudCover>.45)return;
      heats.forEach((heat,i)=>{
        if(weather.temperatureC<(heat.minTemperature??22))return;
        ctx.save();polyPath(heat.polygon);ctx.clip();
        const ys=heat.polygon.map(p=>p[1]),top=Math.min(...ys),bottom=Math.max(...ys),step=(bottom-top)/9;
        for(let k=0;k<9;k++){ctx.save();const a=map(0,top+k*step),b=map(1,top+(k+1)*step);ctx.beginPath();ctx.rect(a.x,a.y,b.x-a.x,b.y-a.y);ctx.clip();paintSource(Math.sin(time*TAU/clamp(heat.period||5,2,20)+k*.8+i)*width*.0018*clamp(heat.gain??1,.1,1.8),0);ctx.restore();}
        ctx.restore();counts.heat++;
      });
    }
    function drawJets(time){
      if(weather.temperatureC<0)return;
      jets.forEach((j,index)=>{
        const spread=clamp(j.spread||.01,.001,.04),heightN=j.base[1]-j.top[1],drift=weather.windX*weather.wind*spread*.22;
        const polygon=validPolygon(j.clipPolygon)?j.clipPolygon:[[j.base[0]-spread*1.5,j.base[1]],[j.top[0]-spread*1.5,j.top[1]],[j.top[0]+spread*1.5,j.top[1]],[j.base[0]+spread*1.5,j.base[1]]];
        ctx.save();polyPath(polygon);ctx.clip();ctx.lineWidth=.6;
        for(let k=0;k<12;k++){
          const phase=(time/clamp(j.period||4.8,2,12)+k/12+index*.17)%1,h=(1-(2*phase-1)**2)*heightN,x=j.base[0]+(j.top[0]-j.base[0])*h/heightN+(k/11-.5)*spread*.8+drift*phase*phase,y=j.base[1]-h,p=map(x,y);
          ctx.strokeStyle=`rgba(${daylight>.3?'232,248,249':'189,224,241'},${(.16+.16*(1-daylight))*clamp(j.power??1,.2,2)})`;
          ctx.beginPath();ctx.moveTo(p.x,p.y);ctx.lineTo(p.x+width*.0003,p.y+height*.0025);ctx.stroke();
        }ctx.restore();counts.jets++;
      });
    }
    function drawWheels(time){
      wheels.forEach((wheel,i)=>{
        const rgb=Array.isArray(wheel.color)?wheel.color.join(','):'238,211,170';
        for(let k=0;k<12;k++){
          const a=TAU*(time/clamp(wheel.period||100,45,240)+(wheel.phase||0)+k/12),p=map(wheel.center[0]+Math.cos(a)*wheel.rx,wheel.center[1]+Math.sin(a)*wheel.ry);
          ctx.fillStyle=`rgba(${rgb},${daylight>.3?.25:.68})`;ctx.beginPath();ctx.ellipse(p.x,p.y,Math.max(.6,width*.0016),Math.max(.8,height*.001),0,0,TAU);ctx.fill();
        }counts.wheels++;
      });
    }
    function eventPosition(route,time,index) {
      const period=route.period||42, travel=route.travel||period*.8;
      const phase=((time+(route.phase||0)*period)%period+period)%period;
      if(phase>=travel)return null;
      const progress=phase/travel, fade=smooth(0,.055,progress)*(1-smooth(.945,1,progress));
      const p=routeAt(route.points,progress);
      if(hidden.some(poly=>inside(p.x,p.y,poly)))return null;
      return {...p,progress,fade,index};
    }
    function drawTraffic(time) {
      routes.forEach((route,i)=>{
        const p=eventPosition(route,time,i);if(!p)return;
        const q=map(p.x,p.y),a=Math.atan2(p.dy*cover.height,p.dx*cover.width),length=(route.size||.007)*cover.width;
        ctx.save();clipPolygons(roads);ctx.globalAlpha=p.fade;ctx.translate(q.x,q.y);ctx.rotate(a);
        ctx.fillStyle=daylight>.5?(route.color||'#657984'):'#334651';ctx.fillRect(-length*.5,-length*.17,length,length*.34);
        if(daylight<.6){ctx.fillStyle='rgba(255,237,191,.95)';ctx.fillRect(length*.35,-length*.23,Math.max(.7,length*.17),Math.max(.5,length*.15));ctx.fillRect(length*.35,length*.08,Math.max(.7,length*.17),Math.max(.5,length*.15));ctx.fillStyle='rgba(222,103,79,.8)';ctx.fillRect(-length*.55,-length*.17,Math.max(.6,length*.13),length*.34);}
        if(weather.wetness>.08){ctx.globalAlpha=p.fade*weather.wetness*(1-daylight*.7)*.36;ctx.fillStyle='#e5c88e';ctx.fillRect(-length*.5,length*.5,length*2.8,Math.max(.6,length*.22));}
        ctx.restore();counts.vehicles++;
      });
    }
    function drawWalkers(time) {
      walkers.forEach((route,i)=>{
        const p=eventPosition(route,time,i);if(!p)return;
        const q=map(p.x,p.y),size=(route.height||.004)*cover.height, stride=Math.sin(time*5+i)*size*.13;
        ctx.save();clipPolygons(plazas);ctx.globalAlpha=p.fade*(daylight>.2?.67:.48);
        ctx.strokeStyle=route.color||'#687b7e';ctx.fillStyle=route.color||'#687b7e';ctx.lineCap='round';ctx.lineWidth=Math.max(.5,size*.18);
        ctx.beginPath();ctx.arc(q.x,q.y-size*.83,size*.14,0,TAU);ctx.fill();ctx.beginPath();ctx.moveTo(q.x,q.y-size*.65);ctx.lineTo(q.x,q.y-size*.25);ctx.moveTo(q.x,q.y-size*.25);ctx.lineTo(q.x+stride,q.y);ctx.moveTo(q.x,q.y-size*.25);ctx.lineTo(q.x-stride,q.y);ctx.stroke();
        if(weather.rain>.16){ctx.globalAlpha=p.fade*.7;ctx.fillStyle=i%2?'#8a9690':'#798e9b';ctx.beginPath();ctx.arc(q.x,q.y-size*.73,size*.56,Math.PI,TAU);ctx.fill();counts.umbrellas++;}
        ctx.restore();counts.pedestrians++;
      });
    }
    function drawDrips(time) {
      const amount=Math.max(weather.rain,weather.wetness*.35);if(amount<.035||weather.temperatureC!==null&&weather.temperatureC<=0)return;
      eaves.forEach((e,index)=>{
        for(let k=0;k<5;k++){
          const f=(k+.5)/5,p=map(e.a[0]+(e.b[0]-e.a[0])*f,e.a[1]+(e.b[1]-e.a[1])*f);
          const phase=(time*(.7+amount*.8)+k*.37+index*.19)%1;
          if(phase>.25+amount*.55)continue;
          const drop=phase**1.7*cover.height*(e.fall||.008);
          ctx.strokeStyle=`rgba(${daylight>.5?'125,153,166':'183,195,191'},${amount*.38})`;ctx.lineWidth=.6;ctx.beginPath();ctx.moveTo(p.x+weather.windX*drop*.15,p.y+drop);ctx.lineTo(p.x+weather.windX*(drop+1)*.15,p.y+drop+1.2);ctx.stroke();counts.drips++;
        }
      });
    }
    function drawRipples(time) {
      ripples = ripples.filter(r => time >= r.started && time - r.started < 3.2);
      for (const ripple of ripples) {
        const age = (time - ripple.started) / 3.2, center = map(ripple.x,ripple.y);
        ctx.save();polyPath(water[ripple.region]);ctx.clip();
        // Intersect the approved water mask with the complement of each
        // reviewed island/structure mask. No ring can paint across the shore.
        for (const polygon of waterExclusions) {
          ctx.beginPath();ctx.rect(cover.x,cover.y,cover.width,cover.height);
          polygon.forEach(([x,y],i)=>{const p=map(x,y);i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y);});ctx.closePath();ctx.clip('evenodd');
        }
        const opacity = (1-age)**1.5 * (daylight>.3?.43:.28);
        const radius = cover.width*(.004+.039*age);
        ctx.strokeStyle=`rgba(${daylight>.3?'235,250,249':'189,216,227'},${opacity})`;
        ctx.lineWidth=Math.max(.55,width*.0015)*(1-age*.45);
        for(let ring=0;ring<2;ring++){
          const r=radius*(1-ring*.31);ctx.beginPath();ctx.ellipse(center.x,center.y,r,r*.24,0,0,TAU);ctx.stroke();
        }
        ctx.restore();counts.ripples++;
      }
    }
    function render(time) {
      if(destroyed||!ctx||!cover||!visible||!intersecting||document.hidden)return;
      ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,width,height);counts={trees:0,glass:0,foam:0,caustics:0,vehicles:0,pedestrians:0,umbrellas:0,drips:0,ripples:0,accents:0,jets:0,wheels:0,ground:0,heat:0};events=[];
      if(config){weather=readWeather(time);drawGround(time);drawTrees(time);drawGlass(time);drawShore(time);drawAccents(time);drawTraffic(time);drawWalkers(time);drawDrips(time);drawJets(time);drawWheels(time);drawRipples(time);}
      frames++;
    }
    function stop(){if(raf)cancelAnimationFrame(raf);raf=0;lastTime=lastPaint=null;}
    function tick(now){raf=0;if(!shouldRun()){stop();return;}if(lastTime!==null)seconds+=Math.min(.1,(now-lastTime)/1000);lastTime=now;if(lastPaint===null||now-lastPaint>=1000/30){render(seconds);lastPaint=now;}raf=requestAnimationFrame(tick);}
    function updateLoop(){if(shouldRun()){if(!raf){lastTime=lastPaint=null;raf=requestAnimationFrame(tick);}}else stop();}
    function resize(){
      if(destroyed)return;const rect=container.getBoundingClientRect();width=Math.max(0,rect.width);height=Math.max(0,rect.height);dpr=Math.min(2,window.devicePixelRatio||1,720/Math.max(1,width));
      canvas.width=Math.max(1,Math.round(width*dpr));canvas.height=Math.max(1,Math.round(height*dpr));
      if(image.naturalWidth&&image.naturalHeight&&width&&height){const scale=Math.max(width/image.naturalWidth,height/image.naturalHeight),cw=image.naturalWidth*scale,ch=image.naturalHeight*scale;
        const position=getComputedStyle(image).objectPosition.split(/\s+/),fraction=v=>/^-?[\d.]+%$/.test(v||'')?parseFloat(v)/100:v==='left'||v==='top'?0:v==='right'||v==='bottom'?1:.5;
        cover={x:(width-cw)*fraction(position[0]),y:(height-ch)*fraction(position[1]),width:cw,height:ch,scale};render(seconds);
      }else cover=null;updateLoop();
    }
    function imageError(){cover=null;stop();ctx?.clearRect(0,0,canvas.width,canvas.height);}
    const onVisibility=()=>{if(!document.hidden)render(seconds);updateLoop();},onReduced=()=>updateLoop();
    const onBlendChange=()=>{if(destroyed)return;render(seconds);updateLoop();};
    image.addEventListener('load',resize);image.addEventListener('error',imageError);blendImage?.addEventListener('load',onBlendChange);blendImage?.addEventListener('error',onBlendChange);window.addEventListener('resize',resize,{passive:true});document.addEventListener('visibilitychange',onVisibility);(reducedQuery.addEventListener?reducedQuery.addEventListener('change',onReduced):reducedQuery.addListener(onReduced));
    if('ResizeObserver'in window){resizeObserver=new ResizeObserver(resize);resizeObserver.observe(container);}
    if('IntersectionObserver'in window){intersecting=false;intersectionObserver=new IntersectionObserver(entries=>{intersecting=entries.some(e=>e.isIntersecting);if(intersecting)render(seconds);updateLoop();});intersectionObserver.observe(container);}
    resize();
    return {
      setPlaying(value){if(destroyed)return;playing=Boolean(value);updateLoop();},
      setVisible(value){if(destroyed)return;visible=Boolean(value);if(visible)resize();else updateLoop();},
      setIntensity(value){if(destroyed)return;intensity=['soft','expressive','bold'].includes(value)?value:'bold';render(seconds);},
      setSky(value){if(destroyed)return;daylight=finite(value?.daylight)?clamp(value.daylight):(theme==='night'?0:1);if(!raf)render(seconds);},
      renderAt(time){if(destroyed||!finite(time)||time<0)return;stop();playing=false;seconds=time;render(seconds);},
      interact(value){
        if(destroyed||!config||!finite(value?.x)||!finite(value?.y)||value.x<0||value.x>1||value.y<.13||value.y>.62)return false;
        const x=value.x,y=value.y,region=water.findIndex(poly=>inside(x,y,poly));
        if(region>=0&&waterExclusions.some(poly=>inside(x,y,poly)))return false;
        if(region>=0){
          ripples.push({x,y,region,started:seconds});if(ripples.length>8)ripples.splice(0,ripples.length-8);render(seconds);return true;
        }
        if(!trees.some(t=>Math.hypot(x-t.center[0],y-t.center[1])<.1))return false;
        windTouch={x,y,time:seconds};render(seconds);return true;
      },
      destroy(){if(destroyed)return;destroyed=true;stop();resizeObserver?.disconnect();intersectionObserver?.disconnect();image.removeEventListener('load',resize);image.removeEventListener('error',imageError);blendImage?.removeEventListener('load',onBlendChange);blendImage?.removeEventListener('error',onBlendChange);window.removeEventListener('resize',resize);document.removeEventListener('visibilitychange',onVisibility);(reducedQuery.removeEventListener?reducedQuery.removeEventListener('change',onReduced):reducedQuery.removeListener(onReduced));canvas.remove();canvas.width=canvas.height=1;cover=null;ripples=[];windTouch=null;},
      get state(){return {sceneId,theme,configured:Boolean(config),identity:config?.localIdentity||'',caption:config?.effectCaption||'',playing,visible,intersecting,pageVisible:!document.hidden,running:Boolean(raf)&&Boolean(shouldRun()),destroyed,reducedMotion:reducedQuery.matches,motionAllowed:allowed(),intensity,time:seconds,frames,daylight,width,height,dpr,pixelWidth:canvas.width,pixelHeight:canvas.height,source:'reviewed-artwork-city-life',counts:{...counts},weather:{...weather},acceptedTraffic:routes.length,acceptedWalkers:walkers.length,rejectedRoutes:[...rejectedRoutes],rippleQueue:ripples.length,canopyAnchors:trees.length,canopySourceBlend,blendImageReady:sourceReady(blendImage),shorelineRegions:shores.length,waterRegions:water.length,imageTransform:cover?{...cover}:null};}
    };
  }
  window.KOneCityLifeV13={attach};
})();
