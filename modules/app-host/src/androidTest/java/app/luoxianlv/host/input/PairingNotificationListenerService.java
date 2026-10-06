package app.luoxianlv.host.input;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 测试包的无界面通知探针，仅处理指定宿主的真实配对通知。 */
public final class PairingNotificationListenerService extends NotificationListenerService {
  static final String TARGET = "app.luoxianlv.debug";
  private static final int NOTIFICATION = 7212;
  private static volatile PairingNotificationListenerService current;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean connected, completedReport;
  private boolean pairSucceeded, helperConnected, sendSucceeded;
  private int replySourcePid, replySourceUid;
  private Snapshot latest;
  private Reply pendingReply;

  @Override public void onListenerConnected() {
    super.onListenerConnected();
    current = this;
    connected = true;
    try {
      for (StatusBarNotification notification : getActiveNotifications()) capture(notification);
      if (latest == null && pendingReply == null && !completedReport)
        report(this, "通知监听已连接，等待目标配对通知", 0, false, null);
    } catch (Throwable failure) {
      report(this, "读取目标通知失败", 0, false, failure.getClass().getSimpleName());
    }
  }

  @Override public void onNotificationPosted(StatusBarNotification notification) {
    capture(notification);
  }

  private void capture(StatusBarNotification notification) {
    if (!target(notification)) return;
    try {
      Notification value = notification.getNotification();
      CharSequence title = value.extras.getCharSequence(Notification.EXTRA_TITLE);
      if ("配对成功".contentEquals(title == null ? "" : title)) {
        pairSucceeded = true;
        report(this, "目标配对成功已确认", replySourcePid, sendSucceeded, null);
        return;
      }
      if ("无线调试已连接".contentEquals(title == null ? "" : title)) {
        helperConnected = true;
        report(this, "目标助手连接已确认", replySourcePid, sendSucceeded, null);
        return;
      }
      if (value.actions == null || value.actions.length != 1) return;
      Notification.Action action = value.actions[0];
      RemoteInput[] fields = action.getRemoteInputs();
      PendingIntent pending = action.actionIntent;
      if (fields == null || fields.length != 1 || !"paring_code".equals(fields[0].getResultKey())) return;
      int targetUid = notification.getUid();
      if (pending == null || !pending.isForegroundService() || !TARGET.equals(pending.getCreatorPackage())
          || pending.getCreatorUid() != targetUid
          || (Build.VERSION.SDK_INT >= 31 && pending.isImmutable())) return;
      latest = new Snapshot(pending, fields.clone(), targetUid);
      if (pendingReply == null) {
        completedReport = false;
        pairSucceeded = helperConnected = sendSucceeded = false;
        replySourcePid = 0;
        replySourceUid = 0;
        report(this, "已捕获目标真实通知", 0, false, null);
      }
    } catch (Throwable failure) {
      if (pendingReply == null && !completedReport)
        report(this, "目标通知捕获失败", 0, false, failure.getClass().getSimpleName());
    }
  }

  @Override public void onNotificationRemoved(StatusBarNotification notification) {
    if (!target(notification)) return;
    latest = null;
    // 删除宿主通知不等于撤销 PendingIntent；预约票据保持到实际回复。
    if (pendingReply == null && !completedReport) report(this, "目标通知已移除", 0, false, null);
  }

  static void schedule(Context context, String code, int sourcePid, long delayMs) {
    PairingNotificationListenerService listener = current;
    if (listener == null || !listener.connected) {
      report(context, "通知监听尚未连接", sourcePid, false, "ListenerUnavailable");
      return;
    }
    try {
      check(Looper.myLooper() == Looper.getMainLooper(), "请求没有在测试进程主线程处理");
      check(listener.pendingReply == null, "已有待提交的通知回复");
      Snapshot snapshot = listener.latest;
      check(snapshot != null, "尚未捕获真实配对输入通知");
      check(sourcePid > 0 && sourcePid != Process.myPid() && snapshot.sourceUid != Process.myUid(),
          "通知探针必须与宿主处于不同进程和身份");
      check(validCode(code), "临时配对码必须为六位数字");
      check(delayMs >= 1000 && delayMs <= 60000, "通知回复延迟无效");
      Reply reply = listener.new Reply(snapshot, code, sourcePid);
      listener.pendingReply = reply;
      listener.completedReport = false;
      listener.pairSucceeded = listener.helperConnected = listener.sendSucceeded = false;
      listener.replySourcePid = sourcePid;
      listener.replySourceUid = snapshot.sourceUid;
      report(context, "已预约真实通知回复，等待宿主冷进程", sourcePid, false, null);
      if (!listener.handler.postDelayed(reply, delayMs)) {
        listener.clearPending();
        report(context, "预约通知回复失败", sourcePid, false, "ScheduleRejected");
      }
    } catch (Throwable failure) {
      report(context, "预约通知回复失败", sourcePid, false, failure.getClass().getSimpleName());
    }
  }

  static void cancel(Context context) {
    PairingNotificationListenerService listener = current;
    if (listener != null) listener.clearPending();
    report(context, "已取消测试通知回复", 0, false, null);
  }

  private void clearPending() {
    Reply reply = pendingReply;
    pendingReply = null;
    latest = null;
    if (reply != null) {
      handler.removeCallbacks(reply);
      reply.clear();
    }
  }

  @Override public void onListenerDisconnected() {
    connected = false;
    if (current == this) current = null;
    if (pendingReply != null)
      report(this, "通知监听断开，取消测试回复", pendingReply.sourcePid, false, "ListenerDisconnected");
    clearPending();
    super.onListenerDisconnected();
  }

  @Override public void onDestroy() {
    connected = false;
    if (current == this) current = null;
    clearPending();
    super.onDestroy();
  }

  private static boolean target(StatusBarNotification notification) {
    return notification != null && TARGET.equals(notification.getPackageName())
        && notification.getId() == NOTIFICATION;
  }

  private final class Reply implements Runnable {
    private Snapshot snapshot;
    private String code;
    private final int sourcePid;
    private boolean started;

    Reply(Snapshot snapshot, String code, int sourcePid) {
      this.snapshot = snapshot;
      this.code = code;
      this.sourcePid = sourcePid;
    }

    @Override public void run() {
      if (pendingReply != this || !connected) { clear(); return; }
      started = true;
      completedReport = true;
      try {
        Bundle results = new Bundle();
        results.putCharSequence(snapshot.fields[0].getResultKey(), code);
        Intent input = new Intent();
        RemoteInput.addResultsToIntent(snapshot.fields, input, results);
        if (Build.VERSION.SDK_INT >= 28)
          RemoteInput.setResultsSource(input, RemoteInput.SOURCE_FREE_FORM_INPUT);
        snapshot.pending.send(PairingNotificationListenerService.this, 0, input);
        sendSucceeded = true;
        results.clear();
        input.setClipData(null);
        report(PairingNotificationListenerService.this, "已提交真实通知回复", sourcePid, true, null);
      } catch (Throwable failure) {
        report(PairingNotificationListenerService.this, "真实通知回复提交失败", sourcePid, false,
            failure.getClass().getSimpleName());
      } finally {
        if (pendingReply == this) pendingReply = null;
        latest = null;
        clear();
      }
    }

    void clear() { code = null; snapshot = null; }
  }

  private static final class Snapshot {
    final PendingIntent pending;
    final RemoteInput[] fields;
    final int sourceUid;
    Snapshot(PendingIntent pending, RemoteInput[] fields, int sourceUid) {
      this.pending = pending;
      this.fields = fields;
      this.sourceUid = sourceUid;
    }
  }

  static void report(Context context, String stage, int sourcePid, boolean sent, String errorType) {
    try {
      JSONObject result = new JSONObject();
      result.put("stage", stage);
      result.put("pid", Process.myPid());
      result.put("sourcePid", sourcePid);
      PairingNotificationListenerService listener = current;
      boolean separate = sourcePid > 0 && sourcePid != Process.myPid() && listener != null
          && listener.replySourcePid == sourcePid && listener.replySourceUid > 0
          && listener.replySourceUid != Process.myUid();
      result.put("separateProcess", separate);
      Reply reply = listener == null ? null : listener.pendingReply;
      result.put("scheduled", reply != null && reply.sourcePid == sourcePid && !reply.started);
      result.put("sendSucceeded", sent);
      result.put("pairSucceeded", listener != null && listener.pairSucceeded);
      result.put("helperConnected", listener != null && listener.helperConnected);
      if (errorType != null) result.put("errorType", errorType);
      AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "pairing-notification-listener.json"));
      FileOutputStream output = file.startWrite();
      try {
        output.write((result.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        file.finishWrite(output);
      } catch (Throwable failure) {
        file.failWrite(output);
      }
    } catch (Throwable ignored) { }
  }

  private static boolean validCode(String value) {
    if (value == null || value.length() != 6) return false;
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
    return true;
  }

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }
}
