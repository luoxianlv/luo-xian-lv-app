package app.luoxianlv.hot;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
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
        new String[] {"healthy", "create_failure", "trial_failure", "input_changed"}) {
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
                        else if (!code.equals("generation_close_failed")) {
                          result.set(code);
                          failure.set(error);
                          done.countDown();
                        }
                      });
              activity.swap = host;
              activity.container.addView(host, new android.widget.FrameLayout.LayoutParams(-1, -1));
              host.lifecycle(NativePage.RESUMED);
              old = new TrackingPage();
              Bundle saved = new Bundle();
              saved.putInt("position", 37);
              host.initial(old, activity, saved);
              events.set(0);
              if (mode.equals("healthy")) lease = host.pinActive();
              Class<? extends NativePage> entry =
                  mode.equals("create_failure")
                      ? BrokenPage.class
                      : mode.equals("input_changed") ? DelayedPage.class : TrackingPage.class;
              NativeLoader.Prepared prepared =
                  new NativeLoader.Prepared(
                      manifest, entry, getClass().getClassLoader(), activity.getResources(), null);
              check(host.offer(prepared, ticket), "候选没有被接收");
            });
        if (mode.equals("input_changed")) {
          long until = SystemClock.elapsedRealtime() + 10000;
          while (DelayedPage.pending == null && SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(20);
          check(DelayedPage.pending != null, "等待状态变化的候选没有创建");
          main(
              () -> {
                host.updateHostState(new Bundle());
                DelayedPage.pending.ready();
                DelayedPage.pending = null;
              });
        } else if (!mode.equals("create_failure")) {
          check(
              exposed.await(15, TimeUnit.SECONDS), "新页面未曝光：" + result.get() + " " + failure.get());
          main(
              () -> {
                check(host.save().getInt("position") == 37, "切换丢失原页面状态");
                check(events.get() == 0, "后台准备的候选触发了实际宿主行为");
                check(!old.closed, "旧页面在试运行结束前被关闭");
                if (mode.equals("trial_failure"))
                  host.failActive(new IllegalStateException("测试注入的业务故障"));
                else clock.addAndGet(60000);
              });
        }
        check(done.await(15, TimeUnit.SECONDS), "切换事务没有结束：" + mode);
        String expected =
            mode.equals("healthy")
                ? "candidate_stable"
                : mode.equals("create_failure")
                    ? "candidate_create_failed"
                    : mode.equals("trial_failure") ? "candidate_failed" : "candidate_input_changed";
        check(expected.equals(result.get()), "切换结果错误：" + result.get() + " " + failure.get());
        if (mode.equals("healthy")) {
          main(() -> check(!old.closed, "演奏租约未释放却关闭旧代际"));
          lease.close();
          lease = null;
          long until = SystemClock.elapsedRealtime() + 3000;
          while (!old.closed && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20);
          check(old.closed, "旧代际租约释放后未关闭");
          check(journal.state().stable.equals(manifest.snapshotId), "健康版本未落盘");
        } else {
          main(
              () -> {
                check(!old.closed, "恢复时丢失旧页面");
                check(host.save().getInt("position") == 37, "恢复后的状态改变");
              });
          check(journal.state().active.isEmpty(), "错误候选仍是活动版本");
          boolean isolated = journal.state().quarantine.contains(manifest.snapshotId);
          check(
              isolated == (mode.equals("create_failure") || mode.equals("trial_failure")),
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

    @Override
    public View create(Context context, Bundle state, Bundle host, Events events, Ready ready) {
      position = state.getInt("position");
      Button view = new Button(context);
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
    public void lifecycle(int state) {}

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

  public static final class DelayedPage extends TrackingPage {
    static volatile Ready pending;

    @Override
    public View create(Context context, Bundle state, Bundle host, Events events, Ready ready) {
      pending = ready;
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
