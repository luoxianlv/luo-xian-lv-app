/** 音量只热更新，不重载壁纸；预热、隐藏和尚未收到原生许可时均静音。 */
export function bindWallpaperAudio(host, doc) {
  let scene = null;
  let enabled = false;
  let applied = 0;
  const sync = () => {
    const volume = enabled && !doc.hidden && !host.wallpaperSuspended ? 1 : 0;
    if (!scene || applied === volume) return;
    scene.setVolume(volume);
    applied = volume;
  };
  host.setWallpaperSoundEnabled = value => { enabled = !!value; sync(); };
  doc.addEventListener('visibilitychange', sync);
  return {
    attach(value) { scene = value; sync(); },
    close() { enabled = false; sync(); scene = null; doc.removeEventListener('visibilitychange', sync); },
  };
}
