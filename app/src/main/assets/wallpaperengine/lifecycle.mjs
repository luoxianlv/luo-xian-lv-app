/** Native suspension and document visibility are independent reasons to pause. */
export function bindWallpaperLifecycle(host, doc) {
  let scene = null;
  let suspended = !!host.wallpaperSuspended;
  let applied = false;
  const sync = () => {
    const paused = suspended || doc.hidden;
    if (!scene || paused === applied) return;
    applied = paused;
    if (paused) scene.pause(); else scene.resume();
  };
  host.setWallpaperSuspended = value => {
    host.wallpaperSuspended = suspended = !!value;
    sync();
  };
  doc.addEventListener('visibilitychange', sync);
  return {
    attach(value) { scene = value; applied = !!scene.paused; sync(); },
    close() {
      doc.removeEventListener('visibilitychange', sync);
      scene?.destroy();
      scene = null;
    },
  };
}
