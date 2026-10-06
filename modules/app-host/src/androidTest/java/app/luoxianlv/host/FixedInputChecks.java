package app.luoxianlv.host;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import app.luoxianlv.hot.contract.NativePage;
import app.luoxianlv.hot.contract.NativePlaybackSession;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 两种真实输入模式均从实验性偏好读取固定布局，关闭无障碍也能演奏。 */
public final class FixedInputChecks {
  public static void run(Instrumentation test) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    while (Bootstrap.source().prepared == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
    AtomicReference<Context> preparedContext = new AtomicReference<>();
    test.runOnMainSync(() -> preparedContext.set(Bootstrap.source().prepared.context(test.getTargetContext())));
    Context context = preparedContext.get();
    Class<?> kv = Class.forName("app.luoxianlv.shared.Kv", true, Bootstrap.source().prepared.classLoader());
    SharedPreferences prefs = (SharedPreferences) kv.getMethod("of", Context.class, String.class)
        .invoke(kv.getField("INSTANCE").get(null), context, "experimental_options");
    String key = "fixed_harmonica_keys";
    boolean present = prefs.contains(key), previous = prefs.getBoolean(key, false);
    AtomicBoolean enabled = new AtomicBoolean();
    AtomicBoolean ready = new AtomicBoolean();
    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicReference<NativePlaybackSession> session = new AtomicReference<>();
    AtomicReference<Bundle> state = new AtomicReference<>();
    try {
      if (!prefs.edit().putBoolean(key, true).commit()) throw new AssertionError("无法开启固定布局");
      test.runOnMainSync(() -> {
        NativePlaybackSession value = Bootstrap.source().factory.playback(); session.set(value);
        Bundle song = new Bundle(); song.putInt("schema", 1); song.putString("id", "fixed-input-check");
        song.putString("title", "固定按键检查"); song.putString("score", "1 2 3 4 5 6 7 8"); song.putInt("bpm", 120);
        Bundle snapshot = new Bundle(); snapshot.putInt("schema", 1); snapshot.putBundle("song", song);
        snapshot.putString("mode", "NATURAL"); snapshot.putFloat("speed", 1); snapshot.putBoolean("floating", false);
        // 故意不在恢复快照设置 fixed，确保播放时读取当前实验性开关。
        value.prepareRecovery(context, new AccessibilityBinding() {
          public boolean current() { return enabled.get(); }
          public boolean gesture(android.accessibilityservice.GestureDescription d, GestureCallback c) {
            throw new AssertionError("固定布局误用了无障碍手势");
          }
          public void screenshot(int d, ScreenshotCallback c) { throw new AssertionError("固定布局发起了截图"); }
          public void foreground(boolean enabled) {}
        }, snapshot, new NativePage.Ready() {
          public void ready() { ready.set(true); }
          public void failed(Throwable failure) { error.set(failure); }
        });
      });
      deadline = SystemClock.elapsedRealtime() + 10000;
      while (!ready.get() && error.get() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
      if (!ready.get() || error.get() != null) throw new AssertionError("固定布局曲目准备失败", error.get());
      test.runOnMainSync(() -> { enabled.set(true); session.get().activate(); session.get().command("play", new Bundle()); });
      SystemClock.sleep(1200);
      test.runOnMainSync(() -> state.set(session.get().query("state")));
      if (!state.get().getBoolean("playing") || state.get().getString("error") != null
          || state.get().getLong("positionMs") < 500) throw new AssertionError("固定布局真实演奏未推进：" + state.get());
    } finally {
      test.runOnMainSync(() -> { enabled.set(false); if (session.get() != null) session.get().close(); });
      deadline = SystemClock.elapsedRealtime() + 5000;
      while (session.get() != null && !session.get().released() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
      var editor = prefs.edit(); if (present) editor.putBoolean(key, previous); else editor.remove(key);
      if (!editor.commit()) throw new AssertionError("没有恢复实验性偏好");
    }
  }
  private FixedInputChecks() {}
}
