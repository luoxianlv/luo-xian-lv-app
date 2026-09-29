package app.luoxianlv.hot.contract;

import android.content.Intent;
import android.os.Bundle;

/** 业务只能通过活动代际的受控入口请求系统结果；宿主不依赖 ActivityResult 等可更新类型。 */
public interface HostActions {
  void launch(String key, Intent intent, Bundle options);

  void permissions(String key, String[] permissions);

  void resultReady(String key);

  boolean hasPendingResults();

  void finish();

  static boolean isStaleWindowOperation(IllegalStateException error) {
    if (!"This activity is currently not freeform-enabled".equals(error.getMessage())) return false;
    for (StackTraceElement frame : error.getStackTrace())
      if (frame.getClassName().equals("com.android.internal.widget.DecorCaptionView")
          && frame.getMethodName().equals("toggleFreeformWindowingMode")) return true;
    return false;
  }
}
