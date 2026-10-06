package app.luoxianlv.input;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 仅运行于 shell 助手。读取、合流与清理由同一控制线程安排，不加载业务或统计。 */
public final class InputUserService extends IInputService.Stub {
  private final int ownerUid;
  private final String nativeLibraryDir;
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
      task -> new Thread(task, "touch-shell-control"));
  private volatile Bundle snapshot = unavailable("正在检查触屏能力");
  private volatile IInputCallback callback;
  private IBinder callbackBinder;
  private final IBinder.DeathRecipient clientDied = () -> worker.execute(this::shutdown);
  private volatile long lastHeartbeat = SystemClock.elapsedRealtime();
  private boolean closed;
  private TouchEngine engine;
  private volatile MergedTouchDispatcher dispatcher;
  private int width, height, rotation;
  private long activeToken;
  private ActivationWait activationWait;
  private ScheduledFuture<?> activationTask;
  private long releaseTicket;
  private volatile int lastCallerUid;

  /** Shizuku v13 提供的是宿主包上下文，不能拿它启动 Activity 或注册广播。 */
  public InputUserService(Context context) {
    this(context.getApplicationInfo().uid, context.getApplicationInfo().nativeLibraryDir);
  }

  /** 本机无线 ADB 引导器先从 PackageManager 验证宿主 UID，再传入本构造器。 */
  public InputUserService(int ownerUid) {
    this(ownerUid, null);
  }

  private InputUserService(int ownerUid, String nativeLibraryDir) {
    if (ownerUid < 10000) throw new IllegalArgumentException("助手缺少有效宿主身份");
    this.ownerUid = ownerUid;
    this.nativeLibraryDir = nativeLibraryDir;
    snapshot.putInt("uid", Process.myUid());
    snapshot.putInt("ownerUid", ownerUid);
    Log.i("触控共存", "输入助手已创建，宿主UID=" + ownerUid + "，助手UID=" + Process.myUid()
        + "，架构=" + (Process.is64Bit() ? "64位" : "32位"));
    worker.scheduleWithFixedDelay(() -> {
      if (!closed && engine != null && engine.isActive()
          && SystemClock.elapsedRealtime() - lastHeartbeat > 4500) {
        releaseEngine("连接超时，已归还触屏");
      }
    }, 1, 1, TimeUnit.SECONDS);
  }

  @Override public Bundle inspect() {
    checkCaller();
    return new Bundle(snapshot);
  }

  @Override public void attach(IInputCallback next) throws RemoteException {
    checkCaller();
    if (next == null) throw new IllegalArgumentException("缺少宿主回调");
    synchronized (this) {
      if (callbackBinder != null) callbackBinder.unlinkToDeath(clientDied, 0);
      next.asBinder().linkToDeath(clientDied, 0);
      callbackBinder = next.asBinder();
      callback = next;
    }
    heartbeat();
    worker.execute(() -> {
      if (closed) return;
      Bundle state;
      if (!InputIdentity.privileged(Process.myUid())) {
        state = unavailable("输入服务缺少系统触控权限，请重新连接 Shizuku 或无线调试");
      } else {
        String probeStage = "native-load";
        try {
          // Shizuku 的类加载器不一定提供可用的 JNI 搜索路径，直接核对并加载宿主库。
          if (nativeLibraryDir != null) TouchEngine.initialize(nativeLibraryDir);
          probeStage = "probe";
          state = TouchEngine.probe(null);
          if (!state.getBoolean("supported", false))
            Log.w("触控共存", "触屏预检失败，阶段=" + state.getString("diagnosticStage", "probe")
                + "，类型=" + state.getString("diagnosticType", "") + "，原因=" + state.getString("message", ""));
        } catch (Throwable error) { state = failed(probeStage, "触屏能力检查失败", error); }
      }
      publish(state);
    });
  }

  @Override public boolean begin(float[] points, int durationMs, int w, int h, int r, long token) {
    checkCaller();
    if (!InputIdentity.privileged(Process.myUid()) || points == null || points.length == 0
        || points.length % 2 != 0 || points.length > 20 || durationMs < 1
        || durationMs > 60000 || w < 1 || h < 1 || r < 0 || r > 3
        || token <= 0 || callback == null) return false;
    for (float value : points) if (!Float.isFinite(value)) return false;
    float[] copy = points.clone();
    worker.execute(() -> startNote(copy, durationMs, w, h, r, token));
    return true;
  }

  private void startNote(float[] points, int durationMs, int w, int h, int r, long token) {
    if (closed) { finished(token, false, "输入助手已关闭"); return; }
    lastHeartbeat = SystemClock.elapsedRealtime();
    String stage = "native-load";
    try {
      if (engine == null || width != w || height != h || rotation != r) {
        releaseEngine("屏幕方向变化，重新建立触屏会话");
        width = w; height = h; rotation = r;
        engine = new TouchEngine(new TouchEngine.Listener() {
          @Override public boolean frame(int[] ids, float[] xy, boolean cancel) {
            MergedTouchDispatcher current = dispatcher;
            return current != null && current.frame(ids, xy, cancel);
          }
          @Override public void noteFinished(long note, boolean success, String message) {
            worker.execute(() -> {
              if (note == activeToken) {
                if (!success && engine != null && !engine.isActive()) {
                  String reason = engine.lastFailure();
                  if (reason != null && !reason.isEmpty()) {
                    releaseEngine(reason);
                    publish(failed("inject", reason, null));
                    return;
                  }
                }
                activeToken = 0;
                finished(note, success, message);
                publish(new Bundle(snapshot));
              }
            });
          }
        });
        stage = "prepare";
        Bundle prepared = engine.prepare(null, w, h, r);
        prepared.putAll(engine.contactState());
        prepared.putInt("uid", Process.myUid());
        prepared.putLong("activationWaitMs", 0);
        publish(prepared);
        if (!prepared.getBoolean("supported", false)) {
          releaseEngine(prepared.getString("message", "当前触屏不支持触控共存"));
          finished(token, false, prepared.getString("message", "当前触屏不支持触控共存"));
          return;
        }
        stage = "dispatcher-prepare";
        dispatcher = new MergedTouchDispatcher(w, h, r);
      }
      if (activeToken != 0) finished(activeToken, false, "已由后续音符替换");
      cancelActivationWait();
      activeToken = token;
      activationWait = new ActivationWait(points, durationMs, token, SystemClock.uptimeMillis());
      attemptActivation(activationWait);
    } catch (Throwable error) {
      activeToken = 0;
      releaseEngine("输入异常，已归还触屏");
      Bundle state = failed(stage, "输入助手发生异常，已归还触屏", error);
      state.putInt("uid", Process.myUid());
      publish(state);
      finished(token, false, state.getString("message", "输入异常，已归还触屏"));
    }
  }

  /** 只等待首次接管前的抬手，不占住控制线程，也不改变已激活会话的合流节奏。 */
  private void attemptActivation(ActivationWait request) {
    if (closed || request != activationWait || !request.current(activeToken)) return;
    String stage = "activate";
    try {
      if (engine == null) throw new IllegalStateException("触控会话已结束");
      boolean activated = engine.isActive();
      if (!activated) {
        activated = engine.activate();
        Bundle observed = new Bundle(snapshot);
        observed.putAll(engine.contactState());
        snapshot = observed;
      }
      if (!activated) {
        String reason = engine.lastFailure();
        if (engine.activationWaiting()) {
          long now = SystemClock.elapsedRealtime();
          if (request.retry(now)) {
            if (!snapshot.getBoolean("waitingForFingers"))
              publishActivationWait(request, "正在等待手指抬起", "waiting-finger");
            activationTask = worker.schedule(() -> attemptActivation(request),
                ActivationWait.RETRY_MS, TimeUnit.MILLISECONDS);
            return;
          }
          activeToken = 0;
          cancelActivationWait();
          // 预检仍然通过；一次长按不能把后续演奏永久判为设备不支持。
          publishActivationWait(request, "开始演奏时仍有手指按下，请松开后再播放", "waiting-finger");
          finished(request.token, false, "开始演奏时仍有手指按下，请松开后再播放");
          return;
        }
        if (reason == null || reason.isEmpty()) reason = "触屏接管失败";
        activeToken = 0;
        releaseEngine(reason);
        publish(failed(stage, reason, null));
        finished(request.token, false, reason);
        return;
      }
      stage = "begin";
      if (!engine.begin(request.points, request.durationMs, request.token)) {
        activeToken = 0;
        releaseEngine("自动按键提交失败，已归还触屏");
        publish(failed("begin", "自动按键提交失败，已归还触屏", null));
        finished(request.token, false, "自动按键提交失败");
        return;
      }
      cancelActivationWait();
      publishActivationWait(request, "触屏预检通过", "ready");
    } catch (Throwable error) {
      activeToken = 0;
      releaseEngine("输入异常，已归还触屏");
      Bundle state = failed(stage, "输入助手发生异常，已归还触屏", error);
      state.putInt("uid", Process.myUid());
      publish(state);
      finished(request.token, false, state.getString("message", "输入异常，已归还触屏"));
    }
  }

  private void publishActivationWait(ActivationWait request, String message, String stage) {
    Bundle state = new Bundle(snapshot);
    if (engine != null) {
      try { state.putAll(engine.contactState()); }
      catch (RuntimeException | LinkageError error) {
        Log.w("触控共存", "触点诊断不可用，类型=" + error.getClass().getSimpleName());
      }
    }
    state.putLong("activationWaitMs", request.waitedMs(SystemClock.elapsedRealtime()));
    state.putLong("activationRequestAt", request.createdAt);
    state.putString("message", message);
    state.putString("diagnosticStage", stage);
    state.putString("diagnosticType", "");
    state.putString("diagnosticMessage", message);
    if ("waiting-finger".equals(stage) || state.getBoolean("staleContactIgnored"))
      Log.i("触控共存", "接管观察：槽位=" + state.getInt("trackedSlots", -1)
          + "，支持接触状态=" + state.getBoolean("touchSupported")
          + "，实际接触=" + state.getBoolean("touchPressed")
          + "，读取错误=" + state.getInt("contactReadErrno")
          + "，忽略非接触槽=" + state.getBoolean("staleContactIgnored"));
    publish(state);
  }

  private void cancelActivationWait() {
    if (activationWait != null) activationWait.cancel();
    activationWait = null;
    if (activationTask != null) activationTask.cancel(false);
    activationTask = null;
  }

  @Override public void cancel(long token) {
    checkCaller();
    worker.execute(() -> {
      if (token != activeToken || engine == null) return;
      if (activationWait != null && activationWait.current(token)) {
        ActivationWait cancelled = activationWait;
        activeToken = 0;
        cancelActivationWait();
        publishActivationWait(cancelled, "触屏预检通过", "ready");
        finished(token, false, "本次按键已取消");
        return;
      }
      engine.cancel();
    });
  }

  @Override public void release() {
    checkCaller();
    worker.execute(() -> releaseEngine(null));
  }

  @Override public void releaseSession(long ticket) {
    checkCaller();
    worker.execute(() -> {
      releaseEngine(null);
      releaseTicket = ticket;
      publish(new Bundle(snapshot));
    });
  }

  /** Binder 工作线程执行；截图不占用触屏合流与心跳控制队列。 */
  @Override public android.os.ParcelFileDescriptor screenshot(int displayId) throws RemoteException {
    checkCaller();
    if (closed) throw new RemoteException("输入服务已关闭");
    try { return ShellScreenshot.capture(displayId); }
    catch (java.io.IOException failure) { throw new RemoteException("系统截图失败：" + failure.getClass().getSimpleName()); }
  }

  @Override public void heartbeat() {
    checkCaller();
    lastHeartbeat = SystemClock.elapsedRealtime();
    worker.execute(() -> { if (engine != null && !closed) engine.heartbeat(); });
  }

  @Override public void destroy() {
    int caller = Binder.getCallingUid();
    if (caller != ownerUid && caller != 2000 && caller != 0)
      throw new SecurityException("无权关闭输入助手");
    worker.execute(this::shutdown);
  }

  private void releaseEngine(String message) {
    long token = activeToken;
    ActivationWait waiting = activationWait;
    activeToken = 0;
    cancelActivationWait();
    TouchEngine old = engine;
    engine = null;
    if (old != null) {
      try { old.yield(); } catch (Throwable ignored) { }
      try { old.close(); } catch (Throwable ignored) { }
    }
    MergedTouchDispatcher previous = dispatcher;
    dispatcher = null;
    if (previous != null) previous.reset();
    Bundle state = new Bundle(snapshot);
    if (waiting != null) {
      state.putLong("activationRequestAt", waiting.createdAt);
      state.putLong("activationWaitMs", waiting.waitedMs(SystemClock.elapsedRealtime()));
    }
    if (message != null) state.putString("message", message);
    publish(state);
    if (token != 0) finished(token, false, message == null ? "演奏已暂停" : message);
  }

  private void publish(Bundle state) {
    state.putInt("helperPid", Process.myPid());
    if (!state.containsKey("contactDecision") && snapshot.containsKey("contactDecision")) {
      state.putString("contactDecision", snapshot.getString("contactDecision"));
      state.putInt("trackedSlots", snapshot.getInt("trackedSlots", -1));
      state.putInt("touchState", snapshot.getInt("touchState", -1));
      state.putBoolean("touchSupported", snapshot.getBoolean("touchSupported"));
      state.putBoolean("touchPressed", snapshot.getBoolean("touchPressed"));
      state.putInt("contactReadErrno", snapshot.getInt("contactReadErrno"));
      state.putBoolean("staleContactIgnored", snapshot.getBoolean("staleContactIgnored"));
    }
    // 硬错误的新快照不能继承上一请求的等待身份；完成回调才知道是否需要本地补偿。
    if (!state.containsKey("activationRequestAt")) state.putLong("activationRequestAt", 0);
    if (!state.containsKey("activationWaitMs")) state.putLong("activationWaitMs", 0);
    state.putInt("uid", Process.myUid());
    state.putBoolean("active", engine != null && engine.isActive());
    state.putLong("activeToken", activeToken);
    state.putBoolean("waitingForFingers", activationWait != null && activationWait.waiting());
    state.putLong("releaseTicket", releaseTicket);
    state.putInt("helperUid", Process.myUid());
    state.putInt("ownerUid", ownerUid);
    state.putInt("callerUid", lastCallerUid);
    state.putString("helperArchitecture", Process.is64Bit() ? "64" : "32");
    snapshot = new Bundle(state);
    IInputCallback listener = callback;
    if (listener == null) return;
    try { listener.changed(new Bundle(snapshot)); }
    catch (RemoteException error) { worker.execute(this::shutdown); }
  }

  private void finished(long token, boolean success, String message) {
    IInputCallback listener = callback;
    if (listener == null) return;
    try { listener.finished(token, success, message == null ? "" : message); }
    catch (RemoteException error) { worker.execute(this::shutdown); }
  }

  private void shutdown() {
    if (closed) return;
    closed = true;
    releaseEngine("输入连接已关闭");
    synchronized (this) {
      if (callbackBinder != null) callbackBinder.unlinkToDeath(clientDied, 0);
      callbackBinder = null;
      callback = null;
    }
    worker.shutdown();
    if (InputIdentity.privileged(Process.myUid())) System.exit(0);
  }

  /** 自有无线引导器的宿主租约失效时正常清理，不开放新的远程入口。 */
  public void shutdownFromHelper() {
    if (Process.myUid() != 2000 || Binder.getCallingUid() != Process.myUid())
      throw new SecurityException("仅允许助手自身清理");
    worker.execute(this::shutdown);
  }

  private void checkCaller() {
    int caller = Binder.getCallingUid();
    if (caller != ownerUid) {
      Log.w("触控共存", "输入助手拒绝身份不匹配请求，宿主UID=" + ownerUid + "，调用UID=" + caller);
      throw new SecurityException("输入助手身份不匹配");
    }
    lastCallerUid = caller;
  }

  private static Bundle unavailable(String message) {
    Bundle value = new Bundle();
    value.putBoolean("supported", false);
    value.putString("message", message);
    return value;
  }

  private static Bundle failed(String stage, String message, Throwable error) {
    String type = "";
    if (error != null) {
      Throwable current = error;
      for (int i = 0; i < 6 && current.getCause() != null && current.getCause() != current; ++i)
        current = current.getCause();
      type = current.getClass().getSimpleName();
    }
    Bundle result = unavailable(type.isEmpty() ? message : message + "（" + type + "）");
    result.putString("diagnosticStage", stage);
    result.putString("diagnosticType", type);
    result.putString("diagnosticMessage", message);
    Log.w("触控共存", "输入助手失败，阶段=" + stage + "，类型=" + type + "，原因=" + message);
    return result;
  }

  /** 等待属于某一次提交；旧定时任务在取消、替换或释放后必须失效。 */
  static final class ActivationWait {
    static final long RETRY_MS = 25, MAX_WAIT_MS = 500;
    final float[] points;
    final int durationMs;
    final long token;
    final long createdAt;
    private long startedMs = -1;
    private boolean cancelled;

    ActivationWait(float[] points, int durationMs, long token) {
      this(points, durationMs, token, 0);
    }

    ActivationWait(float[] points, int durationMs, long token, long createdAt) {
      this.points = points;
      this.durationMs = durationMs;
      this.token = token;
      this.createdAt = createdAt;
    }

    boolean current(long activeToken) { return !cancelled && token == activeToken; }
    boolean waiting() { return !cancelled && startedMs >= 0; }
    boolean retry(long now) {
      if (cancelled) return false;
      if (startedMs < 0) startedMs = now;
      return now - startedMs < MAX_WAIT_MS;
    }
    long waitedMs(long now) { return startedMs < 0 ? 0 : Math.max(0, now - startedMs); }
    void cancel() { cancelled = true; }
  }
}
