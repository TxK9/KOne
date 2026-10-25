/* Art weather and explicitly requested current model estimates; no device location. */
(() => {
  'use strict';
  const clamp=(n,a=0,b=1)=>Math.max(a,Math.min(b,n)), finite=Number.isFinite;
  const smooth=n=>{n=clamp(n);return n*n*(3-2*n);};
  const numeric=['cloudCover','rain','snow','fog','wind','wetness','rainbow','lightning','temperature','temperatureC'];
  const presets={
    clear:{label:'晴朗',cloudCover:.16,rain:0,snow:0,fog:.025,wind:.78,wetness:0,temperature:23},
    cloudy:{label:'多云',cloudCover:.70,rain:0,snow:0,fog:.10,wind:1.05,wetness:0,temperature:20},
    shower:{label:'阵雨',cloudCover:.86,rain:.62,snow:0,fog:.19,wind:1.18,wetness:.85,temperature:18},
    storm:{label:'风雨',cloudCover:.98,rain:.94,snow:0,fog:.26,wind:1.70,wetness:1,temperature:17},
    mist:{label:'薄雾',cloudCover:.65,rain:0,snow:0,fog:.76,wind:.59,wetness:.3,temperature:14},
    after:{label:'雨后初晴',cloudCover:.33,rain:0,snow:0,fog:.20,wind:.84,wetness:.90,rainbow:.14,temperature:20},
    snow:{label:'飘雪',cloudCover:.87,rain:0,snow:.70,fog:.21,wind:1.02,wetness:.30,temperature:-3}
  };
  function snowEligible(scene,season='auto',date=new Date()){
    const lat=Number(scene?.cityLocation?.lat)||0,month=new Date(date).getUTCMonth()+1;
    const winter=season==='winter'||season==='auto'&&(lat>=0?[11,12,1,2,3]:[5,6,7,8,9]).includes(month);
    return Math.abs(lat)>=32&&winter;
  }
  function normalized(value={}){
    const v={id:value.id||'clear',label:value.label||'晴朗',source:value.source||'manual',...value};
    for(const k of numeric){const n=Number(v[k]);v[k]=finite(n)?k==='wind'?clamp(n,.5,2):k==='temperature'||k==='temperatureC'?clamp(n,-60,60):clamp(n):k==='wind'?1:k==='temperature'||k==='temperatureC'?20:0;}
    v.temperatureC=v.temperature;return v;
  }
  function mix(a,b,f){const o={...b};for(const k of numeric)o[k]=a[k]+(b[k]-a[k])*smooth(f);return o;}
  function create(scene,{mode='story',season='auto',date=Date.now()}={}){
    let revision=0,liveMeta=null;const events=[];
    function preset(id,t,selectedSeason=season,selectedDate=date){
      let value=presets[id]||presets.clear;
      if(id==='snow'&&!snowEligible(scene,selectedSeason,selectedDate))value=presets.cloudy;
      return normalized({...value,id:value===presets.cloudy&&id==='snow'?'cloudy':id,source:'manual',rainbow:value.rainbow||0,lightning:0});
    }
    function target(event,t){
      if(event.mode==='live')return normalized({...event.profile,source:'live'});
      if(event.mode==='story'){
        const cycle=((t-event.at)%120+120)%120;
        const keys=[[0,'clear'],[18,'cloudy'],[37,'shower'],[59,'storm'],[79,'after'],[120,'clear']];
        let i=0;while(i<keys.length-2&&cycle>=keys[i+1][0])i++;
        const a=preset(keys[i][1],t,event.season,event.date),b=preset(keys[i+1][1],t,event.season,event.date);
        return {...mix(a,b,(cycle-keys[i][0])/(keys[i+1][0]-keys[i][0])),id:'story',label:'天气故事 · '+(cycle<28?'云渐浓':cycle<48?'阵雨来临':cycle<70?'风雨经过':cycle<101?'雨过天晴':'晴空渐回'),source:'story'};
      }
      const v=preset(event.mode,t,event.season,event.date);
      if(event.mode==='after')v.wetness=Math.max(.08,.9*Math.exp(-Math.max(0,t-event.at)/180));
      return v;
    }
    function sample(time=0){
      const t=finite(time)?Math.max(0,time):0;
      let event=events[0];for(const e of events){if(e.at>t)break;event=e;}
      const end=target(event,t);
      return event.from&&t<event.at+event.duration?mix(event.from,end,(t-event.at)/event.duration):end;
    }
    function setMode(next,time=0,opts={}){
      const t=finite(time)?Math.max(0,time):0,from=events.length?sample(t):null;
      if(next!=='story'&&next!=='live'&&!presets[next])return false;
      if(next==='live'&&(!opts.profile||opts.profile.source!=='live'))return false;
      if(next==='snow'&&!snowEligible(scene,opts.season||season,date))return false;
      while(events.length&&events.at(-1).at>=t)events.pop();
      if(opts.season)season=opts.season;
      events.push({at:t,mode:next,season,date,profile:opts.profile,from,duration:opts.immediate?0:5});
      revision++;if(next!=='live')liveMeta=null;return true;
    }
    if(!setMode(mode,0,{immediate:true}))setMode(mode==='snow'?'cloudy':'clear',0,{immediate:true});
    return {sample,setMode,setLive:(profile,meta,time=0)=>{const ok=setMode('live',time,{profile});if(ok)liveMeta=meta;return ok;},
      setSeason:(value,time=0,opts={})=>{season=value;const current=events.at(-1);return current.mode==='snow'&&!snowEligible(scene,season,date)?setMode('cloudy',time,opts):true;},
      // Calendar changes affect eligibility, not the phase of an ongoing story,
      // a live estimate or a five-second transition. Historical events keep dates.
      setDate:(value,time=0,opts={})=>{if(!finite(new Date(value).getTime()))return false;date=value;const current=events.at(-1);return current.mode==='snow'&&!snowEligible(scene,season,date)?setMode('cloudy',time,opts):true;},get revision(){return revision;},get changeTime(){return events.at(-1).at;},
      get status(){return {mode:events.at(-1).mode,season,date:new Date(date).toISOString().slice(0,10),snowEligible:snowEligible(scene,season,date),live:liveMeta,revision};}};
  }
  function fromCurrent(data){
    const c=data?.current,u=data?.current_units;if(!c||!u)throw Error('天气响应缺少当前时段');
    const required=['time','interval','weather_code','temperature_2m','cloud_cover','wind_speed_10m'];
    if(!required.every(k=>typeof c[k]==='number'&&finite(c[k]))||c.interval<=0)throw Error('天气数据缺项');
    if(u.temperature_2m!=='°C'||u.wind_speed_10m!=='m/s'||u.cloud_cover!=='%')throw Error('天气单位不匹配');
    if(u.rain!=='mm'||u.showers!=='mm'||u.snowfall!=='cm'||u.time!=='unixtime'||u.interval!=='seconds'||u.visibility&&u.visibility!=='m')throw Error('降水或时间单位不匹配');
    const perHour=k=>typeof c[k]==='number'&&finite(c[k])?Math.max(0,c[k])*3600/c.interval:0;
    const code=c.weather_code,thunder=[95,96,97,99].includes(code),snowCode=[71,73,75,77,85,86].includes(code);
    const rainRate=perHour('rain')+perHour('showers'),snowRate=perHour('snowfall');
    const rain=clamp(Math.sqrt(rainRate/6)),snow=snowCode?clamp(Math.sqrt(snowRate/1.5)):0;
    const visibility=typeof c.visibility==='number'&&finite(c.visibility)?c.visibility:10000;
    const fog=[45,48].includes(code)?.8:clamp((6000-visibility)/6500,0,.6);
    const profile=normalized({id:'live',label:thunder?'雷雨':snow>.02?'降雪':rain>.02?'降雨':fog>.4?'雾':c.cloud_cover>65?'多云':'晴朗',source:'live',cloudCover:c.cloud_cover/100,rain,snow,fog,
      wind:.62+Math.max(0,c.wind_speed_10m)/11,wetness:clamp(rain+.25*snow),temperature:c.temperature_2m,lightning:0,rainbow:0});
    return {profile,meta:{validAt:c.time*1000,intervalSeconds:c.interval,temperature:c.temperature_2m,windMs:c.wind_speed_10m,windDirection:c.wind_direction_10m,rainMmPerHour:rainRate,snowCmPerHour:snowRate,weatherCode:code,provider:'Open-Meteo',estimate:true}};
  }
  const api={create,presets,snowEligible,fromCurrent,normalized};
  if(typeof module!=='undefined'&&module.exports)module.exports=api;else window.KOneWeatherModelV11=api;
})();
