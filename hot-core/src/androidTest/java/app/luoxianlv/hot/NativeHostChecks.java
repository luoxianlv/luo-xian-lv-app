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
                } else
                  check(
                      out.getBundle("native.page").getBundle("session.page").getInt("position")
                          == 42,
                      "基础状态未保存");
              }
            });
      } finally {
        runner.runOnMainSync(activity::finish);
        runner.waitForIdleSync();
      }
    }
    NativeLifecycleHarness.mode = "healthy";
    pendingResultAcrossRecreate(runner);
    sessionIdentity(runner);
    delayedPreparation(runner);
  }

  private static void delayedPreparation(Instrumentation runner) {
    for (String mode : new String[] {"delayed", "delayed-create", "delayed-destroy"}) {
      NativeLifecycleHarness.mode = mode;
      NativeLifecycleHarness activity =
          (NativeLifecycleHarness)
              runner.startActivitySync(
                  new Intent(runner.getContext(), NativeLifecycleHarness.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        onMain(
            runner,
            () -> {
              check(
                  activity.creates == 0 && contains(activity.getWindow().getDecorView(), "正在准备…"),
                  "异步准备之前执行了业务");
              if (mode.equals("delayed-destroy")) activity.finish();
              else {
                activity.prepared.run();
                activity.prepared.run();
                check(activity.creates == 1, "就绪回调重复创建业务");
                check(
                    contains(
                        activity.getWindow().getDecorView(),
                        mode.equals("delayed-create") ? "重试" : "业务已显示"),
                    "延迟准备的页面或恢复错误");
                if (mode.equals("delayed"))
                  check(
                      activity.lifecycleEvents.get(activity.lifecycleEvents.size() - 1)
                          == NativePage.RESUMED,
                      "晚到业务没有收到当前窗口状态");
              }
            });
      } finally {
        runner.runOnMainSync(activity::finish);
        runner.waitForIdleSync();
      }
      long deadline = android.os.SystemClock.uptimeMillis() + 10000;
      while (!activity.isDestroyed() && android.os.SystemClock.uptimeMillis() < deadline)
        android.os.SystemClock.sleep(30);
      check(activity.isDestroyed(), "系统没有完成测试窗口的销毁");
      onMain(
          runner,
          () -> {
            activity.prepared.run();
            check(activity.creates == (mode.equals("delayed-destroy") ? 0 : 1), "已结束窗口仍执行晚到业务");
            check(activity.preparationCloses == 1, "准备监听没有释放");
          });
    }
    NativeLifecycleHarness.mode = "healthy";
  }

  private static void sessionIdentity(Instrumentation runner) {
    onMain(
        runner,
        () -> {
          String[] requestKey = {null};
          app.luoxianlv.hot.contract.HostActions platform =
              new app.luoxianlv.hot.contract.HostActions() {
                @Override
                public void launch(String key, Intent intent, Bundle options) {
                  requestKey[0] = key;
                }

                @Override
                public void permissions(String key, String[] permissions) {
                  requestKey[0] = key;
                }

                @Override
                public void resultReady(String key) {}

                @Override
                public boolean hasPendingResults() {
                  return false;
                }

                @Override
                public void finish() {}
              };
          PageSwapHost original =
              new PageSwapHost(
                  runner.getContext(),
                  null,
                  Runnable::run,
                  (event, data) -> {},
                  (code, error) -> {});
          original.attachHost(platform);
          original.lifecycle(NativePage.RESUMED);
          PageSwapChecks.TrackingPage old = new PageSwapChecks.TrackingPage();
          Bundle business = new Bundle();
          business.putInt("position", 37);
          original.initialSession(old, runner.getContext(), business, "content-a", () -> {});
          old.actions.launch("selection", new Intent(), null);
          String originalKey = requestKey[0];
          Bundle saved = original.saveSession();
          original.close();
          for (String identity : new String[] {"content-a", "content-b"}) {
            PageSwapHost restored =
                new PageSwapHost(
                    runner.getContext(),
                    null,
                    Runnable::run,
                    (event, data) -> {},
                    (code, error) -> {});
            try {
              restored.attachHost(platform);
              restored.lifecycle(NativePage.RESUMED);
              PageSwapChecks.TrackingPage page = new PageSwapChecks.TrackingPage();
              restored.initialSession(page, runner.getContext(), saved, identity, () -> {});
              check(
                  restored.result(originalKey, android.app.Activity.RESULT_OK, null),
                  "恢复后的系统结果未消费");
              boolean same = identity.equals("content-a");
              check(
                  page.position == (same ? 37 : 0) && page.results == (same ? 1 : 0),
                  "不同内容继承了旧会话状态或结果");
              page.actions.launch("selection", new Intent(), null);
              check(originalKey.equals(requestKey[0]) == same, "页面请求身份未与内容身份一致恢复");
            } finally {
              restored.close();
            }
          }
        });
  }

  private static void pendingResultAcrossRecreate(Instrumentation runner) {
    NativeLifecycleHarness original =
        (NativeLifecycleHarness)
            runner.startActivitySync(
                new Intent(runner.getContext(), NativeLifecycleHarness.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Instrumentation.ActivityMonitor selection =
        runner.addMonitor(NativeSelectionHarness.class.getName(), null, false);
    NativeSelectionHarness picker = null;
    try {
      onMain(
          runner,
          () ->
              original.actions.launch(
                  "selection", new Intent(original, NativeSelectionHarness.class), null));
      picker = (NativeSelectionHarness) runner.waitForMonitorWithTimeout(selection, 10000);
      check(picker != null, "选择窗口未打开");
      onMain(runner, original::recreate);
      // Android 可推迟后台 Activity 重建；返回时必须将结果移交给恢复后的会话。
      NativeSelectionHarness chosen = picker;
      onMain(
          runner,
          () -> {
            chosen.setResult(
                android.app.Activity.RESULT_OK,
                new Intent().setData(android.net.Uri.parse("content://test/曲谱.mid")));
            chosen.finish();
          });
      long deadline = android.os.SystemClock.uptimeMillis() + 15000;
      boolean[] complete = {false};
      while (!complete[0] && android.os.SystemClock.uptimeMillis() < deadline) {
        onMain(
            runner,
            () -> {
              NativeLifecycleHarness current = NativeLifecycleHarness.latest;
              complete[0] = current != original && current.results == 1 && current.hasWindowFocus();
            });
        if (!complete[0]) android.os.SystemClock.sleep(50);
      }
      check(complete[0], "选择期间重建后未收到结果");
      onMain(
          runner,
          () -> {
            NativeLifecycleHarness current = NativeLifecycleHarness.latest;
            check("selection".equals(current.resultKey), "系统结果的代际前缀泄漏给了业务");
            check("content://test/曲谱.mid".equals(current.resultData.getDataString()), "文件结果在重建时丢失");
            check(!current.actions.hasPendingResults(), "完成系统结果后仍锁住页面替换");
          });
    } finally {
      runner.removeMonitor(selection);
      NativeSelectionHarness opened = picker;
      onMain(
          runner,
          () -> {
            if (opened != null) opened.finish();
            if (NativeLifecycleHarness.latest != null) NativeLifecycleHarness.latest.finish();
            if (!original.isDestroyed()) original.finish();
          });
      runner.waitForIdleSync();
    }
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
