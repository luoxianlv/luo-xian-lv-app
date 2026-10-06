package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 加载真实业务 marker，以计数 WindowManager 代理验收窗口复用；模拟异常不代表系统故障。 */
public final class FloatingMarkerInstrumentation extends Instrumentation {
  private Context context;
  private Class<?> markerClass, functionClass;
  private Object light, dark;
  private final ArrayList<Marker> markers = new ArrayList<>();

  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

  @Override public void onStart() {
    Bundle result = new Bundle();
    boolean success = false;
    try {
      require(getTargetContext().getPackageName().endsWith(".debug"), "仅允许运行 Debug 宿主");
      getTargetContext().startActivity(new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
      await("真实业务没有准备好", () -> {
        try { return Bootstrap.source().prepared != null; }
        catch (IllegalStateException pending) { return false; }
      });
      main(() -> {
        try {
          var source = Bootstrap.source();
          context = source.prepared.context(getTargetContext());
          markerClass = Class.forName("app.luoxianlv.playback.FloatingTouchMarker", true, source.prepared.classLoader());
          functionClass = Class.forName("kotlin.jvm.functions.Function0", false, markerClass.getClassLoader());
          Class<?> player = Class.forName("app.luoxianlv.playback.PlayerUi", true, markerClass.getClassLoader());
          Object instance = player.getField("INSTANCE").get(null);
          light = player.getMethod("getLightPalette").invoke(instance);
          dark = player.getMethod("getDarkPalette").invoke(instance);
          require(markerClass.getClassLoader() == source.prepared.classLoader(), "没有使用真实独立业务 marker");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
      });
      reuseAndHide();
      updateFailureRecovery();
      partialAddRecovery();
      removalFailureRecovery();
      unregisteredRemoval();
      result.putString("stream", "点击标记验收通过：同窗复用、120ms透明隐藏、坐标更新去重、配色更新、不可触摸/不可聚焦、关闭与再次显示，以及模拟添加/更新/移除失败后的保守清理；计数代理未操作系统窗口，模拟异常不代表系统故障。\n");
      success = true;
    } catch (Throwable failure) {
      result.putString("stream", "点击标记验收失败：" + failure + "\n");
      result.putString("error", android.util.Log.getStackTraceString(failure));
    } finally {
      try {
        for (Marker marker : markers) main(marker::close);
      } catch (Throwable cleanup) {
        success = false;
        result.putString("cleanup", android.util.Log.getStackTraceString(cleanup));
      }
    }
    finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }

  private void reuseAndHide() {
    Marker marker = marker();
    main(() -> {
      marker.show(100, 200);
      require(marker.windows.adds == 1 && marker.windows.updates == 0, "首次添加后额外更新了窗口");
      require(marker.windows.firstX == 100 - dp(10) && marker.windows.firstY == 200 - dp(10),
          "首次添加的坐标尚未设好");
      require(marker.windows.width == dp(20) && marker.windows.height == dp(20), "标记不是20dp小窗口");
      require((marker.windows.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
          && (marker.windows.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
          && marker.windows.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
          "标记窗口可能拦截触摸或焦点");
      require(marker.windows.alpha > 0f && marker.windows.alpha <= 0.8f,
          "系统悬浮标记的窗口不透明度超过触摸穿透上限");
    });
    View first = marker.windows.current;
    Drawable background = first.getBackground();
    await("120ms后标记未透明隐藏", () -> hidden(marker));
    main(() -> {
      require(marker.windows.removes == 0 && marker.windows.immediateRemoves == 0, "隐藏时移除了窗口");
      for (int i = 0; i < 20; i++) marker.show(100, 200);
      require(marker.windows.current == first && first.getAlpha() == 1f, "同坐标显示没有复用原窗口");
      require(marker.windows.adds == 1 && marker.windows.updates == 0,
          "同坐标重复标记仍反复添加或更新窗口");
      require(first.getBackground() == background, "配色未变仍重建了背景");
      marker.show(180, 280);
      require(marker.windows.adds == 1 && marker.windows.updates == 1, "坐标变化没有使用单次窗口更新");
      marker.palette.set(dark);
      marker.show(180, 280);
      require(first.getBackground() != background && marker.windows.updates == 1,
          "配色变化未更新背景，或额外更新了窗口位置");
    });
    await("复用后的标记没有按时隐藏", () -> hidden(marker));
    main(() -> {
      require(!marker.released() && marker.windows.removes == 0, "隐藏被当作物理窗口已移除");
      marker.close();
      require(marker.released() && marker.windows.current == null && marker.windows.removes == 1,
          "close没有真正移除标记");
      marker.close();
      require(marker.windows.removes == 1, "重复close再次移除了窗口");
      marker.show(300, 400);
      require(marker.windows.adds == 2 && marker.windows.current != first, "关闭后再次显示未重建窗口");
      marker.close();
      require(marker.released() && marker.windows.removes == 2, "重新显示后关闭留下窗口");
    });
  }

  private void updateFailureRecovery() {
    Marker marker = marker();
    main(() -> {
      marker.show(100, 200);
      marker.windows.failUpdate = true;
      marker.show(180, 280);
      require(marker.released() && marker.windows.current == null, "更新失败后仍保留活动窗口");
      marker.show(300, 400);
      require(marker.windows.adds == 2 && marker.windows.current != null, "更新失败后无法重新显示");
      marker.close();
      require(marker.released(), "更新失败恢复后关闭留下窗口");
    });
  }

  private void partialAddRecovery() {
    Marker marker = marker();
    main(() -> {
      marker.windows.failAddAfterRegistration = true;
      marker.show(100, 200);
      require(marker.released() && marker.windows.current == null, "部分添加失败留下未清理窗口");
      marker.show(180, 280);
      require(marker.windows.adds == 2 && marker.windows.current != null, "添加失败后无法重新显示");
      marker.close();
      require(marker.released(), "添加失败恢复后仍留有窗口");
    });
  }

  private void removalFailureRecovery() {
    Marker marker = marker();
    main(() -> {
      marker.show(100, 200);
      marker.windows.failRemove = true;
      marker.windows.failImmediate = true;
      marker.close();
      require(!marker.released() && marker.windows.current != null && marker.windows.current.getAlpha() == 0f,
          "两次移除失败被错误宣称为已释放，或未隐藏残留窗口");
      marker.windows.failRemove = true;
      marker.windows.failImmediate = true;
      marker.show(180, 280);
      require(!marker.released() && marker.windows.adds == 1 && marker.windows.maximumActive == 1,
          "尚未确认移除时又添加了新窗口");
      marker.close();
      require(marker.released() && marker.windows.current == null, "后续close没有重试未确认移除的窗口");
      marker.show(300, 400);
      require(marker.windows.adds == 2 && marker.windows.maximumActive == 1, "移除恢复后叠加了窗口");
      marker.close();
      require(marker.released(), "移除异常恢复后仍留有窗口");
    });
  }

  private void unregisteredRemoval() {
    Marker marker = marker();
    main(() -> {
      marker.show(100, 200);
      marker.windows.current = null; // 模拟窗口已由系统移除。
      marker.close();
      require(marker.released() && marker.windows.immediateRemoves == 0,
          "未注册窗口没有按已移除处理");
    });
  }

  private int dp(int value) { return (int) (value * context.getResources().getDisplayMetrics().density + .5f); }

  private Marker marker() {
    AtomicReference<Marker> created = new AtomicReference<>();
    main(() -> created.set(new Marker()));
    markers.add(created.get());
    return created.get();
  }

  private boolean hidden(Marker marker) {
    AtomicReference<Boolean> hidden = new AtomicReference<>(false);
    main(() -> hidden.set(marker.windows.current != null && marker.windows.current.getAlpha() == 0f));
    return hidden.get();
  }

  private final class Marker {
    final WindowProbe windows = new WindowProbe();
    final AtomicReference<Object> palette = new AtomicReference<>(light);
    final Object real;
    final Method show, close, released;
    Marker() {
      try {
        Object supplier = Proxy.newProxyInstance(functionClass.getClassLoader(), new Class<?>[] {functionClass},
            (proxy, method, arguments) -> {
              if (method.getName().equals("invoke")) return palette.get();
              if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
              if (method.getName().equals("equals")) return proxy == arguments[0];
              return "marker-palette-fixture";
            });
        Constructor<?> constructor = markerClass.getDeclaredConstructor(Context.class, WindowManager.class,
            Handler.class, functionClass);
        constructor.setAccessible(true);
        real = constructor.newInstance(context, windows.proxy(), new Handler(Looper.getMainLooper()), supplier);
        show = markerClass.getDeclaredMethod("show", float.class, float.class);
        close = markerClass.getDeclaredMethod("close");
        released = markerClass.getDeclaredMethod("getReleased");
        show.setAccessible(true); close.setAccessible(true); released.setAccessible(true);
      } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    void show(float x, float y) { invoke(show, x, y); }
    void close() { invoke(close); }
    boolean released() { return (Boolean) invoke(released); }
    private Object invoke(Method method, Object... arguments) {
      try { return method.invoke(real, arguments); }
      catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
  }

  private final class WindowProbe {
    View current;
    int adds, updates, removes, immediateRemoves, firstX, firstY, width, height, flags, type, maximumActive;
    float alpha;
    boolean failAddAfterRegistration, failUpdate, failRemove, failImmediate;
    WindowManager proxy() {
      return (WindowManager) Proxy.newProxyInstance(getTargetContext().getClassLoader(), new Class<?>[] {WindowManager.class},
          (proxy, method, args) -> {
            switch (method.getName()) {
              case "addView" -> {
                require(current == null, "重复添加仍未移除的窗口");
                adds++;
                current = (View) args[0];
                maximumActive = Math.max(maximumActive, 1);
                var p = (WindowManager.LayoutParams) args[1];
                if (adds == 1) { firstX = p.x; firstY = p.y; }
                width = p.width; height = p.height; flags = p.flags; type = p.type;
                alpha = p.alpha;
                current.setLayoutParams(p);
                if (failAddAfterRegistration) {
                  failAddAfterRegistration = false;
                  throw new WindowManager.BadTokenException("fixture-partial-add");
                }
                return null;
              }
              case "updateViewLayout" -> {
                updates++;
                require(current == args[0], "更新的不是已注册窗口");
                if (failUpdate) { failUpdate = false; throw new IllegalStateException("fixture-update"); }
                current.setLayoutParams((WindowManager.LayoutParams) args[1]);
                return null;
              }
              case "removeView" -> {
                removes++;
                if (failRemove) { failRemove = false; throw new IllegalStateException("fixture-remove"); }
                if (current != args[0]) throw new IllegalArgumentException("fixture-unregistered");
                current = null;
                return null;
              }
              case "removeViewImmediate" -> {
                immediateRemoves++;
                if (failImmediate) { failImmediate = false; throw new IllegalStateException("fixture-remove-immediate"); }
                if (current != args[0]) throw new IllegalArgumentException("fixture-unregistered");
                current = null;
                return null;
              }
              case "hashCode" -> { return System.identityHashCode(proxy); }
              case "equals" -> { return proxy == args[0]; }
              case "toString" -> { return "marker-window-fixture"; }
              default -> throw new AssertionError("意外的窗口调用：" + method.getName());
            }
          });
    }
  }

  private void main(Runnable action) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runOnMainSync(() -> {
      try { action.run(); }
      catch (Throwable error) { failure.set(error); }
    });
    if (failure.get() != null) throw new AssertionError("标记窗口主线程检查失败", failure.get());
  }

  private static void await(String message, BooleanSupplier condition) {
    long end = SystemClock.elapsedRealtime() + 10000;
    while (!condition.getAsBoolean()) {
      require(SystemClock.elapsedRealtime() < end, message);
      SystemClock.sleep(20);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
