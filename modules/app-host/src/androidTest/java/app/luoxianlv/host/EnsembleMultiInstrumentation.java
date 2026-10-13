package app.luoxianlv.host;

import android.accessibilityservice.GestureDescription;
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
import app.luoxianlv.hot.contract.PlaybackPort;
import app.luoxianlv.hot.contract.SharedInput;
import app.luoxianlv.hot.contract.SharedRealtime;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** 六个模拟器独立运行真实业务、WS、校时和播放器；仅末端系统触摸由可计时的绑定代替。 */
public final class EnsembleMultiInstrumentation extends Instrumentation {
  private static final String FIXED = "fixed_harmonica_keys";
  private NativePlaybackSession session;
  private android.view.View background;
  private int backgroundVisibility;
  private final Recorder recorder = new Recorder();

  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

  @Override public void onStart() {
    Bundle output = new Bundle();
    boolean passed = false, preferencesSaved = false, hadFixed = false, oldFixed = false;
    SharedPreferences experimental = null;
    String oldMode = null;
    PlaybackPort previous = null;
    AutoCloseable port = null, observer = null;
    AutoCloseable localProxy = LocalFixtureProxy.direct();
    try {
      Context target = getTargetContext();
      require(target.getPackageName().endsWith(".debug"), "仅限 Debug 模拟器");
      File configFile = new File(target.getFilesDir(), "ensemble-multi-config.json");
      require(configFile.isFile() && configFile.length() < 8192, "缺少本机测试配置");
      String config = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
      JSONObject settings = new JSONObject(config);
      Files.delete(configFile.toPath());
      var activity = startActivitySync(new Intent().setClassName(target, "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
      await("业务加载", () -> { try { return Bootstrap.source().prepared != null; } catch (Exception pending) { return false; } });
      await("宿主连接桥", () -> SharedRealtime.current() != null && SharedInput.current() != null);
      // 游戏时首页在后台；仅停掉测试首页绘制，不替换业务、校时或执行器。
      await("首页首帧", () -> {
        AtomicBoolean shown = new AtomicBoolean();
        main(() -> { var page=findPage(activity.findViewById(android.R.id.content)); shown.set(page!=null && page.readyFrame() && page.getChildCount()==1); });
        return shown.get();
      });
      main(() -> { var page=findPage(activity.findViewById(android.R.id.content)); background=page.getChildAt(0); backgroundVisibility=background.getVisibility(); background.setVisibility(android.view.View.INVISIBLE); require(page.inUse(),"绘制暂停不应让宿主进入后台休眠"); });
      previous = PlaybackBridge.current();
      require(previous == null || !previous.query("state").getBoolean("playing"), "已有演奏不能被测试接管");
      var context = new AtomicReference<Context>();
      main(() -> context.set(Bootstrap.source().prepared.context(target)));
      var loader = Bootstrap.source().prepared.classLoader();
      Class<?> kv = Class.forName("app.luoxianlv.shared.Kv", true, loader);
      experimental = (SharedPreferences) kv.getMethod("of", Context.class, String.class)
          .invoke(kv.getField("INSTANCE").get(null), context.get(), "experimental_options");
      hadFixed = experimental.contains(FIXED);
      oldFixed = experimental.getBoolean(FIXED, false);
      preferencesSaved = true;
      require(experimental.edit().putBoolean(FIXED, true).commit(), "固定布局未保存");
      oldMode = SharedInput.current().state().getString("mode");
      SharedInput.current().select(SharedInput.ACCESSIBILITY);
      await("输入模式", () -> SharedInput.ACCESSIBILITY.equals(SharedInput.current().state().getString("mode")));
      AtomicBoolean ready = new AtomicBoolean();
      AtomicReference<Throwable> failed = new AtomicReference<>();
      main(() -> {
        session = Bootstrap.source().factory.playback();
        session.prepareRecovery(context.get(), recorder, initialState(), new NativePage.Ready() {
          @Override public void ready() { ready.set(true); }
          @Override public void failed(Throwable error) { failed.set(error); }
        });
      });
      await("播放器准备", () -> ready.get() || failed.get() != null);
      require(failed.get() == null, "播放器准备失败：" + failed.get());
      main(() -> { recorder.enabled = true; session.activate(); });
      port = PlaybackBridge.connect(new PlaybackPort() {
        @Override public Bundle query(String kind) { return session.query(kind); }
        @Override public void command(String action, Bundle args) {
          if (action.equals("ensembleStart")) {
            recorder.starts++;
            recorder.localStartUs = args.getLong("localStartUs");
            recorder.positionUs = args.getLong("positionUs");
          }
          session.command(action, args);
        }
      });
      observer = SharedRealtime.current().observe(new SharedRealtime.Listener() {
        @Override public void changed(Bundle state) { }
        @Override public void message(String topic, String type, String json) {
          if (!topic.equals("ensemble")) return;
          try {
            JSONObject plan = new JSONObject(json).getJSONObject("data").getJSONObject("room").optJSONObject("plan");
            if (plan != null) recorder.run = plan.getString("run");
          } catch (Exception ignoredNonRoomMessage) { }
        }
      });
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<String> result = new AtomicReference<>();
      AtomicBoolean success = new AtomicBoolean();
      SharedRealtime.Completion callback = (ok, text) -> { success.set(ok); result.set(text); done.countDown(); };
      Class.forName("app.luoxianlv.ensemble.EnsembleMultiProbe", true, loader)
          .getMethod("run", Context.class, String.class, SharedRealtime.Completion.class)
          .invoke(null, context.get(), config, callback);
      File directory = new File(target.getFilesDir(), "ensemble-multi");
      while (!done.await(500, TimeUnit.MILLISECONDS)) {
        var snapshot = new AtomicReference<JSONObject>();
        main(() -> snapshot.set(recorder.snapshot(false)));
        write(new File(directory, "input.json"), snapshot.get());
      }
      require(success.get(), result.get());
      var trace = new AtomicReference<JSONObject>();
      main(() -> trace.set(recorder.snapshot(true)));
      trace.get().put("index", settings.getInt("index")).put("api", android.os.Build.VERSION.SDK_INT)
          .put("actualRuntimeAndPlayback", true).put("systemTouchSimulated", true).put("controlledClock", settings.optBoolean("controlledClock", false));
      write(new File(directory, "trace.json"), trace.get());
      output.putString("stream", "多模拟器真实业务/播放器验证完成：设备 " + settings.getInt("index") + "\n");
      passed = true;
    } catch (Throwable error) {
      output.putString("stream", android.util.Log.getStackTraceString(error));
    } finally {
      try {
        if (session != null) main(() -> { recorder.enabled = false; session.close(); });
        if (port != null) port.close();
        if (observer != null) observer.close();
        if (previous != null) PlaybackBridge.connect(previous);
        if (preferencesSaved) {
          var editor = experimental.edit();
          if (hadFixed) editor.putBoolean(FIXED, oldFixed); else editor.remove(FIXED);
          require(editor.commit(), "原固定布局偏好恢复失败");
        }
        if (oldMode != null) SharedInput.current().select(oldMode);
        if (background != null) main(() -> background.setVisibility(backgroundVisibility));
      } catch (Throwable error) { output.putString("stream", android.util.Log.getStackTraceString(error)); passed = false; }
      try { localProxy.close(); } catch (Exception error) { output.putString("stream", android.util.Log.getStackTraceString(error)); passed = false; }
    }
    finish(passed ? -1 : 0, output);
  }

  private final class Recorder implements AccessibilityBinding {
    final Handler main = new Handler(Looper.getMainLooper());
    final ArrayList<JSONObject> notes = new ArrayList<>();
    boolean enabled;
    int starts;
    long localStartUs, positionUs;
    String run = "";
    @Override public boolean current() { return enabled; }
    @Override public boolean gesture(GestureDescription description, GestureCallback callback) {
      require(enabled && Looper.myLooper() == Looper.getMainLooper(), "输入提交线程或代次不正确");
      long duration = description.getStroke(0).getDuration();
      if (duration > 1) {
        require(notes.size() < 5000, "测试轨迹超出上限");
        Bundle clock = SharedRealtime.current().clock();
        long local = SystemClock.elapsedRealtimeNanos() / 1000;
        long position = session.query("state").getLong("positionMs") * 1000;
        long noteIndex = (position + 250_000) / 500_000;
        try {
          notes.add(new JSONObject().put("run", run).put("start", starts).put("note", noteIndex)
              .put("localUs", local).put("serverUs", local + clock.getLong("offsetUs"))
              .put("localStartUs", localStartUs).put("positionStartUs", positionUs)
              .put("uncertaintyUs", clock.getLong("uncertaintyUs")).put("durationMs", duration));
        } catch (Exception error) { throw new AssertionError(error); }
      }
      main.postDelayed(() -> callback.completed(true), duration);
      return true;
    }
    JSONObject snapshot(boolean complete) {
      try {
        JSONObject result = new JSONObject().put("starts", starts).put("noteCount", notes.size())
            .put("playing", session.query("ensemble").getBoolean("playing"))
            .put("skipped", session.query("ensemble").getInt("skipped"));
        var bridge = SharedRealtime.current();
        var engine = bridge;
        var clockField = engine.getClass().getDeclaredField("clock");clockField.setAccessible(true);
        var clock = clockField.get(engine);
        var samplesField = clock.getClass().getDeclaredField("samples");samplesField.setAccessible(true);
        var diagnostic = new JSONArray();
        synchronized (clock) {
          for (Object sample : (Iterable<?>) samplesField.get(clock)) {
            var record = new JSONObject();
            for (String name : new String[]{"offset", "delay", "received"}) {
              var method=sample.getClass().getDeclaredMethod("get"+Character.toUpperCase(name.charAt(0))+name.substring(1));
              method.setAccessible(true);record.put(name,method.invoke(sample));
            }
            diagnostic.put(record);
          }
        }
        result.put("clockSamples",diagnostic).put("localNowUs",SystemClock.elapsedRealtimeNanos()/1000)
            .put("roomActive",bridge.state().getBoolean("roomActive"));
        if (!notes.isEmpty()) result.put("first", notes.get(0)).put("last", notes.get(notes.size()-1));
        if (complete) result.put("notes", new JSONArray(notes));
        return result;
      } catch (Exception error) { throw new AssertionError(error); }
    }
    @Override public void screenshot(int displayId, ScreenshotCallback callback) { callback.failure(-1); }
    @Override public void foreground(boolean enabled) { require(!enabled, "测试不应创建额外浮窗"); }
  }

  private static Bundle initialState() {
    Bundle song = new Bundle();
    song.putInt("schema", 1); song.putString("id", "multi-initial"); song.putString("title", "测试准备曲");
    song.putString("score", "1 2 3 4"); song.putInt("bpm", 120); song.putString("source", "本机测试");
    Bundle state = new Bundle();
    state.putInt("schema", 1); state.putBundle("song", song); state.putString("mode", "NATURAL");
    state.putBoolean("fixed", true); state.putBoolean("floating", false); state.putFloat("speed", 1f);
    return state;
  }
  private static app.luoxianlv.hot.PageSwapHost findPage(android.view.View view) {
    if (view instanceof app.luoxianlv.hot.PageSwapHost page) return page;
    if (view instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) {
      var found=findPage(group.getChildAt(i));if(found!=null)return found;
    }
    return null;
  }
  private static void write(File target, JSONObject value) throws Exception {
    File temporary = new File(target.getParentFile(), target.getName()+".tmp");
    Files.write(temporary.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
    require(temporary.renameTo(target), "测试报告未原子提交");
  }
  private void main(Runnable action) {
    var error = new AtomicReference<Throwable>();
    runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
    if (error.get() != null) throw new AssertionError(error.get());
  }
  private static void await(String stage, BooleanSupplier predicate) {
    long deadline = SystemClock.elapsedRealtime() + 45_000;
    while (!predicate.getAsBoolean()) {
      require(SystemClock.elapsedRealtime() < deadline, "等待"+stage+"超时");
      SystemClock.sleep(25);
    }
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
