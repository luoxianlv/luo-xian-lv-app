package app.luoxianlv.host;

import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import app.luoxianlv.hot.contract.NativePage;
import app.luoxianlv.hot.contract.NativePlaybackSession;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.PracticeBridge;
import app.luoxianlv.hot.contract.SharedInput;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 用真实业务会话模拟缺失/迟到回调，不注入实际手势，也不把模拟故障当作真机根因。 */
public final class PlaybackGestureInstrumentation extends Instrumentation {
  private static final String FIXED_KEY = "fixed_harmonica_keys";
  private static final long NOTE_MS = 200, CALLBACK_BUDGET_MS = 1500, BLOCK_MS = 2600;
  private SharedPreferences experimental;
  private Context businessContext;
  private SharedInput.Bridge input;
  private final ArrayList<Fixture> fixtures = new ArrayList<>();

  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

  @Override public void onStart() {
    Bundle report = new Bundle();
    boolean success = false, saved = false, oldFixed = false, fixedPresent = false;
    String oldMode = SharedInput.ACCESSIBILITY;
    try {
      require(getTargetContext().getPackageName().endsWith(".debug"), "仅允许运行 Debug 宿主");
      getTargetContext().startActivity(new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
      await("真实业务尚未准备好", () -> {
        try { return Bootstrap.source().prepared != null; }
        catch (IllegalStateException pending) { return false; }
      });
      await("输入桥尚未初始化", () -> SharedInput.current() != null
          && SharedInput.current().state().getBoolean("initialized"));
      var actual = PlaybackBridge.current();
      require(actual == null || !actual.query("state").getBoolean("playing"),
          "请先暂停真实演奏，测试不会抢占正在使用的输入");
      require(!PracticeBridge.active(), "请先退出前台演练场，避免测试使用演练场路由");
      input = SharedInput.current();
      oldMode = input.state().getString("mode", SharedInput.ACCESSIBILITY);
      main(() -> {
        businessContext = Bootstrap.source().prepared.context(getTargetContext());
        try {
          Class<?> kv = Class.forName("app.luoxianlv.shared.Kv", true,
              Bootstrap.source().prepared.classLoader());
          Object instance = kv.getField("INSTANCE").get(null);
          experimental = (SharedPreferences) kv.getMethod("of", Context.class, String.class)
              .invoke(instance, businessContext, "experimental_options");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
      });
      fixedPresent = experimental.contains(FIXED_KEY);
      oldFixed = experimental.getBoolean(FIXED_KEY, false);
      saved = true;
      require(experimental.edit().putBoolean(FIXED_KEY, true).commit(), "无法开启测试固定布局");
      input.select(SharedInput.ACCESSIBILITY);
      await("未切换到传统无障碍路径", () -> SharedInput.ACCESSIBILITY.equals(input.state().getString("mode")));

      timelySuccessContinues();
      timelyCancelOrReject(Behavior.TIMELY_CANCEL, "手势被系统取消");
      timelyCancelOrReject(Behavior.REJECT, "系统拒绝手势");
      missingAndRetiredCallbacks();
      blockedMainCallback();
      synchronousLateCallback(true);
      synchronousLateCallback(false);
      report.putString("stream", "无障碍等待保护验收通过：及时成功连续演奏、正常取消/拒绝文案、缺失回调超时、进度回退、旧回调隔离、用户暂停、主线程排队及同步提交过慢；未注入系统触摸，不代表真机根因。\n");
      success = true;
    } catch (Throwable failure) {
      report.putString("stream", "无障碍等待保护验收失败：" + failure + "\n");
      report.putString("error", android.util.Log.getStackTraceString(failure));
    } finally {
      try {
        for (Fixture fixture : fixtures) fixture.close();
        if (saved) {
          var editor = experimental.edit();
          if (fixedPresent) editor.putBoolean(FIXED_KEY, oldFixed);
          else editor.remove(FIXED_KEY);
          require(editor.commit(), "无法恢复原固定布局偏好");
          String mode = oldMode;
          input.select(mode);
          await("无法恢复原输入模式", () -> mode.equals(input.state().getString("mode")));
        }
      } catch (Throwable cleanup) {
        success = false;
        report.putString("cleanup", android.util.Log.getStackTraceString(cleanup));
      }
    }
    finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
  }

  private void timelySuccessContinues() {
    Fixture fixture = create(Behavior.TIMELY_SUCCESS);
    fixture.play();
    await("及时成功回调没有连续推进三个音符", () -> fixture.binding.callbacksSent.get() >= 3);
    Bundle running = fixture.state();
    require(running.getBoolean("playing") && running.getString("error") == null,
        "及时成功回调被误判为失败或超时");
    require(fixture.binding.calls.get() >= 3 && fixture.binding.screenshots.get() == 0,
        "正常演奏没有使用真实固定布局的连续手势路径");
    fixture.pause();
    int sent = fixture.binding.calls.get();
    long position = fixture.state().getLong("positionMs");
    SystemClock.sleep(250);
    Bundle stopped = fixture.state();
    require(!stopped.getBoolean("playing") && !fixture.busy()
        && stopped.getString("error") == null && stopped.getLong("positionMs") == position,
        "正常演奏暂停后仍被旧成功回调更改状态");
    require(fixture.binding.calls.get() == sent, "正常演奏暂停后仍继续发送音符");
    fixture.close();
  }

  private void timelyCancelOrReject(Behavior behavior, String expected) {
    Fixture fixture = create(behavior);
    fixture.play();
    await("取消或拒绝没有正常停止播放", () -> {
      Bundle state = fixture.state();
      return !state.getBoolean("playing") && state.getString("error", "").contains(expected);
    });
    require(!fixture.busy() && fixture.binding.calls.get() == 1,
        "正常取消或拒绝后没有解除等待，或继续派发了音符");
    SystemClock.sleep(NOTE_MS + CALLBACK_BUDGET_MS + 250);
    Bundle stopped = fixture.state();
    require(stopped.getString("error", "").contains(expected)
        && !stopped.getString("error", "").contains("超时") && !stopped.getBoolean("playing"),
        "正常取消或拒绝被旧定时任务改成了超时");
    require(fixture.binding.calls.get() == 1, "正常取消或拒绝后自动重启了播放");
    fixture.close();
  }

  private void missingAndRetiredCallbacks() {
    Fixture fixture = create(Behavior.MISSING);
    fixture.play();
    GestureRequest expired = fixture.binding.request(0);
    require(expired.durationMs > 0 && expired.durationMs <= NOTE_MS, "首音不是预期的短手势");
    awaitTimeout(fixture);
    require(SystemClock.uptimeMillis() - expired.submittedAt >= CALLBACK_BUDGET_MS,
        "没有等待回调预算就提前报告超时");
    require(fixture.binding.calls.get() == 1, "回调缺失时仍然继续派发后续音符");
    SystemClock.sleep(350);

    // 新请求仍等待自身结果；旧 true/false 回调都不能完成新请求或暂停新播放。
    fixture.play();
    require(fixture.binding.calls.get() == 2, "超时后无法手动重新播放");
    long before = fixture.state().getLong("positionMs");
    main(() -> { expired.callback.completed(true); expired.callback.completed(false); });
    Bundle running = fixture.state();
    require(running.getBoolean("playing") && fixture.busy(), "旧回调完成或中断了新请求");
    require(running.getString("error") == null && fixture.binding.calls.get() == 2,
        "旧回调产生错误或跳过音符继续派发");
    require(running.getLong("positionMs") - before < 200, "旧回调改变了新播放的进度");

    // 用户在新请求截止前暂停。定时任务和任何迟到回调都不能自动重开播放。
    GestureRequest paused = fixture.binding.request(1);
    fixture.pause();
    long position = fixture.state().getLong("positionMs");
    SystemClock.sleep(NOTE_MS + CALLBACK_BUDGET_MS + 250);
    main(() -> { paused.callback.completed(true); paused.callback.completed(false); });
    Bundle stopped = fixture.state();
    require(!stopped.getBoolean("playing") && !fixture.busy(), "暂停后的旧等待重新启动了播放");
    require(stopped.getString("error") == null && stopped.getLong("positionMs") == position,
        "暂停后的旧超时或回调更改了状态");
    require(fixture.binding.calls.get() == 2, "用户暂停后仍派发了新手势");
    fixture.close();
  }

  /** 回调按原定时刻入队，但主线程被模拟阻塞；恢复后仍必须核验真实截止时间。 */
  private void blockedMainCallback() {
    Fixture fixture = create(Behavior.BLOCK_MAIN);
    fixture.play();
    awaitTimeout(fixture);
    require(fixture.binding.calls.get() == 1, "主线程恢复后迟到回调推进了后续音符");
    fixture.close();
  }

  /** 同步提交耗时超过截止时间；在提交返回前收到的回调也不能冒充按时完成。 */
  private void synchronousLateCallback(boolean success) {
    Fixture fixture = create(success ? Behavior.SYNC_SUCCESS : Behavior.SYNC_CANCEL);
    fixture.play();
    awaitTimeout(fixture);
    require(fixture.binding.calls.get() == 1, "同步提交过慢后仍派发了后续音符");
    fixture.close();
  }

  private void awaitTimeout(Fixture fixture) {
    await("无回调或迟到回调没有按预算安全停止", () -> {
      Bundle state = fixture.state();
      return !state.getBoolean("playing") && !state.getBoolean("preparing")
          && state.getString("error", "").contains("超时");
    });
    Bundle state = fixture.state();
    require(state.getString("error", "").contains("无障碍响应超时"), "未报告准确的无障碍响应超时");
    require(!fixture.busy(), "超时后 busy 没有解除");
    require(state.getLong("positionMs") >= 0 && state.getLong("positionMs") <= NOTE_MS,
        "超时等待跳过了后续音符，未退回本次提交进度");
    require(!state.getBoolean("floatingVisible") && fixture.binding.screenshots.get() == 0,
        "固定布局测试创建了浮窗或请求了截图");
  }

  private Fixture create(Behavior behavior) {
    Fixture fixture = new Fixture(behavior);
    fixtures.add(fixture);
    main(() -> {
      fixture.session = Bootstrap.source().factory.playback();
      require(fixture.session.getClass().getClassLoader() == Bootstrap.source().prepared.classLoader(),
          "测试没有使用真实独立业务加载器");
      fixture.session.prepareRecovery(businessContext, fixture.binding, testSnapshot(), new NativePage.Ready() {
        @Override public void ready() { fixture.ready.set(true); }
        @Override public void failed(Throwable failure) { fixture.failure.set(failure); }
      });
    });
    await("本地测试曲目未准备好", () -> fixture.ready.get() || fixture.failure.get() != null);
    require(fixture.failure.get() == null, "本地测试曲目准备失败：" + fixture.failure.get());
    main(() -> { fixture.binding.enabled = true; fixture.session.activate(); });
    require(fixture.state().getLong("durationMs") == 10000, "测试曲目时长不正确");
    return fixture;
  }

  private static Bundle testSnapshot() {
    Bundle song = new Bundle();
    song.putInt("schema", 1);
    song.putString("id", "instrument-gesture-timeout");
    song.putString("title", "无障碍回调等待测试");
    StringBuilder score = new StringBuilder();
    for (int i = 0; i < 50; i++) score.append("1 ");
    song.putString("score", score.toString());
    song.putInt("bpm", 300);
    song.putString("source", "仪器内存测试曲");
    Bundle state = new Bundle();
    state.putInt("schema", 1);
    state.putBundle("song", song);
    state.putString("mode", "NATURAL");
    state.putBoolean("half", false);
    state.putBoolean("fixed", true);
    state.putBoolean("floating", false);
    state.putFloat("speed", 1f);
    state.putLong("position", 0);
    return state;
  }

  private final class Fixture {
    final FakeBinding binding;
    final AtomicBoolean ready = new AtomicBoolean();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    NativePlaybackSession session;
    boolean closed;

    Fixture(Behavior behavior) { binding = new FakeBinding(behavior); }
    void play() { main(() -> session.command("play", new Bundle())); }
    void pause() { main(() -> session.command("pause", new Bundle())); }
    Bundle state() {
      AtomicReference<Bundle> result = new AtomicReference<>();
      main(() -> result.set(session.query("state")));
      return result.get();
    }
    boolean busy() {
      AtomicBoolean value = new AtomicBoolean();
      main(() -> {
        try {
          Field field = session.getClass().getDeclaredField("busy");
          field.setAccessible(true);
          value.set(field.getBoolean(session));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
      });
      return value.get();
    }
    void close() {
      if (closed || session == null) return;
      closed = true;
      main(() -> { binding.enabled = false; session.close(); });
      await("测试播放会话没有释放后台任务", session::released);
    }
  }

  private enum Behavior { TIMELY_SUCCESS, TIMELY_CANCEL, REJECT, MISSING, BLOCK_MAIN, SYNC_SUCCESS, SYNC_CANCEL }

  private static final class GestureRequest {
    final AccessibilityBinding.GestureCallback callback;
    final long durationMs, submittedAt;
    GestureRequest(AccessibilityBinding.GestureCallback callback, long durationMs) {
      this.callback = callback;
      this.durationMs = durationMs;
      submittedAt = SystemClock.uptimeMillis();
    }
  }

  private static final class FakeBinding implements AccessibilityBinding {
    final Behavior behavior;
    final Handler main = new Handler(Looper.getMainLooper());
    final AtomicInteger calls = new AtomicInteger(), screenshots = new AtomicInteger(), callbacksSent = new AtomicInteger();
    final ArrayList<GestureRequest> requests = new ArrayList<>();
    volatile boolean enabled;
    FakeBinding(Behavior behavior) { this.behavior = behavior; }
    @Override public boolean current() { return enabled; }
    @Override public boolean gesture(GestureDescription description, GestureCallback callback) {
      require(enabled && Looper.myLooper() == Looper.getMainLooper(), "模拟输入未在当前主线程提交");
      GestureRequest request = new GestureRequest(callback, description.getStroke(0).getDuration());
      synchronized (requests) { requests.add(request); }
      calls.incrementAndGet();
      if (behavior == Behavior.REJECT) return false;
      if (behavior == Behavior.TIMELY_SUCCESS || behavior == Behavior.TIMELY_CANCEL) {
        main.postDelayed(() -> {
          callbacksSent.incrementAndGet();
          callback.completed(behavior == Behavior.TIMELY_SUCCESS);
        }, behavior == Behavior.TIMELY_SUCCESS ? request.durationMs : 30);
      } else if (behavior == Behavior.BLOCK_MAIN) {
        main.post(() -> SystemClock.sleep(BLOCK_MS));
        main.postDelayed(() -> callback.completed(true), request.durationMs);
      } else if (behavior == Behavior.SYNC_SUCCESS || behavior == Behavior.SYNC_CANCEL) {
        SystemClock.sleep(BLOCK_MS);
        callback.completed(behavior == Behavior.SYNC_SUCCESS);
      }
      return true;
    }
    GestureRequest request(int index) {
      synchronized (requests) { return requests.get(index); }
    }
    @Override public void screenshot(int displayId, ScreenshotCallback callback) {
      screenshots.incrementAndGet();
      callback.failure(-1);
    }
    @Override public void foreground(boolean enabled) {
      require(!enabled, "测试会话不应显示前台窗口");
    }
  }

  private void main(Runnable action) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runOnMainSync(() -> {
      try { action.run(); }
      catch (Throwable error) { failure.set(error); }
    });
    if (failure.get() != null) throw new AssertionError("无障碍等待主线程检查失败", failure.get());
  }

  private static void await(String message, BooleanSupplier condition) {
    long until = SystemClock.elapsedRealtime() + 10000;
    while (!condition.getAsBoolean()) {
      require(SystemClock.elapsedRealtime() < until, message);
      SystemClock.sleep(20);
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
