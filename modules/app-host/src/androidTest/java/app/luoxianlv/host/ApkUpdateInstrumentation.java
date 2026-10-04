package app.luoxianlv.host;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.ApkUpdateBridge;
import app.luoxianlv.hot.contract.SharedUpdate;
import app.luoxianlv.update.ArtifactVerifier;
import app.luoxianlv.update.Cancellation;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 仅在明确指定本机计划时执行真实宿主、签名、隔离合并及恢复检查。 */
public final class ApkUpdateInstrumentation extends Instrumentation {
  private String planName;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    planName = arguments == null ? null : arguments.getString("plan");
    start();
  }

  @Override
  public void onStart() {
    Bundle output = new Bundle();
    try {
      require(planName != null && planName.matches("[a-zA-Z0-9_-]+\\.json"), "必须指定本机测试计划名");
      var context = getTargetContext();
      require(context.getPackageName().endsWith(".debug"), "拒绝操作正式 APP");
      File fixtures = new File(context.getExternalFilesDir(null), "incremental-fixture");
      File planFile = new File(fixtures, planName);
      require(planFile.isFile() && planFile.length() <= 256 * 1024, "本机测试计划缺失或过大");
      JSONObject plan =
          new JSONObject(new String(Files.readAllBytes(planFile.toPath()), StandardCharsets.UTF_8));
      // Instrumentation.start 与 Application.onCreate 可并行，等待真实宿主安装桥。
      long bridgeDeadline = SystemClock.elapsedRealtime() + 5000;
      while (SharedUpdate.current() == null && SystemClock.elapsedRealtime() < bridgeDeadline)
        SystemClock.sleep(20);
      var bridge = SharedUpdate.current();
      require(bridge != null, "宿主更新桥缺失");
      require(bridge.supportsIncremental(), "临时信任配置不可用");
      File baseline =
          new File(
              context
                  .getPackageManager()
                  .getPackageInfo(context.getPackageName(), 0)
                  .applicationInfo
                  .sourceDir);
      String original =
          ArtifactVerifier.sha256(baseline, new Cancellation(), app.luoxianlv.update.Progress.NONE);
      if (plan.optBoolean("rootFloorOnly", false)) {
        File nativeRoot = new File(context.getNoBackupFilesDir(), "native-update");
        var journal = new app.luoxianlv.hot.ActivationJournal(new File(nativeRoot, "state"));
        var before = journal.state();
        journal.observeVersions(before.revision, 2);
        Files.deleteIfExists(new File(nativeRoot, "trust/trust.bin").toPath());
        boolean rejected = false;
        try {
          bridge.pending();
        } catch (SecurityException expected) {
          rejected = true;
        }
        require(rejected, "授权文件丢失后忽略了激活日志的撤销下限");
        require(
            before.stable.equals(journal.state().stable)
                && before.active.equals(journal.state().active),
            "普通更新改变了活动热更选择");
        output.putString("stream", "普通 APK 更新检查通过：根授权日志下限拒绝降级，活动热更未改变\n");
        finish(-1, output);
        return;
      }
      ArrayList<String> urls = new ArrayList<>();
      var entries = plan.getJSONArray("fullUrls");
      for (int i = 0; i < entries.length(); i++) {
        String url = entries.getString(i);
        require(url.startsWith("http://127.0.0.1:18476/"), "测试下载必须限定本机服务");
        urls.add(url);
      }
      SharedUpdate.Request request =
          new SharedUpdate.Request(
              plan.getJSONObject("deliveryV1").toString(),
              urls,
              plan.getLong("versionCode"),
              plan.getString("sha256"),
              plan.getLong("size"));
      LinkedHashSet<String> phases = new LinkedHashSet<>();
      AtomicBoolean paused = new AtomicBoolean();
      long cancelAfter = plan.optLong("cancelAfterBytes", -1);
      SharedUpdate.Progress progress =
          (phase, completed, total, downloaded, detail) -> {
            phases.add(phase);
            if (cancelAfter >= 0 && downloaded >= cancelAfter && paused.compareAndSet(false, true))
              bridge.cancel();
          };
      long began = SystemClock.elapsedRealtime();
      SharedUpdate.Result result = null;
      String rejected = "";
      try {
        result =
            plan.optBoolean("resume", false)
                ? bridge.resume(progress)
                : bridge.prepare(request, progress);
      } catch (Exception failure) {
        boolean cancelled = failure instanceof Cancellation.CancelledException;
        require(
            plan.optBoolean("reject", false) || (cancelAfter >= 0 && cancelled),
            "非预期更新失败：" + failure);
        if (plan.optBoolean("reject", false))
          require(failure instanceof SecurityException, "拒绝原因不是签名或身份验证");
        rejected = failure.getClass().getSimpleName();
      }
      require(!ApkUpdateBridge.busy(), "任务返回后仍持有普通更新门禁");
      require(
          original.equals(
              ArtifactVerifier.sha256(
                  baseline, new Cancellation(), app.luoxianlv.update.Progress.NONE)),
          "当前安装 APK 被修改");
      if (cancelAfter >= 0) {
        require(paused.get() && result == null && !phases.contains("fallback"), "取消没有中止增量或触发了全包回退");
        SharedUpdate.Pending pending = bridge.pending();
        require(
            pending != null
                && pending.request.versionCode == plan.getLong("versionCode")
                && pending.request.sha256.equals(plan.getString("sha256")),
            "取消后没有可发现的已认证任务");
      } else if (plan.optBoolean("reject", false)) {
        require(result == null && !rejected.isEmpty(), "无效签名说明没有被拒绝");
      } else {
        require(result != null && result.versionCode == plan.getLong("versionCode"), "目标版本不一致");
        require(result.usedDelta == plan.getBoolean("usedDelta"), "实际下载方式与测试计划不一致");
        ArtifactVerifier.verify(
            result.file, plan.getLong("size"), plan.getString("sha256"), new Cancellation());
        var uri =
            app.luoxianlv.hot.contract.SharedFiles.getUriForFile(
                context, context.getPackageName() + ".updates", result.file);
        try (var descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
          require(
              descriptor != null && descriptor.getStatSize() == result.file.length(),
              "系统安装分享入口不能读取完整重建包");
        }
        File reconstructed = new File(fixtures, "reconstructed.apk");
        Files.copy(
            result.file.toPath(), reconstructed.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
      JSONObject report =
          new JSONObject()
              .put("schema", 1)
              .put("plan", planName)
              .put("api", android.os.Build.VERSION.SDK_INT)
              .put("baselineUnchanged", true)
              .put("elapsedMillis", SystemClock.elapsedRealtime() - began)
              .put("phases", phases.toString())
              .put("rejected", rejected)
              .put("downloadedBytes", result == null ? 0 : result.downloadedBytes)
              .put("usedDelta", result != null && result.usedDelta);
      Files.write(
          new File(fixtures, planName + ".result.json").toPath(),
          report.toString(2).getBytes(StandardCharsets.UTF_8));
      output.putString("stream", "普通 APK 更新检查通过：" + report + "\n");
      finish(-1, output);
    } catch (Throwable failure) {
      output.putString("stream", "普通 APK 更新检查失败：" + failure + "\n");
      finish(1, output);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
