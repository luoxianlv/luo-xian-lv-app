package app.luoxianlv.hot.contract;

import android.content.Intent;
import android.os.Bundle;

/** 业务只能通过活动代际的受控入口请求系统结果；宿主不依赖 ActivityResult 等可更新类型。 */
public interface HostActions {
  void launch(String key, Intent intent, Bundle options);

  void permissions(String key, String[] permissions);

  void resultReady(String key);

  boolean hasPendingResults();

  /** 当前代际身份与前后台状态分离；退场中的旧页不能继续发起平台操作。 */
  default boolean isCurrent() {
    return true;
  }

  default void open(Intent intent, boolean closeCurrent) {
    throw new UnsupportedOperationException("宿主未提供页面导航");
  }

  default void closePage() {
    throw new UnsupportedOperationException("宿主未提供页面关闭");
  }

  /** 关闭整个应用任务，仅用于明确的退出/拒绝协议操作。 */
  void finish();

  static boolean isStaleWindowOperation(IllegalStateException error) {
    if (!"This activity is currently not freeform-enabled".equals(error.getMessage())) return false;
    for (StackTraceElement frame : error.getStackTrace())
      if (frame.getClassName().equals("com.android.internal.widget.DecorCaptionView")
          && frame.getMethodName().equals("toggleFreeformWindowingMode")) return true;
    return false;
  }
}
