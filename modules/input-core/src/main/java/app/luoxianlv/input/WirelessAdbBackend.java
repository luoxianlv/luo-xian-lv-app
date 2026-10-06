package app.luoxianlv.input;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import app.luoxianlv.hot.contract.HostDiagnostics;
import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.PairingConnectionCtx;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/** 无线调试连接只作为启动通道；演奏通过经过宿主身份校验的 Binder 进行。 */
public final class WirelessAdbBackend implements InputController.WirelessBackend {
  private static final String CHANNEL = "input-wireless-pairing";
  private static final int NOTIFICATION = 7212;
  private static volatile WirelessAdbBackend current;
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(
      task -> new Thread(task, "wireless-control"));
  private final ExecutorService io = Executors.newSingleThreadExecutor(task -> new Thread(task, "wireless-io"));
  private final ExecutorService streams = Executors.newSingleThreadExecutor(task -> new Thread(task, "wireless-stream"));
  private final ExecutorService cleanup = Executors.newFixedThreadPool(2, task -> new Thread(task, "wireless-close"));
  private final AtomicLong generation = new AtomicLong();
  private final AtomicLong commands = new AtomicLong();
  private final WirelessDiscovery discovery;
  private volatile String notificationToken;
  private volatile InputController.WirelessCallback callback;
  private volatile PairingConnectionCtx pairing;
  private volatile AdbConnection connection;
  private volatile AdbStream helperStream;
  private volatile IInputService service;
  private volatile Attempt attempt;
  private volatile InputBridgeProvider.Lease lease;
  private volatile int pairingPort, connectionPort;
  private volatile WirelessDiscovery.Endpoint pairEndpoint = WirelessDiscovery.Endpoint.EMPTY;
  private volatile WirelessDiscovery.Endpoint connectEndpoint = WirelessDiscovery.Endpoint.EMPTY;
  private volatile long foregroundSession;
  private volatile boolean pairingOnly;
  private volatile boolean busy, closed, watching;
  private volatile boolean wantConnection;
  private boolean notificationVisible;
  private String message = "请开启无线调试，然后使用配对码配对";
  private String errorStage = "", errorType = "";
  private int recoveryAttempts;
  private long recoveryAfter;
  private boolean recovering;
  private final ConnectivityManager connectivity;
  private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
    @Override public void onAvailable(Network network) { enqueueRefresh(); }
    @Override public void onLost(Network network) { enqueueRefresh(); }
    @Override public void onCapabilitiesChanged(Network network, android.net.NetworkCapabilities capabilities) { enqueueRefresh(); }
  };
  private final ContentObserver settings = new ContentObserver(main) {
    @Override public void onChange(boolean selfChange) { enqueueRefresh(); }
  };

  public WirelessAdbBackend(Context source) {
    context = source.getApplicationContext();
    if (context == null) throw new IllegalArgumentException("缺少宿主上下文");
    android.content.SharedPreferences preferences = context.getSharedPreferences("input-wireless", Context.MODE_PRIVATE);
    String storedToken = preferences.getString("notificationToken", null);
    if (storedToken == null || !storedToken.matches("[0-9a-f]{64}")) {
      storedToken = nonce();
      preferences.edit().putString("notificationToken", storedToken).apply();
    }
    notificationToken = storedToken;
    wantConnection = preferences.getBoolean("wantConnection", true);
    current = this;
    connectivity = context.getSystemService(ConnectivityManager.class);
    discovery = new WirelessDiscovery(context, new WirelessDiscovery.Listener() {
      @Override public void changed(WirelessDiscovery.Endpoint pairing, WirelessDiscovery.Endpoint connecting, long revision) {
        if (closed) return;
        submit(() -> {
          int pair = pairing.port;
          int previous;
          synchronized (WirelessAdbBackend.this) {
            if (closed || !watching || revision != discovery.revision()) return;
            previous = pairingPort;
            pairingPort = pair;
            connectionPort = connecting.port;
            pairEndpoint = pairing;
            connectEndpoint = connecting;
          }
          // 输入通知持有发现时的端口，服务短暂消失不会撤掉正在填码的操作。
          if (pair > 0 && pair != previous && !busy) showPairingNotification();
          if (automaticConnectionAllowed()) automaticConnect();
          publish();
        });
      }
      @Override public void failed(String reason) {
        if (!closed) submit(() -> { message = reason; publish(); });
      }
    });
    if (connectivity != null) try { connectivity.registerNetworkCallback(new NetworkRequest.Builder().build(), network); }
    catch (RuntimeException ignored) { }
    try { context.getContentResolver().registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, settings); }
    catch (RuntimeException ignored) { }
    control.scheduleWithFixedDelay(() -> {
      if (!closed && watching) refresh();
    }, 2, 2, TimeUnit.SECONDS);
  }

  static WirelessAdbBackend current() { return current; }

  @Override public void setCallback(InputController.WirelessCallback next) {
    callback = next;
    if (!closed) submit(this::publish);
  }

  @Override public void command(String action, Bundle arguments) {
    if (closed) return;
    Bundle copy = arguments == null ? new Bundle() : new Bundle(arguments);
    // 撤销先改变租约，不等待 TLS 或读取线程返回。
    if ("disconnect".equals(action)) {
      long epoch;
      synchronized (this) {
        commands.incrementAndGet();
        setConnectionWanted(false);
        pairingOnly = false;
        invalidateNotification();
        watching = false;
        pairEndpoint = connectEndpoint = WirelessDiscovery.Endpoint.EMPTY;
        pairingPort = connectionPort = 0;
        discovery.close();
        stopOperation();
        epoch = generation.get();
      }
      submit(() -> {
        if (!validEpoch(epoch)) return;
        cancelNotification();
        WirelessPairingService.complete(context);
        message = "无线调试连接已断开";
        notifyDisconnected(message);
        publish();
      });
      return;
    }
    long commandRevision = commands.get();
    submit(() -> {
      synchronized (WirelessAdbBackend.this) {
        if (closed || commandRevision != commands.get()) return;
        switch (action) {
        case "refresh" -> {
          if (copy.getBoolean("reconnect")) { stopOperation(); setConnectionWanted(true); resetRecovery(); }
          watching = true; refresh();
        }
        case "discover", "pair", "connect", "authorize", "startPairing" -> {
          if ("startPairing".equals(action) && !notificationGranted()) {
            message = "请先允许配对通知，再开始配对";
            publish();
            return;
          }
          copy.putLong("foregroundCommandRevision", commandRevision);
          if ("startPairing".equals(action) || "discover".equals(action)) {
            pairingOnly = true;
            // 已发起的旧连接也要撤销，不能在随后交付时关闭新的输入通知。
            if (busy) stopOperation();
          }
          try { WirelessPairingService.start(context, action, copy); }
          catch (RuntimeException failure) { pairingOnly = false; diagnose("启动配对前台会话", failure); message = "请在应用前台重新发起无线连接"; publish(); }
        }
        case "foregroundDiscover", "foregroundPair", "foregroundConnect" -> {
          if (copy.containsKey("foregroundCommandRevision")
              && !foregroundCommandCurrent(copy.getLong("foregroundCommandRevision"))) return;
          foregroundSession = copy.getLong("foregroundSession");
          pairingOnly = !"foregroundConnect".equals(action);
          notificationVisible = false;
          resetRecovery();
          watching = true;
          discovery.pairingEnabled(pairingOnly);
          if ("foregroundPair".equals(action)) pair(copy.getString("code", ""), copy.getInt("port", 0));
          else if ("foregroundConnect".equals(action)) { setConnectionWanted(true); connect(copy.getInt("port", 0)); }
          else { setConnectionWanted(true); refresh(); }
          if (copy.getBoolean("openSettingsAfterStart")) {
            long session = foregroundSession;
            main.post(() -> {
              if (!closed && commandRevision == commands.get() && foregroundSession == session)
                openWirelessSettings();
            });
          }
        }
        case "foregroundEnded" -> {
          if (copy.getLong("foregroundSession") != foregroundSession) return;
          foregroundSession = 0;
          pairingOnly = false;
          discovery.pairingEnabled(false);
          if (!copy.getBoolean("preserveNotification")) cancelNotification();
          if (copy.getBoolean("cancel")) {
            setConnectionWanted(WirelessRecoveryPolicy.afterForegroundCancel(wantConnection, service != null));
            invalidateNotification();
            if (service == null) {
              stopOperation();
              message = copy.getBoolean("timedOut") ? "配对已超时，请重新点击配对" : "已停止无线调试连接";
              publish();
            }
          }
        }
        case "openSettings" -> main.post(this::openWirelessSettings);
          default -> { message = "不支持的无线调试操作"; publish(); }
        }
      }
    });
  }

  boolean foregroundCommandCurrent(long revision) {
    return !closed && revision == commands.get();
  }

  private void openWirelessSettings() {
    // 标准系统可直达无线调试；厂商修改入口时退回开发者选项。
    Intent[] entries = {
        new Intent().setComponent(new android.content.ComponentName("com.android.settings",
            "com.android.settings.Settings$WirelessDebuggingActivity")),
        new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless"),
        new Intent(Settings.ACTION_SETTINGS)
    };
    for (Intent entry : entries) {
      try {
        context.startActivity(entry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        return;
      } catch (android.content.ActivityNotFoundException | SecurityException unavailable) { }
    }
    submit(() -> { message = "请在系统设置中打开开发者选项和无线调试"; publish(); });
  }

  private void submit(Runnable task) {
    if (closed) return;
    try { control.execute(() -> { if (!closed) task.run(); }); }
    catch (java.util.concurrent.RejectedExecutionException ignored) { }
  }

  private void enqueueRefresh() {
    if (!closed) submit(() -> { if (watching) refresh(); });
  }

  private void refresh() {
    if (closed) return;
    if (!supported()) message = "本机无线调试需要 Android 11 或更新版本，请使用无障碍模式";
    else if (!localNetworkGranted()) message = "请允许访问本地网络以发现无线调试";
    else if (!WirelessDiscovery.wifiConnected(context)) {
      // 无线调试只负责启动。活 Binder、租约和演奏不依赖 Wi-Fi 或 adbd。
      discovery.close();
      if (service == null) message = "请连接 Wi-Fi 后使用无线调试";
    } else if (!wirelessEnabled()) {
      // 系统尚未允许当前网络时等待开关恢复，不用旧发现端口消耗重连次数。
      discovery.close();
      if (service == null) message = "请开启系统无线调试，随后会自动连接";
    } else {
      discovery.start();
      if (foregroundSession != 0 && pairingPort > 0 && !busy && !notificationVisible && notificationGranted()) showPairingNotification();
      if (service != null && !service.asBinder().isBinderAlive()) {
        stopOperation();
        requestRecovery();
        notifyDisconnected(message);
      }
      if (automaticConnectionAllowed()) automaticConnect();
    }
    publish();
  }

  private boolean prerequisites() {
    watching = true;
    if (!supported() || !localNetworkGranted() || !wirelessEnabled() || !WirelessDiscovery.wifiConnected(context)) {
      refresh();
      return false;
    }
    discovery.start();
    return true;
  }

  private void pair(String supplied, int explicitPort) {
    if (busy) { message = "正在连接，请稍候或先断开"; publish(); return; }
    if (!prerequisites()) { WirelessPairingService.result(context, foregroundSession, false, "请连接 Wi-Fi 并开启无线调试后重试"); return; }
    String code = supplied.trim();
    if (!code.matches("[0-9]{6}")) { message = "请输入系统显示的六位配对码"; publish(); WirelessPairingService.result(context, foregroundSession, false, message); return; }
    if (explicitPort != 0 && !validPort(explicitPort)) { message = "配对请求无效，请重新打开系统配对窗口"; publish(); return; }
    stopOperation();
    long epoch = generation.get();
    long operationSession = foregroundSession;
    clearDiagnostics();
    busy = true;
    setConnectionWanted(true);
    message = "正在配对，请保持系统配对窗口打开";
    WirelessPairingService.working(context);
    publish();
    io.execute(() -> {
      PairingConnectionCtx operation = null;
      String stage = "准备无线配对";
      try {
        enableTlsExporter();
        stage = "读取无线身份";
        WirelessIdentity identity = WirelessIdentity.load(context);
        if (!validEpoch(epoch)) return;
        stage = "建立本机配对通道";
        WirelessDiscovery.Endpoint endpoint = awaitEndpoint(true, explicitPort, epoch);
        Exception last = null;
        boolean completed = false;
        for (String host : endpoint.attempts()) {
          if (!validEpoch(epoch)) return;
          if (!discovery.currentLocalHost(host)) continue;
          operation = new PairingConnectionCtx(host, endpoint.port, code.getBytes(StandardCharsets.US_ASCII),
              identity.privateKey, identity.certificate, "Luoxianlv");
          pairing = operation;
          PairingConnectionCtx owned = operation;
          control.schedule(() -> { if (validEpoch(epoch) && pairing == owned) owned.close(); }, 20, TimeUnit.SECONDS);
          try { stage = "认证配对码"; operation.start(); completed = true; break; }
          catch (java.net.ConnectException | java.net.NoRouteToHostException failure) { last = failure; }
          finally { operation.close(); }
        }
        if (!completed) throw last == null ? new java.io.IOException("本机配对通道不可用") : last;
        submit(() -> {
          if (!validEpoch(epoch)) return;
          busy = false;
          context.getSharedPreferences("input-wireless", Context.MODE_PRIVATE).edit().putBoolean("paired", true).apply();
          pairingOnly = false;
          discovery.pairingEnabled(false);
          notificationVisible = false;
          WirelessPairingService.result(context, operationSession, true, "正在连接无线调试");
          message = "配对成功，正在连接无线调试";
          publish();
          connect(0);
        });
      } catch (LinkageError failure) {
        if (!validEpoch(epoch)) return;
        diagnose(epoch, stage, failure);
        resultFailure(epoch, "本机无法加载无线配对组件，请使用 Shizuku 或无障碍模式");
        WirelessPairingService.result(context, operationSession, false, "本机无法加载配对组件，请使用 Shizuku 模式");
      } catch (Exception failure) {
        if (!validEpoch(epoch)) return;
        diagnose(epoch, stage, failure);
        resultFailure(epoch, "配对失败，请确认配对码正确且系统窗口仍然打开");
        WirelessPairingService.result(context, operationSession, false, "请重新获取配对码，并保持系统配对窗口打开");
      } finally {
        if (operation != null) operation.close();
        if (pairing == operation) pairing = null;
      }
    });
  }

  private synchronized void connect(int explicitPort) {
    if (!wantConnection || !watching) return;
    if (busy || service != null) { publish(); return; }
    if (!prerequisites()) return;
    if (explicitPort != 0 && !validPort(explicitPort)) { message = "连接端口无效"; publish(); return; }
    stopOperation();
    long epoch = generation.get();
    clearDiagnostics();
    busy = true;
    message = "正在连接无线调试";
    publish();
    io.execute(() -> {
      AdbConnection adb = null;
      WirelessDiscovery.Endpoint attempted = WirelessDiscovery.Endpoint.EMPTY;
      String stage = "准备无线连接";
      try {
        enableTlsExporter();
        stage = "发现本机连接端口";
        WirelessDiscovery.Endpoint endpoint = awaitEndpoint(false, explicitPort, epoch);
        attempted = endpoint;
        if (!validEpoch(epoch)) return;
        stage = "读取无线身份";
        WirelessIdentity identity = WirelessIdentity.load(context);
        stage = "建立本机 ADB 通道";
        Exception last = null;
        for (String host : endpoint.attempts()) {
          if (!validEpoch(epoch)) return;
          if (!discovery.currentLocalHost(host)) continue;
          try {
            adb = AdbConnection.create(host, endpoint.port, identity.privateKey, identity.certificate, Build.VERSION.SDK_INT);
            connection = adb;
            AdbConnection owned = adb;
            control.schedule(() -> {
              if (validEpoch(epoch) && busy && connection == owned) cleanup.execute(() -> closeQuietly(owned));
            }, 15, TimeUnit.SECONDS);
            stage = "认证本机 ADB";
            if (!adb.connect(8000, TimeUnit.MILLISECONDS, true)) throw new java.net.SocketTimeoutException("无线调试连接超时");
            last = null;
            break;
          } catch (java.io.IOException failure) {
            if (!localRouteFailure(failure)) throw failure;
            last = failure;
            if (adb != null) closeQuietly(adb);
            adb = null;
          }
        }
        if (adb == null) throw last == null ? new java.io.IOException("本机连接通道不可用") : last;
        stage = "查询宿主安装身份";
        ApplicationInfo host = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
        if (host.uid != android.os.Process.myUid() || host.sourceDir == null || host.nativeLibraryDir == null)
          throw new SecurityException("宿主安装信息异常");
        Attempt selected = new Attempt(epoch);
        attempt = selected;
        String command = ShellHelperCommand.build(host.sourceDir, host.nativeLibraryDir,
            context.getPackageName(), host.uid, selected.token, "wireless_shell", true);
        stage = "打开助手启动通道";
        helperStream = adb.open("shell:" + command);
        watchHelper(helperStream, selected);
        stage = "等待宿主接收助手";
        if (!selected.arrived.await(10000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("输入助手启动超时");
        if (selected.bootstrapUnsupported.get()) {
          stage = "校验系统后台启动组件";
          throw new IllegalStateException("系统缺少后台启动组件");
        }
        if (!selected.used.get()) throw new IllegalStateException("输入助手在交付前退出");
        if (!validEpoch(epoch)) return;
        submit(() -> {
          if (!validEpoch(epoch)) return;
          busy = false;
          message = "无线调试已连接";
          clearDiagnostics();
          WirelessPairingService.connected(context);
          WirelessPairingService.complete(context);
          recovering = false;
          control.schedule(() -> {
            if (validEpoch(epoch) && service != null && service.asBinder().isBinderAlive()) resetRecovery();
          }, 30, TimeUnit.SECONDS);
          publish();
        });
        // 合法 Binder 交付后即关闭启动通道；独立进程只受宿主租约与心跳约束。
        if (connection == adb) connection = null;
        closeQuietly(adb);
      } catch (AdbPairingRequiredException failure) {
        if (!validEpoch(epoch)) return;
        diagnose(epoch, stage, failure);
        submit(() -> {
          if (validEpoch(epoch)) context.getSharedPreferences("input-wireless", Context.MODE_PRIVATE)
              .edit().putBoolean("paired", false).apply();
        });
        resultFailure(epoch, "无线调试授权已失效，请重新配对");
      } catch (Exception failure) {
        if (!validEpoch(epoch)) return;
        diagnose(epoch, stage, failure);
        if (knownPairing() && transientConnectionFailure(failure)) {
          int failedPort = attempted.port;
          submit(() -> {
            synchronized (WirelessAdbBackend.this) {
              if (!validEpoch(epoch)) return;
              connectEndpoint = connectEndpoint.withoutFailedPort(failedPort);
              connectionPort = connectEndpoint.port;
              discovery.invalidateConnection(failedPort);
              stopOperation();
              requestRecovery();
            }
            notifyDisconnected(message);
            publish();
          });
        } else resultFailure(epoch, "无法连接无线调试，请重新确认授权");
      } finally {
        if (!validEpoch(epoch) && adb != null) closeQuietly(adb);
      }
    });
  }

  synchronized IBinder offerHelper(String token, IInputService remote) {
    Attempt selected = attempt;
    if (selected == null || token == null || !validEpoch(selected.epoch)
        || SystemClock.elapsedRealtime() > selected.expires
        || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), selected.token.getBytes(StandardCharsets.US_ASCII))
        || !selected.used.compareAndSet(false, true)) throw new SecurityException("输入助手连接令牌无效");
    InputBridgeProvider.Lease granted = new InputBridgeProvider.Lease();
    lease = granted;
    service = remote;
    selected.arrived.countDown();
    submit(() -> {
      if (!validEpoch(selected.epoch)) { granted.revoke(); return; }
      InputController.WirelessCallback target = callback;
      if (target != null) target.connected(remote);
    });
    return granted;
  }

  private WirelessDiscovery.Endpoint awaitEndpoint(boolean pair, int explicit, long epoch) throws Exception {
    if (explicit != 0) return new WirelessDiscovery.Endpoint(explicit, java.util.Collections.emptyList());
    long deadline = SystemClock.elapsedRealtime() + 12000;
    while (validEpoch(epoch) && SystemClock.elapsedRealtime() < deadline) {
      WirelessDiscovery.Endpoint endpoint = pair ? pairEndpoint : connectEndpoint;
      if (validPort(endpoint.port)) return endpoint;
      Thread.sleep(100);
    }
    throw new IllegalStateException(pair ? "未发现本机配对窗口" : "未发现本机无线调试连接");
  }

  private void watchHelper(AdbStream stream, Attempt selected) {
    streams.execute(() -> {
      try {
        byte[] bytes = new byte[1024];
        java.io.ByteArrayOutputStream prefix = new java.io.ByteArrayOutputStream();
        InputStream input = stream.openInputStream();
        int count;
        while (validEpoch(selected.epoch) && (count = input.read(bytes)) >= 0) {
          int keep = Math.min(count, 256 - prefix.size());
          if (keep > 0) prefix.write(bytes, 0, keep);
          if (new String(prefix.toByteArray(), StandardCharsets.US_ASCII).contains("LXL_BOOTSTRAP_UNSUPPORTED")) {
            selected.bootstrapUnsupported.set(true);
            selected.arrived.countDown();
          }
        }
      } catch (Exception failure) {
        if (!selected.used.get()) diagnose(selected.epoch, "读取助手启动结果", failure);
      }
      // 背景 launcher 正常结束会先于交付；EOF 从不等同于成功或合法 Binder 死亡。
    });
  }

  private void resetRecovery() { recoveryAttempts = 0; recoveryAfter = 0; recovering = false; }

  private void requestRecovery() {
    recovering = wantConnection && knownPairing();
    if (!WirelessRecoveryPolicy.mayRecover(wantConnection, knownPairing(), recoveryAttempts)) {
      setConnectionWanted(false);
      message = "连接多次中断，请重新连接";
      return;
    }
    recoveryAfter = SystemClock.elapsedRealtime() + Math.min(10000, 1000L << recoveryAttempts);
    message = "连接已中断，正在等待自动恢复";
  }

  private boolean automaticConnectionAllowed() {
    // 主动重新配对时保留输入通知，不能被旧身份的自动连接抢先结束。
    return !closed && watching && !pairingOnly
        && wantConnection && knownPairing() && service == null && !busy
        && connectionPort > 0 && wirelessEnabled() && WirelessDiscovery.wifiConnected(context)
        && (!recovering || WirelessRecoveryPolicy.mayRecover(wantConnection, true, recoveryAttempts))
        && SystemClock.elapsedRealtime() >= recoveryAfter;
  }

  private void automaticConnect() {
    if (recovering) recoveryAttempts++;
    // 使用当前发现结果及全部本机地址，不把缓存端口当作用户指定的固定端口。
    connect(0);
  }

  private void resultFailure(long epoch, String reason) {
    resultFailure(epoch, reason, false);
  }

  private void resultFailure(long epoch, String reason, boolean recover) {
    if (closed) return;
    submit(() -> {
      if (!validEpoch(epoch)) return;
      stopOperation();
      setConnectionWanted(recover);
      message = reason;
      notifyDisconnected(reason);
      publish();
    });
  }

  private synchronized void stopOperation() {
    generation.incrementAndGet();
    busy = false;
    Attempt oldAttempt = attempt;
    attempt = null;
    if (oldAttempt != null) oldAttempt.arrived.countDown();
    InputBridgeProvider.Lease oldLease = lease;
    lease = null;
    if (oldLease != null) oldLease.revoke();
    service = null;
    PairingConnectionCtx oldPairing = pairing;
    pairing = null;
    AdbConnection oldConnection = connection;
    connection = null;
    helperStream = null;
    cleanup.execute(() -> {
      if (oldPairing != null) oldPairing.close();
      // 撤销租约让助手自行正常清理；不在连接器线程等待远端 Binder。
      if (oldConnection != null) closeQuietly(oldConnection);
    });
  }

  private void publish() {
    InputController.WirelessCallback target = callback;
    if (closed || target == null) return;
    Bundle value = new Bundle();
    value.putBoolean("wirelessSupported", supported());
    value.putBoolean("wifiConnected", WirelessDiscovery.wifiConnected(context));
    value.putBoolean("wirelessEnabled", wirelessEnabled());
    value.putBoolean("notificationGranted", notificationGranted());
    value.putBoolean("localNetworkGranted", localNetworkGranted());
    value.putBoolean("paired", knownPairing());
    value.putBoolean("pairing", pairing != null);
    value.putBoolean("busy", busy);
    value.putInt("pairingPort", pairingPort);
    value.putInt("connectionPort", connectionPort);
    value.putString("message", message);
    value.putString("errorStage", errorStage);
    value.putString("errorType", errorType);
    target.state(value);
  }

  private void notifyDisconnected(String reason) {
    InputController.WirelessCallback target = callback;
    if (target != null) target.disconnected(reason);
  }

  private boolean wirelessEnabled() {
    try { return Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1; }
    catch (RuntimeException ignored) { return connectionPort > 0 || service != null; }
  }
  private boolean knownPairing() { return context.getSharedPreferences("input-wireless", Context.MODE_PRIVATE).getBoolean("paired", false); }
  private boolean supported() { return Build.VERSION.SDK_INT >= 30; }
  private boolean localNetworkGranted() {
    return Build.VERSION.SDK_INT < 37 || context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED;
  }
  private boolean notificationGranted() {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null || !manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return false;
    NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
    return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
  }
  private void setConnectionWanted(boolean wanted) {
    wantConnection = wanted;
    context.getSharedPreferences("input-wireless", Context.MODE_PRIVATE).edit()
        .putBoolean("wantConnection", wanted).apply();
  }

  private void invalidateNotification() {
    // 主动停止也撤销旧通知操作；新进程不能从旧的配对输入恢复已取消的请求。
    notificationToken = nonce();
    context.getSharedPreferences("input-wireless", Context.MODE_PRIVATE).edit()
        .putString("notificationToken", notificationToken).commit();
  }

  boolean notificationMatches(String token) {
    return token != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), notificationToken.getBytes(StandardCharsets.US_ASCII));
  }

  private void showPairingNotification() {
    if (!notificationGranted()) { message = "已发现配对服务，请允许通知以输入配对码"; return; }
    if (foregroundSession == 0 || !pairingOnly) return;
    notificationVisible = WirelessPairingService.showInput(context, pairingPort, notificationToken);
  }

  private void cancelNotification() {
    notificationVisible = false;
    NotificationManager notifications = context.getSystemService(NotificationManager.class);
    if (notifications != null) notifications.cancel(NOTIFICATION);
  }

  private boolean validEpoch(long epoch) { return !closed && epoch == generation.get(); }
  private static boolean validPort(int port) { return port > 0 && port <= 65535; }
  private static void enableTlsExporter() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
      throw new UnsupportedOperationException("无线调试需要 Android 11 或更新版本");
    if (!HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/Conscrypt;"))
      throw new IllegalStateException("系统不允许无线调试 TLS 接口");
  }
  private static String nonce() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    StringBuilder output = new StringBuilder(64);
    for (byte value : bytes) output.append(Character.forDigit(value >>> 4 & 15, 16)).append(Character.forDigit(value & 15, 16));
    return output.toString();
  }
  private static void closeQuietly(AdbConnection connection) {
    try { connection.close(); } catch (Exception ignored) { }
  }

  static boolean localRouteFailure(Throwable failure) {
    boolean route = false;
    for (int depth = 0; failure != null && depth < 4; depth++, failure = failure.getCause()) {
      if (failure instanceof javax.net.ssl.SSLException || failure instanceof SecurityException) return false;
      route |= failure instanceof java.net.ConnectException || failure instanceof java.net.NoRouteToHostException;
    }
    return route;
  }

  static boolean transientConnectionFailure(Throwable failure) {
    boolean transientFailure = false;
    for (int depth = 0; failure != null && depth < 4; depth++, failure = failure.getCause()) {
      if (failure instanceof javax.net.ssl.SSLException || failure instanceof SecurityException) return false;
      transientFailure |= failure instanceof java.net.ConnectException || failure instanceof java.net.NoRouteToHostException
          || failure instanceof java.net.SocketTimeoutException;
    }
    return transientFailure;
  }

  private void diagnose(String stage, Throwable failure) { diagnose(generation.get(), stage, failure); }

  private void diagnose(long epoch, String stage, Throwable failure) {
    StringBuilder type = new StringBuilder(failure.getClass().getSimpleName());
    for (int depth = 0; failure.getCause() != null && depth < 2; depth++) {
      failure = failure.getCause();
      type.append('/').append(failure.getClass().getSimpleName());
    }
    if (!validEpoch(epoch)) return;
    String safeType = type.toString();
    HostDiagnostics.log(Log.ERROR, "落弦律无线连接", "无线连接失败：阶段=" + stage + "；类型=" + safeType, null);
    submit(() -> {
      if (!validEpoch(epoch)) return;
      errorStage = stage;
      errorType = safeType;
      publish();
    });
  }

  private void clearDiagnostics() { errorStage = ""; errorType = ""; }

  @Override public void close() {
    if (closed) return;
    closed = true;
    if (current == this) current = null;
    stopOperation();
    discovery.close();
    WirelessPairingService.complete(context);
    cancelNotification();
    if (connectivity != null) try { connectivity.unregisterNetworkCallback(network); } catch (RuntimeException ignored) { }
    try { context.getContentResolver().unregisterContentObserver(settings); } catch (RuntimeException ignored) { }
    callback = null;
    io.shutdownNow();
    streams.shutdownNow();
    control.shutdownNow();
    cleanup.shutdown();
  }

  private static final class Attempt {
    final long epoch;
    final String token = nonce();
    final long expires = SystemClock.elapsedRealtime() + 15000;
    final AtomicBoolean used = new AtomicBoolean();
    final AtomicBoolean bootstrapUnsupported = new AtomicBoolean();
    final CountDownLatch arrived = new CountDownLatch(1);
    Attempt(long epoch) { this.epoch = epoch; }
  }
}
