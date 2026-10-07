package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import app.luoxianlv.hot.NativePlaybackHost;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import app.luoxianlv.hot.contract.NativePage;
import app.luoxianlv.hot.contract.NativePlaybackSession;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.SharedInput;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 完整播放截图入口；keyboardPath 可指定公开琴键样本，验证慢回退仍能识别演奏。 */
public final class ScreenshotPlaybackInstrumentation extends Instrumentation {
  private Bundle arguments;
  @Override public void onCreate(Bundle args) {
    arguments = args == null ? new Bundle() : new Bundle(args);
    super.onCreate(args); start();
  }
  @Override public void onStart() {
    Bundle output = new Bundle(); boolean passed = false;
    SharedInput.Bridge real = null; AutoCloseable replacement = null; Host host = null;
    android.content.SharedPreferences prefs = null; boolean saved = false, previous = false, present = false;
    android.content.SharedPreferences layoutPrefs = null; java.util.Map<String, ?> savedLayout = null;
    Bitmap keyboard = null;
    try {
      require(getTargetContext().getPackageName().endsWith(".debug"), "只允许 Debug 验证");
      getTargetContext().startActivity(new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long deadline = SystemClock.elapsedRealtime() + 15000;
      while (true) {
        try { if (Bootstrap.source().prepared != null) break; } catch (IllegalStateException pending) { }
        require(SystemClock.elapsedRealtime() < deadline, "业务加载超时"); SystemClock.sleep(20);
      }
      AtomicReference<Context> context = new AtomicReference<>();
      main(() -> context.set(Bootstrap.source().prepared.context(getTargetContext())));
      Class<?> kv = Class.forName("app.luoxianlv.shared.Kv", true, Bootstrap.source().prepared.classLoader());
      prefs = (android.content.SharedPreferences) kv.getMethod("of", Context.class, String.class)
          .invoke(kv.getField("INSTANCE").get(null), context.get(), "experimental_options");
      String keyboardPath = arguments.getString("keyboardPath");
      if (keyboardPath != null) {
        keyboard = BitmapFactory.decodeFile(keyboardPath);
        require(keyboard != null, "琴键样本无法读取：" + keyboardPath);
        layoutPrefs = (android.content.SharedPreferences) kv.getMethod("of", Context.class, String.class)
            .invoke(kv.getField("INSTANCE").get(null), context.get(), "ratio_config_v3");
        savedLayout = new java.util.HashMap<>(layoutPrefs.getAll());
      }
      present = prefs.contains("fixed_harmonica_keys"); previous = prefs.getBoolean("fixed_harmonica_keys", false);
      saved = true; require(prefs.edit().putBoolean("fixed_harmonica_keys", false).commit(), "关闭固定布局失败");
      real = SharedInput.current();
      for (String mode : new String[] {SharedInput.SHIZUKU, SharedInput.WIRELESS}) {
        FakeInput input = new FakeInput(mode, keyboard); replacement = SharedInput.connect(input);
        Host current = new Host(context.get()); host = current;
        main(() -> { require(NativePlaybackHost.current() == null, "请先关闭播放浮窗"); current.open(); });
        // 已创建的真实播放会话采用内存曲目，绑定仍由真正的 NativePlaybackHost 提供。
        Bundle song = new Bundle(); song.putInt("schema",1); song.putString("id","software-screenshot-check");
        song.putString("title","截图入口检查"); song.putString("score","1 2 3"); song.putInt("bpm",120);
        main(() -> PlaybackBridge.current().command("select", song));
        deadline = SystemClock.elapsedRealtime() + 5000;
        while (current.session.query("state").getLong("durationMs") <= 0 && SystemClock.elapsedRealtime() < deadline)
          SystemClock.sleep(20);
        main(() -> PlaybackBridge.current().command("play", new Bundle()));
        deadline = SystemClock.elapsedRealtime() + 5000;
        AtomicReference<Bundle> state = new AtomicReference<>();
        while (SystemClock.elapsedRealtime() < deadline) {
          main(() -> state.set(current.session.query("state")));
          if (current.failure != null || state.get().getString("error") != null) break;
          SystemClock.sleep(20);
        }
        require(current.failure == null && NativePlaybackHost.current() == current, "截图导致宿主关闭：" + current.failure);
        require(input.calls.get() == 1 && state.get().getString("error", "").contains("琴键"),
            "空画面未通过完整播放识别路径：" + state.get());
        input.delayed = true;
        main(() -> PlaybackBridge.current().command("play", new Bundle()));
        require(input.pending != null, "没有接收第二次截图请求");
        main(() -> PlaybackBridge.current().command("pause", new Bundle()));
        main(input::deliver);
        main(() -> state.set(current.session.query("state")));
        require(current.failure == null && NativePlaybackHost.current() == current
            && !state.get().getBoolean("playing") && !state.get().getBoolean("preparing")
            && input.bitmap.isRecycled(), "过期软件截图没有安全释放，或关闭了播放宿主");
        if (keyboard != null) {
          input.recognizable = true;
          main(() -> PlaybackBridge.current().command("play", new Bundle()));
          require(input.pending != null, "没有接收慢截图请求");
          SystemClock.sleep(3000);
          main(() -> state.set(current.session.query("state")));
          require(state.get().getBoolean("preparing") && state.get().getString("error") == null
              && input.presses.get() == 0, "慢截图在交付前提前超时或点击：" + state.get());
          main(input::deliver);
          deadline = SystemClock.elapsedRealtime() + 3000;
          while (SystemClock.elapsedRealtime() < deadline) {
            main(() -> state.set(current.session.query("state")));
            if (current.failure != null || state.get().getString("error") != null || input.presses.get() > 0) break;
            SystemClock.sleep(20);
          }
          require(current.failure == null && NativePlaybackHost.current() == current
              && state.get().getBoolean("playing") && !state.get().getBoolean("preparing")
              && state.get().getString("error") == null && input.presses.get() > 0,
              "3 秒截图没有成功识别并开始演奏：" + state.get());
          main(() -> PlaybackBridge.current().command("pause", new Bundle()));
        }
        main(current::close); host = null; replacement.close(); replacement = null;
      }
      output.putString("stream", "完整播放截图回归通过：Shizuku/无线空图与暂停迟到帧安全；3 秒成功识别="
          + (keyboard != null) + "。\n");
      passed = true;
    } catch (Throwable failure) {
      output.putString("stream", "完整播放截图回归失败：" + failure + "\n");
      output.putString("error", android.util.Log.getStackTraceString(failure));
    } finally {
      try {
        Host current = host; if (current != null) main(current::close);
        if (replacement != null) replacement.close();
        if (real != null) SharedInput.connect(real);
        if (saved) { var edit=prefs.edit(); if (present) edit.putBoolean("fixed_harmonica_keys",previous);
          else edit.remove("fixed_harmonica_keys"); require(edit.commit(),"恢复偏好失败"); }
        if (savedLayout != null) {
          var edit = layoutPrefs.edit();
          for (String key : new String[] {"noteX0", "noteX1", "noteX2", "noteX3", "noteX4", "noteX5", "noteX6", "noteX7",
              "noteY", "SEMITONEX", "SEMITONEY", "RAISEX", "RAISEY", "NATURALX", "NATURALY", "LOWERX", "LOWERY"}) {
            if (savedLayout.containsKey(key)) edit.putFloat(key, (Float) savedLayout.get(key));
            else edit.remove(key);
          }
          require(edit.commit(), "恢复琴键位置失败");
        }
      } catch (Throwable failure) { passed=false; output.putString("cleanup",failure.toString()); }
      if (keyboard != null) keyboard.recycle();
    }
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED,output);
  }
  private void main(Runnable action) {
    AtomicReference<Throwable> failure=new AtomicReference<>();
    runOnMainSync(() -> { try { action.run(); } catch(Throwable error) { failure.set(error); } });
    if(failure.get()!=null)throw new AssertionError(failure.get());
  }
  private static void require(boolean valid,String message) { if(!valid)throw new AssertionError(message); }
  private static final class Host extends NativePlaybackHost {
    NativePlaybackSession session; volatile Throwable failure;
    Host(Context context) { super(context); }
    protected NativePlaybackSession createPlaybackSession() { session=Bootstrap.source().factory.playback();return session; }
    protected void foregroundRequested(boolean enabled) {}
    protected void playbackFailed(Throwable error) { failure=error; }
  }
  private static final class FakeInput implements SharedInput.Bridge {
    final String mode; final Bitmap keyboard; final AtomicInteger calls=new AtomicInteger(), presses=new AtomicInteger();
    boolean delayed, recognizable; AccessibilityBinding.ScreenshotCallback pending; Bitmap bitmap;
    SharedInput.Completion held;
    FakeInput(String mode, Bitmap keyboard){this.mode=mode;this.keyboard=keyboard;}
    public Bundle state(){Bundle b=new Bundle();b.putString("mode",mode);return b;}
    public void select(String mode){} public void command(String a,Bundle b){}
    public AutoCloseable observe(Runnable r){return () -> {};}
    public SharedInput.Session openSession(){return new SharedInput.Session(){
      public AutoCloseable press(float[] p,int d,int w,int h,int r,SharedInput.Completion c){return FakeInput.this.press(p,d,w,h,r,c);}
      public void release(){FakeInput.this.release();} public boolean idle(){return held == null;}
      public void close(){release();}
    };}
    public AutoCloseable press(float[] p,int d,int w,int h,int r,SharedInput.Completion c){
      require(recognizable, "空图不应点击"); presses.incrementAndGet(); held=c; return () -> {};
    }
    public void release(){
      var completion=held;held=null;
      if(completion!=null)new Handler(Looper.getMainLooper()).post(() -> completion.complete(false,"演奏已暂停"));
    }
    public void screenshot(int d,AccessibilityBinding.ScreenshotCallback callback){
      calls.incrementAndGet(); pending=callback;
      if (!delayed) deliver();
    }
    void deliver(){
      var callback=pending;pending=null;
      bitmap=recognizable ? keyboard.copy(Bitmap.Config.ARGB_8888,false)
          : Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);
      callback.success(new AccessibilityBinding.Frame(bitmap));
    }
  }
}
