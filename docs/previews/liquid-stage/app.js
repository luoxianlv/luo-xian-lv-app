import * as T from 'three';
import {RoomEnvironment} from './vendor/RoomEnvironment.js';
const $=id=>document.getElementById(id), clamp=T.MathUtils.clamp, mix=T.MathUtils.lerp;
const smooth=(a,b,x)=>{let t=clamp((x-a)/(b-a),0,1);return t*t*(3-2*t)};
let renderer;
try{renderer=new T.WebGLRenderer({canvas:$('scene'),antialias:true,alpha:false,powerPreference:'high-performance'});}catch(e){$('error').hidden=false;$('error').textContent='此浏览器无法启动 WebGL，请启用硬件加速后重试。';throw e}
renderer.setPixelRatio(Math.min(devicePixelRatio,1.5));renderer.toneMapping=T.ACESFilmicToneMapping;renderer.toneMappingExposure=1.25;renderer.outputColorSpace=T.SRGBColorSpace;
const scene=new T.Scene(),camera=new T.PerspectiveCamera(40,2.16,.1,70);camera.position.set(0,0,9);
const pmrem=new T.PMREMGenerator(renderer),room=new RoomEnvironment();const environment=pmrem.fromScene(room,.04);scene.environment=environment.texture;room.dispose();pmrem.dispose();
scene.add(new T.HemisphereLight(0xd9edff,0x534063,2));const light=new T.DirectionalLight(0xe6f6ff,4);light.position.set(-4,6,4);scene.add(light);const rim=new T.PointLight(0xb6a2ff,32,20);rim.position.set(4,1,2);scene.add(rim);
const texture=await new T.TextureLoader().loadAsync('wallpaper.jpg');texture.colorSpace=T.SRGBColorSpace;
const lensMat=new T.ShaderMaterial({depthTest:false,depthWrite:false,toneMapped:false,
 uniforms:{wallpaper:{value:texture},time:{value:0},strength:{value:1},aspect:{value:2.16},release:{value:0},settle:{value:0}},
 vertexShader:`varying vec2 vUv;void main(){vUv=uv;gl_Position=vec4(position.xy,0.999,1.0);}`,
 fragmentShader:`precision highp float;
 varying vec2 vUv;uniform sampler2D wallpaper;uniform float time,strength,aspect,release,settle;
 void main(){
 vec2 q=(vUv-.5)*vec2(aspect,1.0);float r=length(q);float a=atan(q.y,q.x);
 float core=.072*(1.0-.20*release);float lensRing=.145;
 float field=exp(-pow((r-lensRing)/.09,2.0))*.055*strength;
 vec2 dir=q/max(r,.001);vec2 displaced=q-dir*field;
 float twist=.028*strength*exp(-r*7.0);displaced+=vec2(-dir.y,dir.x)*twist;
 vec2 uv=displaced/vec2(aspect,1.0)+.5;
 vec2 chroma=dir/vec2(aspect,1.0)*.0018*strength*exp(-pow((r-lensRing)/.065,2.0));
 vec3 bg=vec3(texture2D(wallpaper,uv+chroma).r,texture2D(wallpaper,uv).g,texture2D(wallpaper,uv-chroma).b);
 bg*=mix(.22,.79,settle);bg=mix(bg,vec3(.018,.036,.065),.3*strength);
 // Stylized luminous tear: layered ink-like silhouettes, not a circular shaded ball.
 float wobble=1.0+.14*sin(a*3.0+time*.34)+.065*sin(a*7.0-time*.28)+.025*sin(a*13.0);
 float rr=r/wobble;
 float silhouette=1.0-smoothstep(core*.98,core*1.025,rr);
 float fringe=1.0-smoothstep(core*1.14,core*1.18,rr);
 float violet=1.0-smoothstep(core*1.30,core*1.34,rr);
 vec3 color=bg;
 color=mix(color,vec3(.34,.32,.62),violet*strength*.65);
 color=mix(color,vec3(.63,.85,.95),fringe*strength);
 color=mix(color,vec3(1.0,.985,.94),silhouette*strength);
 float ellipse=length(vec2(q.x*.67+q.y*.20,q.y*2.2));
 float ribbon=1.0-smoothstep(.001,.004,abs(ellipse-.144-.003*sin(a*5.0+time*.35)));
 float broken=smoothstep(-.3,.4,sin(a*2.0+.7));
 float echo=exp(-pow((r-lensRing)/.005,2.0))*.22;
 color+=vec3(.65,.80,.94)*(ribbon*broken*.8+echo)*strength;
 color+=vec3(.27,.44,.59)*exp(-r*12.0)*strength*.26;
 float waveRadius=.11+release*.87;
 float irregular=r/(1.0+.05*sin(a*5.0)+.03*sin(a*11.0));
 float wave=(1.0-smoothstep(.001,.006,abs(irregular-waveRadius)))*sin(release*3.14159)*.5;
 color+=vec3(.68,.84,1.0)*wave;
 gl_FragColor=vec4(clamp(color,0.0,1.0),1.0);
 #include <colorspace_fragment>
 }`});
const lens=new T.Mesh(new T.PlaneGeometry(2,2),lensMat);lens.frustumCulled=false;lens.renderOrder=-10;scene.add(lens);
// Three asymmetric shard meshes with flat faces and a narrow dark edge.
// r160 supports RGBA data textures consistently across WebGL implementations.
const ramp=new T.DataTexture(new Uint8Array([70,70,70,255,155,155,155,255,255,255,255,255]),3,1,T.RGBAFormat);ramp.minFilter=ramp.magFilter=T.NearestFilter;ramp.needsUpdate=true;
const shardMat=new T.MeshToonMaterial({color:0xd4ebf3,gradientMap:ramp,side:T.DoubleSide});
function shardGeometry(kind){
 const outlines=[[-.48,-.3,.35,-.5,.2,.65,-.12,.28],[-.22,-.65,.34,-.24,.12,.6,-.34,.18],[-.6,-.2,.28,-.37,.48,.25,-.2,.34]];
 const xy=outlines[kind],v=[];for(let face=0;face<2;face++)for(let i=0;i<4;i++)v.push(xy[i*2],xy[i*2+1],face?.028:-.028);
 const g=new T.BufferGeometry();g.setAttribute('position',new T.Float32BufferAttribute(v,3));g.setIndex([0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,1,2,6,1,6,5,2,3,7,2,7,6,3,0,4,3,4,7]);g.computeVertexNormals();return g.toNonIndexed();
}
const shards=Array.from({length:3},(_,i)=>{const m=new T.InstancedMesh(shardGeometry(i),shardMat,64);m.instanceMatrix.setUsage(T.DynamicDrawUsage);m.frustumCulled=false;scene.add(m);return m});
let randomSeed=72631;function rand(){randomSeed=(Math.imul(randomSeed,1664525)+1013904223)>>>0;return randomSeed/4294967296}
const clusters=[-.32,.83,2.25,3.45,4.9];
const particles=Array.from({length:192},(_,i)=>{
 const a=clusters[Math.floor(rand()*clusters.length)]+(rand()+rand()-1)*.7;
 return {a,s:rand(),spread:1.3+rand()*3.6,z:(rand()-.5)*4.4,delay:rand()*.42,spin:new T.Vector3((rand()-.5)*5,(rand()-.5)*7,(rand()-.5)*5),group:Math.floor(rand()*8),theta:rand()*Math.PI*2,size:i<16?.3+rand()*.23:.045+Math.pow(rand(),2)*.22,stretch:.5+rand()*1.6,bend:(rand()-.5)*2.3};
});
const palette=[0xd8edf4,0xa8c6de,0xebe7fc,0xf9f3e5,0x8199bc];shards.forEach((m,j)=>{for(let i=0;i<64;i++)m.setColorAt(i,new T.Color(palette[(i*7+j)%palette.length]));m.instanceColor.needsUpdate=true});
const ringMat=new T.MeshPhysicalMaterial({color:0xc8dfff,metalness:.55,roughness:.19,clearcoat:1,transparent:true,opacity:0,emissive:0x668bb8,emissiveIntensity:.3});
const rings=Array.from({length:8},(_,i)=>{const m=new T.Mesh(new T.TorusGeometry(.35,.018,8,80),ringMat);scene.add(m);const label=document.createElement('span');label.textContent=i===7?'1':String(i+1);$('labels').append(label);return m});
const dummy=new T.Object3D(),target=new T.Vector3(),position=new T.Vector3(),projected=new T.Vector3(),initial=new T.Vector3(),out=new T.Vector3();
let elapsed=0,playing=true,orbit=false,last=performance.now(),frameCount=0,frameStart=last,dragging=false;
const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
if(reduced){playing=false;elapsed=7.8;$('play').textContent='播放'}
function resize(){const r=$('stage').getBoundingClientRect();renderer.setSize(r.width,r.height,false);camera.aspect=r.width/r.height;camera.updateProjectionMatrix()}
new ResizeObserver(resize).observe($('stage'));resize();
function endpoint(i){const worldHeight=2*9*Math.tan(T.MathUtils.degToRad(20)),worldWidth=worldHeight*camera.aspect;return new T.Vector3((i-3.5)*worldWidth*.091,-worldHeight*.12,0)}
let destinations=rings.map((_,i)=>endpoint(i));
function animate(now){requestAnimationFrame(animate);const dt=Math.min((now-last)/1000,.05);last=now;if(playing&&!dragging){elapsed=Math.min(7.8,elapsed+dt);if(elapsed===7.8){playing=false;$('play').textContent='播放'}}
const t=orbit?1.35:elapsed,charge=smooth(1.3,2.5,t),explode=smooth(2.45,3.7,t),assemble=smooth(3.7,6.4,t),settle=smooth(6.0,7.3,t);
lensMat.uniforms.time.value=orbit?now*.001:t;
lensMat.uniforms.strength.value=1-smooth(2.6,4.0,t);
lensMat.uniforms.release.value=smooth(2.45,4.1,t);
lensMat.uniforms.settle.value=settle;
lensMat.uniforms.aspect.value=camera.aspect;
destinations=rings.map((_,i)=>endpoint(i));
function locate(i){const v=particles[i],age=Math.max(0,t-2.4-v.delay),e=1-Math.exp(-age*3.3),k=smooth(4.0+v.delay,6.15+v.delay,t);
initial.set(Math.cos(v.a)*.24,Math.sin(v.a)*.24,0);
out.set(Math.cos(v.a)*v.spread,Math.sin(v.a)*(v.spread*.52),v.z);
position.copy(initial).lerp(out,e);
position.x+=Math.sin(age*1.1)*v.bend*.3;position.y-=age*age*.08;
target.copy(destinations[v.group]);target.x+=Math.cos(v.theta)*.35;target.y+=Math.sin(v.theta)*.35;
position.lerp(target,k);position.x+=Math.sin(k*Math.PI)*v.bend;position.z+=Math.sin(k*Math.PI)*(v.s-.5)*2;
dummy.position.copy(position);dummy.rotation.set(v.a+age*v.spin.x,v.a*.4+age*v.spin.y,v.a+age*v.spin.z);
const size=v.size*smooth(0,.10,age)*(1-smooth(6.0+v.delay,6.8+v.delay,t));dummy.scale.set(size*v.stretch,size,size);dummy.updateMatrix();return dummy.matrix}
shards.forEach((m,j)=>{m.visible=t>2.4&&t<7.3;if(m.visible){for(let i=0;i<64;i++)m.setMatrixAt(i,locate(j*64+i));m.instanceMatrix.needsUpdate=true}});
ringMat.opacity=smooth(5.6,6.8,t)*.72;ringMat.emissiveIntensity=.2+.6*(1-settle);
rings.forEach((ring,i)=>{ring.position.copy(destinations[i]);ring.visible=t>5.6;projected.copy(ring.position).project(camera);const label=$('labels').children[i];label.style.left=(projected.x*.5+.5)*100+'%';label.style.top=(-projected.y*.5+.5)*100+'%';label.style.opacity=smooth(6.6,7.6,t)});
camera.position.set(0,0,9);camera.lookAt(0,0,0);
const phase=t<2.45?['白洞','光从中心释放，空间在边缘弯曲。']:t<3.7?['碎光','不规则光片，沿不同纵深飞散。']:t<6.5?['归弦','散开的光，沿弧线重新凝聚。']:['成奏','八个落点，一场演奏。'];$('phase').textContent=phase[0];$('description').textContent=phase[1];
if(!dragging)$('timeline').value=elapsed;$('time').textContent=elapsed.toFixed(1)+' / 7.8s';renderer.render(scene,camera);
frameCount++;if(now-frameStart>1000){$('performance').textContent=Math.round(frameCount*1000/(now-frameStart))+' fps';frameCount=0;frameStart=now}}
requestAnimationFrame(animate);
$('play').onclick=()=>{if(elapsed>=7.8)elapsed=0;playing=!playing;orbit=false;$('orbit').setAttribute('aria-pressed','false');$('play').textContent=playing?'暂停':'播放'};
$('replay').onclick=()=>{elapsed=0;playing=true;orbit=false;$('orbit').setAttribute('aria-pressed','false');$('play').textContent='暂停'};
$('timeline').oninput=e=>{elapsed=+e.target.value;playing=false;orbit=false;$('orbit').setAttribute('aria-pressed','false');$('play').textContent='播放'};
$('orbit').onclick=()=>{orbit=!orbit;playing=false;$('play').textContent='播放';$('orbit').setAttribute('aria-pressed',String(orbit))};
document.addEventListener('visibilitychange',()=>{if(document.hidden){playing=false;$('play').textContent='播放'}});
