const smooth=value=>{value=Math.max(0,Math.min(1,value));return value*value*(3-2*value);};

// Active scene time drives the cycle, so background tabs do not jump ahead.
export function createInkLifeModel(random=Math.random){
  const between=(low,high)=>low+(high-low)*random();
  const boatStart=between(.045,.105),boatDuration=between(160,210);
  let rainStart=between(9,15),rainDuration=between(23,31),nextRain=rainStart+rainDuration+between(42,74);
  let time=0,flight=null,nextFlight=between(1,2.5),direction=random()<.5?-1:1,lastCount=0,serial=0;
  function weather(){
    return smooth((time-rainStart)/4)*(1-smooth((time-rainStart-rainDuration+6)/6));
  }
  function snapshot(){
    const progress=(boatStart+time/boatDuration)%1;
    return {time,boatProgress:progress,boatOpacity:smooth(progress/.035)*(1-smooth((progress-.91)/.09)),
      rain:weather(),flight:flight?{...flight,progress:Math.max(0,Math.min(1,(time-flight.start)/flight.duration))}:null};
  }
  return {
    advance(seconds){
      time+=Math.max(0,seconds);
      if(time>=nextRain){rainStart=time+between(8,20);rainDuration=between(18,34);nextRain=rainStart+rainDuration+between(42,74);}
      if(flight&&time>=flight.start+flight.duration){nextFlight=flight.start+flight.duration+between(7,17);flight=null;}
      if(!flight&&time>=nextFlight){
        if(weather()>.7)nextFlight=time+7;
        else{
          direction=-direction;
          let count=1+Math.floor(random()*5);if(count===lastCount)count=count%5+1;lastCount=count;
          flight={id:++serial,start:time,duration:between(19,29),direction,count,
            startY:between(.1,.23),endY:between(.12,.3),arc:between(-.075,.045),scale:between(.66,.95)};
        }
      }
      return snapshot();
    },
    snapshot
  };
}

export function createInkLife(page,host){
  const svgNS='http://www.w3.org/2000/svg';
  const boat=page.querySelector('.album-boat'),route=page.querySelector('#album-boat-route');
  const birds=page.querySelector('.album-birds'),rain=page.querySelector('.album-rain');
  const streaks=page.querySelector('.rain-streaks'),rings=page.querySelector('.water-rain-ripples');
  const length=route.getTotalLength();
  const points=Array.from({length:193},(_,i)=>route.getPointAtLength(length*i/192));
  function point(progress){
    const index=Math.max(0,Math.min(191.999,progress*192)),i=Math.floor(index),mix=index-i;
    const a=points[i],b=points[i+1];
    let angle=Math.atan2(b.y-a.y,b.x-a.x)*180/Math.PI;
    if(angle>90)angle-=180;else if(angle<-90)angle+=180;
    return {x:a.x+(b.x-a.x)*mix,y:a.y+(b.y-a.y)*mix,angle:Math.max(-13,Math.min(13,angle))};
  }
  for(let i=0;i<38;i++){
    const line=document.createElementNS(svgNS,'line');
    const x=Math.random()*1150-60,y=Math.random()*1420-100,size=22+Math.random()*32;
    line.setAttribute('x1',x);line.setAttribute('y1',y);line.setAttribute('x2',x-9);line.setAttribute('y2',y+size);
    line.setAttribute('class','rain-streak');line.style.setProperty('--fall-time',(1.1+Math.random()*.9).toFixed(2)+'s');
    line.style.animationDelay=(-Math.random()*3).toFixed(2)+'s';line.style.opacity=(.2+Math.random()*.3).toFixed(2);
    streaks.append(line);
  }
  for(const [i,progress] of [.12,.22,.34,.45,.57,.7,.81].entries()){
    const p=point(progress),ring=document.createElementNS(svgNS,'ellipse');
    ring.setAttribute('cx',p.x);ring.setAttribute('cy',p.y);ring.setAttribute('rx',12+i%3*3);ring.setAttribute('ry',3.5+i%2);
    ring.setAttribute('class','rain-ring');ring.style.animationDelay=(-i*.61).toFixed(2)+'s';
    ring.style.setProperty('--ring-time',(2.1+i%3*.43).toFixed(2)+'s');rings.append(ring);
  }
  let model=createInkLifeModel(),frame=0,last=0,active=false,disposed=false,flockId=0,boatAngle=0;
  let width=page.clientWidth,height=page.clientHeight;
  const observer=new ResizeObserver(()=>{width=page.clientWidth;height=page.clientHeight;});observer.observe(page);
  function flock(flight){
    birds.replaceChildren();flockId=flight.id;
    for(let i=0;i<flight.count;i++){
      const anchor=document.createElementNS(svgNS,'g'),wing=document.createElementNS(svgNS,'g'),path=document.createElementNS(svgNS,'path');
      wing.setAttribute('class','bird-wing');anchor.setAttribute('transform',`translate(${i%3*31+Math.random()*5} ${Math.floor(i/3)*22+Math.random()*9})`);
      path.setAttribute('d','M0 9Q6 2 13 9Q20 2 27 5');wing.append(path);anchor.append(wing);birds.append(anchor);
      wing.style.setProperty('--wing-time',(2.4+Math.random()*1.8).toFixed(2)+'s');wing.style.animationDelay=(-Math.random()*3).toFixed(2)+'s';
    }
    birds.dataset.direction=flight.direction>0?'left-to-right':'right-to-left';birds.dataset.count=flight.count;
    birds.dataset.flight=flight.id;
  }
  function draw(state){
    const p=point(state.boatProgress);
    boatAngle+=Math.max(-.35,Math.min(.35,p.angle-boatAngle));
    boat.setAttribute('transform',`translate(${p.x.toFixed(2)} ${p.y.toFixed(2)}) rotate(${boatAngle.toFixed(2)})`);
    boat.style.opacity=(state.boatOpacity*.95).toFixed(3);
    boat.dataset.progress=state.boatProgress.toFixed(4);
    const flight=state.flight;
    birds.style.display=flight?'':'none';
    if(flight){
      if(flight.id!==flockId)flock(flight);
      const q=flight.progress,start=flight.direction>0?-90:width+90,end=flight.direction>0?width+90:-90;
      const x=start+(end-start)*q,y=height*(flight.startY+(flight.endY-flight.startY)*q+Math.sin(q*Math.PI)*flight.arc);
      const fade=smooth(q/.1)*(1-smooth((q-.86)/.14));
      birds.style.transform=`translate3d(${x.toFixed(2)}px,${y.toFixed(2)}px,7px) scale(${flight.direction*flight.scale},${flight.scale})`;
      birds.style.opacity=(fade*.5).toFixed(3);
    }
    rain.hidden=state.rain<.01;rain.style.opacity=(state.rain*.5).toFixed(3);
    rings.style.display=state.rain>.01?'':'none';rings.style.opacity=(state.rain*.7).toFixed(3);
    page.style.setProperty('--rain-strength',state.rain.toFixed(3));
    page.dataset.weather=state.rain>.65?'rain':state.rain>.01?'drizzle':'clear';
  }
  function tick(time){
    frame=0;if(!active||disposed)return;
    if(!last)last=time;
    const seconds=(time-last)/1000;
    if(seconds>=1/12){last=time;draw(model.advance(Math.min(seconds,.3)));}
    frame=requestAnimationFrame(tick);
  }
  function pause(){active=false;cancelAnimationFrame(frame);frame=0;last=0;}
  function still(){
    pause();draw(model.snapshot());birds.style.display='';birds.style.opacity='.35';
    birds.style.transform=`translate3d(${width*.7}px,${height*.16}px,7px) scale(.8)`;
    rain.hidden=true;rings.style.display='none';page.style.setProperty('--rain-strength','0');page.dataset.weather='clear';
  }
  still();
  return {
    start(){if(disposed)return;active=true;last=0;if(!frame)frame=requestAnimationFrame(tick);},
    pause,
    still,
    reset(){pause();model=createInkLifeModel();flockId=0;boatAngle=0;draw(model.snapshot());},
    dispose(){disposed=true;pause();observer.disconnect();streaks.replaceChildren();rings.replaceChildren();}
  };
}
