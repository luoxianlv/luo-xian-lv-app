let minute = null;
export function setSceneTime(value) {
  if (value !== null && (!Number.isInteger(value) || value < 0 || value >= 1440)) throw Error('场景时间必须为 0～1439 的整数分钟或 null');
  minute = value;
}
function now() {
  if (minute === null) return Date.now();
  const date = new Date();
  date.setHours(Math.floor(minute / 60), minute % 60, 0, 0);
  return date.getTime();
}
// 仅向壁纸脚本注入时间；动画计时和浏览器时钟仍使用真实时间。
export const SceneDate = new Proxy(Date, {
  construct(target, args) { return Reflect.construct(target, args.length ? args : [now()]); },
  apply() { return new Date(now()).toString(); },
  get(target, key) { return key === 'now' ? now : Reflect.get(target, key); },
});
