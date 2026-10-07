package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 在真实宿主加载音源，验证音频线程写入、快速松键、换音和关闭。 */
public final class HarmonicaOutputInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

  @Override public void onStart() {
    Bundle result = new Bundle(); Object sampler = null; Class<?> type = null; boolean passed = false;
    try {
      require(getTargetContext().getPackageName().endsWith(".debug"), "仅允许 Debug 音频验收");
      getTargetContext().startActivity(new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long deadline = SystemClock.elapsedRealtime() + 15000;
      while (true) {
        try { if (Bootstrap.source().prepared != null) break; }
        catch (IllegalStateException pending) { }
        require(SystemClock.elapsedRealtime() < deadline, "业务音源尚未准备");
        SystemClock.sleep(20);
      }
      ClassLoader loader = Bootstrap.source().prepared.classLoader();
      type = Class.forName("app.luoxianlv.practice.HarmonicaSampler", true, loader);
      Object companion = type.getField("Companion").get(null);
      AtomicReference<Context> owner = new AtomicReference<>();
      runOnMainSync(() -> owner.set(Bootstrap.source().prepared.context(getTargetContext())));
      long loading = SystemClock.elapsedRealtime();
      Map<?, ?> samples = (Map<?, ?>) companion.getClass().getMethod("load", Context.class)
          .invoke(companion, owner.get());
      loading = SystemClock.elapsedRealtime() - loading;
      require(samples.size() == 38, "音源不完整");
      AtomicBoolean interrupted = new AtomicBoolean();
      Class<?> function = Class.forName("kotlin.jvm.functions.Function0", true, loader);
      Object unit = Class.forName("kotlin.Unit", true, loader).getField("INSTANCE").get(null);
      Object callback = Proxy.newProxyInstance(loader, new Class<?>[] {function}, (proxy, method, args) -> {
        if (method.getName().equals("invoke")) { interrupted.set(true); return unit; }
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        if (method.getName().equals("equals")) return proxy == args[0];
        return "口琴输出验收";
      });
      AtomicReference<Object> created = new AtomicReference<>(); Class<?> samplerType = type;
      runOnMainSync(() -> {
        try { created.set(samplerType.getConstructor(Map.class, float.class, function).newInstance(samples, 1f, callback)); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
      });
      sampler = created.get();
      var trackField = type.getDeclaredField("track"); trackField.setAccessible(true);
      var workerField = type.getDeclaredField("worker"); workerField.setAccessible(true);
      AudioTrack track = (AudioTrack) trackField.get(sampler);
      Thread worker = (Thread) workerField.get(sampler);
      for (int note : new int[] {60, 64, 67, 72, 60}) {
        require((Boolean) type.getMethod("noteOn", int.class).invoke(sampler, note), "音频线程拒绝起音");
        type.getMethod("noteOff").invoke(sampler);
        SystemClock.sleep(40);
      }
      SystemClock.sleep(650);
      require(!interrupted.get() && worker.isAlive(), "演奏期间音频线程中断");
      require(track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING && track.getPlaybackHeadPosition() > 0,
          "系统音频轨没有消费 PCM");
      int frames = track.getPlaybackHeadPosition();
      type.getMethod("close").invoke(sampler); worker.join(3000);
      require(!worker.isAlive() && track.getState() == AudioTrack.STATE_UNINITIALIZED, "关闭后音频资源未释放");
      result.putString("stream", "口琴系统输出通过：38 个音源，加载=" + loading + " 毫秒，快速松键与连续换音，播放帧=" + frames + "；线程与音轨已释放。\n");
      passed = true;
    } catch (Throwable error) {
      result.putString("error", android.util.Log.getStackTraceString(error));
      result.putString("stream", "口琴系统输出失败：" + error + "\n");
    } finally {
      if (sampler != null && type != null) try { type.getMethod("close").invoke(sampler); } catch (Exception ignored) { }
    }
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }

  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
