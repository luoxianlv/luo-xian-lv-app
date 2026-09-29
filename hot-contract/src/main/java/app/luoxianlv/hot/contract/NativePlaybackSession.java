package app.luoxianlv.hot.contract;

import android.content.Context;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;

/** 无障碍组件的业务会话；系统连接、回调隔离与资源关闭由稳定宿主负责。 */
public interface NativePlaybackSession extends PlaybackPort, AutoCloseable {
  void connect(Context context, AccessibilityBinding binding);

  void interrupt();

  default void event(AccessibilityEvent event) {}

  boolean canReplace();

  /** 旧业务没有两阶段协议时，宿主必须延后到重启，不能调用 connect 冒充无副作用预热。 */
  default boolean supportsHandover() {
    return false;
  }

  /** 只准备数据；此时 binding.current() 为 false，不允许创建浮窗或执行系统输入。 */
  default void prepare(
      Context context, AccessibilityBinding binding, Bundle state, NativePage.Ready ready) {
    throw new UnsupportedOperationException("播放业务不支持预热交接");
  }

  /** 基础值快照不包含正在执行的手势或自动恢复播放指令。 */
  default Bundle snapshot() {
    throw new UnsupportedOperationException("播放业务不支持状态迁移");
  }

  /** 只在停用会话调用；故障无法取出快照时，null 表示读取当前持久化选曲。 */
  default void restore(Bundle state, NativePage.Ready ready) {
    throw new UnsupportedOperationException("播放业务不支持状态恢复");
  }

  default void activate() {
    throw new UnsupportedOperationException("播放业务不支持激活");
  }

  default void deactivate() {
    throw new UnsupportedOperationException("播放业务不支持停用");
  }

  /** 用户交互、浮窗拖动或显示配置改变后递增，防止提交过期的准备结果。 */
  default long revision() {
    return 0;
  }

  /** close 返回后仍有后台任务持有本代代码时返回 false。 */
  default boolean released() {
    return true;
  }

  @Override
  void close();
}
