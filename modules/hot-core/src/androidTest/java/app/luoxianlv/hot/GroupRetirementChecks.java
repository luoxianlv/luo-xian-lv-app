package app.luoxianlv.hot;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 回退不能仅凭页面恢复结束：真实后台租约未释放时保留事务，关闭错误阻止后续交接。 */
final class GroupRetirementChecks {
  static void run(HotCoreInstrumentation runner) throws Exception {
    scenario(runner, true, false);
    scenario(runner, false, false);
    scenario(runner, true, true);
    confirmedWithOldLease(runner, false);
    confirmedWithOldLease(runner, true);
  }

  private static void confirmedWithOldLease(HotCoreInstrumentation runner, boolean closeWindow)
      throws Exception {
    var activity =
        (NativeHarnessActivity)
            runner.startActivitySync(
                new Intent(runner.getContext(), NativeHarnessActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    var original = new PageSwapChecks.TrackingPage();
    var candidate = new PageSwapChecks.TrackingPage();
    var ready = new CountDownLatch(1);
    var error = new AtomicReference<Throwable>();
    var closed = new AtomicInteger();
    var acknowledged = new AtomicInteger();
    AutoCloseable[] pin = new AutoCloseable[1];
    GroupHandover[] group = new GroupHandover[1];
    var prepared =
        new NativeLoader.Prepared(
            null,
            candidate.getClass(),
            candidate.getClass().getClassLoader(),
            activity.getResources(),
            activity.getFilesDir());
    try {
      main(
          runner,
          () -> {
            activity.swap =
                new PageSwapHost(
                    activity, null, Runnable::run, (event, state) -> {}, (code, failure) -> {});
            activity.container.addView(
                activity.swap, new android.widget.FrameLayout.LayoutParams(-1, -1));
            activity.swap.lifecycle(NativePage.RESUMED);
            activity.swap.initial(original, activity, new Bundle());
            activity.swap.initialTarget(target());
          });
      await(runner, "成功收尾没有安全点", () -> activity.swap.canStage());
      main(
          runner,
          () -> {
            pin[0] = activity.swap.pinActive();
            group[0] =
                GroupHandover.prepare(
                    prepared,
                    new BusinessFactory() {
                      public NativePage page(String route) {
                        return candidate;
                      }

                      public NativePlaybackSession playback() {
                        throw new AssertionError("不应创建播放");
                      }

                      public ProcessHooks process(Context context) {
                        throw new AssertionError("不应创建进程");
                      }

                      public ForegroundPolicy foreground(Context context) {
                        throw new AssertionError("不应创建服务");
                      }
                    },
                    List.of(new GroupHandover.Page(activity.swap, "main")),
                    null,
                    new GroupHandover.Publication() {
                      public void selectCandidate() {}

                      public void restorePrevious() {}

                      public void finish() {
                        closed.incrementAndGet();
                      }

                      public boolean released() {
                        acknowledged.incrementAndGet();
                        return true;
                      }
                    },
                    new GroupHandover.Listener() {
                      public void ready(GroupHandover value) {
                        ready.countDown();
                      }

                      public void failed(
                          GroupHandover value, Throwable failure, boolean contentFailure) {
                        error.set(failure);
                        ready.countDown();
                      }
                    });
          });
      check(ready.await(10, TimeUnit.SECONDS) && error.get() == null, "成功候选未就绪");
      main(
          runner,
          () -> {
            check(group[0].commit(), "成功候选未提交");
            if (closeWindow) activity.swap.close();
            group[0].finish();
            group[0].finish();
            check(
                group[0].phase() == GroupHandover.Phase.FINISHING && !original.closed,
                "旧页面仍被租用却宣布退出");
            check(closed.get() == 1 && acknowledged.get() == 0, "重复关闭旧业务或过早释放旧模块监听");
          });
      pin[0].close();
      pin[0] = null;
      await(
          runner,
          "旧页面租约释放后没有收尾",
          () -> {
            group[0].finish();
            return group[0].retired();
          });
      check(original.closed && closed.get() == 1 && acknowledged.get() == 1, "成功收尾没有准确确认旧页面退出");
    } finally {
      if (pin[0] != null) pin[0].close();
      main(
          runner,
          () -> {
            prepared.closeCallbacks();
            activity.finish();
          });
    }
  }

  private static void scenario(HotCoreInstrumentation runner, boolean exposed, boolean failRelease)
      throws Exception {
    var activity =
        (NativeHarnessActivity)
            runner.startActivitySync(
                new Intent(runner.getContext(), NativeHarnessActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    var candidate = new PageSwapChecks.TrackingPage();
    var began = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    var ready = new CountDownLatch(1);
    var restored = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var completed = new AtomicInteger();
    var discarded = new AtomicInteger();
    var selected = new AtomicInteger();
    var configurationCalls = new AtomicInteger();
    var gate = new WorkGate();
    var lease = gate.acquire();
    Thread task =
        new Thread(
            () -> {
              try (lease) {
                began.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("测试工作未获释放");
              } catch (Throwable error) {
                failure.compareAndSet(null, error);
              } finally {
                done.countDown();
              }
            },
            "回退候选工作验收");
    task.start();
    var prepared =
        new NativeLoader.Prepared(
            null,
            PageSwapChecks.TrackingPage.class,
            candidate.getClass().getClassLoader(),
            activity.getResources(),
            activity.getFilesDir());
    GroupHandover[] group = new GroupHandover[1];
    AutoCloseable[] pin = new AutoCloseable[1];
    try {
      check(began.await(10, TimeUnit.SECONDS), "候选后台任务没有开始");
      main(
          runner,
          () -> {
            var application = prepared.context(activity).getApplicationContext();
            application.registerComponentCallbacks(
                new android.content.ComponentCallbacks() {
                  public void onConfigurationChanged(android.content.res.Configuration value) {
                    configurationCalls.incrementAndGet();
                  }

                  public void onLowMemory() {}
                });
            activity
                .getApplication()
                .onConfigurationChanged(
                    new android.content.res.Configuration(
                        activity.getResources().getConfiguration()));
            check(configurationCalls.get() == 1, "候选监听没有登记到真实进程应用");
            activity.swap =
                new PageSwapHost(
                    activity, null, Runnable::run, (event, state) -> {}, (code, error) -> {});
            activity.container.addView(
                activity.swap, new android.widget.FrameLayout.LayoutParams(-1, -1));
            activity.swap.lifecycle(NativePage.RESUMED);
            activity.swap.initial(new PageSwapChecks.TrackingPage(), activity, new Bundle());
            activity.swap.initialTarget(target());
          });
      await(runner, "没有交接安全点", () -> activity.swap.canStage());
      main(
          runner,
          () -> {
            group[0] =
                GroupHandover.prepare(
                    prepared,
                    new BusinessFactory() {
                      public NativePage page(String route) {
                        return candidate;
                      }

                      public NativePlaybackSession playback() {
                        throw new AssertionError("不应创建播放");
                      }

                      public ProcessHooks process(Context context) {
                        throw new AssertionError("不应创建进程");
                      }

                      public ForegroundPolicy foreground(Context context) {
                        throw new AssertionError("不应创建前台服务");
                      }
                    },
                    List.of(new GroupHandover.Page(activity.swap, "main")),
                    null,
                    new GroupHandover.Publication() {
                      public void selectCandidate() {
                        selected.incrementAndGet();
                      }

                      public void restorePrevious() {
                        selected.decrementAndGet();
                      }

                      public void discardCandidate() {
                        discarded.incrementAndGet();
                        gate.retire();
                      }

                      public boolean candidateReleased() {
                        if (failRelease) throw new IllegalStateException("测试：候选退出失败");
                        if (!gate.released()) return false;
                        prepared.closeCallbacks();
                        return true;
                      }
                    },
                    new GroupHandover.Listener() {
                      public void ready(GroupHandover value) {
                        ready.countDown();
                      }

                      public void failed(
                          GroupHandover value, Throwable error, boolean contentFailure) {
                        failure.set(error);
                        ready.countDown();
                      }
                    });
            check(group[0] != null, "候选准备没有建立事务");
          });
      check(ready.await(10, TimeUnit.SECONDS) && failure.get() == null, "候选没有准备完成");
      main(
          runner,
          () -> {
            if (exposed) {
              check(group[0].commit(), "候选未提交");
              if (!failRelease) pin[0] = activity.swap.pinActive();
            }
            group[0].rollback(
                new NativePage.Ready() {
                  public void ready() {
                    completed.incrementAndGet();
                    restored.countDown();
                  }

                  public void failed(Throwable error) {
                    failure.set(error);
                    completed.incrementAndGet();
                    restored.countDown();
                  }
                });
          });
      await(runner, "没有关闭候选", () -> discarded.get() == 1);
      if (!failRelease) {
        // 至少跨过两次轮询，证明回退完成没有抢在真实任务 finally 之前。
        check(!restored.await(400, TimeUnit.MILLISECONDS), "任务仍在执行就宣布回退完成");
        main(
            runner,
            () -> check(group[0].phase() == GroupHandover.Phase.RESTORING, "候选未退出就解除了事务门禁"));
      }
      release.countDown();
      check(done.await(10, TimeUnit.SECONDS), "候选任务未退出");
      task.join(1000);
      if (pin[0] != null) {
        check(!restored.await(300, TimeUnit.MILLISECONDS), "页面仍被租用就宣布候选退出");
        pin[0].close();
        pin[0] = null;
      }
      check(restored.await(10, TimeUnit.SECONDS), "候选释放后回退未完成");
      main(
          runner,
          () -> {
            check(
                completed.get() == 1 && discarded.get() == 1 && selected.get() == 0,
                "重复回调、重复关闭或来源没有恢复");
            check(candidate.closed && group[0].retired(), "候选页面或事务没有结束");
            check(failRelease == (failure.get() != null), "退出故障归因错误");
            if (!failRelease) {
              activity
                  .getApplication()
                  .onConfigurationChanged(
                      new android.content.res.Configuration(
                          activity.getResources().getConfiguration()));
              check(configurationCalls.get() == 1, "退役候选仍接收真实进程配置回调");
              boolean rejected = false;
              try {
                var application = prepared.context(activity).getApplicationContext();
                application.registerComponentCallbacks(
                    new android.content.ComponentCallbacks() {
                      public void onConfigurationChanged(
                          android.content.res.Configuration configuration) {}

                      public void onLowMemory() {}
                    });
              } catch (IllegalStateException | IllegalArgumentException expected) {
                rejected = true;
              }
              check(rejected, "退出后的候选还能登记进程监听");
            }
          });
    } finally {
      release.countDown();
      if (pin[0] != null) pin[0].close();
      task.join(2000);
      main(
          runner,
          () -> {
            prepared.closeCallbacks();
            activity.finish();
          });
    }
  }

  private static PageTarget target() {
    return new PageTarget() {
      public String identity() {
        return "baseline";
      }

      public Context context(Context owner) {
        return owner;
      }

      public NativePage create() {
        return new PageSwapChecks.TrackingPage();
      }
    };
  }

  private static void await(
      HotCoreInstrumentation runner, String message, BooleanSupplier condition) {
    long deadline = SystemClock.uptimeMillis() + 10000;
    while (true) {
      boolean[] result = new boolean[1];
      main(runner, () -> result[0] = condition.getAsBoolean());
      if (result[0]) return;
      check(SystemClock.uptimeMillis() < deadline, message);
      SystemClock.sleep(40);
    }
  }

  private static void main(HotCoreInstrumentation runner, Runnable action) {
    var error = new AtomicReference<Throwable>();
    runner.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable failure) {
            error.set(failure);
          }
        });
    if (error.get() != null) throw new AssertionError("退出验收主线程失败", error.get());
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
