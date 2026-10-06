/*
 * Ported from Shizuku Manager v13.6.0, AdbMdns.kt.
 * Commit: 2650830c5b099ae0dd34fedf614d4f592ca05d65
 * Source: https://github.com/RikkaApps/Shizuku/blob/2650830c5b099ae0dd34fedf614d4f592ca05d65/manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt
 * Licensed under the Apache License, Version 2.0.
 * License: https://www.apache.org/licenses/LICENSE-2.0
 *
 * 落弦律修改：Kotlin/Observer 改为纯 Java/PortListener；处理 Java 的网卡检查异常；
 * 发现状态对回调线程可见；同步启动失败恢复状态；停止早于发现启动回调时仍注销发现。
 * 日志改为中文，仅记录发现阶段与失败类型，不输出服务名、设备序列号或完整地址。
 * TLS 服务类型、本机网卡地址筛选和 IPv4 回环端口检查保留上游逻辑。
 */
package moe.shizuku.manager.adb;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Log;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Objects;

/** 保留 Shizuku 的本机无线调试发现流程；调用方分别持有配对和连接发现实例。 */
@TargetApi(Build.VERSION_CODES.R)
public final class AdbMdns {
  public static final String TLS_CONNECT = "_adb-tls-connect._tcp";
  public static final String TLS_PAIRING = "_adb-tls-pairing._tcp";
  public static final String TAG = "AdbMdns";

  @FunctionalInterface
  public interface PortListener {
    void onPortChanged(int port);
  }

  private final String serviceType;
  private final PortListener observer;
  private final NsdManager nsdManager;
  private final NsdManager.DiscoveryListener listener = new DiscoveryListener();
  private volatile boolean registered;
  private volatile boolean running;
  private volatile String serviceName;

  public AdbMdns(Context context, String serviceType, PortListener observer) {
    this.serviceType = Objects.requireNonNull(serviceType);
    this.observer = Objects.requireNonNull(observer);
    this.nsdManager = Objects.requireNonNull(context.getSystemService(NsdManager.class));
  }

  public void start() {
    if (running) return;
    running = true;
    if (!registered) {
      try {
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
      } catch (RuntimeException failure) {
        running = false;
        throw failure;
      }
    }
  }

  public void stop() {
    if (!running) return;
    running = false;
    if (registered) nsdManager.stopServiceDiscovery(listener);
  }

  private void onDiscoveryStart() {
    registered = true;
    // Android 的启动回调可以晚于 stop()；仍须释放刚登记的监听器。
    if (!running) nsdManager.stopServiceDiscovery(listener);
  }

  private void onDiscoveryStop() {
    registered = false;
  }

  private void onServiceFound(NsdServiceInfo info) {
    nsdManager.resolveService(info, new ResolveListener());
  }

  private void onServiceLost(NsdServiceInfo info) {
    if (Objects.equals(info.getServiceName(), serviceName)) observer.onPortChanged(-1);
  }

  private void onServiceResolved(NsdServiceInfo info) {
    if (!running) return;
    try {
      if (isLocalHost(info.getHost()) && isPortAvailable(info.getPort())) {
        serviceName = info.getServiceName();
        observer.onPortChanged(info.getPort());
      }
    } catch (SocketException failure) {
      Log.w(TAG, "本机网卡检查失败，忽略此次发现：" + failure.getClass().getSimpleName());
    }
  }

  /** 与原 Kotlin asSequence/any 相同：只接受解析地址属于本机网卡的服务。 */
  static boolean isLocalHost(InetAddress host) throws SocketException {
    if (host == null) return false;
    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
    if (interfaces == null) return false;
    while (interfaces.hasMoreElements()) {
      Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
      while (addresses.hasMoreElements()) {
        if (host.getHostAddress().equals(addresses.nextElement().getHostAddress())) return true;
      }
    }
    return false;
  }

  /** 保留上游命名与判定：回环绑定失败说明端口已有监听，返回 true。 */
  static boolean isPortAvailable(int port) {
    try (ServerSocket socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress("127.0.0.1", port), 1);
      return false;
    } catch (IOException occupied) {
      return true;
    }
  }

  private final class DiscoveryListener implements NsdManager.DiscoveryListener {
    @Override public void onDiscoveryStarted(String type) {
      Log.v(TAG, "无线调试服务发现已启动");
      onDiscoveryStart();
    }

    @Override public void onStartDiscoveryFailed(String type, int errorCode) {
      Log.w(TAG, "无线调试服务发现启动失败，错误码：" + errorCode);
    }

    @Override public void onDiscoveryStopped(String type) {
      Log.v(TAG, "无线调试服务发现已停止");
      onDiscoveryStop();
    }

    @Override public void onStopDiscoveryFailed(String type, int errorCode) {
      Log.w(TAG, "无线调试服务发现停止失败，错误码：" + errorCode);
    }

    @Override public void onServiceFound(NsdServiceInfo info) {
      Log.v(TAG, "发现无线调试服务，准备解析");
      AdbMdns.this.onServiceFound(info);
    }

    @Override public void onServiceLost(NsdServiceInfo info) {
      Log.v(TAG, "无线调试服务已失效");
      AdbMdns.this.onServiceLost(info);
    }
  }

  private final class ResolveListener implements NsdManager.ResolveListener {
    @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
      Log.w(TAG, "无线调试服务解析失败，错误码：" + errorCode);
    }

    @Override public void onServiceResolved(NsdServiceInfo info) {
      AdbMdns.this.onServiceResolved(info);
    }
  }
}
