/* Shared weather bridge. Historical wake samples are read-only and deterministic. */
(() => {
  'use strict';
  const base=window.KOneEnvironmentV10,providers=new Map(),clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v));
  function visibility(profile={}){
    const fog=clamp(Number(profile.fog)||0),t=clamp((fog-.35)/.36),fogVisibility=1-t*t*(3-2*t);
    return {fogVisibility,directVisibility:fogVisibility*(1-clamp(Number(profile.cloudCover)||0)*.82)*(1-clamp(Number(profile.rain)||0)*.72)*(1-clamp(Number(profile.snow)||0)*.55)};
  }
  function bind(id,model){providers.clear();providers.set(id,{model,revision:model.revision,integral:[0]});}
  function sample(id,seconds){
    const t=Number.isFinite(seconds)?Math.max(0,seconds):0,env=base.sample(id,t),entry=providers.get(id);
    if(!entry)return {...env,rain:0,snow:0,fog:0,wetness:0,activity:1,transmission:1,fogVisibility:1,directVisibility:1};
    const {model}=entry,step=.5;
    if(entry.revision!==model.revision){entry.integral.length=Math.min(entry.integral.length,Math.floor(model.changeTime/step)+1);entry.revision=model.revision;}
    const velocity=x=>base.sample(id,x).wind*clamp(model.sample(x).wind,.5,2);
    const n=Math.floor(t/step);
    while(entry.integral.length<=n){const i=entry.integral.length-1;entry.integral.push(entry.integral[i]+step*(velocity(i*step)+velocity((i+1)*step))/2);}
    const start=n*step,adv=entry.integral[n]+(t-start)*(velocity(start)+velocity(t))/2;
    const p=model.sample(t),wind=velocity(t),cover=clamp(p.cloudCover*.88+env.cloudCover*.12),rain=p.rain||0,snow=p.snow||0,fog=p.fog||0;
    return {...env,wind,advection:adv,gust:clamp((wind-.4)/1.8),cloudCover:cover,
      waveGroup:clamp(env.waveGroup*.6+(wind-.5)*.24+rain*.18),shimmer:env.shimmer*(1-cover*.55),
      rain,snow,fog,wetness:p.wetness||0,temperature:p.temperature,activity:clamp((1-rain*.94)*(1-snow*.78)*(1-fog*.7),.025,1),
      transmission:clamp(1-.88*cover-.4*rain-.25*fog,.035,1),...visibility(p),source:p.source};
  }
  const api={sample,bind,visibility,unbind:()=>providers.clear(),source:'shared-weather-art-direction'};
  window.KOneEnvironmentV13=api;window.KOneEnvironmentV10=api;
})();
