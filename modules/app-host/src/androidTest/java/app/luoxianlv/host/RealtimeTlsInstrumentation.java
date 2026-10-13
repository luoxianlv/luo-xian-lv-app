package app.luoxianlv.host;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.SharedRealtime;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** 真实共享运行时 WSS：本机测试 CA、主机名错配和不受信证书分别验证。 */
public final class RealtimeTlsInstrumentation extends Instrumentation {
  private String mode;

  @Override public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    mode = arguments.getString("mode", "valid");
    start();
  }

  @Override public void onStart() {
    Bundle report = new Bundle();
    boolean passed = false;
    SharedRealtime.Engine engine = null;
    SSLContext original = null;
    String socketProvider = java.security.Security.getProperty("ssl.SocketFactory.provider");
    boolean replacedProvider = false;
    try {
      if (!getTargetContext().getPackageName().endsWith(".debug"))
        throw new AssertionError("仅限本机 Debug");
      if (!java.util.Set.of("valid", "wronghost", "untrusted").contains(mode))
        throw new AssertionError("未知 TLS 测试");
      startActivitySync(new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long until = SystemClock.elapsedRealtime() + 30_000;
      while (true) {
        try { if (Bootstrap.source().prepared != null) break; }
        catch (IllegalStateException waiting) { }
        if (SystemClock.elapsedRealtime() >= until) throw new AssertionError("业务加载失败");
        SystemClock.sleep(25);
      }
      KeyStore keys = KeyStore.getInstance("PKCS12");
      try (var input = getContext().getAssets().open("tls-probe.p12")) {
        keys.load(input, "public-test".toCharArray());
      }
      KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      manager.init(keys, "public-test".toCharArray());
      SSLContext server = SSLContext.getInstance("TLS");
      server.init(manager.getKeyManagers(), null, null);
      original = SSLContext.getDefault();
      if (!mode.equals("untrusted")) {
        KeyStore ca = KeyStore.getInstance(KeyStore.getDefaultType());
        ca.load(null, null);
        ca.setCertificateEntry("local-test", keys.getCertificate("test-local"));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(ca);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, trust.getTrustManagers(), null);
        // 只替换本次 instrumentation 进程的默认上下文；仍执行真实证书链及主机名校验。
        SSLContext.setDefault(client);
        if (android.os.Build.VERSION.SDK_INT < 28 && socketProvider != null) {
          // 旧 Android 的显式 factory provider 会绕过默认上下文；清空后才使用上面的测试 CA。
          java.security.Security.setProperty("ssl.SocketFactory.provider", "");
          replacedProvider = true;
        }
      }
      String address = mode.equals("wronghost") ? "127.0.0.2" : "127.0.0.1";
      try (var peer = new RealtimeInstrumentation.Peer(server.getServerSocketFactory()
          .createServerSocket(0, 4, InetAddress.getByName(address)))) {
        AtomicBoolean subscribed = new AtomicBoolean();
        SharedRealtime.Host host = new SharedRealtime.Host() {
          @Override public AutoCloseable ticket(SharedRealtime.Authorization callback) {
            callback.complete("a".repeat(64), 0, 0); return () -> {};
          }
          @Override public AutoCloseable accountGrant(String token, String epoch, SharedRealtime.Authorization callback) {
            throw new AssertionError("此测试不请求账号授权");
          }
          @Override public void hotChanged(boolean ready, String revision, String trust) { subscribed.set(ready); }
          @Override public void usageChanged() { }
        };
        var loader = Bootstrap.source().prepared.classLoader().getParent();
        var type = Class.forName("app.luoxianlv.runtime.realtime.RealtimeEngine", true, loader);
        engine = (SharedRealtime.Engine) type.getConstructor(Context.class, SharedRealtime.Host.class, String.class)
            .newInstance(getTargetContext(), host, "wss://" + address + ":" + peer.server.getLocalPort());
        engine.usage(true);
        until = SystemClock.elapsedRealtime() + 12_000;
        if (mode.equals("valid")) {
          while (!subscribed.get()) {
            if (SystemClock.elapsedRealtime() >= until) throw new AssertionError("有效 WSS 未完成订阅：" + engine.state() + "; " + peer.lastFailure);
            SystemClock.sleep(25);
          }
          if (peer.epochs.get() != 1 || peer.live.get() != 1) throw new AssertionError("WSS 连接不唯一");
        } else {
          while (peer.active == null || peer.live.get() != 0) {
            if (SystemClock.elapsedRealtime() >= until) throw new AssertionError("没有观察到 TLS 拒绝");
            SystemClock.sleep(25);
          }
          if (peer.epochs.get() != 0 || subscribed.get() || engine.state().getBoolean("connected"))
            throw new AssertionError("无效 TLS 仍完成 WS 握手");
        }
      }
      report.putString("stream", "WSS 验证通过：" + mode + "，API " + android.os.Build.VERSION.SDK_INT + "\n");
      passed = true;
    } catch (Throwable failure) {
      report.putString("stream", android.util.Log.getStackTraceString(failure));
    } finally {
      if (engine != null) {
        try { engine.close(); }
        catch (Exception failure) { report.putString("stream", android.util.Log.getStackTraceString(failure)); passed = false; }
      }
      if (original != null) SSLContext.setDefault(original);
      if (replacedProvider) java.security.Security.setProperty("ssl.SocketFactory.provider", socketProvider);
    }
    finish(passed ? -1 : 0, report);
  }
}
