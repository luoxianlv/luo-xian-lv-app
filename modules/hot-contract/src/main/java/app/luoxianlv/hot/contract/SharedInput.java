package app.luoxianlv.hot.contract;

import android.os.Bundle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 输入模式和授权连接由宿主持有，业务只提交当前音符与屏幕快照。 */
public final class SharedInput {
  public static final String ACCESSIBILITY = "accessibility";
  public static final String SHIZUKU = "shizuku";
  public static final String WIRELESS = "wireless";
  private static final AtomicReference<Bridge> CURRENT = new AtomicReference<>();

  private SharedInput() {}

  public static Bridge current() { return CURRENT.get(); }

  public static AutoCloseable connect(Bridge bridge) {
    CURRENT.set(Objects.requireNonNull(bridge));
    return () -> CURRENT.compareAndSet(bridge, null);
  }

  public interface Completion {
    void complete(boolean success, String message);
  }

  /** 每个播放业务拥有独立租约，旧业务退场不能取消新业务的按键。 */
  public interface Session extends AutoCloseable {
    AutoCloseable press(float[] points, int durationMs, int width, int height, int rotation,
        Completion completion);
    void release();
    boolean idle();
    @Override void close();
  }

  public interface Bridge {
    /** 按所选输入模式截图，回调在主线程交付。 */
    default void screenshot(int displayId, AccessibilityBinding.ScreenshotCallback callback) {
      callback.failure(-1);
    }

    /** 返回内存快照，不在 UI 查询中进行 Binder 或设备 IO。 */
    Bundle state();

    void select(String mode);

    /** refresh、authorize、connect、disconnect、pair、openSettings。 */
    void command(String action, Bundle arguments);

    /** 监听权限、连接和能力变化；调用者退出页面时关闭监听。 */
    AutoCloseable observe(Runnable changed);

    Session openSession();

    /** 异步合流；返回仅取消本次提交的句柄，旧回调不得取消后续音符。 */
    AutoCloseable press(float[] points, int durationMs, int width, int height, int rotation,
        Completion completion);

    /** 暂停、切换模式或业务退场时归还触屏；后续播放重新建立会话。 */
    void release();
  }
}
