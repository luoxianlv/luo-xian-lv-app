package app.luoxianlv.host;

import android.app.Instrumentation;
import android.os.SystemClock;
import app.luoxianlv.hot.ActivationJournal;
import app.luoxianlv.hot.ContentQuarantine;
import app.luoxianlv.hot.ContentStore;
import app.luoxianlv.hot.HotPackage;
import app.luoxianlv.hot.NativeLoader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import org.json.JSONObject;

/** 实际签名候选经过内部对象库及加载器；错误 SDK 不应触发运行时或业务类加载。 */
final class NativeCompiledContractChecks {
  private NativeCompiledContractChecks() {}

  static void run(Instrumentation test) throws Exception {
    var context = test.getTargetContext();
    var startup = Bootstrap.startupState();
    check(
        context.getPackageName().equals("app.luoxianlv.debug")
            && startup != null
            && startup.config.environment.equals("test")
            && startup.config.origin.toString().equals("http://127.0.0.1:18472"),
        "仅允许独立本机 Debug 验证");
    String sdk;
    try (var input =
        context
            .createPackageContext(context.getPackageName(), 0)
            .getAssets()
            .open("hot/host-contract.sha256")) {
      byte[] raw = input.readNBytes(66);
      check(raw.length <= 65, "安装宿主的编译身份超限");
      sdk = new String(raw, StandardCharsets.US_ASCII).trim();
    }
    check(sdk.matches("[a-f0-9]{64}"), "实际安装宿主缺少 SDK 编译身份");
    var current = Bootstrap.source();
    check(current.prepared.manifest == null, "须先验证新安装包的正常恢复组合加载成功");
    String runtimeBefore = NativeLoader.residentRuntimeHash();
    File area = new File(context.getFilesDir(), "native-contract-test");
    File input = new File(area, "mismatch.lxhp");
    check(input.isFile(), "缺少明确的签名错误 SDK 测试包");
    File sentinel = new File(area, "user-sentinel.txt");
    byte[] userBytes = "编译兼容拒绝应保留用户文件".getBytes(StandardCharsets.UTF_8);
    Files.write(sentinel.toPath(), userBytes);
    File work = Files.createTempDirectory(area.toPath(), "attempt-").toFile();
    var store = new ContentStore(new File(work, "store"));
    var journal = new ActivationJournal(new File(work, "journal"));
    var policy =
        new HotPackage.Policy(
            startup.config.root,
            context.getPackageName(),
            "test",
            startup.config.hostContract,
            1,
            Instant.now(),
            startup.config.mounts,
            null,
            null);
    String target;
    boolean rejected = false;
    try (var candidate = new HotPackage(input, policy)) {
      check(candidate.mode.equals("full"), "兼容性反例必须为完整已签名候选");
      var snapshot = store.prepare(candidate);
      target = snapshot.manifest.snapshotId;
      journal.begin(
          target,
          1,
          candidate.trust.version,
          android.os.Process.myPid(),
          SystemClock.elapsedRealtime());
      var loader =
          new NativeLoader(
              context,
              store,
              new ContentQuarantine(new File(work, "quarantine")),
              startup.config.hostContract,
              startup.config.mounts);
      try {
        loader.prepare(snapshot, journal.state());
      } catch (IllegalArgumentException mismatch) {
        rejected = mismatch.getMessage().contains("业务编译 SDK 与安装宿主不匹配");
      }
    }
    check(rejected, "有效签名的错误编译 SDK 未在加载前拒绝");
    check(
        Bootstrap.source() == current && NativeLoader.residentRuntimeHash().equals(runtimeBefore),
        "拒绝候选却改变了当前业务或共享运行时");
    check(Arrays.equals(Files.readAllBytes(sentinel.toPath()), userBytes), "拒绝候选改变用户文件");
    var report =
        new JSONObject()
            .put("passed", true)
            .put("pid", android.os.Process.myPid())
            .put("hostContractSDKHash", sdk)
            .put("snapshot", target)
            .put("validSignedCandidateRejectedBeforeCandidateLoading", true)
            .put("sharedRuntimeAlreadyResident", true)
            .put("normalBaselineLoaded", true)
            .put("activeSourceUnchanged", true)
            .put("residentRuntimeUnchanged", true)
            .put("userFilePreserved", true)
            .put("isolatedCandidateJournal", true);
    Files.writeString(
        new File(area, "report.json").toPath(), report.toString(2), StandardCharsets.UTF_8);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
