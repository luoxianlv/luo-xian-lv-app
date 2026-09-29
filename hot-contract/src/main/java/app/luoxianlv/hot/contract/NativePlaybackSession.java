package app.luoxianlv.hot.contract;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;

/** 无障碍组件的业务会话；系统连接、回调隔离与资源关闭由稳定宿主负责。 */
public interface NativePlaybackSession extends PlaybackPort, AutoCloseable {
  void connect(Context context, AccessibilityBinding binding);

  void interrupt();

  default void event(AccessibilityEvent event) {}

  boolean canReplace();

  @Override
  void close();
}
