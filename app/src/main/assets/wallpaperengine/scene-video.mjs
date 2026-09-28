// 隐藏视频图层须释放解码器；多个 4K 图层叠加双缓冲循环，即使暂停也可能耗尽 Android 解码资源。
export function createSceneVideo(src, options) {
  const active = document.createElement('video');
  const standby = document.createElement('video'); // API placeholder, never allocates a decoder.
  active.muted = options.muted;
  active.playsInline = true;
  active.loop = true;
  active.preload = 'auto';
  let position = 0, destroyed = false, playPending = false, generation = 0;
  active.addEventListener('loadedmetadata', () => {
    if (position > 0 && position < active.duration) active.currentTime = position;
  });
  return {
    active, standby,
    resume() {
      if (destroyed) return;
      if (!active.hasAttribute('src')) active.src = src;
      if (!active.paused || playPending) return;
      playPending = true;
      const request = generation;
      active.play().catch(() => {}).finally(() => { if (request === generation) playPending = false; });
    },
    pause() {
      if (!active.hasAttribute('src')) return;
      position = active.currentTime;
      generation++; playPending = false;
      active.pause(); active.removeAttribute('src'); active.load();
    },
    setVolume(volume) { active.volume = Math.min(1,Math.max(0,volume)); active.muted = volume <= 0; },
    destroy() { this.pause(); destroyed = true; active.remove(); standby.remove(); },
  };
}
