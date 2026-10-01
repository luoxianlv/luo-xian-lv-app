package app.luoxianlv.host;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.widget.TextView;

/** 用户确认后由独立 Java 进程重新打开应用；此进程不加载业务或更新运行时。 */
public final class RecoveryActivity extends Activity {
  private static final String SUFFIX = ":recovery";

  static void open(Activity activity) {
    if (!Bootstrap.recoveryAvailable()) return;
    activity.startActivity(
        new Intent(activity, RecoveryActivity.class)
            .putExtra("failedPid", Process.myPid())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  public static boolean isRecoveryProcess(Context context) {
    if (android.os.Build.VERSION.SDK_INT >= 28)
      return Api28.name().equals(context.getPackageName() + SUFFIX);
    var processes = context.getSystemService(ActivityManager.class).getRunningAppProcesses();
    if (processes == null) return false;
    return processes.stream()
        .anyMatch(
            value ->
                value.pid == Process.myPid()
                    && value.processName.equals(context.getPackageName() + SUFFIX));
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    TextView message = new TextView(this);
    message.setGravity(android.view.Gravity.CENTER);
    message.setText("正在重新打开…");
    setContentView(message);
    int failed = getIntent().getIntExtra("failedPid", 0);
    if (!isRecoveryProcess(this) || failed <= 0 || failed == Process.myPid()) {
      finish();
      return;
    }
    if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
      try {
        boolean kotlinPresent = false;
        try {
          Class.forName("kotlin.Unit", false, getClassLoader());
          kotlinPresent = true;
        } catch (ClassNotFoundException expected) {
        }
        var report =
            new org.json.JSONObject()
                .put("helperPid", Process.myPid())
                .put("failedPid", failed)
                .put("businessStarted", Bootstrap.startedForRecoveryCheck())
                .put("kotlinPresent", kotlinPresent);
        java.nio.file.Files.write(
            new java.io.File(getFilesDir(), "native-recovery-helper-report.json").toPath(),
            report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      } catch (Exception recording) {
        android.util.Log.w("原生恢复", "测试恢复进程资料未能保存", recording);
      }
    }
    var processes = getSystemService(ActivityManager.class).getRunningAppProcesses();
    if (processes != null)
      for (var process : processes)
        if (process.pid == failed
            && process.uid == Process.myUid()
            && process.processName.equals(getPackageName())) Process.killProcess(failed);
    waitForExit(failed, android.os.SystemClock.elapsedRealtime() + 5000);
  }

  private void waitForExit(int failed, long deadline) {
    var processes = getSystemService(ActivityManager.class).getRunningAppProcesses();
    if (processes == null) {
      finish();
      return;
    }
    boolean present =
        processes != null
            && processes.stream()
                .anyMatch(
                    value ->
                        value.pid == failed
                            && value.uid == Process.myUid()
                            && value.processName.equals(getPackageName()));
    if (present) {
      if (android.os.SystemClock.elapsedRealtime() >= deadline) {
        finish();
        return;
      }
      new Handler(getMainLooper()).postDelayed(() -> waitForExit(failed, deadline), 50);
      return;
    }
    startActivity(
        new Intent()
            .setClassName(this, "app.luoxianlv.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    finish();
  }

  private static final class Api28 {
    static String name() {
      return android.app.Application.getProcessName();
    }
  }
}
