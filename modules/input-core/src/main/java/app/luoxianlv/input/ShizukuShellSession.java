package app.luoxianlv.input;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;

/** 按 Hail 的授权进程用法发送启动脚本；不经过管理器 Provider，也不输出脚本或令牌。 */
final class ShizukuShellSession implements AutoCloseable {
  static final long START_TIMEOUT_MS = 10000;
  private final HelperBootstrapTicket ticket = new HelperBootstrapTicket(
      SystemClock.elapsedRealtime(), START_TIMEOUT_MS);
  private final InputBridgeProvider.Lease lease = new InputBridgeProvider.Lease();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final BoundedInputRpc rpc;
  private final BiConsumer<ShizukuShellSession, IInputService> delivery;
  private volatile IRemoteProcess process;

  ShizukuShellSession(BoundedInputRpc rpc, BiConsumer<ShizukuShellSession, IInputService> delivery) {
    this.rpc = rpc;
    this.delivery = delivery;
  }

  void start(Context context, IShizukuService source) throws Exception {
    var host = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
    if (host.uid != Process.myUid()) throw new SecurityException("宿主安装身份不一致");
    String command = ShellHelperCommand.build(host.sourceDir, host.nativeLibraryDir,
        context.getPackageName(), host.uid, ticket.nonce, "touch_shell", false);
    // 启动参数只有 sh。令牌通过标准输入发送，避免 Shizuku 自身记录完整命令参数。
    rpc.call(() -> {
      IRemoteProcess launched = source.newProcess(new String[] {"/system/bin/sh"}, null, null);
      if (launched == null) throw new IllegalStateException("Shizuku 未返回启动通道");
      process = launched;
      if (closed.get() || ticket.expired(SystemClock.elapsedRealtime())) {
        launched.destroy();
        throw new IllegalStateException("启动请求已过期");
      }
      return null;
    });
    rpc.call(() -> {
      if (closed.get()) throw new IllegalStateException("启动请求已取消");
      try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(process.getOutputStream())) {
        output.write(command.getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
      return null;
    });
  }

  android.os.IBinder offer(String nonce, IInputService remote) {
    if (!ticket.accept(nonce, SystemClock.elapsedRealtime())) return null;
    delivery.accept(this, remote);
    return lease;
  }
  boolean expired() { return ticket.expired(SystemClock.elapsedRealtime()); }

  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    ticket.revoke();
    lease.revoke();
    IRemoteProcess previous = process;
    if (previous != null) try { rpc.call(() -> { previous.destroy(); return null; }); }
    catch (Exception ignored) { /* 撤销的租约和宿主死亡监听仍会释放助手。 */ }
  }
}
