import {test} from 'node:test';
import assert from 'node:assert/strict';
import {SceneDate,setSceneTime} from '../app/src/main/assets/wallpaperengine/clock.mjs';

test('provided time reaches both Date constructor and Date.now without changing native time', () => {
  const real = Date.now();
  for (const minute of [0,359,360,599,600,1019,1020,1139,1140,1439]) {
    setSceneTime(minute);
    for (const date of [new SceneDate(), new Date(SceneDate.now())]) {
      assert.equal(date.getHours()*60+date.getMinutes(),minute);
      assert.equal(date.getSeconds(),0);
    }
  }
  assert.ok(Math.abs(Date.now()-real)<1000);
});
test('explicit dates, static helpers, real time and invalid input', () => {
  assert.equal(new SceneDate(0).getTime(),0);
  assert.equal(SceneDate.UTC(2000,0,1),Date.UTC(2000,0,1));
  for (const value of [-1,1440,1.5,NaN,'08:00']) assert.throws(() => setSceneTime(value));
  setSceneTime(null);
  assert.ok(Math.abs(new SceneDate().getTime()-Date.now())<100);
});
