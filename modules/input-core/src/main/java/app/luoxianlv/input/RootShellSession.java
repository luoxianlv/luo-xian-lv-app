package app.luoxianlv.input;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Root 仅负责启动现有输入服务；授权等待不占用连接、暂停或截图线程。 */
final class RootShellSession implements AutoCloseable {
  static final long START_TIMEOUT_MS = 45000;
  private final HelperBootstrapTicket ticket = new HelperBootstrapTicket(
      SystemClock.elapsedRealtime(), START_TIMEOUT_MS);
  private final InputBridgeProvider.Lease lease = new InputBridgeProvider.Lease(0);
  private final AtomicBoolean closed = new AtomicBoolean();
  private final BiConsumer<RootShellSession, IInputService> delivery;
  private final BiConsumer<RootShellSession, Throwable> failure;
  private volatile java.lang.Process process;

  RootShellSession(BiConsumer<RootShellSession, IInputService> delivery,
      BiConsumer<RootShellSession, Throwable> failure) {
    this.delivery = delivery;
    this.failure = failure;
  }

  void start(Context context) throws Exception {
    var host = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
    if (host.uid != Process.myUid()) throw new SecurityException("宿主安装身份不一致");
    String command = ShellHelperCommand.build(host.sourceDir, host.nativeLibraryDir,
        context.getPackageName(), host.uid, ticket.nonce, "root_shell", false);
    Thread starter = new Thread(() -> {
      try {
        // 不向 su 命令参数暴露令牌，也不使用全局 Root Shell 或常驻守护服务。
        java.lang.Process launched = new ProcessBuilder("su").redirectErrorStream(true).start();
        process = launched;
        if (closed.get() || ticket.expired(SystemClock.elapsedRealtime())) {
          launched.destroy();
          return;
        }
        try (var output = launched.getOutputStream()) {
          output.write(command.getBytes(StandardCharsets.UTF_8));
          output.flush();
        }
        // 持续排空输出，避免系统警告填满管道；不收集包含本机信息的原始输出。
        try (var input = launched.getInputStream()) {
          byte[] buffer = new byte[1024];
          while (input.read(buffer) != -1) { }
        }
        int exit = launched.waitFor();
        if (!closed.get()) failure.accept(this, new IllegalStateException("Root 输入进程已退出（" + exit + "）"));
      } catch (Exception error) {
        if (!closed.get()) failure.accept(this, error);
      }
    }, "input-root-start");
    starter.setDaemon(true);
    starter.start();
  }

  android.os.IBinder offer(String nonce, IInputService remote) {
    if (closed.get() || !ticket.accept(nonce, SystemClock.elapsedRealtime())) return null;
    delivery.accept(this, remote);
    return lease;
  }

  boolean expired() { return ticket.expired(SystemClock.elapsedRealtime()); }

  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    ticket.revoke();
    lease.revoke();
    java.lang.Process previous = process;
    if (previous != null) previous.destroy();
  }
}
