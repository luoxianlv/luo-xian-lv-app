package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import app.luoxianlv.hot.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** 真实结束进程后再从普通入口检查恢复，不伪造系统退出原因或缩短健康时钟。 */
final class NativeStableRecoveryChecks {
  private static final String USER_DATA = "验证谱子和设置不随代码版本回滚。";

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  static void crash(Instrumentation runner, Activity home, String target) throws Exception {
    prepare(runner, home, target);
    // 使用真实安装的异常处理器；记录、系统崩溃上报和进程退出均执行实际路径。
    Thread.getDefaultUncaughtExceptionHandler()
        .uncaughtException(Thread.currentThread(), new IllegalStateException("测试：已稳定热更组合的真实进程崩溃"));
    throw new AssertionError("真实崩溃处理器意外返回");
  }

  static void prepare(Instrumentation runner, Activity home, String target) throws Exception {
    var startup = environment(runner);
    var state = startup.journal.state();
    check(
        HotManifest.validHash(target)
            && state.stable.equals(target)
            && state.phase == ActivationJournal.Phase.STABLE,
        "真实崩溃前没有明确稳定目标");
    check(Bootstrap.source().prepared.manifest.snapshotId.equals(target), "正在运行的组合与稳定日志不一致");
    check(
        startup.running != null
            && startup.running.snapshot.equals(target)
            && startup.running.pid == android.os.Process.myPid(),
        "当前进程运行身份未登记");
    var files = runner.getTargetContext().getFilesDir();
    Files.write(
        new File(files, "native-recovery-user-data.txt").toPath(),
        USER_DATA.getBytes(StandardCharsets.UTF_8));
    var report =
        new JSONObject()
            .put("target", target)
            .put("attempt", state.stableAttempt)
            .put("pid", android.os.Process.myPid())
            .put("revision", state.revision)
            .put("trustVersion", state.trustVersion)
            .put("startedAt", startup.running.startedAt);
    Files.write(
        new File(files, "native-stable-crash-before.json").toPath(),
        report.toString(2).getBytes(StandardCharsets.UTF_8));
  }

  static void recovered(Instrumentation runner, Activity home, String target, String failed)
      throws Exception {
    var startup = environment(runner);
    check(
        (target.equals("apk") || HotManifest.validHash(target)) && HotManifest.validHash(failed),
        "恢复验收目标无效");
    var state = startup.journal.state();
    String expected = target.equals("apk") ? "" : target;
    check(
        state.phase == ActivationJournal.Phase.STABLE && state.stable.equals(expected),
        "启动未恢复指定旧组合");
    var prepared = Bootstrap.source().prepared;
    check(
        target.equals("apk")
            ? prepared.manifest == null
            : prepared.manifest.snapshotId.equals(target),
        "日志已恢复但实际运行仍是故障组合");
    check(state.quarantine.contains(failed), "故障快照没有隔离");
    boolean blocked = false;
    try {
      startup.quarantine.requireAllowed(
          startup.store.snapshot(failed).manifest, startup.config.hostContract);
    } catch (IllegalArgumentException expectedFailure) {
      blocked = true;
    }
    check(blocked, "故障内容仍能靠修改发布名重新执行");
    var files = runner.getTargetContext().getFilesDir();
    var before =
        new JSONObject(
            Files.readString(new File(files, "native-stable-crash-before.json").toPath()));
    check(
        state.revision >= before.getLong("revision")
            && state.trustVersion >= before.getLong("trustVersion"),
        "恢复清掉了发布或信任下限");
    check(
        Files.readString(new File(files, "native-recovery-user-data.txt").toPath())
            .equals(USER_DATA),
        "用户文件随着代码恢复被覆盖");
    long deadline = SystemClock.elapsedRealtime() + 15000;
    while (!home.hasWindowFocus()) {
      check(SystemClock.elapsedRealtime() < deadline, "恢复页面没有回到前台");
      SystemClock.sleep(100);
    }
    var report =
        new JSONObject()
            .put("passed", true)
            .put("kind", before.optString("kind", "crash"))
            .put("productionTouched", false)
            .put("failed", failed)
            .put("fallback", target)
            .put("actualOldCodeLoaded", true)
            .put("contentQuarantined", true)
            .put("floorsPreserved", true)
            .put("userDataPreserved", true)
            .put("originalAttempt", before.getString("attempt"));
    Files.write(
        new File(files, "native-stable-recovery-report.json").toPath(),
        report.toString(2).getBytes(StandardCharsets.UTF_8));
  }

  private static HostStartup environment(Instrumentation runner) {
    var startup = Bootstrap.startupState();
    check(
        runner.getTargetContext().getPackageName().equals("app.luoxianlv.debug")
            && startup != null
            && startup.config.environment.equals("test")
            && startup.config.origin.toString().equals("http://127.0.0.1:18472"),
        "进程故障验收仅允许本机 Debug 测试配置");
    return startup;
  }
}
