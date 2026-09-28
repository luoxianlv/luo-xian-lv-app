import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

// 执行实际引擎的视频挂载代码，防止手机通道重新引入片尾第二路解码器。
const engine = readFileSync(new URL('../app/src/main/assets/wallpaperengine/webwallgl.mjs', import.meta.url), 'utf8');
const start = engine.indexOf('function mountVideoDom(');
const end = engine.indexOf('function applyDecodeHint(', start);
assert.ok(start >= 0 && end > start);
for (const loop of [true, false]) {
  const videos = [];
  let plays = 0, frames = 0;
  const container = {appendChild() {}};
  const sandbox = {
    document: {createElement(type) {
      assert.equal(type, 'video');
      const listeners = new Map();
      const video = {style:{}, readyState:0, paused:true, currentTime:0,
        addEventListener(name, callback) { listeners.set(name, callback); },
        play() { plays++; this.paused = false; return Promise.resolve(); },
        listeners};
      videos.push(video);
      return video;
    }},
    clear() {}, resolveVideoContainer:()=>container,
    fitObjectFit:()=>({objectFit:'cover', background:'black'}),
    getComputedStyle:()=>({position:'relative'}),
    applyDecodeHint() {}, attachVideoSpectrum() {}, reportDiag() {},
    markFrame() { frames++; }, performance:{now:()=>1},
    requestAnimationFrame:()=>1, cancelAnimationFrame() {},
    createLoopingVideo() { throw Error('手机视频不应创建 A/B 双缓冲'); },
    supportsWebCodecsVideo() { throw Error('声音开关不应改变解码后端'); },
  };
  const mount = vm.runInNewContext(engine.slice(start, end) + '\nmountVideoDom', sandbox);
  const rt = {paused:true};
  mount(rt, {src:'/fixture.mp4', loop, muted:true, videoAudioControls:true});
  assert.equal(videos.length, 1);
  const video = videos[0];
  assert.equal(video.loop, loop);
  assert.equal(video.autoplay, false);
  assert.equal(video.muted, true);
  video.listeners.get('canplay')();
  assert.equal(plays, 0, '后台加载完成不能自行播放');
  rt.paused = false;
  video.listeners.get('canplay')();
  assert.equal(plays, 1);
  video.listeners.get('loadeddata')();
  assert.equal(frames, 1);
  assert.equal(rt.video, video);
  assert.equal(rt.videoPairs?.length ?? 0, 0);
}
console.log('视频挂载：单解码器、原生循环、保留静音、后台禁止自播、首帧通知通过');
