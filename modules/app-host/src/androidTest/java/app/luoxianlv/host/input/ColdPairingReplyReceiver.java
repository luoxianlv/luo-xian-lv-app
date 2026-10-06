package app.luoxianlv.host.input;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 仅测试包导出并由 DUMP 权限保护；Shell 提交临时输入，全程不切换 Activity。 */
public final class ColdPairingReplyReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    String code = null;
    int sourcePid = 0;
    try {
      if (intent == null) return;
      if (intent.getBooleanExtra("cancel", false)) {
        PairingNotificationListenerService.cancel(context);
        return;
      }
      sourcePid = intent.getIntExtra("sourcePid", 0);
      code = intent.getStringExtra("code");
      long delayMs = intent.getLongExtra("delayMs", 15000);
      PairingNotificationListenerService.schedule(context, code, sourcePid, delayMs);
    } catch (Throwable failure) {
      PairingNotificationListenerService.report(context, "测试通知请求失败", sourcePid, false,
          failure.getClass().getSimpleName());
    } finally {
      if (intent != null) intent.removeExtra("code");
      code = null;
    }
  }
}
