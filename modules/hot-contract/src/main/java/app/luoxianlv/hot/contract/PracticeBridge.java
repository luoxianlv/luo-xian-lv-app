package app.luoxianlv.hot.contract;

import android.util.Log;
import android.os.Bundle;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 不同业务加载器共用演练场状态；只能由当前页面所有者修改或释放。 */
public final class PracticeBridge {
  public static final class Pitch {
    public final String mode;
    public final boolean half;

    public Pitch(String mode, boolean half) {
      this.mode = mode;
      this.half = half;
    }
  }

  private static final class Entry {
    final Object owner;
    final Supplier<Pitch> pitch;
    final BooleanSupplier foreground;
    final Supplier<Bundle> geometry;
    final long token;
    volatile boolean ready;

    Entry(Object owner, Supplier<Pitch> pitch, BooleanSupplier foreground, Supplier<Bundle> geometry) {
      this.owner = owner;
      this.pitch = pitch;
      this.foreground = foreground;
      this.geometry = geometry;
      this.token = TOKENS.incrementAndGet();
    }
  }

  private static final AtomicReference<Entry> CURRENT = new AtomicReference<>();
  private static final AtomicLong TOKENS = new AtomicLong();

  private PracticeBridge() {}

  public static boolean active() {
    return foreground(CURRENT.get());
  }

  public static boolean ready() {
    Entry entry = CURRENT.get();
    return entry != null && entry.ready && foreground(entry);
  }

  public static boolean owns(Object owner) {
    Entry entry = CURRENT.get();
    return entry != null && entry.owner == owner;
  }

  public static void enter(Object owner, Supplier<Pitch> pitch) {
    enter(owner, pitch, () -> true);
  }

  public static void enter(Object owner, Supplier<Pitch> pitch, BooleanSupplier foreground) {
    enter(owner, pitch, foreground, null);
  }

  public static void enter(Object owner, Supplier<Pitch> pitch, BooleanSupplier foreground,
      Supplier<Bundle> geometry) {
    CURRENT.set(
        new Entry(
            java.util.Objects.requireNonNull(owner), java.util.Objects.requireNonNull(pitch),
            java.util.Objects.requireNonNull(foreground), geometry));
  }

  public static long token(Object owner) {
    Entry entry = CURRENT.get();
    return entry != null && entry.owner == owner ? entry.token : 0;
  }

  public static long readyToken() {
    Entry entry = CURRENT.get();
    return entry != null && entry.ready && foreground(entry) ? entry.token : 0;
  }

  /** 只返回当前可交互画布的基础坐标，不向宿主暴露 View 或业务模型。 */
  public static Bundle geometry() {
    Entry entry = CURRENT.get();
    if (entry == null || !entry.ready || !foreground(entry) || entry.geometry == null) return null;
    try {
      Bundle source = entry.geometry.get();
      if (source == null) return null;
      int width = source.getInt("width"), height = source.getInt("height");
      int x = source.getInt("screenX"), y = source.getInt("screenY");
      if (width < 1 || height < 1 || width > 32768 || height > 32768
          || x < -32768 || x > 32768 || y < -32768 || y > 32768) return null;
      if (CURRENT.get() != entry || !entry.ready || !foreground(entry)) return null;
      Bundle result = new Bundle();
      result.putInt("width", width); result.putInt("height", height);
      result.putInt("screenX", x); result.putInt("screenY", y);
      return result;
    } catch (Throwable failure) {
      HostDiagnostics.log(Log.WARN, "演练场", "读取画布坐标失败", failure);
      return null;
    }
  }

  public static void setReady(Object owner, boolean ready) {
    Entry entry = CURRENT.get();
    if (entry != null && entry.owner == owner) entry.ready = ready;
  }

  public static void leave(Object owner) {
    Entry entry = CURRENT.get();
    if (entry != null && entry.owner == owner) CURRENT.compareAndSet(entry, null);
  }

  public static Pitch pitch() {
    Entry entry = CURRENT.get();
    if (entry == null || !entry.ready || !foreground(entry)) return null;
    try {
      Pitch pitch = entry.pitch.get();
      return CURRENT.get() == entry && entry.ready && foreground(entry) ? pitch : null;
    } catch (Throwable failure) {
      HostDiagnostics.log(Log.WARN, "演练场", "读取当前音区失败", failure);
      return null;
    }
  }

  private static boolean foreground(Entry entry) {
    if (entry == null) return false;
    try {
      return entry.foreground.getAsBoolean() && CURRENT.get() == entry;
    } catch (Throwable failure) {
      HostDiagnostics.log(Log.WARN, "演练场", "读取窗口状态失败", failure);
      return false;
    }
  }
}
