package app.luoxianlv.input;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import moe.shizuku.manager.adb.AdbMdns;

/** Shizuku 服务发现的薄适配：仅管理连接租约，发现与本机端口检查由上游实现承担。 */
final class WirelessDiscovery implements AutoCloseable {
  interface Listener { void changed(Endpoint pairing, Endpoint connection, long revision); void failed(String message); }
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Listener listener;
  private final AtomicLong generation = new AtomicLong();
  private final ConnectionFailures connectionFailures = new ConnectionFailures();
  private AdbMdns pairSearch, connectSearch;
  private Endpoint pair = Endpoint.EMPTY, connect = Endpoint.EMPTY;
  private boolean running, pairingEnabled;
  private long pairRevision, retryConnectionAt;

  WirelessDiscovery(Context context, Listener listener) {
    this.context = context;
    this.listener = listener;
  }

  void start() {
    long requested = generation.get();
    main.post(() -> {
      if (running) {
        if (retryConnectionAt != 0 && SystemClock.elapsedRealtime() >= retryConnectionAt) {
          retryConnectionAt = 0;
          restartConnection();
        }
        return;
      }
      if (!generation.compareAndSet(requested, requested + 1)) return;
      running = true;
      if (pairingEnabled) startPairing();
      restartConnection();
    });
  }

  void pairingEnabled(boolean enabled) {
    main.post(() -> {
      pairingEnabled = enabled;
      if (!running) return;
      if (enabled) { if (pairSearch == null) startPairing(); return; }
      pairRevision++;
      if (pairSearch != null) pairSearch.stop();
      pairSearch = null;
      pair = Endpoint.EMPTY;
      notifyPorts();
    });
  }

  private void startPairing() {
    long epoch = generation.get(), revision = ++pairRevision;
    pairSearch = new AdbMdns(context, AdbMdns.TLS_PAIRING, port -> main.post(() -> {
      if (!running || epoch != generation.get() || revision != pairRevision || !pairingEnabled) return;
      pair = endpoint(port);
      notifyPorts();
    }));
    start(pairSearch);
  }

  private void restartConnection() {
    if (connectSearch != null) connectSearch.stop();
    long epoch = generation.get();
    AdbMdns[] instance = new AdbMdns[1];
    instance[0] = new AdbMdns(context, AdbMdns.TLS_CONNECT, port -> main.post(() -> {
      if (!running || epoch != generation.get() || connectSearch != instance[0]) return;
      connect = connectionFailures.accepts(port, SystemClock.elapsedRealtime()) ? endpoint(port) : Endpoint.EMPTY;
      notifyPorts();
    }));
    connectSearch = instance[0];
    start(connectSearch);
  }

  private void start(AdbMdns source) {
    try { source.start(); }
    catch (RuntimeException failure) { listener.failed("无法搜索无线调试服务，请检查本地网络权限"); }
  }

  private static Endpoint endpoint(int port) {
    return port > 0 && port <= 65535 ? new Endpoint(port, Collections.emptyList()) : Endpoint.EMPTY;
  }

  private void notifyPorts() { listener.changed(pair, connect, generation.get()); }
  long revision() { return generation.get(); }

  void invalidateConnection(int port) {
    main.post(() -> {
      connectionFailures.reject(port, SystemClock.elapsedRealtime());
      connect = connect.withoutFailedPort(port);
      retryConnectionAt = SystemClock.elapsedRealtime() + 30000;
      if (running) restartConnection();
      notifyPorts();
    });
  }

  boolean currentLocalHost(String host) {
    try {
      InetAddress address = InetAddress.getByName(host);
      if (currentLocal(address) != null) return true;
      if (address.isAnyLocalAddress() || address.isMulticastAddress()) return false;
      ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
      if (connectivity == null) return false;
      for (Network network : connectivity.getAllNetworks()) {
        android.net.LinkProperties properties = connectivity.getLinkProperties(network);
        if (properties == null) continue;
        for (android.net.LinkAddress local : properties.getLinkAddresses())
          if (sameAddress(address, local.getAddress())) return true;
      }
    } catch (Exception ignored) { }
    return false;
  }

  static boolean local(InetAddress address) { return currentLocal(address) != null; }

  private static InetAddress currentLocal(InetAddress address) {
    if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) return null;
    if (address.isLoopbackAddress()) return address;
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
        while (addresses.hasMoreElements()) {
          InetAddress actual = addresses.nextElement();
          if (sameAddress(address, actual)) return actual;
        }
      }
    } catch (Exception ignored) { }
    return null;
  }

  static boolean sameAddress(InetAddress left, InetAddress right) {
    return left != null && right != null && Arrays.equals(addressBytes(left), addressBytes(right));
  }

  private static byte[] addressBytes(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length == 16) {
      boolean mapped = bytes[10] == (byte) 255 && bytes[11] == (byte) 255;
      for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
      if (mapped) return Arrays.copyOfRange(bytes, 12, 16);
    }
    return bytes;
  }

  static boolean wifiConnected(Context context) {
    ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
    if (connectivity == null) return false;
    try {
      for (Network network : connectivity.getAllNetworks()) {
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return true;
      }
    } catch (SecurityException ignored) { }
    return false;
  }

  static String normalize(String type) {
    if (type == null) return "";
    String value = type.trim().toLowerCase(Locale.ROOT);
    while (value.startsWith(".")) value = value.substring(1);
    while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
    if (value.endsWith(".local")) value = value.substring(0, value.length() - 6);
    return value.isEmpty() ? "" : value + ".";
  }

  private static String key(String type, NsdServiceInfo info) {
    String name = info == null ? null : info.getServiceName();
    return name == null || name.isEmpty() ? null : type + ":" + name.toLowerCase(Locale.ROOT);
  }

  static final class Endpoint {
    static final Endpoint EMPTY = new Endpoint(0, Collections.emptyList());
    final int port;
    final List<String> hosts;
    Endpoint(int port, List<String> hosts) { this.port = port; this.hosts = Collections.unmodifiableList(new ArrayList<>(hosts)); }
    Endpoint withoutFailedPort(int failed) { return port == failed ? EMPTY : this; }
    List<String> attempts() {
      List<String> attempts = new ArrayList<>(Arrays.asList("127.0.0.1", "::1"));
      for (String host : hosts) if (!attempts.contains(host)) attempts.add(host);
      return attempts;
    }
  }

  static final class ConnectionFailures {
    private final Map<Integer, Long> rejectedUntil = new LinkedHashMap<>();
    void reject(int port, long now) {
      if (port <= 0 || port > 65535) return;
      rejectedUntil.put(port, now + 30000);
      while (rejectedUntil.size() > 8) rejectedUntil.remove(rejectedUntil.keySet().iterator().next());
    }
    boolean accepts(int port, long now) {
      Long until = rejectedUntil.get(port);
      if (until == null) return true;
      if (now < until) return false;
      rejectedUntil.remove(port);
      return true;
    }
  }

  @Override public void close() {
    generation.incrementAndGet();
    main.post(() -> {
      running = false;
      pairRevision++;
      if (pairSearch != null) pairSearch.stop();
      if (connectSearch != null) connectSearch.stop();
      pairSearch = connectSearch = null;
      pair = connect = Endpoint.EMPTY;
      retryConnectionAt = 0;
      notifyPorts();
    });
  }
}
