package app.luoxianlv.hot;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.NativePage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 回退页已恢复后旧实例关闭失败：报告收尾错误，不再次访问已释放的 View。 */
final class GroupRecoveryChecks {
  static void run(HotCoreInstrumentation runner) throws Exception {
    var activity =
        (NativeHarnessActivity)
            runner.startActivitySync(
                new Intent(runner.getContext(), NativeHarnessActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    var old =
        new PageSwapChecks.TrackingPage() {
          @Override
          public void close() {
            super.close();
            throw new IllegalStateException("测试：旧页面关闭失败");
          }
        };
    var candidate = new PageSwapChecks.TrackingPage();
    var failure = new AtomicReference<Throwable>();
    var ready = new CountDownLatch(1);
    var restored = new CountDownLatch(1);
    var completions = new AtomicInteger();
    PageSwapHost.Change[] change = new PageSwapHost.Change[1];
    try {
      main(
          runner,
          () -> {
            activity.swap =
                new PageSwapHost(
                    activity, null, Runnable::run, (event, state) -> {}, (code, error) -> {});
            activity.container.addView(
                activity.swap, new android.widget.FrameLayout.LayoutParams(-1, -1));
            activity.swap.lifecycle(NativePage.RESUMED);
            Bundle state = new Bundle();
            state.putInt("position", 7);
            activity.swap.initial(old, activity, state);
            activity.swap.initialTarget(target("baseline", PageSwapChecks.TrackingPage::new));
          });
      long until = SystemClock.uptimeMillis() + 10000;
      boolean[] available = new boolean[1];
      do {
        main(runner, () -> available[0] = activity.swap.canStage());
        if (available[0]) break;
        PageSwapChecks.check(SystemClock.uptimeMillis() < until, "恢复故障检查没有安全点");
        SystemClock.sleep(40);
      } while (true);
      main(
          runner,
          () ->
              change[0] =
                  activity.swap.stage(
                      target("candidate", () -> candidate),
                      new PageSwapHost.ChangeListener() {
                        @Override
                        public void ready(PageSwapHost.Change change) {
                          ready.countDown();
                        }

                        @Override
                        public void failed(
                            PageSwapHost.Change change,
                            String code,
                            Throwable error,
                            boolean contentFailure) {
                          failure.set(error == null ? new AssertionError(code) : error);
                          ready.countDown();
                        }
                      }));
      PageSwapChecks.check(
          ready.await(10, TimeUnit.SECONDS) && failure.get() == null, "恢复故障候选没有准备好");
      main(
          runner,
          () -> {
            PageSwapChecks.check(change[0].commit(), "恢复故障候选没有提交");
            candidate.position = 83;
            change[0].rollback(
                new NativePage.Ready() {
                  @Override
                  public void ready() {
                    completions.incrementAndGet();
                    restored.countDown();
                  }

                  @Override
                  public void failed(Throwable error) {
                    failure.set(error);
                    completions.incrementAndGet();
                    restored.countDown();
                  }
                });
          });
      PageSwapChecks.check(restored.await(10, TimeUnit.SECONDS), "收尾故障使恢复永久等待");
      main(
          runner,
          () -> {
            PageSwapChecks.check(completions.get() == 1 && failure.get() != null, "关闭错误未准确报告一次");
            PageSwapChecks.check(old.closed && candidate.closed, "未覆盖关闭后恢复边界");
            PageSwapChecks.check(activity.swap.save().getInt("position") == 83, "收尾出错又丢掉已恢复的最新状态");
            PageSwapChecks.check(!activity.swap.canStage(), "关闭出错后仍接受下一组交接");
          });
    } finally {
      main(runner, activity::finish);
    }
  }

  private static PageTarget target(String identity, Supplier<NativePage> factory) {
    return new PageTarget() {
      @Override
      public String identity() {
        return identity;
      }

      @Override
      public Context context(Context owner) {
        return owner;
      }

      @Override
      public NativePage create() {
        return factory.get();
      }
    };
  }

  private static void main(HotCoreInstrumentation runner, Runnable action) {
    var failure = new AtomicReference<Throwable>();
    runner.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null) throw new AssertionError("恢复收尾检查失败", failure.get());
  }
}
