import assert from 'node:assert/strict';
import {bindWallpaperLifecycle} from '../app/src/main/assets/wallpaperengine/lifecycle.mjs';
let visibility;
const doc = {hidden:false, addEventListener:(_,f)=>visibility=f,
  removeEventListener:(_,f)=>{if (visibility) assert.equal(f,visibility);visibility=null;}};
const host = {};
const calls = [];
const scene = {paused:false, pause(){calls.push('pause');this.paused=true;},
 resume(){calls.push('resume');this.paused=false;},destroy(){calls.push('destroy');}};
const lifecycle = bindWallpaperLifecycle(host,doc);
// Suspension before async mount must survive attachment.
host.setWallpaperSuspended(true);
lifecycle.attach(scene);
host.setWallpaperSuspended(true);
assert.deepEqual(calls,['pause']);
// Visibility alone must never undo native suspension.
doc.hidden=true;visibility();doc.hidden=false;visibility();
assert.deepEqual(calls,['pause']);
host.setWallpaperSuspended(false);
assert.deepEqual(calls,['pause','resume']);
doc.hidden=true;visibility();host.setWallpaperSuspended(false);
assert.equal(scene.paused,true);
doc.hidden=false;visibility();
assert.deepEqual(calls,['pause','resume','pause','resume']);
lifecycle.close();lifecycle.close();
assert.equal(calls.filter(x=>x==='destroy').length,1);
assert.equal(visibility,null);
console.log('Wallpaper lifecycle: pre-mount pause, idempotency, visibility/native overlap, resume and cleanup passed');

// Exercise the shipped engine controller, including its direct-video fallback.
const {readFileSync} = await import('node:fs');
const engine = readFileSync(new URL('../app/src/main/assets/wallpaperengine/webwallgl.mjs', import.meta.url),'utf8');
const begin = engine.indexOf('  const instance = {');
const end = engine.indexOf('    setFit(fit)',begin);
assert.ok(begin >= 0 && end > begin);
const controller = new Function('rt','resetFrameMeter', engine.slice(begin,end)+'}; return instance;');
let activeResumes=0, hiddenResumes=0, pauses=0;
const rt = {paused:false,cfg:{type:'scene'},videoPairs:[
  {pause(){},resume(){activeResumes++;}}, {pause(){},resume(){hiddenResumes++;}}],
  sceneCtl:{pause(){pauses++;},resume(){activeResumes++;}}};
const instance = controller(rt,()=>{});
instance.pause();instance.pause();instance.resume();instance.resume();
assert.equal(pauses,1);
assert.equal(activeResumes,1);
assert.equal(hiddenResumes,0,'Hidden layers must not allocate decoders on resume');
rt.cfg.type='video';rt.sceneCtl=null;
instance.pause();instance.resume();
assert.equal(activeResumes,2);
assert.equal(hiddenResumes,1,'Direct video pairs still need the outer controller');
console.log('Engine: idempotent pause/resume, hidden scene layers and direct video resume passed');
