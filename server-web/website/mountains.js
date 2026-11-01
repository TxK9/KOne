import {createInkRelief} from './ink-relief.js';
import {createMobileInkScene} from './mobile-ink.js';

// The painting appears first. A continuous rock surface then grows from paper.
export async function createMountainScene(host){
  const compact=matchMedia('(max-width:650px)');
  let isCompact=compact.matches,entered=false,disposed=false,queue=Promise.resolve();
  const prepare=()=>compact.matches?createMobileInkScene(host):createDesktopMountainScene(host);
  let current=await prepare();
  function change(){
    queue=queue.then(async()=>{
      if(disposed||isCompact===compact.matches)return;
      isCompact=compact.matches;current?.dispose();current=null;
      host.classList.remove('has-art','has-relief','has-mobile-ink','is-entered','is-static','relief-settled');
      delete host.dataset.triangles;
      const next=await prepare();
      if(disposed){next.dispose();return;}current=next;if(entered)current.enter();
    });
  }
  compact.addEventListener('change',change);
  return {enter(){entered=true;current?.enter();},reset(){entered=false;current?.reset();},
    dispose(){disposed=true;compact.removeEventListener('change',change);current?.dispose();}};
}

async function createDesktopMountainScene(host){
  const response=await fetch('assets/landscape-layers.svg');
  if(!response.ok)throw new Error('Artwork unavailable');
  const parsed=new DOMParser().parseFromString(await response.text(),'image/svg+xml');
  if(parsed.querySelector('parsererror'))throw new Error('Artwork invalid');
  const art=host.querySelector('.scroll-art'),stage=host.querySelector('.scroll-stage');
  const image=art.querySelector('img');await image.decode();
  const svg=document.importNode(parsed.documentElement,true);svg.classList.add('scroll-ink-flat');
  const paint=svg.querySelector('.paint-reveal');paint.removeAttribute('mask');
  paint.replaceChildren();const fullImage=document.importNode(svg.querySelector('#scroll-paint'),true);
  fullImage.removeAttribute('id');paint.append(fullImage);
  art.insertBefore(svg,art.querySelector('.scroll-cloud'));
  const motion=matchMedia('(prefers-reduced-motion: reduce)');
  const finePointer=matchMedia('(hover:hover) and (pointer:fine)');
  const economical=!!navigator.connection?.saveData||!!(navigator.hardwareConcurrency&&navigator.hardwareConcurrency<=2);
  const relief=!motion.matches&&!economical?await createInkRelief(art,image):null;
  host.classList.add('has-art');host.classList.toggle('has-relief',!!relief);
  host.dataset.scene=relief?'ink-continuous-relief':'ink-scroll-static';
  host.dataset.phase='waiting';host.dataset.relief='waiting';host.dataset.motion='idle';
  let entered=false,disposed=false,visible=true,frame=0,last=0,openedAt=0,pausedAt=0;
  let x=0,y=0,targetX=0,targetY=0,rise=0,ink=0,finished=false;
  const smooth=t=>{t=Math.max(0,Math.min(1,t));return t*t*t*(t*(t*6-15)+10);};
  const canMove=()=>entered&&visible&&!document.hidden&&!motion.matches&&!economical;
  function render(){relief?.render({rise,ink,x,y});}
  function applyView(){
    stage.style.setProperty('--yaw',(-1.8-x*1.2).toFixed(2)+'deg');
    stage.style.setProperty('--pitch',(6+y*.8).toFixed(2)+'deg');render();
  }
  function request(){if(!frame&&canMove())frame=requestAnimationFrame(tick);}
  function unfold(){
    stage.style.setProperty('--spread','1');host.dataset.spread='1';host.dataset.phase='complete';
  }
  function settle(){
    finished=true;host.classList.add('relief-settled');host.dataset.relief=relief?'raised':'flat';
    host.dataset.motion='idle';
  }
  function staticView(){
    unfold();host.classList.add('is-static');rise=0;ink=1;x=y=targetX=targetY=0;
    finished=true;host.dataset.relief='static';host.dataset.motion='idle';applyView();
  }
  function tick(time){
    frame=0;if(!canMove())return;
    if(time-last<1000/30-1){frame=requestAnimationFrame(tick);return;}last=time;
    const elapsed=time-openedAt;
    if(!finished){
      const t=Math.max(0,Math.min(1,(elapsed-800)/5700));
      const spread=t<.5?4*t*t*t:1-Math.pow(-2*t+2,3)/2;
      stage.style.setProperty('--spread',spread.toFixed(4));host.dataset.spread=spread.toFixed(3);
      ink=smooth((elapsed-950)/5300);rise=relief?smooth((elapsed-6500)/5600):0;
      if(elapsed>=7300)unfold();
      host.dataset.relief=rise>0?'forming':'waiting';host.dataset.motion=rise>0?'sculpting':'unrolling';
      render();
      if(elapsed>=12100||(!relief&&elapsed>=7300))settle();
      else{frame=requestAnimationFrame(tick);return;}
    }
    const moving=Math.abs(targetX-x)+Math.abs(targetY-y)>.003;
    if(moving){
      x+=(targetX-x)*.12;y+=(targetY-y)*.12;applyView();
      host.dataset.motion='parallax';frame=requestAnimationFrame(tick);
    }else{
      if(x!==targetX||y!==targetY){x=targetX;y=targetY;applyView();}
      host.dataset.motion='idle';
    }
  }
  function pointer(event){
    if(!finePointer.matches||!canMove()||!finished||!relief)return;
    const rect=host.getBoundingClientRect();targetX=(event.clientX-rect.left)/rect.width-.5;
    targetY=(event.clientY-rect.top)/rect.height-.5;request();
  }
  function center(){targetX=targetY=0;request();}
  function syncVisibility(){
    const paused=document.hidden||!visible;host.classList.toggle('scene-paused',paused);
    if(paused){
      if(!pausedAt)pausedAt=performance.now();cancelAnimationFrame(frame);frame=0;host.dataset.motion='paused';
    }else{
      if(pausedAt&&openedAt)openedAt+=performance.now()-pausedAt;
      pausedAt=0;host.dataset.motion='idle';request();
    }
  }
  function motionChanged(){
    cancelAnimationFrame(frame);frame=0;
    if(motion.matches){if(entered)staticView();}
    else{host.classList.toggle('is-static',economical);if(entered){rise=relief?1:0;ink=1;settle();applyView();}}
  }
  const observer=new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;syncVisibility();},{threshold:.05});
  observer.observe(host);host.addEventListener('pointermove',pointer,{passive:true});
  host.addEventListener('pointerleave',center,{passive:true});document.addEventListener('visibilitychange',syncVisibility);
  motion.addEventListener('change',motionChanged);
  return {
    enter(){
      if(disposed)return;entered=true;finished=false;openedAt=performance.now();pausedAt=0;last=0;
      host.classList.add('is-entered');host.classList.remove('relief-settled');
      if(motion.matches||economical)staticView();
      else{host.dataset.phase='opening';host.classList.remove('is-static');request();}syncVisibility();
    },
    reset(){
      entered=false;finished=false;cancelAnimationFrame(frame);frame=0;
      host.classList.remove('is-entered','relief-settled');
      host.dataset.phase='waiting';host.dataset.relief='waiting';host.dataset.motion='idle';
      stage.style.setProperty('--spread','0');host.dataset.spread='0';
      x=y=targetX=targetY=rise=ink=openedAt=pausedAt=0;applyView();
    },
    dispose(){
      disposed=true;cancelAnimationFrame(frame);observer.disconnect();relief?.dispose();svg.remove();
      host.removeEventListener('pointermove',pointer);host.removeEventListener('pointerleave',center);
      document.removeEventListener('visibilitychange',syncVisibility);motion.removeEventListener('change',motionChanged);
    }
  };
}
