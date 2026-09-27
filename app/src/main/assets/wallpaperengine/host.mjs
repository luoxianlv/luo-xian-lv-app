import {mount, bytesSource, httpSource} from './webwallgl.mjs';
import {setSceneTime} from './clock.mjs';
const suppliedMinute = new URLSearchParams(location.search).get('minute');
setSceneTime(suppliedMinute === null ? null : Number(suppliedMinute));
window.setWallpaperTime = setSceneTime;
let scene;
let videoFrameReady = false;
window.wallpaperState = 'loading';
function failure(error) {
  console.error('Wallpaper: ' + String(error));
  window.wallpaperState = 'error';
  document.title = 'wallpaper:error';
}
try {
  const projectResponse = await fetch('/project/project.json', {cache:'no-store'});
  if (!projectResponse.ok) throw Error('project.json missing');
  const project = await projectResponse.json();
  project.type = String(project.type).toLowerCase();
  let source;
  if (project.type === 'scene') {
    const response = await fetch('/project/scene.pkg', {cache:'no-store'});
    if (!response.ok) throw Error('scene.pkg missing');
    source = bytesSource(await response.arrayBuffer(), project, String(project.workshopid || 'local'));
  } else {
    const file = String(project.file || (project.type === 'web' ? 'index.html' : '')).replaceAll('\\','/');
    const entry = new URL('/project/' + file.split('/').map(encodeURIComponent).join('/'), location.origin).href;
    source = {...httpSource(new URL('/project/',location.origin).href, {cache:'no-store'}),
      project: async () => project, webEntry: async () => ({url:entry}), mediaEntry: async () => ({url:entry,type:project.type})};
  }
  scene = await mount(document.getElementById('scene'), {
    source, webSandbox: 'strict',
    properties: Object.fromEntries(Object.entries(project.general?.properties || {}).map(([key, prop])=>[key,prop.value])),
    fit: 'cover', fps: 30, renderDpr: Math.min(devicePixelRatio, 1280 / Math.max(innerWidth, innerHeight)),
    volume: 0, audio: null, media: null,
    quality: {antiAliasing:'off', particles:'low', postProcessing:'high'},
    onDiagnostic: (message, level) => {
      if (message.includes('video tex ready ')) videoFrameReady = true;
      console.log('Wallpaper ' + level + ': ' + message);
    },
    onError: failure,
  });
  if (project.type === 'scene' && document.querySelector('video[src]')) {
    const deadline = performance.now() + 45000;
    while (!videoFrameReady && performance.now() < deadline) await new Promise(resolve => setTimeout(resolve, 100));
    if (!videoFrameReady) throw Error('Video first frame timed out');
  }
  window.wallpaperState = 'ready';
  document.title = 'wallpaper:ready';
  window.wallpaperStats = () => scene.stats;
  document.addEventListener('visibilitychange', () => document.hidden ? scene.pause() : scene.resume());
  window.stopWallpaper = () => { scene.destroy(); scene = null; };
} catch (error) { failure(error); }
