package app.luoxianlv.host.input;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.SharedInput;
import java.nio.charset.StandardCharsets;

/** 仅暂停本机 Debug 专用助手，验证 Binder 停滞后离线、状态刷新和重新连接。 */
final class ShizukuFrozenChecks {
  static void run(Instrumentation test, UiAutomation automation, SharedInput.Bridge bridge) throws Exception {
    require(test.getTargetContext().getPackageName().endsWith(".debug"), "仅允许 Debug 助手");
    require(SharedInput.SHIZUKU.equals(bridge.state().getString("mode")), "需要已连接的 Shizuku 模式");
    require(bridge.state().getInt("uid",-1)==2000 && !bridge.state().getBoolean("active"), "需要空闲 shell 助手");
    int pid=bridge.state().getInt("helperPid",-1);
    require(pid>1,"助手没有进程标识");
    String identity=shell(automation,"ps -p "+pid+" -o USER,NAME");
    require(identity.contains("shell") && identity.contains(test.getTargetContext().getPackageName()+":touch_shell"),
        "拒绝暂停非本次 Debug 助手："+identity);
    try {
      shell(automation,"kill -STOP "+pid);
      long limit=SystemClock.elapsedRealtime()+20000;
      while (bridge.state().getBoolean("connected") && SystemClock.elapsedRealtime()<limit) SystemClock.sleep(50);
      require(!bridge.state().getBoolean("connected"),"冻结后一直保留已连接状态");
      bridge.command("disconnect",new android.os.Bundle());
      limit=SystemClock.elapsedRealtime()+20000;
      while (bridge.state().getBoolean("busy") && SystemClock.elapsedRealtime()<limit) SystemClock.sleep(50);
      require(!bridge.state().getBoolean("busy"),"冻结后连接忙碌状态无法解除");
    } finally { shell(automation,"kill -CONT "+pid); }
    bridge.command("connect",new android.os.Bundle());
    long limit=SystemClock.elapsedRealtime()+30000;
    while ((!bridge.state().getBoolean("connected") || !bridge.state().getBoolean("touchReady"))
        && SystemClock.elapsedRealtime()<limit) SystemClock.sleep(50);
    require(bridge.state().getBoolean("connected") && bridge.state().getBoolean("touchReady"),
        "恢复响应后无法重新连接："+bridge.state());
  }
  private static String shell(UiAutomation automation,String command) throws Exception {
    try (ParcelFileDescriptor fd=automation.executeShellCommand(command);
         var stream=new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
    }
  }
  private static void require(boolean valid,String message){if(!valid)throw new AssertionError(message);}
}
