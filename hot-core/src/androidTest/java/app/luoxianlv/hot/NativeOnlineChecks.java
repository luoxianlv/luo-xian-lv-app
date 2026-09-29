package app.luoxianlv.hot;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.NativePage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 仅本机测试源站：已知基础关于页 → API 下载新组件 → 签名许可 → 同窗口替换 → 实际观察。 */
final class NativeOnlineChecks {
  static void run(HotCoreInstrumentation runner, File root, String source) throws Exception {
    URI origin = URI.create(source);
    check(origin.getHost().equals("127.0.0.1"), "在线验收只允许显式反向连接的本机源站");
    byte[] publicKey;
    try (InputStream input = runner.getContext().getAssets().open("native/root.public.json")) {
      publicKey = HotPackage.read(input, StrictJson.MAX_BYTES);
    }
    HotSignatures.PublicKey key = new HotSignatures.PublicKey(StrictJson.object(publicKey));
    File area = new File(root, "online");
    check(area.mkdirs(), "无法创建在线验收目录");
    File archive = new File(area, "base.lxhp");
    try (InputStream input = runner.getContext().getAssets().open("native/native.lxhp");
        FileOutputStream output = new FileOutputStream(archive)) {
      byte[] buffer = new byte[32768];
      int n;
      while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
    }
    ContentStore store = new ContentStore(new File(area, "store"));
    ContentQuarantine quarantine = new ContentQuarantine(new File(area, "quarantine"));
    ActivationJournal journal = new ActivationJournal(new File(area, "state"));
    TrustStore trust = new TrustStore(new File(area, "trust"), key);
    ActivationController controller =
        new ActivationController(journal, trust, quarantine, 1, SystemClock::elapsedRealtime);
    NativeLoader loader = new NativeLoader(runner.getContext(), store, quarantine, 1);
    NativeLoader.Prepared base;
    String baseAttempt;
    ContentStore.Snapshot baseline;
    try (HotPackage packaged =
        new HotPackage(
            archive,
            new HotPackage.Policy(
                key,
                runner.getContext().getPackageName(),
                "test",
                1,
                1,
                Instant.now(),
                Collections.emptySet(),
                null,
                null))) {
      baseline = store.prepare(packaged);
      // 此分支仅模拟 APK 内置、已知可用的基础组合；远端目标必须走下方真实许可路径。
      baseAttempt =
          journal.begin(
              baseline.manifest.snapshotId,
              1,
              1,
              android.os.Process.myPid(),
              System.currentTimeMillis());
      base = loader.prepare(baseline, journal.state());
    }
    ExecutorService worker = Executors.newSingleThreadExecutor();
    NativeHarnessActivity activity =
        (NativeHarnessActivity)
            runner.startActivitySync(
                new Intent(runner.getContext(), NativeHarnessActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    CountDownLatch baseReady = new CountDownLatch(1),
        exposed = new CountDownLatch(1),
        stable = new CountDownLatch(1);
    AtomicReference<Throwable> problem = new AtomicReference<>();
    AtomicReference<PageSwapHost> hostRef = new AtomicReference<>();
    try {
      main(
          runner,
          () -> {
            PageSwapHost host =
                new PageSwapHost(
                    activity,
                    controller,
                    worker,
                    (event, payload) -> {},
                    (code, error) -> {
                      if (code.equals("candidate_exposed")) exposed.countDown();
                      else if (code.equals("candidate_stable")) stable.countDown();
                      else {
                        problem.set(error == null ? new AssertionError(code) : error);
                        exposed.countDown();
                        stable.countDown();
                      }
                    });
            activity.swap = host;
            hostRef.set(host);
            activity.container.addView(host, new android.widget.FrameLayout.LayoutParams(-1, -1));
            Bundle state = new Bundle();
            state.putString("versionName", "1.0.9 在线原生热更验收");
            host.updateHostState(state);
            host.lifecycle(NativePage.RESUMED);
            try {
              host.initial(
                  base.instantiate(), base.context(activity), new Bundle(), baseReady::countDown);
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      check(baseReady.await(20, TimeUnit.SECONDS), "基础 Compose 关于页没有就绪");
      journal.firstFrame(baseAttempt);
      journal.healthy(baseAttempt, 60000);
      ClassLoader oldLoader = base.classLoader();
      try {
        Class.forName("app.luoxianlv.hot.business.NextBadgeView", false, oldLoader);
        throw new AssertionError("基础业务已经包含待下载组件");
      } catch (ClassNotFoundException expected) {
      }
      InstallationIdentity identity =
          InstallationIdentity.open(
              new File(area, "installation"), runner.getContext().getPackageName(), "test");
      HotApiClient api =
          new HotApiClient(
              origin,
              identity,
              1,
              HotSignatures.hash(
                  "native-host-contract-1".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              true);
      DownloadBudget budget = new DownloadBudget(new File(area, "budget"));
      ObjectDownloader downloads = new ObjectDownloader(new File(area, "downloads"), budget);
      UpdateClient client =
          new UpdateClient(
              api,
              key,
              store,
              trust,
              journal,
              controller,
              quarantine,
              downloads,
              budget,
              Collections.emptySet(),
              SystemClock::elapsedRealtime);
      UpdateClient.PreparedUpdate candidate = client.prepare(1, () -> false, () -> false);
      check(
          candidate != null
              && !candidate.snapshot.manifest.snapshotId.equals(baseline.manifest.snapshotId),
          "服务端未提供新的原生业务包");
      check(
          !candidate.needsRestart(baseline.manifest.runtime.sha256, baseline.manifest.runtimeAbi),
          "同运行时业务更新不应要求冷启动");
      check(
          downloads.partial(candidate.snapshot.manifest.business.sha256).isFile(), "新组件没有经过网络对象下载");
      ActivationController.Ticket ticket =
          client.authorize(candidate, 1, android.os.Process.myPid(), () -> false);
      NativeLoader.Prepared prepared = loader.prepare(candidate.snapshot, journal.state());
      ClassLoader nextLoader = prepared.classLoader();
      check(nextLoader.getParent() == oldLoader.getParent(), "在线更新重复实例化共享运行时");
      Class.forName("app.luoxianlv.hot.business.NextBadgeView", false, nextLoader);
      long started = SystemClock.elapsedRealtime();
      main(runner, () -> check(hostRef.get().offer(prepared, ticket), "原位替换未接受候选"));
      boolean visible = false;
      for (int attempt = 0; attempt < 8 && !visible; attempt++) {
        visible = exposed.await(5, TimeUnit.SECONDS);
        if (!visible) {
          AtomicReference<String> state = new AtomicReference<>();
          main(runner, () -> state.set(hostRef.get().diagnosticState()));
          Bundle progress = new Bundle();
          progress.putString("stream", "在线切换观察：" + state.get() + "\n");
          runner.sendStatus(0, progress);
        }
      }
      check(visible, "在线候选没有曝光");
      if (problem.get() != null) throw new AssertionError("在线替换失败", problem.get());
      main(runner, () -> check(containsNewView(activity.container), "下载的新组件未出现在实际 View 树"));
      check(stable.await(75, TimeUnit.SECONDS), "真实前台观察没有达标");
      if (problem.get() != null) throw new AssertionError("在线观察失败", problem.get());
      long observed = SystemClock.elapsedRealtime() - started;
      check(
          observed >= 60000
              && journal.state().stable.equals(candidate.snapshot.manifest.snapshotId),
          "未完成真实观察就标记稳定");
      HotApiClient.Decision unchanged = api.check(journal.state().stable, 1);
      check(unchanged.kind.equals("none") && unchanged.trust != null, "无新版本时未收到当前根授权");
      java.util.List<HealthEvent> events =
          java.util.Arrays.asList(
              new HealthEvent(ticket.attemptId, 1, "activated", "native_page_exposed"),
              new HealthEvent(ticket.attemptId, 2, "module_used", "native_about"),
              new HealthEvent(ticket.attemptId, 3, "healthy", "observed_60_seconds"));
      // 显式 online 验收只向本机 test 环境回报，不改变正式 Debug 的隐私策略。
      HotApiClient.ReportReply receipt = api.report(events, true);
      check(receipt != null && !receipt.autoPaused, "本机健康回报未被接受");
      check(api.report(events, true).revision == receipt.revision, "相同事件重试改变了发布决定");
      File report = new File(runner.getContext().getFilesDir(), "native-online-report.json");
      java.nio.file.Files.write(
          report.toPath(),
          JsonWire.encode(
              JsonWire.fields(
                  "passed",
                  true,
                  "source",
                  "local-rust-api",
                  "applicationId",
                  runner.getContext().getPackageName(),
                  "baseline",
                  baseline.manifest.snapshotId,
                  "target",
                  candidate.snapshot.manifest.snapshotId,
                  "trustVersion",
                  journal.state().trustVersion,
                  "observedForegroundMillis",
                  observed,
                  "runtimeReused",
                  true,
                  "newNativeView",
                  true,
                  "healthReportAccepted",
                  true,
                  "productionTouched",
                  false)));
      android.graphics.Bitmap image = runner.getUiAutomation().takeScreenshot();
      if (image != null)
        try (FileOutputStream output =
            new FileOutputStream(
                new File(runner.getContext().getFilesDir(), "native-online-about.png"))) {
          image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally {
          if (image != null) image.recycle();
        }
    } finally {
      main(runner, activity::finish);
      runner.waitForIdleSync();
      worker.shutdown();
      worker.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static boolean containsNewView(android.view.View view) {
    if (view.getClass().getName().equals("app.luoxianlv.hot.business.NextBadgeView")) return true;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        if (containsNewView(group.getChildAt(i))) return true;
    }
    return false;
  }

  private static void main(HotCoreInstrumentation runner, Runnable action) throws Exception {
    AtomicReference<Throwable> error = new AtomicReference<>();
    runner.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable failure) {
            error.set(failure);
          }
        });
    if (error.get() != null) throw new AssertionError("在线窗口操作失败", error.get());
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
