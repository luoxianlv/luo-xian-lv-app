package app.luoxianlv.host.input;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 仅在测试 APK 注册；持有真实通知操作，宿主冷进程后再模拟用户回复。 */
public final class PairingNotificationRelayActivity extends Activity {
  static final String EXTRA_PENDING = "pairingPendingIntent";
  static final String EXTRA_FIELDS = "pairingRemoteInputs";
  static final String EXTRA_CODE = "pairingTemporaryCode";
  static final String EXTRA_DELAY = "pairingDelayMs";
  static final String EXTRA_SOURCE_PACKAGE = "pairingSourcePackage";
  static final String EXTRA_SOURCE_PID = "pairingSourcePid";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private PendingIntent pending;
  private RemoteInput[] fields;
  private String code;
  private int sourcePid;
  private String sourcePackage;
  private long delayMs;
  private boolean submitted;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    TextView label = new TextView(this);
    label.setText("正在验证配对通知的冷进程回复…");
    label.setPadding(24, 24, 24, 24);
    setContentView(label);
    try {
      Intent source = getIntent();
      pending = source.getParcelableExtra(EXTRA_PENDING);
      android.os.Parcelable[] incoming = source.getParcelableArrayExtra(EXTRA_FIELDS);
      if (incoming != null) {
        fields = new RemoteInput[incoming.length];
        for (int i = 0; i < incoming.length; i++) fields[i] = (RemoteInput) incoming[i];
      }
      code = source.getStringExtra(EXTRA_CODE);
      sourcePackage = source.getStringExtra(EXTRA_SOURCE_PACKAGE);
      sourcePid = source.getIntExtra(EXTRA_SOURCE_PID, -1);
      delayMs = source.getLongExtra(EXTRA_DELAY, -1);
      // 不留在 Activity 的可恢复 Intent 中，也不将临时码写入状态或结果文件。
      setIntent(new Intent());
      int sourceUid = getPackageManager().getApplicationInfo(sourcePackage, 0).uid;
      check(sourceUid != Process.myUid() && sourcePid > 0 && sourcePid != Process.myPid(),
          "Relay 没有处于独立测试包进程");
      check(Build.VERSION.SDK_INT < 28 || Application.getProcessName().endsWith(":pairing_notification_relay"),
          "Relay 进程声明不符");
      check(pending != null && pending.isForegroundService()
          && sourcePackage.equals(pending.getCreatorPackage()) && sourceUid == pending.getCreatorUid(),
          "缺少宿主真实前台服务通知操作");
      check(fields != null && fields.length == 1 && "paring_code".equals(fields[0].getResultKey()),
          "缺少上游配对输入键");
      check(PairingNotificationInstrumentation.validCode(code), "缺少有效临时配对码");
      check(delayMs >= 1000 && delayMs <= 60000, "通知回复延迟无效");
      writeResult("已接收通知操作，等待冷进程", null);
      handler.postDelayed(this::reply, delayMs);
    } catch (Throwable failure) {
      fail(failure);
    }
  }

  private void reply() {
    if (submitted) return;
    submitted = true;
    try {
      Bundle results = new Bundle();
      results.putCharSequence(fields[0].getResultKey(), code);
      Intent input = new Intent();
      RemoteInput.addResultsToIntent(fields, input, results);
      if (Build.VERSION.SDK_INT >= 28)
        RemoteInput.setResultsSource(input, RemoteInput.SOURCE_FREE_FORM_INPUT);
      // 真实通知授权仍属于宿主；不重建操作，不读取宿主内存或自行拼入配对端口。
      pending.send(this, 0, input);
      results.clear();
      writeResult("已提交真实通知回复", null);
      clear();
      finish();
    } catch (Throwable failure) {
      fail(failure);
    }
  }

  private void fail(Throwable failure) {
    try { writeResult("通知回复验证失败", failure.getClass().getSimpleName()); }
    catch (Throwable ignored) { }
    clear();
    finish();
  }

  private void writeResult(String stage, String errorType) throws Exception {
    JSONObject result = new JSONObject();
    result.put("stage", stage);
    result.put("relayPackage", getPackageName());
    result.put("relayPid", Process.myPid());
    result.put("relayUid", Process.myUid());
    result.put("sourcePackage", sourcePackage);
    result.put("sourcePid", sourcePid);
    result.put("delayMs", delayMs);
    result.put("elapsedRealtime", SystemClock.elapsedRealtime());
    result.put("separateProcess", sourcePid > 0 && sourcePid != Process.myPid());
    result.put("sendSucceeded", "已提交真实通知回复".equals(stage));
    if (errorType != null) result.put("errorType", errorType);
    AtomicFile file = new AtomicFile(new File(getFilesDir(), "pairing-notification-relay.json"));
    FileOutputStream output = file.startWrite();
    try {
      output.write((result.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
      file.finishWrite(output);
    } catch (Throwable failure) {
      file.failWrite(output);
      throw failure;
    }
  }

  private void clear() {
    handler.removeCallbacksAndMessages(null);
    code = null;
    fields = null;
    pending = null;
  }

  @Override protected void onDestroy() {
    clear();
    super.onDestroy();
  }

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }
}
