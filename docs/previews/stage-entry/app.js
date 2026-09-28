(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const frame=$('frame'), home=$('home'), canvas=$('scene'), ctx=canvas.getContext('2d'), portal=$('portal'), video=$('wallpaper-video');
  const clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v));
  const lerp=(a,b,t)=>a+(b-a)*t;
  const smooth=(a,b,x)=>{const t=clamp((x-a)/(b-a));return t*t*(3-2*t)};
  const ease=t=>t<.5?4*t*t*t:1-Math.pow(-2*t+2,3)/2;
  const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
  const notes=[96,347,598,848,1098,1349,1600,1852], modes=[588,882,1116,1350];
  const state={p:0,goal:0,start:0,from:0,duration:1180,active:-1,half:false,mode:0,wallpaper:'sunset',dark:false,pointer:0,hover:false,page:null,fromStage:false,floating:false,sequence:0};
  let portrait,landscape,source,current,raf=0,voice=null,audio=null,noteToken=0,toastTimer,dragId=null,soundIndex=null;
  const buffers=new Map(), pending=new Map();
  const device=$('device');
  const realPhone=false; // HTML demonstrates the automatic device transition, without sensor permission.
  const turn={angle:0,target:0,landscape:false,requested:false,exiting:false,stable:0,lastAngle:0,drag:null};
  document.body.classList.toggle('real-phone',realPhone);
  let previousTick=0;


  function measure(){
    const pw=realPhone?Math.min(innerWidth,innerHeight):390,ph=realPhone?Math.max(innerWidth,innerHeight):844;
    portrait={w:pw,h:ph,x:0,y:0};landscape={w:ph,h:pw,x:0,y:0};
    const sx=pw/390,sy=ph/844;
    let px=0,py=0;for(let el=portal;el&&el!==home;el=el.offsetParent){px+=el.offsetLeft;py+=el.offsetTop}
    source={x:px*sx,y:py*sy,w:portal.offsetWidth*sx,h:portal.offsetHeight*sy};
    const dpr=Math.min(devicePixelRatio,2);
    canvas.width=Math.ceil(ph*dpr);canvas.height=Math.ceil(ph*dpr);
    if(realPhone){turn.target=turn.angle=innerWidth>innerHeight?90:0;turn.stable=0}
    render(performance.now());
  }
  function rounded(x,y,w,h,r){ctx.beginPath();ctx.roundRect(x,y,w,h,Math.min(r,w/2,h/2))}
  function text(value,x,y,size,color,weight=500){ctx.font=`${weight} ${size}px "Microsoft YaHei UI", sans-serif`;ctx.fillStyle=color;ctx.textAlign='center';ctx.textBaseline='alphabetic';const m=ctx.measureText(value);ctx.fillText(value,x,y+(m.actualBoundingBoxAscent-m.actualBoundingBoxDescent)/2)}
  function line(x,y,x2,y2,color,width=1){ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(x2,y2);ctx.strokeStyle=color;ctx.lineWidth=width;ctx.stroke()}
  function geometry(w,h){const scale=Math.min(w*.7/1970,h*.64/512);return {scale,left:(w-1970*scale)/2,top:h*.53-256*scale}}

  // Light lives in the glass; no decorative controls appear before landscape settles.
  function draw(now,w,h){
    const p=state.p, dark=state.dark, expand=smooth(0,.28,p), clear=smooth(.67,.94,p);
    ctx.setTransform(canvas.width/w,0,0,canvas.height/h,0,0);ctx.clearRect(0,0,w,h);
    const bx=lerp(source.x,0,expand),by=lerp(source.y,0,expand),bw=lerp(source.w,w,expand),bh=lerp(source.h,h,expand);
    const r=lerp(24*portrait.w/390,0,expand);
    if(p<.07){
      const rest=1-smooth(0,.07,p),u=portrait.w/390;
      ctx.save();ctx.globalAlpha=rest;
      ctx.beginPath();ctx.roundRect(source.x,source.y,source.w,source.h,[24*u,0,0,24*u]);
      ctx.shadowColor=dark?'#07142560':'#44607820';ctx.shadowBlur=18*u;ctx.shadowOffsetY=7*u;
      const face=ctx.createLinearGradient(0,source.y,0,source.y+source.h);
      face.addColorStop(0,dark?'#60748b88':'#ffffffb8');face.addColorStop(1,dark?'#344a6488':'#ecf3fa85');
      ctx.fillStyle=face;ctx.fill();ctx.shadowBlur=0;ctx.shadowOffsetY=0;
      ctx.lineWidth=u;ctx.strokeStyle=dark?'#d3e7fa38':'#ffffffc4';ctx.stroke();
      text('演练场',source.x+source.w/2,source.y+source.h/2,14*u,dark?'#d7e2f0':'#395970',500);
      const rim=ctx.createLinearGradient(source.x,0,source.x+source.w,0);rim.addColorStop(0,'#ffffff00');rim.addColorStop(.5,'#ffffffcc');rim.addColorStop(1,'#ffffff00');
      line(source.x+28*u,source.y+1*u,source.x+source.w-28*u,source.y+1*u,rim,u);
      ctx.restore();if(p===0)return;
    }
    if(p<.97){
      ctx.save();ctx.beginPath();ctx.roundRect(bx,by,bw,bh,[r,0,0,r]);ctx.clip();
      const glass=ctx.createLinearGradient(0,0,w,h);
      glass.addColorStop(0,dark?'#1e3a55':'#b4dcec');glass.addColorStop(.52,dark?'#243044':'#dce6f2');glass.addColorStop(1,dark?'#3a2a3e':'#eddae8');
      ctx.globalAlpha=smooth(0,.07,p);ctx.fillStyle=glass;ctx.fillRect(bx,by,bw,bh);
      const waking=smooth(.04,.27,p),time=now/1000;
      function glow(x,y,rx,ry,color,alpha){
        ctx.save();ctx.translate(x,y);ctx.scale(rx,ry);const g=ctx.createRadialGradient(0,0,0,0,0,1);g.addColorStop(0,color);g.addColorStop(1,color+'00');ctx.fillStyle=g;ctx.globalAlpha=alpha*(1-clear);ctx.fillRect(-1,-1,2,2);ctx.restore();
      }
      // Three broad, slow planes create parallax without particles or spinning widgets.
      glow(w*(.36+.055*Math.sin(time*.37)),h*(.36+.055*Math.cos(time*.31)),w*.68,h*.35,dark?'#698da0':'#ffffff',waking*.42);
      glow(w*(.76+.08*Math.sin(time*.23+1)),h*.64,w*.62,h*.44,dark?'#907582':'#e9bfbe',waking*.42);
      glow(w*(.22+.09*Math.cos(time*.29)),h*.69,w*.5,h*.38,dark?'#304e68':'#8cbace',waking*.36);
      // A fixed anchor: gather inward, compress, then release one expanding light front.
      const cx=w/2,cy=h/2,unit=portrait.w/390;
      const arrive=smooth(.07,.27,p),compression=smooth(.43,.63,p),burst=smooth(.63,.98,p);
      const charge=arrive*(.35+.65*Math.max(turn.angle/90,compression));
      const dim=arrive*(1-smooth(.64,.85,p));
      ctx.globalAlpha=dim*(dark?.46:.38);ctx.fillStyle='#172941';ctx.fillRect(0,0,w,h);ctx.globalAlpha=1;
      const radius=Math.hypot(w,h)*.88*(1-Math.pow(1-burst,2.1));
      if(burst>0){
        ctx.save();ctx.globalAlpha=1;ctx.globalCompositeOperation='destination-out';
        const opening=ctx.createRadialGradient(cx,cy,0,cx,cy,Math.max(1,radius));
        opening.addColorStop(0,'#000');opening.addColorStop(.82,'#000');opening.addColorStop(1,'transparent');
        ctx.fillStyle=opening;ctx.fillRect(0,0,w,h);ctx.restore();
      }
      const energy=arrive*(1-smooth(.66,.87,p));
      glow(cx,cy,(145+charge*45)*unit,(145+charge*45)*unit,'#a9d8ff',energy*.8);
      glow(cx,cy,82*unit,82*unit,'#ffffff',energy*.7);
      ctx.save();ctx.globalCompositeOperation='screen';
      // Fine converging light threads give the brighter center something to draw inward.
      for(let i=0;i<32;i++){
        const a=i*2.39996,flow=(time*.21+i*.137)%1;
        const dist=(42+(1-flow)*120)*(1-compression*.55)*unit;
        const x=cx+Math.cos(a)*dist,y=cy+Math.sin(a)*dist;
        ctx.globalAlpha=energy*Math.sin(flow*Math.PI)*(.12+.22*charge);
        ctx.strokeStyle=i%3?'#d3ecff':'#ffe6d7';ctx.lineWidth=(i%4===0?1.5:.75)*unit;
        ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(x+Math.cos(a)*9*unit,y+Math.sin(a)*9*unit);ctx.stroke();
      }
      for(let j=0;j<3;j++){
        const rr=(42+j*17)*(1-compression*.5)*unit;
        ctx.globalAlpha=energy*(.27-j*.055);ctx.strokeStyle=j===1?'#e3c9ff':'#d7efff';ctx.lineWidth=(j===0?1.6:.65)*unit;
        ctx.beginPath();ctx.arc(cx,cy,rr,time*.16+j*2.1,time*.16+j*2.1+Math.PI*1.3);ctx.stroke();
      }
      ctx.globalCompositeOperation='source-over';ctx.globalAlpha=energy;
      const coreR=lerp(25,13,compression)*unit;
      const pearl=ctx.createRadialGradient(cx-coreR*.25,cy-coreR*.3,0,cx,cy,coreR);
      pearl.addColorStop(0,'#ffffff');pearl.addColorStop(.35,'#f4fdff');pearl.addColorStop(.73,'#bedaff');pearl.addColorStop(.92,'#9bafd4');pearl.addColorStop(1,'#f4efff');
      ctx.fillStyle=pearl;ctx.shadowColor='#d6f3ff';ctx.shadowBlur=(24+compression*34)*unit;
      ctx.beginPath();ctx.arc(cx,cy,coreR,0,Math.PI*2);ctx.fill();ctx.shadowBlur=0;
      ctx.lineWidth=.8*unit;ctx.strokeStyle='#ffffffcc';ctx.stroke();ctx.restore();
      if(burst>0&&burst<1){
        ctx.save();ctx.globalCompositeOperation='screen';
        const power=Math.pow(1-burst,1.2),edge=radius*.88;
        const wave=ctx.createRadialGradient(cx,cy,Math.max(0,edge-60*unit),cx,cy,Math.max(1,edge+18*unit));
        wave.addColorStop(0,'#c4e9ff00');wave.addColorStop(.6,'#c4e9ff40');wave.addColorStop(.82,'#f6f5ffb0');wave.addColorStop(1,'#d8d0ff00');
        ctx.globalAlpha=power;ctx.fillStyle=wave;ctx.fillRect(0,0,w,h);
        for(let j=0;j<2;j++){
          ctx.globalAlpha=power*(j?.28:.8);ctx.strokeStyle=j?'#dacbff':'#effbff';ctx.lineWidth=(j?1:2.3)*unit;
          ctx.beginPath();ctx.arc(cx,cy,edge*(j?.86:1),0,Math.PI*2);ctx.stroke();
        }
        for(let i=0;i<44;i++){
          const a=i*2.39996,seed=(Math.sin(i*7.13)+1)/2,dist=radius*(.44+seed*.45);
          const length=(16+seed*42)*unit*Math.sin(Math.PI*burst);
          ctx.globalAlpha=power*(.22+seed*.45);ctx.strokeStyle=i%3?'#d7efff':'#ffe5d6';ctx.lineWidth=(.6+seed)*unit;
          ctx.beginPath();ctx.moveTo(cx+Math.cos(a)*dist,cy+Math.sin(a)*dist);ctx.lineTo(cx+Math.cos(a)*(dist+length),cy+Math.sin(a)*(dist+length));ctx.stroke();
        }
        // A single horizontal lens reflection, fading with the outgoing wave.
        const flare=ctx.createLinearGradient(cx-w*.42,0,cx+w*.42,0);
        flare.addColorStop(0,'#d8eeff00');flare.addColorStop(.5,'#f5fdff');flare.addColorStop(1,'#d8eeff00');
        ctx.globalAlpha=Math.sin(Math.PI*burst)*.55;ctx.fillStyle=flare;ctx.fillRect(cx-w*.42,cy-unit,w*.84,2*unit);ctx.restore();
      }
      ctx.globalAlpha=1;
      if(p<.13)text('演练场',source.x+source.w/2,source.y+source.h/2,13*portrait.w/390,dark?`rgba(215,226,240,${.72*(1-smooth(0,.12,p))})`:`rgba(41,68,90,${.72*(1-smooth(0,.12,p))})`,500);
      ctx.restore();
      if(p<.29){ctx.beginPath();ctx.roundRect(bx+.5,by+.5,bw-1,bh-1,[r,0,0,r]);ctx.strokeStyle=`rgba(255,255,255,${.4*(1-expand)})`;ctx.lineWidth=1;ctx.stroke()}
    }
    if(p<.79)return;
    const g=geometry(w,h),appear=smooth(.79,1,p),settle=1-Math.pow(1-appear,3);
    ctx.save();ctx.globalAlpha=appear;
    const border=`rgba(190,202,182,${appear*.25})`;
    line(g.left+5*g.scale,g.top+7*g.scale,g.left+957*g.scale,g.top+7*g.scale,border);
    line(g.left+1013*g.scale,g.top+7*g.scale,g.left+1970*g.scale,g.top+7*g.scale,border);
    line(g.left+5*g.scale,g.top+477*g.scale,g.left+1970*g.scale,g.top+477*g.scale,border);
    line(g.left+740*g.scale,g.top+84*g.scale,g.left+740*g.scale,g.top+173*g.scale,border);
    [...notes,...modes].forEach((x,i)=>{
      const note=i<8,j=i-8,cx=g.left+x*g.scale,cy=g.top+(note?355:126)*g.scale+(1-settle)*12,rr=(note?86:74)*g.scale;
      const selected=note?state.active===i:j===0?state.half:j===1?state.mode===1:j===2?state.mode===0:state.mode===-1;
      ctx.beginPath();ctx.ellipse(cx,cy,rr,rr*(.78+.22*settle),0,0,Math.PI*2);ctx.fillStyle=selected?'#697066bc':'#06080799';ctx.fill();ctx.strokeStyle=selected?'#e7ece3':'#89928070';ctx.lineWidth=(selected?3:2.5)*g.scale;ctx.stroke();
      const sweep=smooth(.82,1,p),a=clamp(1-Math.abs(i/11-sweep)*6)*Math.sin(appear*Math.PI);
      if(a>0){ctx.strokeStyle=`rgba(240,250,255,${a*.7})`;ctx.stroke()}
      const labels=smooth(.85,1,p);ctx.globalAlpha=labels;
      text(note?(i===7?'1':String(i+1)):['半音','升调','自然音','降调'][j],cx,cy,(note?80:31)*g.scale,'#eeeeE5',note?700:500);
      if(note){if(state.half)text('#',cx-39*g.scale,cy-22*g.scale,40*g.scale,'#eeeee5',600);const register=state.mode+(i===7?1:0);for(let k=0;k<Math.abs(register);k++){ctx.beginPath();ctx.arc(cx,cy+(register>0?-53-k*12:58+k*12)*g.scale,5.2*g.scale,0,Math.PI*2);ctx.fillStyle='#eeeee5';ctx.fill()}}
      ctx.globalAlpha=appear;
    });ctx.restore();
  }

  function render(now){
    const p=state.p;
    current=realPhone?{w:innerWidth,h:innerHeight,x:0,y:0}:(turn.landscape?{...landscape}:{...portrait});
    if(realPhone){
      Object.assign(device.style,{width:innerWidth+'px',height:innerHeight+'px',left:'0px',top:'0px',transform:'none'});
      Object.assign(frame.style,{left:'0px',top:'0px',width:current.w+'px',height:current.h+'px',transform:'none',borderRadius:'0px'});
    }else{
      // Fixed physical dimensions. Only the device rotates; its aspect ratio never morphs.
      const diagonal=Math.hypot(portrait.w,portrait.h);
      const scale=Math.min((innerWidth-48)/diagonal,(innerHeight-156)/diagonal,.85);
      Object.assign(device.style,{width:portrait.w+'px',height:portrait.h+'px',left:'50%',top:'calc(50% - 55px)',transform:`translate(-50%,-50%) scale(${Math.max(.18,scale)}) rotate(${-turn.angle}deg)`});
      Object.assign(frame.style,{left:'50%',top:'50%',width:current.w+'px',height:current.h+'px',transform:`translate(-50%,-50%) rotate(${turn.landscape?90:0}deg)`,borderRadius:'28px'});
    }
    $('preview-status').textContent=!turn.requested?'点击「演练场」 · 全程自动':turn.exiting?'光晕收拢 · 返回首页':state.p>=.999?'已进入演奏':state.p>.62?'光核散开 · 揭开舞台':turn.angle>1&&turn.angle<89?'转屏中 · 光核保持居中':'蓄光';
    home.style.transform=`scale(${portrait.w/390},${portrait.h/844}) scale(${1-.045*smooth(0,.24,p)})`;
    home.style.opacity=1-smooth(.15,.28,p);home.style.filter=`blur(${smooth(.08,.28,p)*3}px)`;home.inert=p>.04||state.page!==null;
    $('stage-background').style.opacity=smooth(.55,.91,p);$('stage-background').style.filter=`blur(${20*(1-smooth(.64,.94,p))}px)`;
    video.style.transform=`scale(${lerp(1.18,1,smooth(.64,1,p))})`;
    $('wallpaper-image').style.transform=video.style.transform;
    $('stage-ui').style.opacity=smooth(.88,1,p);
    $('stage-ui').inert=p<.999||state.page!==null;
    $('stage-ui').classList.toggle('live',p>=.999&&state.page===null);
    draw(now,current.w,current.h);layoutTargets();
    if(state.page){Object.assign($('subpage').style,{width:'390px',height:'844px',transform:`scale(${portrait.w/390},${portrait.h/844})`,opacity:1-smooth(.1,.7,p)})}
  }
  function moveTo(goal,duration){
    state.from=state.p;state.goal=goal;state.start=performance.now();state.duration=reduced?1:duration;
  }
  function tick(now){
    const dt=Math.min(50,now-(previousTick||now));previousTick=now;
    if(!realPhone){turn.angle=lerp(turn.angle,turn.target,reduced?1:1-Math.exp(-dt/180));if(Math.abs(turn.angle-turn.target)<.03)turn.angle=turn.target}
    if(state.p!==state.goal){const t=clamp((now-state.start)/state.duration);state.p=lerp(state.from,state.goal,ease(t));if(t===1)state.p=state.goal}
    const settled=Math.abs(turn.angle-turn.lastAngle)<.15;turn.lastAngle=turn.angle;
    const correct=turn.exiting?turn.angle<2:turn.angle>88;
    if(turn.requested&&correct&&settled){turn.stable||=now}else turn.stable=0;
    if(turn.requested&&!turn.exiting){
      if(state.p===.4&&state.goal===.4)turn.target=90;
      if(turn.angle<84&&state.p>.4&&state.goal!==1){stopNote();turn.landscape=false;state.p=.4;moveTo(.4,1);frame.dataset.state='waiting'}
      if(state.p===.4&&state.goal===.4&&turn.stable&&now-turn.stable>300){turn.landscape=true;moveTo(1,1700);frame.dataset.state='revealing'}
      if(state.p===1){frame.dataset.state='playing';if(state.pendingMelody){state.pendingMelody=false;demoMelody()}};
    }
    if(turn.exiting&&state.p<=.4&&state.goal===.4){
      turn.target=0;
      if(turn.stable&&now-turn.stable>300){turn.landscape=false;moveTo(0,650);frame.dataset.state='leaving'}
    }
    if(turn.exiting&&state.p===0){turn.exiting=false;turn.requested=false;video.pause();frame.dataset.state='home';if(!state.page)portal.focus({preventScroll:true})}
    render(now);raf=requestAnimationFrame(tick);
  }
  function animate(goal){
    if(goal){
      turn.requested=true;turn.exiting=false;turn.stable=0;frame.dataset.state='waiting';moveTo(.4,850);
      state.mode=0;state.half=false;state.active=-1;if(state.wallpaper==='day')video.play().catch(()=>{});loadNatural();
      try{audio??=new (window.AudioContext||window.webkitAudioContext)();audio.resume()}catch{}
    }else{
      stopNote();state.sequence++;state.pendingMelody=false;turn.exiting=true;turn.stable=0;moveTo(Math.min(.4,state.p),400);frame.dataset.state='returning';
      if(state.floating)$('float-state').textContent='自然音 · 已暂停';
    }
  }
  portal.addEventListener('click',()=>{state.page=null;animate(1)});
  $('leave').onclick=()=>animate(0);
  $('replay').onclick=()=>{
    stopNote();state.sequence++;state.page=null;$('subpage').hidden=true;state.p=state.goal=0;
    turn.requested=turn.exiting=turn.landscape=false;turn.target=turn.angle=realPhone&&innerWidth>innerHeight?90:0;frame.dataset.state='home';render(performance.now());
  };
  window.addEventListener('resize',measure);
  window.screen.orientation?.addEventListener('change',()=>{if(realPhone)requestAnimationFrame(measure)});

  function layoutTargets(){
    const {scale:s,left,top}=geometry(current.w,current.h);
    document.querySelectorAll('.hit-zone').forEach(b=>{const mode=b.dataset.mode!==undefined,i=+(mode?b.dataset.mode:b.dataset.note),r=(mode?74:86)*s;Object.assign(b.style,{left:(left+(mode?modes[i]:notes[i])*s-r)+'px',top:(top+(mode?126:355)*s-r)+'px',width:2*r+'px',height:2*r+'px'});if(mode)b.setAttribute('aria-pressed',String(i===0?state.half:i===1?state.mode===1:i===2?state.mode===0:state.mode===-1))});
  }
  for(let i=0;i<12;i++){
    const b=document.createElement('button');b.className='hit-zone';
    if(i<8){b.dataset.note=i;b.setAttribute('aria-label',`音符 ${i===7?'高音 1':i+1}`);b.onpointerdown=e=>{if(state.p<.99)return;e.preventDefault();b.setPointerCapture(e.pointerId);dragId=e.pointerId;playNote(i)};const release=e=>{if(dragId===e.pointerId){dragId=null;stopNote()}};b.onpointerup=release;b.onpointercancel=release;b.onlostpointercapture=release}
    else{b.dataset.mode=i-8;b.setAttribute('aria-label',['半音','升调','自然音','降调'][i-8]);b.onpointerdown=e=>{e.preventDefault();if(state.p<.99)return;if(i===8)state.half=!state.half;else state.mode=i===9?1:i===10?0:-1};b.onkeydown=e=>{if(e.key===' '||e.key==='Enter'){e.preventDefault();if(i===8)state.half=!state.half;else state.mode=i===9?1:i===10?0:-1}}}
    $('key-targets').append(b);
  }
  async function sample(midi){
    if(buffers.has(midi))return buffers.get(midi);if(pending.has(midi))return pending.get(midi);
    const promise=fetch(`/audio/${midi}.pcm`).then(r=>{if(!r.ok)throw Error('sample');return r.arrayBuffer()}).then(data=>{audio??=new (window.AudioContext||window.webkitAudioContext)();const pcm=new DataView(data),buffer=audio.createBuffer(1,data.byteLength/2,48000),channel=buffer.getChannelData(0);for(let i=0;i<channel.length;i++)channel[i]=pcm.getInt16(i*2,true)/32768;buffers.set(midi,buffer);return buffer}).finally(()=>pending.delete(midi));pending.set(midi,promise);return promise;
  }
  function loadNatural(){[60,62,64,65,67,69,71,72].forEach(n=>sample(n).catch(()=>{}));if(!soundIndex)fetch('/audio/index.tsv').then(r=>r.text()).then(t=>{soundIndex=new Map(t.trim().split('\n').map(l=>{const n=l.trim().split(/\s+/).map(Number);return[n[0],n]}))}).catch(()=>{})}
  async function playNote(i){
    stopNote();state.active=i;const token=++noteToken,midi=60+[0,2,4,5,7,9,11,12][i]+state.mode*12+(state.half?1:0);
    try{const buffer=await sample(midi);if(token!==noteToken||state.active!==i||state.p<.99)return;await audio.resume();if(token!==noteToken)return;
      const src=audio.createBufferSource(),gain=audio.createGain();src.buffer=buffer;src.loop=true;const meta=soundIndex?.get(midi);src.loopStart=(meta?.[2]??96000)/48000;src.loopEnd=(meta?.[3]??buffer.length)/48000;gain.gain.setValueAtTime(0,audio.currentTime);gain.gain.linearRampToValueAtTime(.65,audio.currentTime+.014);src.connect(gain);gain.connect(audio.destination);src.start();voice={src,gain};
    }catch{toast('暂时无法加载音源')}
  }
  function stopNote(){noteToken++;state.active=-1;if(voice){const old=voice;voice=null;old.gain.gain.cancelScheduledValues(audio.currentTime);old.gain.gain.setTargetAtTime(0,audio.currentTime,.012);old.src.stop(audio.currentTime+.08)}}
  addEventListener('keydown',e=>{if(e.target.matches('input,textarea'))return;if(e.key==='Escape'){if(state.page)closePage();else animate(0)}if(state.p>.99&&/^[1-8]$/.test(e.key)&&!e.repeat){e.preventDefault();playNote(+e.key-1)}});
  addEventListener('keyup',e=>{if(/^[1-8]$/.test(e.key)&&state.active===+e.key-1)stopNote()});
  addEventListener('blur',stopNote);document.addEventListener('visibilitychange',()=>{if(document.hidden){stopNote();video.pause()}else if(state.p>.5)video.play().catch(()=>{})});

  function setTheme(dark){state.dark=dark;document.body.classList.toggle('dark',dark);$('theme').innerHTML=`<svg><use href="#i-${dark?'sun':'moon'}"/></svg>`}
  $('theme').onclick=()=>setTheme(!state.dark);
  const snow=$('snow');for(let i=0;i<20;i++){const f=document.createElement('i');f.style.cssText=`--x:${(i*43.71)%100}%;--duration:${19+i%9}s;--delay:-${(i*3.17)%25}s;width:${i%3?2:3}px;height:${i%3?2:3}px`;snow.append(f)}
  function clock(){const d=new Date(),h=String(d.getHours()).padStart(2,'0'),m=String(d.getMinutes()).padStart(2,'0');$('hour').textContent=h;$('minute').textContent=m;$('system-time').textContent=`${h}:${m}`;$('greeting').textContent=d.getHours()>=5&&d.getHours()<12?'早上好':d.getHours()>=12&&d.getHours()<18?'下午好':'晚上好'}clock();setInterval(clock,15000);
  function toast(message){clearTimeout(toastTimer);$('toast').textContent=message;$('toast').classList.add('show');toastTimer=setTimeout(()=>$('toast').classList.remove('show'),2000)}
  function floating(on){state.floating=on;$('floating').hidden=!on;$('start').querySelector('span').textContent=on?'关闭':'启动';$('running-status').classList.toggle('on',on);$('running-status').querySelector('span').textContent=on?'悬浮窗运行中 · 点击关闭':'悬浮窗已关闭 · 点击开启';if(!on){state.sequence++;stopNote()}}
  $('start').onclick=() =>floating(!state.floating);$('running-status').onclick=$('start').onclick;$('float-close').onclick=()=>floating(false);
  $('float-play').onclick=()=>{if(state.p<.99){toast('进入演练场后即可播放');return}if($('float-state').textContent.includes('演奏中')){state.sequence++;stopNote();$('float-state').textContent='自然音 · 已暂停';return}demoMelody()};
  async function demoMelody(){const id=++state.sequence;state.mode=0;state.half=false;$('float-state').textContent='自然音 · 演奏中';for(const note of [0,0,4,4,5,5,4,3,3,2,2,1,1,0]){if(id!==state.sequence||state.p<.99)return;playNote(note);await new Promise(r=>setTimeout(r,480));if(id!==state.sequence)return;stopNote();await new Promise(r=>setTimeout(r,70))}$('float-state').textContent='自然音 · 已暂停'}

  function showPage(name){state.fromStage=state.p>.1;if(state.fromStage)animate(0);state.page=name;stopNote();renderPage(name);$('subpage').hidden=false;home.inert=true;$('page-back').focus({preventScroll:true})}
  function closePage(returnToStage=true){const back=state.fromStage;state.page=null;state.fromStage=false;$('subpage').hidden=true;if(back&&returnToStage)animate(1);else home.inert=false}
  $('page-back').onclick=()=>closePage();$('wallpapers').onclick=()=>showPage('wallpapers');$('home-settings').onclick=()=>showPage('personalize');
  document.querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>showPage(b.dataset.page));
  function renderPage(name){
    const content=$('page-content');content.innerHTML='';$('page-title').textContent={wallpapers:'演练场壁纸',settings:'设置',personalize:'首页设置',library:'曲库',discover:'发现'}[name];
    if(name==='wallpapers'){
      for(const [id,title,src] of [['day','窗旁の伊蕾娜','/assets/poster.jpg'],['sunset','暮色与远山','/assets/sunset.jpg']]){
        const b=document.createElement('button');b.className='page-card wallpaper-card'+(state.wallpaper===id?' selected':'');b.innerHTML=`<img src="${src}" alt=""><span><b>${title}</b><small>${state.wallpaper===id?'使用中':'轻触选择'}</small></span>${state.wallpaper===id?'<svg><use href="#i-check"/></svg>':''}`;
        b.onclick=()=>{state.wallpaper=id;video.style.display=id==='day'?'block':'none';$('wallpaper-image').style.display=id==='sunset'?'block':'none';renderPage(name)};content.append(b);
      }
      const go=document.createElement('button');go.className='page-action';go.textContent='进入演练场';go.onclick=()=>{closePage(false);animate(1)};content.append(go);
    }else if(name==='settings'){
      for(const [label,on,fn] of [['深色模式',state.dark,()=>setTheme(!state.dark)],['飘雪',!snow.hidden,()=>snow.hidden=!snow.hidden]]){const row=document.createElement('div');row.className='page-card setting-row';row.innerHTML=`<span>${label}</span><button class="toggle ${on?'on':''}" aria-label="${label}" aria-pressed="${on}"></button>`;row.querySelector('button').onclick=()=>{fn();renderPage(name)};content.append(row)}
      const wall=document.createElement('button');wall.className='page-card setting-row';wall.innerHTML='<span>演练场壁纸</span><svg><use href="#i-arrow"/></svg>';wall.onclick=()=>renderPage('wallpapers');content.append(wall);
    }else if(name==='personalize'){
      const box=document.createElement('div');box.className='page-card';box.innerHTML='<label style="font-size:13px;display:block;margin-bottom:12px" for="quote-input">首页一言</label><input class="page-input" id="quote-input" maxlength="30"><button class="page-action">完成</button>';content.append(box);$('quote-input').value=$('quote').textContent;box.querySelector('button').onclick=()=>{$('quote').textContent=$('quote-input').value.trim()||'天空你是否知晓一切？';closePage()};
    }else{
      const button=document.createElement('button');button.className='page-card song-row';button.innerHTML='<span class="song-art"><svg><use href="#i-note"/></svg></span><span><b>星光练习</b><small>口琴 · C 调</small></span><svg class="song-play"><use href="#i-play"/></svg>';
      button.onclick=()=>{closePage(false);floating(true);animate(1);state.pendingMelody=true};content.append(button);
    }
  }
  // The semantic button remains interactive; Canvas draws the quiet label and aperture.
  document.querySelectorAll('.portal-copy,.portal-arrow').forEach(el=>el.style.visibility='hidden');
  video.style.display='none';$('wallpaper-image').style.display='block';
  measure();raf=requestAnimationFrame(tick);
})();
