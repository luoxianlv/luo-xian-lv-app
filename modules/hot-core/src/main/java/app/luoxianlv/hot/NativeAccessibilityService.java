package app.luoxianlv.hot;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** 无障碍身份只提供系统能力；关闭它不会关闭其他输入模式的播放会话。 */
public abstract class NativeAccessibilityService extends AccessibilityService {
  private static volatile NativeAccessibilityService instance;

  static NativeAccessibilityService current() { return instance; }

  public static boolean isConnected() { return instance != null; }

  @Override
  protected final void onServiceConnected() { instance = this; }

  @Override
  public final void onAccessibilityEvent(AccessibilityEvent event) {
    NativePlaybackHost host = NativePlaybackHost.current();
    if (instance == this && host != null) host.accessibilityEvent(event);
  }

  @Override
  public final void onInterrupt() {
    NativePlaybackHost host = NativePlaybackHost.current();
    if (instance == this && host != null) host.accessibilityInterrupted();
  }

  private void disconnected() {
    if (instance != this) return;
    instance = null;
    NativePlaybackHost host = NativePlaybackHost.current();
    if (host != null) host.accessibilityInterrupted();
  }

  @Override
  public final boolean onUnbind(Intent intent) {
    disconnected();
    return super.onUnbind(intent);
  }

  @Override
  public final void onDestroy() {
    disconnected();
    super.onDestroy();
  }
}
