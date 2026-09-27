// A hidden time-of-day layer must release its decoder. Four 4K layers plus
// double-buffered looping can exhaust Android codecs even while paused.
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
