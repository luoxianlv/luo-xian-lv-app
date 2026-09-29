package app.luoxianlv.hot.contract;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.view.accessibility.AccessibilityManager;
import java.util.concurrent.atomic.AtomicReference;

/** 进程内唯一的播放连接；旧系统服务释放时不能注销后来建立的连接。 */
public final class PlaybackBridge {
  public static final String FOREGROUND_CHANNEL = "playback_controls";
  private static final AtomicReference<PlaybackPort> CURRENT = new AtomicReference<>();

  private PlaybackBridge() {}

  public static PlaybackPort current() {
    return CURRENT.get();
  }

  public static AutoCloseable connect(PlaybackPort port) {
    CURRENT.set(java.util.Objects.requireNonNull(port));
    return () -> CURRENT.compareAndSet(port, null);
  }

  public static boolean isEnabled(Context context) {
    AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
    if (manager == null) return false;
    for (AccessibilityServiceInfo info :
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
      if (context.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName))
        return true;
    }
    return false;
  }
}
