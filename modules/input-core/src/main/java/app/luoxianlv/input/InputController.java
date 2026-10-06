package app.luoxianlv.input;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import app.luoxianlv.hot.contract.PlatformApplication;
import app.luoxianlv.hot.contract.SharedInput;
import app.luoxianlv.hot.contract.HostDiagnostics;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Objects;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** 宿主持有输入授权和 shell 会话；页面读取快照，不等待 Binder 或设备检查。 */
public final class InputController implements SharedInput.Bridge, AutoCloseable {
  private static final String TAG = "落弦律输入模式";
  private static final String MANAGER = "moe.shizuku.privileged.api";
  private static final int REQUEST_PERMISSION = 7201;
  private static volatile InputController current;
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
      task -> new Thread(task, "input-connection"));
  private final java.util.concurrent.ExecutorService screenshots = Executors.newSingleThreadExecutor(
      task -> new Thread(task, "input-screenshot"));
  private final BoundedInputRpc rpc = new BoundedInputRpc(2500);
  // 旧助手冻结时，不能占满 Shizuku 授权、启动和清理使用的 RPC 通道。
  private final BoundedInputRpc shizukuRpc = new BoundedInputRpc(2500);
  private final AtomicBoolean screenshotPending = new AtomicBoolean();
  private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<Long, Request> requests = new ConcurrentHashMap<>();
  private final AtomicLong tokens = new AtomicLong();
  private final AtomicLong owners = new AtomicLong();
  private final Lease globalSession = new Lease(0);
  private final InputOwnership<Lease> ownership = new InputOwnership<>();
  private IInputService releaseRemote;
  private long expectedRelease;
  private final AtomicBoolean refreshQueued = new AtomicBoolean();
  private volatile Bundle snapshot = defaults(SharedInput.ACCESSIBILITY);
  private volatile boolean closed;
  private String mode = SharedInput.ACCESSIBILITY;
  private boolean wantConnection = true;
  private boolean initialized;
  private boolean overlayGranted;
  private android.app.AppOpsManager overlayOps;
  private final android.app.AppOpsManager.OnOpChangedListener overlayWatcher = this::overlayChanged;
  private boolean binding;
  private volatile long generation;
  private volatile IInputService service;
  private IBinder.DeathRecipient serviceDeath;
  private ServiceConnection connection;
  private Shizuku.UserServiceArgs serviceArgs;
  private volatile ShizukuShellSession shellSession;
  private final ShizukuConnectionPolicy shizukuConnection = new ShizukuConnectionPolicy();
  private IBinder shizukuSource;
  private boolean shizukuReady;
  private WirelessBackend wireless;
  private Bundle wirelessState = new Bundle();
  private final Shizuku.OnBinderReceivedListener received = () -> enqueueShizukuCallback(() -> {
    if (closed) return;
    IBinder source = Shizuku.getBinder();
    if (source == null || !source.pingBinder()) return;
    if (source != shizukuSource) {
      if (SharedInput.SHIZUKU.equals(mode) && (service != null || binding))
        disconnectService("Shizuku 连接已重建");
      shizukuSource = source;
      shizukuConnection.reset();
    }
    shizukuReady = true;
    refreshNow();
  });
  private final Shizuku.OnBinderDeadListener died = () -> enqueueShizukuCallback(() -> {
    if (closed) return;
    if (Shizuku.pingBinder()) { refreshNow(); return; } // 旧死亡通知不能断开已到达的新 Binder。
    shizukuReady = false;
    shizukuSource = null;
    shizukuConnection.cancel();
    if (SharedInput.SHIZUKU.equals(mode)) disconnectService("Shizuku 已停止");
    refreshNow();
  });
  private final Shizuku.OnRequestPermissionResultListener permission = (request, result) -> {
    if (request != REQUEST_PERMISSION) return;
    enqueueShizukuCallback(() -> {
      if (!SharedInput.SHIZUKU.equals(mode) || !wantConnection) return;
      wantConnection = result == PackageManager.PERMISSION_GRANTED;
      if (wantConnection && service == null && !binding) shizukuConnection.reset();
      else if (!wantConnection) shizukuConnection.cancel();
      if (!wantConnection) disconnectService("Shizuku 授权未通过");
      refreshNow();
    });
  };

  private void enqueueShizukuCallback(Runnable task) {
    if (closed) return;
    try { worker.execute(() -> { if (!closed) task.run(); }); }
    catch (RejectedExecutionException ignored) { }
  }

  public interface WirelessCallback {
    void state(Bundle state);
    void connected(IInputService service);
    void disconnected(String message);
  }

  /** 无线配对和连接器注入同一 shell 服务；不把配对成功等同于触屏可用。 */
  public interface WirelessBackend extends AutoCloseable {
    void setCallback(WirelessCallback callback);
    void command(String action, Bundle arguments);
    @Override void close();
  }

  public static synchronized InputController install(Context source) {
    if (current == null) {
      current = new InputController(PlatformApplication.of(source));
      SharedInput.connect(current);
    }
    return current;
  }

  public static InputController current() { return current; }

  /** 前台连接服务收到用户请求后恢复意愿；不会替用户切模式或创建输入会话。 */
  public void resumeWirelessFromUser() {
    worker.execute(() -> {
      if (!closed && SharedInput.WIRELESS.equals(mode)) wantConnection = true;
    });
  }

  /** 防止助手或未来独立输入进程执行宿主业务和统计初始化。 */
  public static boolean isHelperProcess(Context context) {
    if (InputIdentity.privileged(Process.myUid())) return true;
    if (Build.VERSION.SDK_INT < 28) return false;
    String name = Application.getProcessName();
    return name != null && (name.endsWith(":touch_shell") || name.endsWith(":wireless_shell"));
  }

  private InputController(Context context) {
    this.context = context;
    Shizuku.addBinderReceivedListenerSticky(received);
    Shizuku.addBinderDeadListener(died);
    Shizuku.addRequestPermissionResultListener(permission);
    worker.execute(() -> {
      String selected = context.getSharedPreferences("input-mode", Context.MODE_PRIVATE)
          .getString("mode", SharedInput.ACCESSIBILITY);
      mode = validMode(selected) ? selected : SharedInput.ACCESSIBILITY;
      if (SharedInput.WIRELESS.equals(mode)) wantConnection =
          context.getSharedPreferences("input-wireless", Context.MODE_PRIVATE).getBoolean("wantConnection", true);
      initialized = true;
      refreshNow();
    });
    overlayOps = context.getSystemService(android.app.AppOpsManager.class);
    if (overlayOps != null) try {
      overlayOps.startWatchingMode(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
          context.getPackageName(), overlayWatcher);
    } catch (RuntimeException unavailable) {
      HostDiagnostics.log(Log.WARN, "输入权限", "系统未提供悬浮窗授权监听，返回应用时重新核对", unavailable);
    }
    worker.scheduleWithFixedDelay(() -> {
      if (closed || service == null) return;
      try {
        if (SharedInput.SHIZUKU.equals(mode) && (!Shizuku.pingBinder() || !freshPermission())) {
          disconnectService("Shizuku 权限已失效");
          refreshNow();
          return;
        }
        IInputService current = service;
        rpc.call(() -> { current.heartbeat(); return null; });
        if (SharedInput.SHIZUKU.equals(mode)) shizukuConnection.stable(SystemClock.elapsedRealtime());
      } catch (Exception error) {
        if (SharedInput.SHIZUKU.equals(mode)) {
          failShizuku("维持连接", error);
          return;
        }
        disconnectService("输入连接已断开");
        diagnose("维持连接或检查授权", error, "连接已断开，请重新连接");
        refreshNow();
      }
    }, 1, 1, TimeUnit.SECONDS);
  }

  public void setWirelessBackend(WirelessBackend backend) {
    worker.execute(() -> {
      if (wireless != null) wireless.close();
      if (SharedInput.WIRELESS.equals(mode)) disconnectService("无线连接已重置");
      wireless = backend;
      wirelessState = new Bundle();
      if (backend != null) backend.setCallback(new WirelessCallback() {
        @Override public void state(Bundle value) {
          Bundle copy = value == null ? new Bundle() : new Bundle(value);
          long eventGeneration = generation;
          worker.execute(() -> {
            if (wireless != backend || eventGeneration != generation || closed) return;
            wirelessState = copy;
            if (SharedInput.WIRELESS.equals(mode)) refreshNow();
          });
        }
        @Override public void connected(IInputService remote) {
          long eventGeneration = generation;
          worker.execute(() -> {
            if (wireless != backend || eventGeneration != generation || closed || !SharedInput.WIRELESS.equals(mode)
                || !wantConnection) return;
            attach(remote);
          });
        }
        @Override public void disconnected(String message) {
          long eventGeneration = generation;
          worker.execute(() -> {
            if (wireless != backend || eventGeneration != generation || closed || !SharedInput.WIRELESS.equals(mode)) return;
            disconnectService(message == null ? "无线连接已断开" : message);
            refreshNow();
          });
        }
      });
      refreshNow();
      if (backend != null && SharedInput.WIRELESS.equals(mode) && wantConnection) {
        // 冷启动只恢复已保存的意愿，显式连接才能撤销用户的“停止连接”。
        backend.command("refresh", new Bundle());
      }
    });
  }

  @Override public Bundle state() { return new Bundle(snapshot); }

  @Override public AutoCloseable observe(Runnable changed) {
    observers.add(changed);
    return () -> observers.remove(changed);
  }

  @Override public void select(String selected) {
    if (!validMode(selected)) return;
    worker.execute(() -> {
      if (closed || selected.equals(mode)) return;
      disconnectService("输入模式已切换");
      if (wireless != null) wireless.command("disconnect", new Bundle());
      mode = selected;
      wantConnection = true;
      shizukuConnection.reset();
      context.getSharedPreferences("input-mode", Context.MODE_PRIVATE)
          .edit().putString("mode", selected).apply();
      publish(defaults(mode));
      refreshNow();
      if (SharedInput.WIRELESS.equals(mode) && wireless != null) {
        Bundle reconnect = new Bundle();
        reconnect.putBoolean("reconnect", true);
        wireless.command("refresh", reconnect);
      }
    });
  }

  @Override public void command(String action, Bundle arguments) {
    Bundle copy = arguments == null ? new Bundle() : new Bundle(arguments);
    if ("refresh".equals(action)) { refresh(); return; }
    worker.execute(() -> {
      if (closed) return;
      switch (action) {
        case "openSettings" -> open(new Intent(SharedInput.ACCESSIBILITY.equals(mode)
            ? Settings.ACTION_ACCESSIBILITY_SETTINGS : Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        case "openShizuku" -> {
          Intent launch = context.getPackageManager().getLaunchIntentForPackage(MANAGER);
          if (launch == null) installShizuku(); else open(launch);
        }
        case "installShizuku" -> installShizuku();
        case "disconnect" -> {
          wantConnection = false;
          shizukuConnection.cancel();
          disconnectService("输入连接已断开");
          if (SharedInput.WIRELESS.equals(mode) && wireless != null)
            wireless.command(action, copy);
          refreshNow();
        }
        case "startPairing" -> {
          if (!SharedInput.WIRELESS.equals(mode)) return;
          wantConnection = true;
          if (wireless == null) message("无线连接尚未准备好");
          else wireless.command(action, copy);
        }
        case "connect", "authorize", "pair", "discover" -> {
          wantConnection = true;
          if (SharedInput.WIRELESS.equals(mode)) {
            if (wireless == null) message("无线连接尚未准备好");
            else wireless.command(action, copy);
          } else if (SharedInput.SHIZUKU.equals(mode)) {
            if (service == null && !binding) shizukuConnection.reset();
            if (!Shizuku.pingBinder()) { message("请先启动 Shizuku"); return; }
            if (!shizukuReady || Shizuku.getBinder() != shizukuSource) {
              message("正在等待 Shizuku 就绪"); return;
            }
            try {
              if (!freshPermission()) Shizuku.requestPermission(REQUEST_PERMISSION);
              else if ("connect".equals(action) && service != null) {
                if (ownership.owner() != null) releaseOwner(ownership.owner());
                else { IInputService current = service; rpc.call(() -> { current.release(); return null; }); }
                for (Long token : requests.keySet()) complete(token, false, "正在重新检查连接");
                attach(service);
              } else refreshNow();
            } catch (Exception error) { diagnose("请求 Shizuku 授权", error, "无法请求授权，请打开 Shizuku 检查"); }
          } else open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
        default -> message("不支持的输入操作");
      }
    });
  }

  private void refresh() {
    if (closed || !refreshQueued.compareAndSet(false, true)) return;
    worker.execute(() -> {
      refreshQueued.set(false);
      refreshNow();
      if (SharedInput.WIRELESS.equals(mode) && wireless != null)
        wireless.command("refresh", new Bundle());
    });
  }

  private void overlayChanged(String operation, String packageName) {
    if (android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW.equals(operation)
        && context.getPackageName().equals(packageName)) refresh();
  }

  private void refreshNow() {
    if (closed) return;
    // 窗口授权查询走输入控制工作线程，前台播放服务只读快照，避免主线程周期 Binder 调用。
    try { overlayGranted = Settings.canDrawOverlays(context); }
    catch (RuntimeException denied) { overlayGranted = false; }
    Bundle value = new Bundle(snapshot);
    value.putString("mode", mode);
    boolean accessibility = accessibilityEnabled();
    value.putBoolean("accessibilityEnabled", accessibility);
    value.putBoolean("wirelessSupported", wireless != null
        && wirelessState.getBoolean("wirelessSupported", false));
    if (SharedInput.ACCESSIBILITY.equals(mode)) {
      value = defaults(mode);
      value.putBoolean("accessibilityEnabled", accessibility);
      value.putBoolean("installed", true);
      value.putBoolean("permissionGranted", accessibility);
      value.putString("message", accessibility ? "使用无障碍自动演奏" : "请开启无障碍服务");
    } else if (SharedInput.SHIZUKU.equals(mode)) {
      boolean installed = installed(MANAGER);
      boolean alive = installed && Shizuku.pingBinder();
      boolean ready = alive && shizukuReady && Shizuku.getBinder() == shizukuSource;
      boolean allowed = false;
      String permissionState = ready ? "denied" : "waiting";
      String permissionError = "";
      if (ready) try {
        allowed = freshPermission();
        permissionState = allowed ? "granted" : "denied";
      } catch (Exception error) {
        permissionState = "unavailable";
        permissionError = error.getClass().getSimpleName();
      }
      String permissionLabel = switch (permissionState) {
        case "granted" -> "已授权";
        case "denied" -> "未授权";
        case "unavailable" -> "读取失败";
        default -> "等待客户端就绪";
      };
      if (!permissionState.equals(snapshot.getString("permissionState"))
          || !permissionError.equals(snapshot.getString("permissionCheckError", "")))
        HostDiagnostics.log(Log.INFO, TAG, "Shizuku 授权检查：包名=" + context.getPackageName()
            + " 宿主UID=" + Process.myUid() + " Binder存活=" + alive + " 客户端就绪=" + ready
            + " 结果=" + permissionLabel + " 错误类型=" + permissionError, null);
      value.putBoolean("installed", installed);
      value.putBoolean("binderAlive", alive);
      value.putBoolean("binderReady", ready);
      value.putBoolean("permissionGranted", allowed);
      value.putString("permissionState", permissionState);
      value.putString("permissionCheckError", permissionError);
      value.putBoolean("connected", service != null);
      value.putBoolean("busy", binding);
      if (allowed && wantConnection && service == null && !binding
          && !snapshot.getBoolean("permissionGranted")) shizukuConnection.reset();
      if (!allowed) {
        if (service != null || binding) disconnectService("Shizuku 授权已失效");
        if (!alive || ready) shizukuConnection.cancel();
        value.putBoolean("connected", false);
        value.putBoolean("touchReady", false);
        value.putBoolean("busy", alive && !ready && wantConnection);
        value.putString("message", !installed ? "请安装 Shizuku"
            : !alive ? "请先启动 Shizuku" : !ready ? "正在等待 Shizuku 就绪"
            : !permissionError.isEmpty() ? "暂时无法读取 Shizuku 授权，请重新检查连接" : "请授予 Shizuku 权限");
      } else if (service == null && !binding && wantConnection) {
        if (shizukuConnection.mayStart(SystemClock.elapsedRealtime())) {
          publish(value);
          bindShizuku();
          return;
        }
        value.putBoolean("busy", shizukuConnection.waitingToRetry(SystemClock.elapsedRealtime()));
      }
    } else {
      value.putAll(wirelessState);
      value.putString("wirelessMessage", wirelessState.getString("message", ""));
      value.putString("mode", mode);
      value.putBoolean("wirelessSupported", wireless != null
          && wirelessState.getBoolean("wirelessSupported", false));
      value.putBoolean("connected", service != null);
      value.putBoolean("permissionGranted", service != null);
      value.putString("permissionState", service != null ? "granted" : "unavailable");
      if (service == null) value.putBoolean("touchReady", false);
      else {
        value.putString("message", snapshot.getString("message", "正在检查触屏能力"));
        value.putBoolean("touchReady", snapshot.getBoolean("touchReady", false));
        value.putBoolean("busy", snapshot.getBoolean("busy", false));
      }
      if (wireless == null) value.putString("message", "无线连接尚未准备好");
    }
    publish(value);
  }

  // SDK 的 checkSelfPermission 会缓存已授权状态；直接查询官方 AIDL 及时发现管理器撤权。
  private boolean freshPermission() throws Exception {
    IBinder binder = Shizuku.getBinder();
    return binder != null && shizukuRpc.call(() -> IShizukuService.Stub.asInterface(binder).checkSelfPermission());
  }

  private void bindShizuku() {
    String stage = "读取安装信息";
    long attempt = shizukuConnection.start(SystemClock.elapsedRealtime());
    if (attempt == 0) return;
    try {
      if (shizukuRpc.call(Shizuku::getVersion) < 13) {
        shizukuConnection.cancel(); message("请升级 Shizuku 至 v13 或更新版本"); return;
      }
      int serverUid = shizukuRpc.call(Shizuku::getUid);
      Bundle server = new Bundle(snapshot);
      server.putInt("shizukuUid", serverUid);
      publish(server);
      if (!InputIdentity.privileged(serverUid)) {
        shizukuConnection.cancel();
        diagnose("核对 Shizuku 身份", null, "Shizuku 服务身份不可用，请重新启动 Shizuku 后连接");
        HostDiagnostics.log(Log.WARN, TAG, "Shizuku 返回不支持的服务UID=" + serverUid, null);
        return;
      }
      var installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      int version = Build.VERSION.SDK_INT >= 28
          ? (int) installed.getLongVersionCode() : installed.versionCode;
      // 同版 Debug 安装也可能更新 JNI 和 IPC，不能复用旧 APK 的助手进程。
      int revision = Objects.hash(version, installed.lastUpdateTime) & Integer.MAX_VALUE;
      if (revision == 0) revision = 1;
      long installedAt = installed.lastUpdateTime;
      long selectedGeneration = ++generation;
      IBinder source = shizukuSource;
      Bundle installation = new Bundle(snapshot);
      installation.putInt("hostUid", Process.myUid());
      installation.putInt("serviceRevision", revision);
      installation.putInt("installedVersionCode", version);
      installation.putLong("installedUpdateTime", installed.lastUpdateTime);
      installation.putString("errorStage", "");
      installation.putString("errorType", "");
      installation.putInt("connectionAttempt", shizukuConnection.attempts());
      installation.putString("shizukuTransport", serverUid == 2000 ? "shell-process" : "user-service");
      publish(installation);
      if (serverUid == 2000) {
        bindShizukuShell(attempt, selectedGeneration, source, version, installedAt, revision);
        return;
      }
      serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, InputUserService.class))
          .tag(ShizukuConnectionPolicy.serviceTag(Process.myUid(), Process.myPid(), revision, selectedGeneration))
          .version(revision).daemon(false)
          .processNameSuffix("touch_shell");
      connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
          enqueueShizukuCallback(() -> {
            if (closed || selectedGeneration != generation || !binding || connection != this
                || !wantConnection || !SharedInput.SHIZUKU.equals(mode)
                || source != shizukuSource || source != Shizuku.getBinder()) return;
            try {
              var current = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
              int currentVersion = Build.VERSION.SDK_INT >= 28
                  ? (int) current.getLongVersionCode() : current.versionCode;
              if (currentVersion != version || current.lastUpdateTime != installedAt) {
                failShizuku("核对安装修订", null);
                return;
              }
              if (binder == null || !binder.pingBinder()) {
                failShizuku("等待有效的 Shizuku 触控连接", null);
                return;
              }
              if (!freshPermission()) {
                shizukuConnection.cancel();
                disconnectService("Shizuku 授权已失效");
                refreshNow();
                return;
              }
            } catch (Exception error) { failShizuku("核对 Shizuku 触控连接", error); return; }
            if (!shizukuConnection.connected(attempt, SystemClock.elapsedRealtime())) return;
            binding = false;
            attach(IInputService.Stub.asInterface(binder));
          });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
          enqueueShizukuCallback(() -> {
            if (selectedGeneration != generation || connection != this || closed) return;
            failShizuku("Shizuku 触控连接已断开", null);
          });
        }
      };
      binding = true;
      message("正在建立 Shizuku 连接");
      HostDiagnostics.log(Log.INFO, TAG, "请求 Shizuku 输入助手：宿主UID=" + Process.myUid()
          + " ShizukuUID=" + serverUid + " 安装版本=" + version + " 助手修订=" + revision, null);
      stage = "启动 Shizuku 输入助手";
      Shizuku.UserServiceArgs pendingArgs = serviceArgs;
      ServiceConnection pendingConnection = connection;
      shizukuRpc.call(() -> { Shizuku.bindUserService(pendingArgs, pendingConnection); return null; });
      ServiceConnection expected = connection;
      worker.schedule(() -> {
        if (!closed && binding && selectedGeneration == generation && connection == expected
            && shizukuConnection.expired(attempt, SystemClock.elapsedRealtime())) {
          failShizuku("等待 Shizuku 助手回调", null);
        }
      }, ShizukuConnectionPolicy.START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (Exception error) {
      failShizuku(stage, error);
    }
  }

  /** shell 路径复用无线调试的输入服务；仅启动通道改为已授权的 Shizuku 服务。 */
  private void bindShizukuShell(long attempt, long epoch, IBinder source, int version,
      long installedAt, int revision) throws Exception {
    ShizukuShellSession pending = new ShizukuShellSession(shizukuRpc, (session, remote) ->
        enqueueShizukuCallback(() -> {
          if (closed || shellSession != session || epoch != generation || !binding
              || !wantConnection || !SharedInput.SHIZUKU.equals(mode)
              || source != shizukuSource || source != Shizuku.getBinder()) return;
          try {
            var current = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
            if (code != version || current.lastUpdateTime != installedAt)
              throw new SecurityException("连接期间安装修订发生变化");
            if (!freshPermission()) throw new SecurityException("Shizuku 授权已失效");
            if (remote == null || !remote.asBinder().pingBinder())
              throw new IllegalStateException("输入助手未返回有效连接");
            if (!shizukuConnection.connected(attempt, SystemClock.elapsedRealtime())) return;
            binding = false;
            HostDiagnostics.log(Log.INFO, TAG, "已收到 Shizuku 后台进程连接，开始核对身份和触屏", null);
            attach(remote);
          } catch (Exception error) { failShizuku("核对 Shizuku 后台连接", error); }
        }));
    shellSession = pending;
    binding = true;
    message("正在建立 Shizuku 连接");
    HostDiagnostics.log(Log.INFO, TAG, "请求 Shizuku 后台进程：宿主UID=" + Process.myUid()
        + " ShizukuUID=2000 安装版本=" + version + " 助手修订=" + revision, null);
    pending.start(context, IShizukuService.Stub.asInterface(source));
    HostDiagnostics.log(Log.INFO, TAG, "Shizuku 启动指令已发送，等待受保护的连接交付", null);
    worker.schedule(() -> {
      if (!closed && binding && shellSession == pending && epoch == generation && pending.expired())
        failShizuku("等待 Shizuku 后台进程交付", null);
    }, ShizukuShellSession.START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  android.os.IBinder offerShizukuHelper(String token, IInputService remote) {
    ShizukuShellSession pending = shellSession;
    return pending == null ? null : pending.offer(token, remote);
  }

  private void failShizuku(String stage, Throwable error) {
    boolean retryable = error == null || error instanceof IllegalStateException
        || error instanceof java.util.concurrent.TimeoutException;
    Throwable cause = error;
    for (int i = 0; i < 6 && cause != null; ++i, cause = cause.getCause()) {
      if (cause instanceof SecurityException || cause instanceof IllegalArgumentException
          || cause instanceof PackageManager.NameNotFoundException) { retryable = false; break; }
      if (cause instanceof RemoteException) { retryable = true; break; }
      if (cause.getCause() == cause) break;
    }
    long retryDelay = shizukuConnection.failed(shizukuConnection.currentTicket(),
        SystemClock.elapsedRealtime(), retryable);
    disconnectService("Shizuku 触控连接未建立");
    if (retryDelay < 0) {
      shizukuConnection.cancel();
      diagnose(stage, error, retryable
          ? "Shizuku 后台连接失败，请允许其后台运行后重试" : "Shizuku 连接失败，请检查授权后重试");
      return;
    }
    diagnose(stage, error, "正在重试 Shizuku 触控连接");
    Bundle value = new Bundle(snapshot);
    value.putBoolean("busy", true);
    publish(value);
    long expectedGeneration = generation;
    worker.schedule(() -> {
      if (closed || !wantConnection || expectedGeneration != generation
          || !SharedInput.SHIZUKU.equals(mode) || service != null || binding) return;
      refreshNow();
    }, retryDelay, TimeUnit.MILLISECONDS);
  }

  private void attach(IInputService remote) {
    if (remote == null) { message("连接不可用，请重试"); return; }
    final int expectedUid = SharedInput.SHIZUKU.equals(mode) ? snapshot.getInt("shizukuUid", -1) : 2000;
    String stage = "关联助手死亡监听";
    try {
      if (service != null && service != remote) disconnectService("输入连接已重连");
      if (service == remote && serviceDeath != null)
        remote.asBinder().unlinkToDeath(serviceDeath, 0);
      service = remote;
      long epoch = generation;
      serviceDeath = () -> enqueueShizukuCallback(() -> {
        if (service != remote || closed) return;
        if (SharedInput.SHIZUKU.equals(mode)) {
          failShizuku("Shizuku 触控连接已停止", null);
          return;
        }
        disconnectService("输入连接已停止");
        refreshNow();
      });
      remote.asBinder().linkToDeath(serviceDeath, 0);
      stage = "登记输入助手回调";
      IInputCallback callback = new IInputCallback.Stub() {
        @Override public void changed(Bundle state) {
          Bundle copy = state == null ? new Bundle() : new Bundle(state);
          worker.execute(() -> {
            acknowledgeRelease(remote, copy);
            if (service != remote || epoch != generation || closed) return;
            Bundle value = new Bundle(snapshot);
            value.putAll(copy);
            value.putString("mode", mode);
            value.putBoolean("connected", true);
            value.putBoolean("busy", false);
            value.putBoolean("touchReady", InputIdentity.matches(expectedUid, copy.getInt("uid", -1))
                && copy.getBoolean("supported", false));
            boolean contactChanged = copy.containsKey("contactDecision")
                && (!java.util.Objects.equals(copy.getString("contactDecision"), snapshot.getString("contactDecision"))
                    || copy.getInt("trackedSlots", -1) != snapshot.getInt("trackedSlots", -1)
                    || copy.getInt("touchState", -1) != snapshot.getInt("touchState", -1));
            if (contactChanged && (copy.getBoolean("waitingForFingers")
                || copy.getBoolean("staleContactIgnored") || copy.getInt("contactReadErrno") != 0))
              HostDiagnostics.log(Log.INFO, TAG, "接管触点检查：槽位=" + copy.getInt("trackedSlots", -1)
                  + " 实际接触=" + copy.getInt("touchState", -1)
                  + " 判断=" + copy.getString("contactDecision")
                  + " 读取错误=" + copy.getInt("contactReadErrno")
                  + " 忽略非接触槽=" + copy.getBoolean("staleContactIgnored"), null);
            if (!value.getBoolean("touchReady", false)) {
              HostDiagnostics.log(Log.WARN, TAG, "输入预检未就绪：模式=" + mode
                  + " 助手UID=" + copy.getInt("uid", -1)
                  + " 阶段=" + copy.getString("errorStage", copy.getString("diagnosticStage", "预检"))
                  + " 类型=" + copy.getString("errorType", copy.getString("diagnosticType", ""))
                  + " 原因=" + copy.getString("message", ""), null);
            }
            publish(value);
          });
        }
        @Override public void finished(long token, boolean success, String message) {
          worker.execute(() -> {
            if (epoch != generation || service != remote) return;
            complete(token, success, message);
          });
        }
      };
      rpc.call(() -> { remote.attach(callback); return null; });
      stage = "读取助手预检快照";
      Bundle initial = rpc.call(remote::inspect);
      if (!InputIdentity.matches(expectedUid, initial.getInt("uid", -1)))
        throw new SecurityException("输入助手与所选连接的身份不一致");
      Bundle value = new Bundle(snapshot);
      value.putAll(initial);
      value.putBoolean("connected", true);
      value.putBoolean("busy", true);
      value.putBoolean("touchReady", false);
      value.putString("message", "已连接，正在检查触屏能力");
      publish(value);
      HostDiagnostics.log(Log.INFO, TAG, "已关联输入助手：模式=" + mode + " 宿主UID=" + Process.myUid(), null);
    } catch (Exception error) {
      if (SharedInput.SHIZUKU.equals(mode)) {
        failShizuku(stage, error);
        return;
      }
      wantConnection = false;
      disconnectService("授权或连接已失效");
      diagnose(stage, error, "连接失败，请重新授权或连接");
      refreshNow();
    }
  }

  @Override public SharedInput.Session openSession() { return new Lease(owners.incrementAndGet()); }

  @Override public void screenshot(int displayId, app.luoxianlv.hot.contract.AccessibilityBinding.ScreenshotCallback callback) {
    IInputService remote = service;
    long epoch = generation;
    if (closed || remote == null || displayId != 0 || !screenshotPending.compareAndSet(false, true)) {
      main.post(() -> callback.failure(-1)); return;
    }
    try {
      screenshots.execute(() -> {
        android.graphics.Bitmap bitmap = null;
        long began = SystemClock.elapsedRealtime();
        try (var descriptor = remote.screenshot(displayId)) {
          if (descriptor == null) throw new java.io.IOException("截图没有返回图像");
          var options = new android.graphics.BitmapFactory.Options();
          options.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
          bitmap = android.graphics.BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
          if (bitmap == null || bitmap.getWidth() < 2 || bitmap.getHeight() < 2)
            throw new java.io.IOException("截图图像无效");
          var frame = new app.luoxianlv.hot.contract.AccessibilityBinding.Frame(bitmap);
          bitmap = null;
          HostDiagnostics.log(Log.INFO, "截图", "输入服务截图完成：尺寸=" + frame.width + "x" + frame.height
              + "，耗时=" + (SystemClock.elapsedRealtime() - began) + "毫秒", null);
          main.post(() -> {
            screenshotPending.set(false);
            if (closed || service != remote || generation != epoch) { frame.close(); callback.failure(-1); }
            else callback.success(frame);
          });
        } catch (Exception | LinkageError failure) {
          if (bitmap != null) bitmap.recycle();
          HostDiagnostics.log(Log.WARN, "截图", "输入服务截图失败：类型=" + failure.getClass().getSimpleName(), null);
          main.post(() -> { screenshotPending.set(false); callback.failure(-1); });
        }
      });
    } catch (RejectedExecutionException stopped) {
      screenshotPending.set(false); main.post(() -> callback.failure(-1));
    }
  }

  @Override public AutoCloseable press(float[] points, int durationMs, int width, int height,
      int rotation, SharedInput.Completion completion) {
    return press(globalSession, points, durationMs, width, height, rotation, completion);
  }

  private AutoCloseable press(Lease owner, float[] points, int durationMs, int width, int height,
      int rotation, SharedInput.Completion completion) {
    Request request = new Request(tokens.incrementAndGet(), owner, completion);
    owner.pending.incrementAndGet();
    requests.put(request.token, request);
    float[] copy = points == null ? null : points.clone();
    worker.execute(() -> {
      if (closed || owner.closed.get() || owner.releasing.get() || request.cancelled.get()) {
        complete(request.token, false, "本次按键已取消"); return;
      }
      IInputService remote = service;
      if (remote == null || !snapshot.getBoolean("touchReady", false)) {
        HostDiagnostics.log(Log.WARN, TAG, "未提交合流按键：模式=" + mode
            + " 连接=" + (remote != null) + " 就绪=" + snapshot.getBoolean("touchReady", false)
            + " 助手UID=" + snapshot.getInt("uid", -1)
            + " 原因=" + snapshot.getString("message", ""), null);
        complete(request.token, false, snapshot.getString("message", "连接尚未就绪"));
        return;
      }
      if (!ownership.claim(owner)) {
        complete(request.token, false, "上一播放会话尚未归还触屏");
        return;
      }
      for (Request pending : requests.values()) {
        if (pending.token != request.token && pending.owner == owner && pending.submitted) {
          complete(request.token, false, "上一按键尚未完成");
          return;
        }
      }
      try {
        request.submitted = true;
        if (!remote.begin(copy, durationMs, width, height, rotation, request.token)) {
          HostDiagnostics.log(Log.WARN, TAG, "助手拒绝按键：模式=" + mode + " 显示=" + width + "x" + height
              + " 方向=" + rotation + " 时长=" + durationMs + " 点数=" + (copy == null ? 0 : copy.length / 2), null);
          complete(request.token, false, "连接未接受本次按键");
          releaseOwner(owner);
        }
      } catch (Exception error) {
        complete(request.token, false, "输入连接已断开");
        disconnectService("输入连接已断开");
        diagnose("提交合流按键", error, "输入连接已断开，请重新连接");
        refreshNow();
      }
    });
    return () -> {
      if (!request.cancelled.compareAndSet(false, true)) return;
      worker.execute(() -> {
        if (!request.submitted) { complete(request.token, false, "本次按键已取消"); return; }
        try { if (service != null) service.cancel(request.token); }
        catch (RemoteException error) { disconnectService("输入连接已断开"); }
        // 原生完成回调后才清除任务，不能把排队取消当成按键已结束。
      });
    };
  }

  @Override public void release() {
    worker.execute(() -> {
      Lease owner = ownership.owner();
      if (owner != null) releaseOwner(owner);
      for (Long token : requests.keySet()) complete(token, false, "演奏已暂停");
    });
  }

  private void releaseOwner(Lease owner) {
    for (Request request : requests.values())
      if (request.owner == owner) complete(request.token, false, "演奏已暂停");
    if (ownership.owner() != owner) { owner.releasing.set(false); return; }
    owner.releasing.set(true);
    IInputService remote = service;
    if (remote == null) return; // 断连路径等待旧助手确认归还或 Binder 死亡。
    releaseRemote = remote;
    expectedRelease = tokens.incrementAndGet();
    ownership.releasing(owner, expectedRelease);
    long ticket = expectedRelease;
    try { rpc.call(() -> { remote.releaseSession(ticket); return null; }); }
    catch (Exception error) {
      if (!remote.asBinder().isBinderAlive()) clearOwner();
      else pollRelease(remote, expectedRelease);
    }
  }

  private void acknowledgeRelease(IInputService remote, Bundle state) {
    if (releaseRemote != remote || expectedRelease <= 0) return;
    Lease previous = ownership.acknowledge(state.getLong("releaseTicket", 0),
        state.getBoolean("active", true));
    if (previous != null) finishOwner(previous);
  }

  private void clearOwner() {
    finishOwner(ownership.clear());
  }

  private void finishOwner(Lease previous) {
    releaseRemote = null;
    expectedRelease = 0;
    if (previous != null) previous.releasing.set(false);
    if (closed) worker.shutdown();
  }

  private void pollRelease(IInputService remote, long ticket) {
    worker.schedule(() -> {
      if (releaseRemote != remote || expectedRelease != ticket) return;
      if (!remote.asBinder().isBinderAlive()) { clearOwner(); return; }
      try { acknowledgeRelease(remote, rpc.call(remote::inspect)); }
      catch (Exception ignored) { }
      if (releaseRemote == remote && expectedRelease == ticket) pollRelease(remote, ticket);
    }, 100, TimeUnit.MILLISECONDS);
  }

  private void disconnectService(String message) {
    ++generation;
    binding = false;
    shizukuConnection.invalidate();
    ShizukuShellSession previousShell = shellSession;
    shellSession = null;
    IInputService old = service;
    if (old != null && ownership.owner() != null) releaseOwner(ownership.owner());
    service = null;
    if (old != null) {
      if (serviceDeath != null) old.asBinder().unlinkToDeath(serviceDeath, 0);
      if (releaseRemote == old) pollRelease(old, expectedRelease);
      else try { rpc.call(() -> { old.release(); return null; }); } catch (Exception ignored) { }
    }
    serviceDeath = null;
    if (previousShell != null) previousShell.close();
    Shizuku.UserServiceArgs previousArgs = serviceArgs;
    ServiceConnection previousConnection = connection;
    serviceArgs = null;
    connection = null;
    if (previousArgs != null && previousConnection != null) {
      // 先移除本地订阅，再删除远端记录，与 GKD 的清理顺序一致。
      try { shizukuRpc.call(() -> { Shizuku.unbindUserService(previousArgs, previousConnection, false); return null; }); }
      catch (Exception ignored) { }
      try { shizukuRpc.call(() -> { Shizuku.unbindUserService(previousArgs, previousConnection, true); return null; }); }
      catch (Exception ignored) { }
    }
    for (Long token : requests.keySet()) complete(token, false, message);
    Bundle value = new Bundle(snapshot);
    value.putBoolean("connected", false);
    value.putBoolean("touchReady", false);
    value.putBoolean("busy", false);
    value.putString("message", message);
    publish(value);
  }

  private void complete(long token, boolean success, String message) {
    Request request = requests.remove(token);
    if (request != null) {
      if (!success) HostDiagnostics.log(Log.WARN, TAG, "按键未完成：模式=" + mode + " 原因=" + message, null);
      request.owner.pending.decrementAndGet();
      main.post(() -> request.completion.complete(success, message == null ? "" : message));
    }
  }

  private boolean accessibilityEnabled() {
    String enabled;
    try {
      enabled = Settings.Secure.getString(context.getContentResolver(),
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    } catch (SecurityException error) { return false; }
    if (enabled == null) return false;
    ComponentName expected = new ComponentName(context.getPackageName(),
        "app.luoxianlv.service.MusicAccessibilityService");
    for (String item : enabled.split(":"))
      if (expected.equals(ComponentName.unflattenFromString(item))) return true;
    return false;
  }

  private boolean installed(String name) {
    try { context.getPackageManager().getApplicationInfo(name, 0); return true; }
    catch (PackageManager.NameNotFoundException error) { return false; }
  }

  private void installShizuku() {
    open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
  }

  private void open(Intent intent) {
    main.post(() -> {
      try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
      catch (Exception error) { worker.execute(() -> message("无法打开页面，请在系统设置中操作")); }
    });
  }

  private void message(String message) {
    Bundle value = new Bundle(snapshot);
    value.putString("message", message);
    value.putBoolean("busy", binding);
    publish(value);
  }

  private void diagnose(String stage, Throwable error, String message) {
    String type = error == null ? "" : error.getClass().getSimpleName();
    HostDiagnostics.log(Log.WARN, TAG, "阶段=" + stage + " 错误类型=" + type + " " + message, null);
    Bundle value = new Bundle(snapshot);
    value.putString("errorStage", stage);
    value.putString("errorType", type);
    value.putString("message", message);
    publish(value);
  }

  private void publish(Bundle value) {
    value.putBoolean("overlayGranted", overlayGranted);
    value.putBoolean("initialized", initialized);
    Bundle previous = snapshot;
    if (sameValues(previous, value)) return;
    snapshot = new Bundle(value);
    main.post(() -> { for (Runnable observer : observers) observer.run(); });
  }

  private static boolean sameValues(Bundle left, Bundle right) {
    if (!left.keySet().equals(right.keySet())) return false;
    for (String key : left.keySet()) if (!Objects.equals(left.get(key), right.get(key))) return false;
    return true;
  }

  @Override public void close() {
    if (closed) return;
    closed = true;
    screenshots.shutdown();
    if (overlayOps != null) try { overlayOps.stopWatchingMode(overlayWatcher); }
    catch (RuntimeException ignored) { }
    Shizuku.removeBinderReceivedListener(received);
    Shizuku.removeBinderDeadListener(died);
    Shizuku.removeRequestPermissionResultListener(permission);
    worker.execute(() -> {
      shizukuConnection.cancel();
      disconnectService("输入连接已关闭");
      if (wireless != null) wireless.close();
      observers.clear();
      rpc.close();
      shizukuRpc.close();
      if (ownership.owner() == null) worker.shutdown();
    });
  }

  private static Bundle defaults(String mode) {
    Bundle value = new Bundle();
    value.putString("mode", mode);
    value.putString("message", "正在检查授权状态");
    return value;
  }

  private static boolean validMode(String value) {
    return SharedInput.ACCESSIBILITY.equals(value) || SharedInput.SHIZUKU.equals(value)
        || SharedInput.WIRELESS.equals(value);
  }

  private static final class Request {
    final long token;
    final SharedInput.Completion completion;
    final Lease owner;
    boolean submitted;
    final AtomicBoolean cancelled = new AtomicBoolean();
    Request(long token, Lease owner, SharedInput.Completion completion) {
      this.token = token;
      this.owner = owner;
      this.completion = completion;
    }
  }

  private final class Lease implements SharedInput.Session {
    final long id;
    final AtomicInteger pending = new AtomicInteger();
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicBoolean releasing = new AtomicBoolean();
    Lease(long id) { this.id = id; }
    @Override public AutoCloseable press(float[] points, int durationMs, int width, int height,
        int rotation, SharedInput.Completion completion) {
      return InputController.this.press(this, points, durationMs, width, height, rotation, completion);
    }
    @Override public void release() {
      releasing.set(true);
      worker.execute(() -> releaseOwner(this));
    }
    @Override public boolean idle() {
      return pending.get() == 0 && !releasing.get() && ownership.owner() != this;
    }
    @Override public void close() {
      if (closed.compareAndSet(false, true)) release();
    }
  }
}
