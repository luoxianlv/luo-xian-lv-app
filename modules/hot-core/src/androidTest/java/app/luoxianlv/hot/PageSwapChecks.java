package app.luoxianlv.hot;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import app.luoxianlv.hot.contract.HostActions;
import app.luoxianlv.hot.contract.NativePage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 真实窗口验证切换事务、状态复制、旧代际租约和失败恢复；测试模块不冒充完整 APP 迁移。 */
final class PageSwapChecks {
  static void run(HotCoreInstrumentation runner, File root) throws Exception {
    stateBoundaries();
    for (String mode :
        new String[] {
          "healthy",
          "create_failure",
          "trial_failure",
          "input_changed",
          "system_scope",
          "system_busy",
          "system_rollback",
          "system_callback_failure",
          "system_resume_failure",
          "editor_busy",
          "input_isolation",
          "rollback_restore_failure",
          "async_failure"
        }) {
      new Case(runner, root, mode).run();
    }
  }

  private static void stateBoundaries() {
    Bundle state = new Bundle();
    byte[] original = new byte[] {1, 2};
    state.putByteArray("bytes", original);
    Bundle copied = PageState.copy(state);
    original[0] = 9;
    check(copied.getByteArray("bytes")[0] == 1, "状态没有深复制");
    state.putBundle("self", state);
    rejects(() -> PageState.copy(state));
    Bundle large = new Bundle();
    large.putByteArray("big", new byte[65536]);
    rejects(() -> PageState.copy(large));
    Bundle foreign = new Bundle();
    foreign.putParcelable("intent", new Intent());
    rejects(() -> PageState.copy(foreign));
  }

  private static final class Case {
    final HotCoreInstrumentation runner;
    final String mode;
    final File root;
    final AtomicLong clock = new AtomicLong(1200);
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final AtomicInteger events = new AtomicInteger();
    final CountDownLatch exposed = new CountDownLatch(1), done = new CountDownLatch(1);
    final AtomicReference<String> result = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    NativeHarnessActivity activity;
    ActivationController controller;
    ActivationJournal journal;
    ContentQuarantine quarantine;
    ActivationController.Ticket ticket;
    PageSwapHost host;
    TrackingPage old;
    AutoCloseable lease;
    final Platform platform = new Platform();
    String candidateKey;
    boolean baselineFailure;

    Case(HotCoreInstrumentation runner, File root, String mode) {
      this.runner = runner;
      this.mode = mode;
      this.root = new File(root, "swap-" + UUID.randomUUID());
      check(this.root.mkdirs(), "无法创建切换验证目录");
    }

    byte[] read(String name) throws Exception {
      try (InputStream input = runner.getContext().getAssets().open("permit-v1/" + name)) {
        return HotPackage.read(input, StrictJson.MAX_BYTES);
      }
    }

    void run() throws Exception {
      try {
        HotSignatures.PublicKey key =
            new HotSignatures.PublicKey(StrictJson.object(read("root.public.json")));
        journal = new ActivationJournal(new File(root, "state"));
        quarantine = new ContentQuarantine(new File(root, "quarantine"));
        TrustStore trust = new TrustStore(new File(root, "trust"), key);
        StrictJson.Obj permitFields = StrictJson.object(read("permit.json"));
        Instant time = Instant.ofEpochSecond(permitFields.number("issuedAt") + 1);
        TrustStore.Record authority =
            trust.accept(read("trust.json"), read("trust.sig.json"), journal, time);
        HotManifest manifest = new HotManifest(read("manifest.json"));
        File metadata = new File(root, "snapshot");
        check(metadata.mkdirs(), "无法准备清单");
        Files.write(new File(metadata, "manifest.json").toPath(), read("manifest.json"));
        Files.write(new File(metadata, "manifest.sig.json").toPath(), read("manifest.sig.json"));
        ContentStore.Snapshot snapshot = new ContentStore.Snapshot(manifest, metadata);
        ActivationPermit.Request request =
            new ActivationPermit.Request(
                permitFields.string("installationId"),
                permitFields.string("nonce"),
                permitFields.string("hostIdentity"),
                1,
                permitFields.number("channelRevision"),
                1000,
                manifest);
        ActivationPermit permit =
            new ActivationPermit(
                read("permit.json"),
                read("permit.sig.json"),
                authority.authority,
                request,
                1,
                1,
                time,
                1100);
        controller = new ActivationController(journal, trust, quarantine, 1, clock::get);
        ticket = controller.begin(snapshot, permit, time, android.os.Process.myPid());
        Intent launch =
            new Intent(runner.getContext(), NativeHarnessActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (NativeHarnessActivity) runner.startActivitySync(launch);
        main(
            () -> {
              host =
                  new PageSwapHost(
                      activity,
                      controller,
                      worker,
                      (event, payload) -> events.incrementAndGet(),
                      (code, error) -> {
                        if (code.equals("candidate_exposed")) exposed.countDown();
                        else if (code.equals("baseline_recovery_failed")) baselineFailure = true;
                        else if (!code.equals("generation_close_failed")) {
                          result.set(code);
                          failure.set(error);
                          done.countDown();
                        }
                      });
              activity.swap = host;
              host.attachHost(platform);
              activity.container.addView(host, new android.widget.FrameLayout.LayoutParams(-1, -1));
              host.lifecycle(NativePage.RESUMED);
              old = mode.equals("editor_busy") ? new EditorPage() : new TrackingPage();
              Bundle saved = new Bundle();
              saved.putInt("position", 37);
              host.initial(old, activity, saved);
              if (mode.equals("editor_busy")) check(old.view.requestFocus(), "输入框无法获得焦点");
              events.set(0);
              if (mode.equals("healthy")) lease = host.pinActive();
              ActionPage.latest = null;
              if (mode.equals("system_busy")) platform.busy = true;
              Class<? extends NativePage> entry =
                  mode.equals("create_failure")
                      ? BrokenPage.class
                      : mode.equals("async_failure")
                          ? AsyncFailurePage.class
                          : mode.equals("input_changed") || mode.equals("input_isolation")
                              ? DelayedPage.class
                              : mode.equals("system_resume_failure")
                                  ? ResumeFailurePage.class
                                  : mode.startsWith("system_") || mode.equals("editor_busy")
                                      ? ActionPage.class
                                      : TrackingPage.class;
              NativeLoader.Prepared prepared =
                  new NativeLoader.Prepared(
                      manifest, entry, getClass().getClassLoader(), activity.getResources(), null);
              check(host.offer(prepared, ticket), "候选没有被接收");
            });
        if (mode.equals("system_busy") || mode.equals("editor_busy")) {
          SystemClock.sleep(250);
          main(
              () -> {
                check(ActionPage.latest == null, "系统选择或输入未结束却已实例化候选");
                platform.busy = false;
                if (mode.equals("editor_busy")) {
                  check(host.findFocus().onCheckIsTextEditor(), "未覆盖原生编辑焦点");
                  old.view.setFocusable(false);
                  old.view.clearFocus();
                }
              });
        }
        if (mode.equals("input_changed") || mode.equals("input_isolation")) {
          long until = SystemClock.elapsedRealtime() + 10000;
          while (DelayedPage.pending == null && SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(20);
          check(DelayedPage.pending != null, "等待状态变化的候选没有创建");
          main(
              () -> {
                if (mode.equals("input_isolation")) {
                  TrackingPage page = DelayedPage.page;
                  page.view.setFocusableInTouchMode(true);
                  check(!page.view.requestFocus(), "预绘制页抢走了输入焦点");
                  page.view.setElevation(1000);
                  old.view.setOnClickListener(null);
                  old.view.setClickable(false);
                  long gestureTime = SystemClock.uptimeMillis();
                  for (int action :
                      new int[] {
                        android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP
                      }) {
                    android.view.MotionEvent event =
                        android.view.MotionEvent.obtain(
                            gestureTime,
                            gestureTime,
                            action,
                            host.getWidth() / 2f,
                            host.getHeight() / 2f,
                            0);
                    try {
                      host.dispatchTouchEvent(event);
                    } finally {
                      event.recycle();
                    }
                  }
                  check(page.position == 37, "触摸穿透到了隐藏候选页");
                } else host.updateHostState(new Bundle());
                DelayedPage.pending.ready();
                DelayedPage.pending = null;
                DelayedPage.page = null;
              });
        } else if (!mode.equals("create_failure")
            && !mode.equals("async_failure")
            && !mode.equals("system_resume_failure")) {
          check(
              exposed.await(15, TimeUnit.SECONDS), "新页面未曝光：" + result.get() + " " + failure.get());
          main(
              () -> {
                check(host.save().getInt("position") == 37, "切换丢失原页面状态");
                check(events.get() == 0, "后台准备的候选触发了实际宿主行为");
                check(!old.closed, "旧页面在试运行结束前被关闭");
                if (mode.startsWith("system_") || mode.equals("editor_busy")) {
                  check(
                      platform.launches == 1 && platform.permissions == 0 && platform.finishes == 0,
                      "候选创建期间调用了系统入口，或正式展示后的请求丢失");
                  candidateKey = platform.key;
                  old.actions.launch("selection", new Intent(), null);
                  old.actions.permissions(
                      "permissions", new String[] {"android.permission.CAMERA"});
                  old.actions.finish();
                  old.actions.open(new Intent(), true);
                  old.actions.closePage();
                  check(
                      platform.launches == 1 && platform.permissions == 0 && platform.finishes == 0,
                      "旧代际仍能操作系统入口");
                  platform.busy = false;
                  if (mode.equals("system_callback_failure")) ActionPage.latest.failResult = true;
                  check(
                      host.result(candidateKey, android.app.Activity.RESULT_CANCELED, null),
                      "当前代际回调未消费");
                  if (!mode.equals("system_callback_failure"))
                    check(
                        ActionPage.latest.results == 1
                            && "selection".equals(ActionPage.latest.resultKey),
                        "系统键未还原给正确业务页");
                }
                if (mode.equals("rollback_restore_failure")) old.failResume = true;
                if (mode.equals("trial_failure")
                    || mode.equals("system_rollback")
                    || mode.equals("rollback_restore_failure"))
                  host.failActive(new IllegalStateException("测试注入的业务故障"));
                else if (!mode.equals("system_callback_failure")) clock.addAndGet(60000);
              });
        }
        check(done.await(15, TimeUnit.SECONDS), "切换事务没有结束：" + mode);
        String expected =
            success()
                ? "candidate_stable"
                : mode.equals("create_failure")
                    ? "candidate_create_failed"
                    : mode.equals("async_failure")
                        ? "candidate_prepare_failed"
                        : mode.equals("trial_failure")
                                || mode.equals("system_rollback")
                                || mode.equals("rollback_restore_failure")
                            ? "candidate_failed"
                            : mode.equals("system_callback_failure")
                                ? "candidate_callback_failed"
                                : mode.equals("system_resume_failure")
                                    ? "candidate_swap_failed"
                                    : "candidate_input_changed";
        check(expected.equals(result.get()), "切换结果错误：" + result.get() + " " + failure.get());
        if (success()) {
          if (lease != null) {
            main(() -> check(!old.closed, "演奏租约未释放却关闭旧代际"));
            // 模拟工作线程已投递释放，但主线程先关闭页面；关闭不能清掉这次归还。
            main(
                () -> {
                  Thread releaser =
                      new Thread(
                          () -> {
                            try {
                              lease.close();
                            } catch (Exception error) {
                              throw new AssertionError(error);
                            }
                          });
                  releaser.start();
                  try {
                    releaser.join(1000);
                  } catch (InterruptedException error) {
                    throw new AssertionError(error);
                  }
                  check(!releaser.isAlive(), "租约线程未完成投递");
                  host.close();
                });
            lease = null;
          }
          long until = SystemClock.elapsedRealtime() + 3000;
          while (!old.closed && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20);
          check(old.closed, "旧代际租约释放后未关闭");
          check(journal.state().stable.equals(manifest.snapshotId), "健康版本未落盘");
        } else {
          main(
              () -> {
                check(!old.closed, "恢复时丢失旧页面");
                check(
                    baselineFailure == mode.equals("rollback_restore_failure"), "旧页恢复故障未交给稳定宿主处理");
                check(host.save().getInt("position") == 37, "恢复后的状态改变");
                if (mode.startsWith("system_")) {
                  String stale = candidateKey == null ? platform.key : candidateKey;
                  int count = old.results;
                  check(
                      host.result(stale, android.app.Activity.RESULT_OK, new Intent()), "失效结果未消费");
                  check(old.results == count, "失败代际的结果交给了旧页同名回调");
                  ActionPage.latest.actions.launch("selection", new Intent(), null);
                  check(platform.launches == 1, "已回退的候选仍能发起系统操作");
                  platform.busy = false;
                  old.actions.launch("selection", new Intent(), null);
                  check(!stale.equals(platform.key), "回退页继承了故障代际的请求身份");
                  check(
                      host.result(platform.key, android.app.Activity.RESULT_OK, null)
                          && old.results == count + 1,
                      "旧页恢复后的新请求未投递");
                }
              });
          check(journal.state().active.isEmpty(), "错误候选仍是活动版本");
          boolean isolated = journal.state().quarantine.contains(manifest.snapshotId);
          check(
              isolated == !(mode.equals("input_changed") || mode.equals("input_isolation")),
              "错误归因或内容隔离不正确");
        }
      } finally {
        if (lease != null) lease.close();
        if (activity != null) main(activity::finish);
        runner.waitForIdleSync();
        worker.shutdown();
        worker.awaitTermination(5, TimeUnit.SECONDS);
      }
    }

    boolean success() {
      return mode.equals("healthy")
          || mode.equals("system_scope")
          || mode.equals("system_busy")
          || mode.equals("editor_busy");
    }

    void main(Runnable action) throws Exception {
      AtomicReference<Throwable> error = new AtomicReference<>();
      runner.runOnMainSync(
          () -> {
            try {
              action.run();
            } catch (Throwable e) {
              error.set(e);
            }
          });
      if (error.get() != null) throw new AssertionError("窗口验证失败", error.get());
    }
  }

  public static class TrackingPage implements NativePage {
    volatile boolean closed;
    int position;
    HostActions actions;
    int results;
    String resultKey;
    boolean failResult;
    boolean failResume;
    TextView view;

    protected TextView view(Context context) {
      return new TextView(context);
    }

    @Override
    public void attachHost(HostActions actions) {
      this.actions = actions;
    }

    @Override
    public boolean result(String key, int code, Intent data) {
      if (failResult) throw new IllegalStateException("测试注入的回调故障");
      results++;
      resultKey = key;
      return true;
    }

    @Override
    public View create(Context context, Bundle state, Bundle host, Events events, Ready ready) {
      position = state.getInt("position");
      view = view(context);
      view.setText("原生页面 " + position);
      view.setOnClickListener(
          v -> {
            position++;
            events.emit("clicked", Bundle.EMPTY);
          });
      events.emit("prepared-side-effect", Bundle.EMPTY);
      ready.ready();
      return view;
    }

    @Override
    public Bundle save() {
      Bundle b = new Bundle();
      b.putInt("position", position);
      return b;
    }

    @Override
    public void updateHostState(Bundle state) {}

    @Override
    public void lifecycle(int state) {
      if (failResume && state == RESUMED) throw new IllegalStateException("测试注入的旧页恢复故障");
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  public static final class BrokenPage extends TrackingPage {
    @Override
    public View create(Context context, Bundle state, Bundle host, Events events, Ready ready) {
      throw new IllegalStateException("测试注入的创建故障");
    }
  }

  public static final class AsyncFailurePage extends TrackingPage {
    @Override
    public View create(Context context, Bundle state, Bundle host, Events events, Ready ready) {
      View view = super.create(context, state, host, events, () -> {});
      view.post(() -> ready.failed(new IllegalStateException("测试注入的异步准备失败")));
      return view;
    }
  }

  public static final class EditorPage extends TrackingPage {
    @Override
    protected TextView view(Context context) {
      return new android.widget.EditText(context);
    }
  }

  public static class ActionPage extends TrackingPage {
    static volatile ActionPage latest;
    private boolean launched;

    @Override
    public View create(Context context, Bundle state, Bundle host, Events events, Ready ready) {
      latest = this;
      actions.launch("preparing", new Intent(), null);
      actions.permissions("preparing_permission", new String[] {"android.permission.CAMERA"});
      actions.finish();
      actions.open(new Intent(), true);
      actions.closePage();
      actions.resultReady("selection");
      return super.create(context, state, host, events, ready);
    }

    @Override
    public void lifecycle(int state) {
      if (state == RESUMED && !launched) {
        launched = true;
        actions.launch("selection", new Intent(), null);
      }
    }
  }

  public static final class ResumeFailurePage extends ActionPage {
    @Override
    public void lifecycle(int state) {
      super.lifecycle(state);
      if (state == RESUMED) throw new IllegalStateException("测试注入的恢复故障");
    }
  }

  private static final class Platform implements HostActions {
    boolean busy;
    int launches, permissions, finishes;
    String key;

    @Override
    public void launch(String key, Intent intent, Bundle options) {
      this.key = key;
      launches++;
      busy = true;
    }

    @Override
    public void permissions(String key, String[] names) {
      this.key = key;
      permissions++;
      busy = true;
    }

    @Override
    public void resultReady(String key) {}

    @Override
    public boolean hasPendingResults() {
      return busy;
    }

    @Override
    public void finish() {
      finishes++;
    }
  }

  public static final class DelayedPage extends TrackingPage {
    static volatile Ready pending;
    static volatile DelayedPage page;

    @Override
    public View create(Context context, Bundle state, Bundle host, Events events, Ready ready) {
      pending = ready;
      page = this;
      return super.create(context, state, host, events, () -> {});
    }
  }

  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  static void rejects(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError("应拒绝不安全的页面状态");
  }
}
