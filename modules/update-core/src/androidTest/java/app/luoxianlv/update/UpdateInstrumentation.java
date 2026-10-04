package app.luoxianlv.update;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.UUID;

/** 在真实 Android ABI 上验证隔离 FD 解码与损坏输入，不安装 APK 或触碰活动对象。 */
public final class UpdateInstrumentation extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle report = new Bundle();
    try {
      File root = new File(getTargetContext().getFilesDir(), "delta-check-" + UUID.randomUUID());
      if (!root.mkdirs()) throw new AssertionError("无法创建测试目录");
      File base = inflate(root, "old.bin"), target = inflate(root, "new.bin");
      File patch = new File(root, "update.hpatch"), output = new File(root, "output.bin");
      try (InputStream input = getContext().getAssets().open("w26/update.hpatch");
          FileOutputStream out = new FileOutputStream(patch)) {
        copy(input, out);
      }
      Cancellation cancellation = new Cancellation();
      String baseDigest = ArtifactVerifier.sha256(base, cancellation, Progress.NONE);
      long started = System.nanoTime();
      IsolatedPatchMerger merger = new IsolatedPatchMerger(getTargetContext());
      String measured = measureMerge(base, patch, output, target.length());
      ArtifactVerifier.verify(
          output,
          target.length(),
          ArtifactVerifier.sha256(target, cancellation, Progress.NONE),
          cancellation);
      try (RandomAccessFile shortPatch = new RandomAccessFile(patch, "rw")) {
        shortPatch.setLength(patch.length() - 1);
      }
      boolean rejected = false;
      try {
        merger.merge(base, patch, output, target.length(), cancellation);
      } catch (java.io.IOException expected) {
        rejected = true;
      }
      if (!rejected) throw new AssertionError("截断补丁未拒绝");
      try (InputStream input = getContext().getAssets().open("w26/update.hpatch");
          FileOutputStream out = new FileOutputStream(patch)) {
        copy(input, out);
      }
      checkGenericOutputAliases(base, patch, target.length(), baseDigest);
      checkDescriptorGuards(base, patch, output, target.length());
      File stress = new File(root, "stress.hpatch");
      try (InputStream input = getContext().getAssets().open("w26/stress.hpatch");
          FileOutputStream out = new FileOutputStream(stress)) {
        copy(input, out);
      }
      checkCancel(base, stress, output);
      checkWorkerCrash(base, stress, output);
      if (!baseDigest.equals(ArtifactVerifier.sha256(base, cancellation, Progress.NONE)))
        throw new AssertionError("旧对象被修改");
      for (File file : root.listFiles()) Files.delete(file.toPath());
      Files.delete(root.toPath());
      report.putString(
          "stream",
          "隔离 FD 精确解码、截断拒绝、输入只读/输出别名防护、运行中取消及 worker 崩溃恢复通过；旧对象不变，整组耗时="
              + (System.nanoTime() - started) / 1000000
              + "ms\n"
              + measured
              + "\n");
      finish(-1, report);
    } catch (Throwable failure) {
      report.putString("stream", "增量解码检查失败：" + failure);
      finish(0, report);
    }
  }

  private void checkGenericOutputAliases(File base, File patch, long size, String original)
      throws Exception {
    IsolatedPatchMerger merger = new IsolatedPatchMerger(getTargetContext());
    boolean rejected = false;
    try {
      merger.merge(base, patch, base, size, new Cancellation());
    } catch (java.io.IOException expected) {
      rejected = true;
    }
    if (!rejected) throw new AssertionError("generic merger 未拒绝旧包作为输出");
    ArtifactVerifier.verify(base, 2250000, original, new Cancellation());
    File hardlink = new File(base.getParentFile(), "base-hardlink");
    try {
      Files.createLink(hardlink.toPath(), base.toPath());
    } catch (java.nio.file.AccessDeniedException deniedByTestImage) {
      // 测试镜像的 SELinux 禁止应用创建硬链接；仅为此临时测试文件由镜像 root 建立。
      shell("/system/xbin/su 0 ln " + base.getAbsolutePath() + " " + hardlink.getAbsolutePath());
      if (!hardlink.isFile() || !Files.isSameFile(base.toPath(), hardlink.toPath()))
        throw new AssertionError("测试镜像不能建立硬链接夹具");
    }
    try {
      rejected = false;
      try {
        merger.merge(base, patch, hardlink, size, new Cancellation());
      } catch (java.io.IOException expected) {
        rejected = true;
      }
      if (!rejected) throw new AssertionError("generic merger 未拒绝旧包硬链接输出");
      ArtifactVerifier.verify(base, 2250000, original, new Cancellation());
    } finally {
      Files.delete(hardlink.toPath());
    }
  }

  private String measureMerge(File base, File patch, File output, long size) throws Exception {
    try (Worker worker = new Worker(getTargetContext());
        android.os.ParcelFileDescriptor old =
            android.os.ParcelFileDescriptor.open(
                base, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        android.os.ParcelFileDescriptor diff =
            android.os.ParcelFileDescriptor.open(
                patch, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        android.os.ParcelFileDescriptor out =
            android.os.ParcelFileDescriptor.open(
                output,
                android.os.ParcelFileDescriptor.MODE_CREATE
                    | android.os.ParcelFileDescriptor.MODE_TRUNCATE
                    | android.os.ParcelFileDescriptor.MODE_READ_WRITE)) {
      String pid = workerPid();
      String before = shell("/system/xbin/su 0 cat /proc/" + pid + "/status");
      long start = System.nanoTime();
      int result = worker.remote.merge(old, diff, out, size, 180000, System.nanoTime());
      long elapsed = (System.nanoTime() - start) / 1000000;
      if (result != 0) throw new AssertionError("测量合并失败：" + result);
      String after = shell("/system/xbin/su 0 cat /proc/" + pid + "/status");
      long high = procKiB(after, "VmHWM"), rss = procKiB(after, "VmRSS");
      if (high < 0) return "合并 RPC+fsync=" + elapsed + "ms；OS峰值RSS无法读取，不用观测PSS替代峰值。";
      return "2,250,015 字节夹具：合并 RPC+fsync="
          + elapsed
          + "ms；工作进程 VmHWM="
          + high
          + "KiB，合并前 VmHWM="
          + procKiB(before, "VmHWM")
          + "KiB，完成时 VmRSS="
          + rss
          + "KiB。VmHWM为该新worker启动以来累计的真实进程峰值RSS，包含Android运行时，未重置OS计数。";
    }
  }

  private static long procKiB(String status, String key) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("(?m)^" + key + ":\\s+([0-9]+)\\s+kB\\s*$").matcher(status);
    return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
  }

  private String workerPid() throws Exception {
    String name = getTargetContext().getPackageName() + ":delta_worker";
    String pid =
        shell("pidof " + name + " " + name + ":" + DeltaWorkerService.class.getName())
            .trim()
            .split("\\s+")[0];
    if (!pid.matches("[0-9]+")) throw new AssertionError("无法找到隔离工作进程");
    return pid;
  }

  private void checkDescriptorGuards(File base, File patch, File output, long size)
      throws Exception {
    try (Worker worker = new Worker(getTargetContext());
        android.os.ParcelFileDescriptor writableBase =
            android.os.ParcelFileDescriptor.open(
                base, android.os.ParcelFileDescriptor.MODE_READ_WRITE);
        android.os.ParcelFileDescriptor diff =
            android.os.ParcelFileDescriptor.open(
                patch, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        android.os.ParcelFileDescriptor out =
            android.os.ParcelFileDescriptor.open(
                output, android.os.ParcelFileDescriptor.MODE_READ_WRITE)) {
      if (worker.remote.merge(writableBase, diff, out, size, 180000, System.nanoTime()) == 0)
        throw new AssertionError("可写基线 FD 未拒绝");
    }
    try (Worker worker = new Worker(getTargetContext());
        android.os.ParcelFileDescriptor old =
            android.os.ParcelFileDescriptor.open(
                base, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        android.os.ParcelFileDescriptor diff =
            android.os.ParcelFileDescriptor.open(
                patch, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        android.os.ParcelFileDescriptor sameFile =
            android.os.ParcelFileDescriptor.open(
                base, android.os.ParcelFileDescriptor.MODE_READ_WRITE)) {
      if (worker.remote.merge(old, diff, sameFile, size, 180000, System.nanoTime()) == 0)
        throw new AssertionError("旧包作为输出未拒绝");
    }
  }

  private void checkCancel(File base, File patch, File output) throws Exception {
    Cancellation cancellation = new Cancellation();
    MergeAttempt attempt = startMerge(base, patch, output, cancellation);
    waitForOutput(output, attempt.thread);
    cancellation.cancel();
    attempt.thread.join(15000);
    if (attempt.thread.isAlive()) throw new AssertionError("运行中取消未及时退出");
    if (!(attempt.failure.get() instanceof Cancellation.CancelledException))
      throw new AssertionError("取消被错误地转成失败或成功：" + attempt.failure.get());
    Files.deleteIfExists(output.toPath());
  }

  private void checkWorkerCrash(File base, File patch, File output) throws Exception {
    MergeAttempt attempt = startMerge(base, patch, output, new Cancellation());
    waitForOutput(output, attempt.thread);
    String pid = workerPid();
    // SIGKILL 模拟原生崩溃；不经过 Java 崩溃调度，避免合并先完成的时间竞态。
    String killed = shell("/system/xbin/su 0 kill -9 " + pid);
    attempt.thread.join(15000);
    if (attempt.thread.isAlive() || !(attempt.failure.get() instanceof java.io.IOException))
      throw new AssertionError("工作进程崩溃未成为可控失败：" + attempt.failure.get() + "；测试信号结果=" + killed);
    Files.deleteIfExists(output.toPath());
    // 崩溃后同一宿主仍能启动新的隔离进程，完成已知准确的目标。
    File normal = new File(patch.getParentFile(), "update.hpatch");
    new IsolatedPatchMerger(getTargetContext())
        .merge(base, normal, output, 2250015, new Cancellation());
    ArtifactVerifier.verify(
        output,
        2250015,
        ArtifactVerifier.sha256(
            new File(patch.getParentFile(), "new.bin"), new Cancellation(), Progress.NONE),
        new Cancellation());
  }

  private MergeAttempt startMerge(File base, File patch, File output, Cancellation cancellation)
      throws Exception {
    Files.deleteIfExists(output.toPath());
    java.util.concurrent.atomic.AtomicReference<Throwable> failure =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                new IsolatedPatchMerger(getTargetContext())
                    .merge(base, patch, output, 540000015, cancellation);
              } catch (Throwable failed) {
                failure.set(failed);
              }
            },
            "instrument-delta-merge");
    thread.setDaemon(true);
    thread.start();
    return new MergeAttempt(thread, failure);
  }

  private static void waitForOutput(File output, Thread worker) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
    while (output.length() == 0 && worker.isAlive() && System.nanoTime() < deadline)
      Thread.sleep(2);
    if (output.length() == 0 || !worker.isAlive()) throw new AssertionError("压力合并未进入工作状态");
  }

  private String shell(String command) throws Exception {
    try (android.os.ParcelFileDescriptor descriptor =
            getUiAutomation().executeShellCommand(command);
        InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
      java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (result.size() + count > 65536) throw new AssertionError("测试命令输出过大");
        result.write(buffer, 0, count);
      }
      return result.toString("UTF-8");
    }
  }

  private static final class MergeAttempt {
    final Thread thread;
    final java.util.concurrent.atomic.AtomicReference<Throwable> failure;

    MergeAttempt(Thread thread, java.util.concurrent.atomic.AtomicReference<Throwable> failure) {
      this.thread = thread;
      this.failure = failure;
    }
  }

  private static final class Worker implements AutoCloseable {
    final android.content.Context context;
    final android.content.ServiceConnection connection;
    final IDeltaWorker remote;

    Worker(android.content.Context context) throws Exception {
      this.context = context;
      java.util.concurrent.CompletableFuture<IDeltaWorker> connected =
          new java.util.concurrent.CompletableFuture<>();
      connection =
          new android.content.ServiceConnection() {
            @Override
            public void onServiceConnected(
                android.content.ComponentName name, android.os.IBinder binder) {
              connected.complete(IDeltaWorker.Stub.asInterface(binder));
            }

            @Override
            public void onServiceDisconnected(android.content.ComponentName name) {}
          };
      if (!context.bindService(
          new android.content.Intent(context, DeltaWorkerService.class),
          connection,
          android.content.Context.BIND_AUTO_CREATE)) throw new AssertionError("无法绑定测试工作进程");
      remote = connected.get(15, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      context.unbindService(connection);
    }
  }

  private File inflate(File root, String name) throws Exception {
    File file = new File(root, name);
    // Android 资产合并会解压 .gz 并移除后缀，仓库中仍保存压缩夹具。
    try (InputStream input = getContext().getAssets().open("w26/" + name);
        FileOutputStream out = new FileOutputStream(file)) {
      copy(input, out);
    }
    return file;
  }

  private static void copy(InputStream input, FileOutputStream output) throws Exception {
    byte[] bytes = new byte[65536];
    int count;
    while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
  }
}
