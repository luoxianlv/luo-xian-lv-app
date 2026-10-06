package app.luoxianlv.host.input;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.SharedInput;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** 只在本机模拟器冻结管理器；shell 服务保持运行，检查两次独立冷连接。 */
final class ShizukuManagerFrozenChecks {
  static void run(Instrumentation test, UiAutomation automation, SharedInput.Bridge bridge) throws Exception {
    require(test.getTargetContext().getPackageName().endsWith(".debug"), "仅允许 Debug 宿主");
    require("1".equals(shell(automation, "getprop ro.kernel.qemu")), "冻结管理器仅限模拟器");
    require(SharedInput.SHIZUKU.equals(bridge.state().getString("mode"))
        && bridge.state().getInt("uid", -1) == 2000, "需要实际 shell Shizuku 连接");
    String raw = shell(automation, "pidof moe.shizuku.privileged.api");
    require(raw.matches("[0-9]+"), "需要唯一的官方 Shizuku 管理器进程");
    int pid = Integer.parseInt(raw);
    require(pid > 1 && shell(automation, "ps -p " + pid + " -o NAME").trim()
        .endsWith("moe.shizuku.privileged.api"), "拒绝冻结其他进程");
    int previous = bridge.state().getInt("helperPid", -1);
    bridge.command("disconnect", new Bundle());
    await("预备断开未完成", () -> !bridge.state().getBoolean("connected")
        && !bridge.state().getBoolean("busy"), 20000);
    try {
      shell(automation, "su 0 kill -STOP " + pid);
      // 不等待交付就取消，迟到进程和回调仍不能改变停止状态。
      for (int round = 0; round < 3; ++round) {
        bridge.command("connect", new Bundle());
        bridge.command("disconnect", new Bundle());
        await("取消启动后仍在连接", () -> !bridge.state().getBoolean("connected")
            && !bridge.state().getBoolean("busy"), 15000);
        SystemClock.sleep(2000);
        require(!bridge.state().getBoolean("connected") && !bridge.state().getBoolean("busy"),
            "迟到的启动结果重新打开了连接");
      }
      for (int round = 0; round < 2; ++round) {
        bridge.command("connect", new Bundle());
        await("管理器冻结后无法冷连接：" + bridge.state().getString("message"),
            () -> bridge.state().getBoolean("connected") && bridge.state().getBoolean("touchReady"), 12000);
        require(bridge.state().getInt("uid", -1) == 2000, "助手没有保持 shell 身份");
        int current = bridge.state().getInt("helperPid", -1);
        require(current > 1 && current != previous, "复用了冻结前的旧助手，未验证冷启动");
        previous = current;
        app.luoxianlv.host.FixedInputChecks.run(test);
        require(bridge.state().getBoolean("connected"), "管理器冻结导致演奏连接丢失");
        bridge.command("disconnect", new Bundle());
        await("停止连接后未清除状态", () -> !bridge.state().getBoolean("connected")
            && !bridge.state().getBoolean("busy"), 20000);
        SystemClock.sleep(1500);
        require(!bridge.state().getBoolean("connected") && !bridge.state().getBoolean("busy"),
            "停止后自行重连");
      }
    } finally {
      shell(automation, "su 0 kill -CONT " + pid);
    }
    bridge.command("connect", new Bundle());
    await("管理器恢复后连接未恢复", () -> bridge.state().getBoolean("connected")
        && bridge.state().getBoolean("touchReady"), 30000);
    Bundle report = new Bundle();
    report.putString("stream", "通过：管理器冻结时三次启动即取消、两次独立 shell 冷连接、实际演奏和停止后不重连。\n");
    test.sendStatus(0, report);
  }

  private static void await(String message, BooleanSupplier ready, long timeout) {
    long until = SystemClock.elapsedRealtime() + timeout;
    while (!ready.getAsBoolean()) {
      require(SystemClock.elapsedRealtime() < until, message);
      SystemClock.sleep(30);
    }
  }

  private static String shell(UiAutomation automation, String command) throws Exception {
    try (ParcelFileDescriptor fd = automation.executeShellCommand(command);
         var input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
