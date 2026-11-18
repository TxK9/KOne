// A continuous height-field surface registered to the artist's ink painting.
// No billboards, separate raised image slices, realtime noise or external library.
const vertexSource=`
precision highp float;
attribute vec3 a_point;
attribute vec2 a_gradient;
attribute vec2 a_uv;
uniform mediump float u_rise,u_pass,u_pitch;
uniform mediump vec2 u_artSize;
uniform float u_sky,u_canvasHeight;
uniform float u_geometryScale;
uniform vec2 u_view;
varying mediump vec2 v_uv,v_gradient;
varying mediump float v_height;
void main(){
  float height=a_point.z*u_rise*u_geometryScale;
  float yaw=u_view.x*u_rise;
  float pitch=u_pitch+u_view.y*u_rise;
  vec2 source=a_point.xy;
  if(u_pass<.5)source+=vec2(.46,.21)*height;
  vec2 center=u_artSize*.5;
  vec3 p=vec3(source.x-center.x,(center.y-source.y)/cos(pitch),u_pass<.5?0.:height);
  vec3 right=vec3(cos(yaw),sin(yaw),0.);
  vec3 up=vec3(-sin(yaw)*cos(pitch),cos(yaw)*cos(pitch),sin(pitch));
  vec3 eye=vec3(sin(yaw)*sin(pitch),-cos(yaw)*sin(pitch),cos(pitch));
  vec2 screen=vec2(dot(p,right)+center.x,center.y-dot(p,up)+u_sky);
  float perspective=1.-(u_pass<.5?0.:height)*.0001;
  vec2 clip=vec2(screen.x/u_artSize.x*2.-1.,1.-screen.y/u_canvasHeight*2.);
  gl_Position=vec4(clip,-dot(p,eye)/1700.,perspective);
  v_uv=a_uv;v_gradient=a_gradient;v_height=a_point.z;
}`;
const fragmentSource=`
precision mediump float;
uniform sampler2D u_paint,u_elevation;
uniform mediump float u_rise,u_pass,u_ink;
uniform mediump vec2 u_artSize;
uniform float u_heightMax;
uniform float u_lightStrength,u_shadeStrength;
varying mediump vec2 v_uv,v_gradient;
varying mediump float v_height;
void main(){
  vec4 paint=texture2D(u_paint,v_uv);
  if(u_pass<.5){
    vec2 d=vec2(10.)/u_artSize;
    float a=paint.a*2.;
    a+=texture2D(u_paint,v_uv+d).a+texture2D(u_paint,v_uv-d).a;
    a+=texture2D(u_paint,v_uv+vec2(d.x,-d.y)).a+texture2D(u_paint,v_uv+vec2(-d.x,d.y)).a;
    a/=6.;
    a*=.16*u_rise*u_ink*smoothstep(8.,110.,v_height);
    gl_FragColor=vec4(vec3(.20,.22,.18)*a,a);return;
  }
  if(paint.a<.014)discard;
  vec3 normal=normalize(vec3(v_gradient*u_rise,1.));
  float diffuse=dot(normal,normalize(vec3(-.55,.35,1.)));
  float light=clamp(1.+(diffuse-.84)*u_lightStrength*u_rise,.66,1.09);
  float here=texture2D(u_elevation,v_uv).r*u_heightMax*u_rise;
  float shade=0.;
  for(int i=1;i<=5;i++){
    float distance=float(i)*17.;
    vec2 sampleUV=v_uv+vec2(-.82,-.57)/u_artSize*distance;
    float ridge=texture2D(u_elevation,sampleUV).r*u_heightMax*u_rise;
    float covered=texture2D(u_paint,sampleUV).a;
    shade=max(shade,smoothstep(4.,23.,ridge-here-distance*1.12)*covered);
  }
  light*=1.-shade*u_shadeStrength;
  float a=paint.a*u_ink;
  gl_FragColor=vec4(paint.rgb*light*a,a);
}`;

export async function createInkRelief(art,image,options={}){
  const {width:W=2172,height:H=724,sky:SKY=420,floor:FLOOR=20,
    fieldWidth:EW=512,fieldHeight:EH=172,peak:PEAK=520,
    geometryScale:GEOMETRY_SCALE=1,lightStrength:LIGHT_STRENGTH=.62,
    shadeStrength:SHADE_STRENGTH=.19,yawBase:YAW_BASE=-.012}=options;
  const mobile=matchMedia('(max-width:650px)').matches;
  const canvas=document.createElement('canvas');canvas.className='ink-terrain';
  canvas.setAttribute('aria-hidden','true');
  const gl=canvas.getContext('webgl',{alpha:true,antialias:false,depth:true,
    premultipliedAlpha:true,preserveDrawingBuffer:false,powerPreference:'low-power'});
  if(!gl){art.closest('.scene').dataset.rendererError='webgl-unavailable';return null;}
  const cleanup=[];
  try{
    const response=await fetch(options.field||'assets/ink-elevation-v1.bin');
    if(!response.ok)throw new Error('Elevation unavailable');
    const field=new Uint8Array(await response.arrayBuffer());
    if(field.length!==EW*EH)throw new Error('Elevation invalid');
    function sample(x,y){
      const px=Math.max(0,Math.min(EW-1,x/W*(EW-1))),py=Math.max(0,Math.min(EH-1,y/H*(EH-1)));
      const x0=Math.floor(px),y0=Math.floor(py),x1=Math.min(EW-1,x0+1),y1=Math.min(EH-1,y0+1);
      const fx=px-x0,fy=py-y0;
      return ((field[y0*EW+x0]*(1-fx)+field[y0*EW+x1]*fx)*(1-fy)+
        (field[y1*EW+x0]*(1-fx)+field[y1*EW+x1]*fx)*fy)/255*PEAK;
    }
    const cols=options.cols||(mobile?112:160),rows=options.rows||(mobile?38:54);
    const vertices=new Float32Array((cols+1)*(rows+1)*7),indices=new Uint16Array(cols*rows*6);
    let cursor=0;
    for(let row=0;row<=rows;row++)for(let col=0;col<=cols;col++){
      const x=col/cols*W,y=row/rows*H;
      const gx=-(sample(x+8,y)-sample(x-8,y))/16;
      const gy=(sample(x,y+8)-sample(x,y-8))/16*Math.cos(.88);
      vertices.set([x,y,sample(x,y),gx,gy,x/W,y/H],cursor);cursor+=7;
    }
    cursor=0;
    for(let row=0;row<rows;row++)for(let col=0;col<cols;col++){
      const a=row*(cols+1)+col,b=a+cols+1;
      indices.set([a,b,a+1,a+1,b,b+1],cursor);cursor+=6;
    }
    function shader(type,source){
      const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);
      if(!gl.getShaderParameter(s,gl.COMPILE_STATUS)){const message=gl.getShaderInfoLog(s);gl.deleteShader(s);throw new Error(message);}
      cleanup.push(()=>gl.deleteShader(s));return s;
    }
    const program=gl.createProgram();
    gl.attachShader(program,shader(gl.VERTEX_SHADER,vertexSource));
    gl.attachShader(program,shader(gl.FRAGMENT_SHADER,fragmentSource));gl.linkProgram(program);
    if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(program));
    cleanup.push(()=>gl.deleteProgram(program));gl.useProgram(program);
    const vb=gl.createBuffer(),ib=gl.createBuffer();
    cleanup.push(()=>{gl.deleteBuffer(vb);gl.deleteBuffer(ib);});
    gl.bindBuffer(gl.ARRAY_BUFFER,vb);gl.bufferData(gl.ARRAY_BUFFER,vertices,gl.STATIC_DRAW);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,ib);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,indices,gl.STATIC_DRAW);
    for(const [name,size,offset] of [['a_point',3,0],['a_gradient',2,12],['a_uv',2,20]]){
      const location=gl.getAttribLocation(program,name);gl.enableVertexAttribArray(location);
      gl.vertexAttribPointer(location,size,gl.FLOAT,false,28,offset);
    }
    function texture(unit,format,width,height,data){
      const t=gl.createTexture();cleanup.push(()=>gl.deleteTexture(t));
      gl.activeTexture(gl.TEXTURE0+unit);gl.bindTexture(gl.TEXTURE_2D,t);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
      if(width)gl.texImage2D(gl.TEXTURE_2D,0,format,width,height,0,format,gl.UNSIGNED_BYTE,data);
      else gl.texImage2D(gl.TEXTURE_2D,0,format,format,gl.UNSIGNED_BYTE,data);
    }
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL,false);gl.pixelStorei(gl.UNPACK_ALIGNMENT,1);
    texture(0,gl.RGBA,0,0,image);texture(1,gl.LUMINANCE,EW,EH,field);
    const uniform=Object.fromEntries(['u_paint','u_elevation','u_rise','u_pass','u_pitch','u_view','u_ink','u_artSize','u_sky','u_canvasHeight','u_heightMax','u_geometryScale','u_lightStrength','u_shadeStrength']
      .map(name=>[name,gl.getUniformLocation(program,name)]));
    gl.uniform1i(uniform.u_paint,0);gl.uniform1i(uniform.u_elevation,1);
    gl.uniform1f(uniform.u_pitch,.88);
    gl.uniform2f(uniform.u_artSize,W,H);gl.uniform1f(uniform.u_sky,SKY);
    gl.uniform1f(uniform.u_canvasHeight,H+SKY+FLOOR);gl.uniform1f(uniform.u_heightMax,PEAK);
    gl.uniform1f(uniform.u_geometryScale,GEOMETRY_SCALE);gl.uniform1f(uniform.u_lightStrength,LIGHT_STRENGTH);
    gl.uniform1f(uniform.u_shadeStrength,SHADE_STRENGTH);
    canvas.dataset.geometryMode=GEOMETRY_SCALE===0?'painted-contour':'height-field';
    gl.enable(gl.BLEND);gl.blendFunc(gl.ONE,gl.ONE_MINUS_SRC_ALPHA);
    gl.depthFunc(gl.LEQUAL);
    art.insertBefore(canvas,art.querySelector('.scroll-cloud'));
    canvas.style.aspectRatio=W+'/'+(H+SKY+FLOOR);
    canvas.style.transform='translateY(-'+((SKY+H/2)/(H+SKY+FLOOR)*100)+'%)';
    let last={rise:0,ink:0,x:0,y:0},alive=true,draws=0;
    function size(){
      const width=Math.min(options.maxWidth||(mobile?1080:1560),Math.max(options.minWidth||600,Math.round(art.clientWidth*Math.min(devicePixelRatio,1.4))));
      canvas.width=width;canvas.height=Math.round(width/W*(H+SKY+FLOOR));
      gl.viewport(0,0,canvas.width,canvas.height);
    }
    function render(state){
      if(!alive)return;last=state;
      gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
      gl.uniform1f(uniform.u_rise,state.rise);gl.uniform1f(uniform.u_ink,state.ink);
      gl.uniform2f(uniform.u_view,YAW_BASE+state.x*.065,state.y*.10);
      gl.disable(gl.DEPTH_TEST);gl.uniform1f(uniform.u_pass,0);
      if(state.rise>.005&&GEOMETRY_SCALE>0)gl.drawElements(gl.TRIANGLES,indices.length,gl.UNSIGNED_SHORT,0);
      gl.enable(gl.DEPTH_TEST);gl.uniform1f(uniform.u_pass,1);
      gl.drawElements(gl.TRIANGLES,indices.length,gl.UNSIGNED_SHORT,0);
      canvas.dataset.rise=state.rise.toFixed(3);canvas.dataset.draws=String(++draws);
    }
    const observer=new ResizeObserver(()=>{size();render(last);});observer.observe(art);
    size();render(last);
    function lost(event){event.preventDefault();alive=false;art.closest('.scene').classList.remove('has-relief');canvas.hidden=true;}
    canvas.addEventListener('webglcontextlost',lost);
    art.closest('.scene').dataset.triangles=String(indices.length/3);
    return {render,dispose(){alive=false;observer.disconnect();canvas.removeEventListener('webglcontextlost',lost);cleanup.forEach(fn=>fn());canvas.remove();}};
  }catch(error){art.closest('.scene').dataset.rendererError=error.message;cleanup.forEach(fn=>fn());canvas.remove();return null;}
}
