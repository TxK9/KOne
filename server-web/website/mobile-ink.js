import {createInkRelief} from './ink-relief.js';
import {createInkLife} from './ink-life.js';

export async function createMobileInkScene(host){
  const page=host.querySelector('.mobile-ink-scene'),art=host.querySelector('.mobile-ink-art');
  const image=art.querySelector('.mobile-paint');image.src=image.dataset.src;await image.decode();
  const motion=matchMedia('(prefers-reduced-motion:reduce)');
  const economical=!!navigator.connection?.saveData||!!(navigator.hardwareConcurrency&&navigator.hardwareConcurrency<=2);
  const renderer=!motion.matches&&!economical?await createInkRelief(art,image,{
    width:1024,height:1536,sky:300,floor:20,fieldWidth:256,fieldHeight:384,peak:140,
    geometryScale:0,lightStrength:.4,shadeStrength:.07,yawBase:0,
    field:'assets/mobile-elevation-v1.bin',cols:56,rows:84,maxWidth:720,minWidth:360
  }):null;
  host.classList.add('has-mobile-ink');host.classList.toggle('has-relief',!!renderer);
  host.dataset.scene='mobile-ink-soft-depth';host.dataset.phase='waiting';host.dataset.motion='idle';
  const life=createInkLife(page,host);
  let entered=false,disposed=false,visible=true,frame=0,depthFrame=0,last=0,started=0,pausedAt=0;
  let rise=0,ink=0,appearance=0,finished=false;
  const smooth=t=>{t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);};
  function render(){
    page.style.setProperty('--mountain-offset',((1-appearance)*12).toFixed(2)+'px');
    page.style.setProperty('--mountain-scale',(.986+appearance*.014).toFixed(5));
    renderer?.render({rise,ink,x:0,y:0});
  }
  function depth(){
    depthFrame=0;
    if(!entered||!finished||disposed||!visible||document.hidden||motion.matches||economical)return;
    const y=Math.max(-3,Math.min(5,-host.getBoundingClientRect().top*.025));
    page.style.setProperty('--depth-y',y.toFixed(2)+'px');
  }
  function requestDepth(){if(!depthFrame&&finished&&entered&&!disposed&&visible&&!document.hidden&&!motion.matches&&!economical)depthFrame=requestAnimationFrame(depth);}
  function request(){if(!frame&&entered&&visible&&!document.hidden&&!motion.matches&&!economical)frame=requestAnimationFrame(tick);}
  function settle(staticMode=false){
    page.style.setProperty('--ink','1');
    rise=staticMode?0:(renderer?1:0);ink=1;appearance=1;page.style.setProperty('--depth-y','0px');render();finished=true;
    host.dataset.phase='complete';host.dataset.relief=staticMode?'static':(renderer?'soft-depth':'flat');host.dataset.motion='idle';
    if(staticMode)life.still();else{requestDepth();if(visible&&!document.hidden)life.start();}
  }
  function tick(time){
    frame=0;if(!entered||!visible||document.hidden||motion.matches||economical||disposed)return;
    if(time-last<1000/30-1){frame=requestAnimationFrame(tick);return;}last=time;
    if(finished){host.dataset.motion='idle';return;}
    const elapsed=time-started;
    ink=smooth((elapsed-150)/2350);page.style.setProperty('--ink',ink.toFixed(4));
    appearance=smooth((elapsed-1200)/3500);rise=renderer?appearance:0;render();
    host.dataset.relief=appearance>0?'lighting':'waiting';host.dataset.motion=appearance>0?'emerging':'unfolding';
    if(elapsed>=4800){finished=true;host.dataset.phase='complete';host.dataset.relief=renderer?'soft-depth':'flat';host.dataset.motion='idle';requestDepth();life.start();}
    else frame=requestAnimationFrame(tick);
  }
  function sync(){
    const paused=document.hidden||!visible;host.classList.toggle('scene-paused',paused);
    if(paused){if(!pausedAt)pausedAt=performance.now();cancelAnimationFrame(frame);cancelAnimationFrame(depthFrame);frame=depthFrame=0;life.pause();host.dataset.motion='paused';}
    else{if(pausedAt&&started)started+=performance.now()-pausedAt;pausedAt=0;request();requestDepth();if(finished&&!motion.matches&&!economical)life.start();}
  }
  function changed(){cancelAnimationFrame(frame);cancelAnimationFrame(depthFrame);frame=depthFrame=0;host.classList.toggle('is-static',motion.matches||economical);if(entered)settle(motion.matches||economical);}
  const observer=new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;sync();},{threshold:.05});
  observer.observe(host);document.addEventListener('visibilitychange',sync);motion.addEventListener('change',changed);
  document.addEventListener('scroll',requestDepth,{passive:true});
  return {
    enter(){if(disposed)return;entered=true;finished=false;appearance=0;started=performance.now();last=0;pausedAt=0;life.reset();page.style.setProperty('--depth-y','0px');
      host.classList.add('is-entered');host.classList.toggle('is-static',motion.matches||economical);
      if(motion.matches||economical)settle(true);else{host.dataset.phase='opening';request();}sync();},
    reset(){entered=false;finished=false;cancelAnimationFrame(frame);cancelAnimationFrame(depthFrame);life.reset();frame=depthFrame=0;rise=ink=appearance=started=pausedAt=0;
      page.style.setProperty('--ink','0');page.style.setProperty('--depth-y','0px');render();host.classList.remove('is-entered');
      host.dataset.phase='waiting';host.dataset.relief='waiting';host.dataset.motion='idle';},
    dispose(){disposed=true;cancelAnimationFrame(frame);cancelAnimationFrame(depthFrame);life.dispose();observer.disconnect();renderer?.dispose();
      document.removeEventListener('visibilitychange',sync);document.removeEventListener('scroll',requestDepth);motion.removeEventListener('change',changed);}
  };
}
