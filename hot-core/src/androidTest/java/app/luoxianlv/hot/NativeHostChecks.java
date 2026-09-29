package app.luoxianlv.hot;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import app.luoxianlv.hot.contract.NativePage;
import java.util.concurrent.atomic.AtomicReference;

final class NativeHostChecks {
  static void run(Instrumentation runner) {
    for (String mode : new String[] {"healthy", "create", "resume", "save", "close"}) {
      NativeLifecycleHarness.mode = mode;
      NativeLifecycleHarness activity =
          (NativeLifecycleHarness)
              runner.startActivitySync(
                  new Intent(runner.getContext(), NativeLifecycleHarness.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      try {
        onMain(
            runner,
            () -> {
              boolean broken = mode.equals("create") || mode.equals("resume");
              check(
                  contains(activity.getWindow().getDecorView(), broken ? "重试" : "业务已显示"),
                  "宿主恢复显示错误：" + mode);
              if (broken) {
                check(activity.closes == 1, "失败页面未关闭");
              } else {
                check(
                    activity.lifecycleEvents.containsAll(
                        java.util.List.of(
                            NativePage.CREATED, NativePage.STARTED, NativePage.RESUMED)),
                    "业务生命周期未转发");
                Bundle out = new Bundle();
                runner.callActivityOnSaveInstanceState(activity, out);
                if (mode.equals("save")) {
                  check(
                      out.getBundle("native.page") == null
                          && activity.warnings.contains("page_state_failed"),
                      "异常状态未隔离");
                  check(contains(activity.getWindow().getDecorView(), "业务已显示"), "保存失败却关闭了活动页面");
                } else check(out.getBundle("native.page").getInt("position") == 42, "基础状态未保存");
              }
            });
      } finally {
        runner.runOnMainSync(activity::finish);
        runner.waitForIdleSync();
      }
    }
    NativeLifecycleHarness.mode = "healthy";
  }

  private static void onMain(Instrumentation runner, Runnable action) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runner.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null) throw new AssertionError("原生宿主检查失败", failure.get());
  }

  private static boolean contains(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        if (contains(group.getChildAt(i), text)) return true;
    }
    return false;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
