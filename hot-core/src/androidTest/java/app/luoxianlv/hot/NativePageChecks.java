package app.luoxianlv.hot;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import app.luoxianlv.hot.contract.NativePage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 以正式包协议加载实际关于页；资源与 Compose 来自单独的共享运行时。 */
final class NativePageChecks {
  static void run(HotCoreInstrumentation runner, File root) throws Exception {
    byte[] publicKey;
    try (InputStream input = runner.getContext().getAssets().open("native/root.public.json")) {
      publicKey = HotPackage.read(input, StrictJson.MAX_BYTES);
    }
    HotSignatures.PublicKey key = new HotSignatures.PublicKey(StrictJson.object(publicKey));
    HotPackage.Policy policy =
        new HotPackage.Policy(
            key,
            runner.getContext().getPackageName(),
            "test",
            1,
            1,
            Instant.now(),
            Collections.emptySet(),
            null,
            null);
    ContentStore store = new ContentStore(new File(root, "native-store"));
    ContentQuarantine quarantine = new ContentQuarantine(new File(root, "native-quarantine"));
    ActivationJournal journal = new ActivationJournal(new File(root, "native-state"));
    ClassLoader sharedRuntime = null;
    Bundle previousState = new Bundle();
    for (String name : new String[] {"native.lxhp", "next.lxhp"}) {
      File archive = new File(root, name);
      try (InputStream input = runner.getContext().getAssets().open("native/" + name);
          FileOutputStream output = new FileOutputStream(archive)) {
        byte[] buffer = new byte[32768];
        int n;
        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
      }
      try (HotPackage candidate = new HotPackage(archive, policy)) {
        ContentStore.Snapshot snapshot = store.prepare(candidate);
        String attempt =
            journal.begin(
                snapshot.manifest.snapshotId,
                1,
                1,
                android.os.Process.myPid(),
                System.currentTimeMillis());
        NativeLoader loader = new NativeLoader(runner.getContext(), store, quarantine, 1);
        NativeLoader.Prepared prepared = loader.prepare(snapshot, journal.state());
        ClassLoader business = prepared.context(runner.getContext()).getClassLoader();
        if (sharedRuntime == null) sharedRuntime = business.getParent();
        else if (sharedRuntime != business.getParent()) throw new AssertionError("业务升级重新创建了共享运行时");
        boolean expectedNew = name.equals("next.lxhp");
        try {
          Class.forName("app.luoxianlv.hot.business.NextBadgeView", false, business);
          if (!expectedNew) throw new AssertionError("基础包意外包含新组件");
        } catch (ClassNotFoundException missing) {
          if (expectedNew) throw new AssertionError("新版未提供新组件", missing);
        }
        Bundle transferredState = previousState;
        Intent intent =
            new Intent(runner.getContext(), NativeHarnessActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NativeHarnessActivity activity = (NativeHarnessActivity) runner.startActivitySync(intent);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runner.runOnMainSync(
            () -> {
              try {
                NativePage page = prepared.instantiate();
                activity.page = page;
                Bundle host = new Bundle();
                host.putString("versionName", "1.0.9 原生热更验收");
                View view =
                    page.create(
                        prepared.context(activity),
                        transferredState,
                        host,
                        (event, payload) -> {},
                        ready::countDown);
                activity.container.addView(view);
                page.lifecycle(NativePage.RESUMED);
              } catch (Throwable error) {
                failure.set(error);
                ready.countDown();
              }
            });
        if (!ready.await(20, TimeUnit.SECONDS)) throw new AssertionError("动态关于页没有产生就绪首帧");
        if (failure.get() != null) throw new AssertionError("动态页面创建失败", failure.get());
        journal.firstFrame(attempt);
        AtomicReference<Bundle> saved = new AtomicReference<>();
        runner.runOnMainSync(
            () -> {
              activity.page.lifecycle(NativePage.CREATED);
              saved.set(activity.page.save());
              activity.page.lifecycle(NativePage.RESUMED);
            });
        if (saved.get() == null || !saved.get().containsKey("scrollY"))
          throw new AssertionError("业务页没有导出基础状态");
        previousState = saved.get();
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        runner.runOnMainSync(() -> found.set(containsNewView(activity.container)));
        if (found.get() != expectedNew) throw new AssertionError("新组件未按版本实际出现在原生 View 树中");
        SystemClock.sleep(250);
        android.graphics.Bitmap image = runner.getUiAutomation().takeScreenshot();
        if (image != null) {
          try (FileOutputStream output =
              new FileOutputStream(
                  new File(
                      runner.getContext().getFilesDir(),
                      expectedNew ? "native-about-next.png" : "native-about.png"))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
          } finally {
            image.recycle();
          }
        }
        runner.runOnMainSync(activity::finish);
        runner.waitForIdleSync();
        journal.fail(attempt, false);
      }
    }
  }

  private static boolean containsNewView(View view) {
    if (view.getClass().getName().equals("app.luoxianlv.hot.business.NextBadgeView")) return true;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        if (containsNewView(group.getChildAt(i))) return true;
    }
    return false;
  }
}
