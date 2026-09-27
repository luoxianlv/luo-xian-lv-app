let minute = null;
export function setSceneTime(value) {
  if (value !== null && (!Number.isInteger(value) || value < 0 || value >= 1440)) throw Error('Invalid scene time');
  minute = value;
}
function now() {
  if (minute === null) return Date.now();
  const date = new Date();
  date.setHours(Math.floor(minute / 60), minute % 60, 0, 0);
  return date.getTime();
}
// Inject only into wallpaper scripts. Animation timing and browser clocks stay real.
export const SceneDate = new Proxy(Date, {
  construct(target, args) { return Reflect.construct(target, args.length ? args : [now()]); },
  apply() { return new Date(now()).toString(); },
  get(target, key) { return key === 'now' ? now : Reflect.get(target, key); },
});
