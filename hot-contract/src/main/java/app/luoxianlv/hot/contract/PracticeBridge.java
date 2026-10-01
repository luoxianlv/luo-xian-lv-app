package app.luoxianlv.hot.contract;

import android.util.Log;
import java.util.concurrent.atomic.AtomicReference;
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
    volatile boolean ready;

    Entry(Object owner, Supplier<Pitch> pitch) {
      this.owner = owner;
      this.pitch = pitch;
    }
  }

  private static final AtomicReference<Entry> CURRENT = new AtomicReference<>();

  private PracticeBridge() {}

  public static boolean active() {
    return CURRENT.get() != null;
  }

  public static boolean ready() {
    Entry entry = CURRENT.get();
    return entry != null && entry.ready;
  }

  public static boolean owns(Object owner) {
    Entry entry = CURRENT.get();
    return entry != null && entry.owner == owner;
  }

  public static void enter(Object owner, Supplier<Pitch> pitch) {
    CURRENT.set(
        new Entry(
            java.util.Objects.requireNonNull(owner), java.util.Objects.requireNonNull(pitch)));
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
    if (entry == null || !entry.ready) return null;
    try {
      Pitch pitch = entry.pitch.get();
      return CURRENT.get() == entry && entry.ready ? pitch : null;
    } catch (Throwable failure) {
      HostDiagnostics.log(Log.WARN, "演练场", "读取当前音区失败", failure);
      return null;
    }
  }
}
