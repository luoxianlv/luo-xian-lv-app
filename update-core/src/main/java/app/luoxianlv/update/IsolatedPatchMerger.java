package app.luoxianlv.update;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** 描述符只读输入、独占输出，worker 崩溃和超时会成为可控的合并失败。 */
public final class IsolatedPatchMerger implements PatchMerger {
  private final Context context;
  private final long timeoutMillis;
  public IsolatedPatchMerger(Context context) { this(context, 180000); }
  public IsolatedPatchMerger(Context context, long timeoutMillis) {
    this.context = context.getApplicationContext(); this.timeoutMillis = timeoutMillis;
    if (timeoutMillis < 1000 || timeoutMillis > 180000) throw new IllegalArgumentException("合并超时范围无效");
  }
  @Override public void merge(File base, File patch, File output, long targetSize, Cancellation cancellation) throws IOException {
    cancellation.check();
    SafeFiles.rejectLink(base); SafeFiles.rejectLink(patch); SafeFiles.rejectLink(output);
    SafeFiles.directory(output.getParentFile());
    CompletableFuture<IDeltaWorker> connected = new CompletableFuture<>();
    AtomicReference<IDeltaWorker> worker = new AtomicReference<>();
    long jobToken = System.nanoTime();
    ServiceConnection connection = new ServiceConnection() {
      @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        IDeltaWorker remote = IDeltaWorker.Stub.asInterface(binder); worker.set(remote); connected.complete(remote);
      }
      @Override public void onServiceDisconnected(ComponentName name) { connected.completeExceptionally(new IOException("合并进程已退出")); }
      @Override public void onBindingDied(ComponentName name) { connected.completeExceptionally(new IOException("合并进程绑定已失效")); }
      @Override public void onNullBinding(ComponentName name) { connected.completeExceptionally(new IOException("合并进程无法启动")); }
    };
    boolean bound = context.bindService(new Intent(context, DeltaWorkerService.class), connection, Context.BIND_AUTO_CREATE);
    if (!bound) throw new IOException("无法启动合并进程");
    try (AutoCloseable ignored = cancellation.onCancel(() -> {
      IDeltaWorker remote = worker.get();
      if (remote != null) try { remote.cancel(jobToken); } catch (android.os.RemoteException dead) { /* worker 已退出 */ }
    })) {
      IDeltaWorker remote = null;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (remote == null) {
        cancellation.check();
        if (System.nanoTime() > deadline) throw new IOException("合并进程启动超时");
        try { remote = connected.get(100, TimeUnit.MILLISECONDS); }
        catch (TimeoutException wait) { /* 允许启动期间取消 */ }
      }
      cancellation.check();
      try (ParcelFileDescriptor old = ParcelFileDescriptor.open(base, ParcelFileDescriptor.MODE_READ_ONLY);
           ParcelFileDescriptor diff = ParcelFileDescriptor.open(patch, ParcelFileDescriptor.MODE_READ_ONLY);
           ParcelFileDescriptor target = ParcelFileDescriptor.open(output,
               ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE)) {
        int result = remote.merge(old, diff, target, targetSize, timeoutMillis, jobToken);
        cancellation.check();
        if (result != 0) throw new IOException("增量合并失败：" + result);
      }
    } catch (Cancellation.CancelledException cancelled) { throw cancelled; }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Cancellation.CancelledException(); }
    catch (ExecutionException failure) { throw new IOException("合并进程已退出", failure.getCause()); }
    catch (IOException failure) { cancellation.check(); throw failure; }
    catch (Exception failure) { cancellation.check(); throw new IOException("合并进程失败", failure); }
    finally { worker.set(null); context.unbindService(connection); cancellation.awaitClosures(); }
  }
}
