package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.SharedRealtime;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** 模拟器本机协议对端；验证真实运行时加载与 Socket，不把它当作公网或容量验收。 */
public final class RealtimeInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  private static void await(String message, BooleanSupplier condition) {
    long deadline = SystemClock.elapsedRealtime() + 20_000;
    while (!condition.getAsBoolean()) {
      require(SystemClock.elapsedRealtime() < deadline, message);
      SystemClock.sleep(50);
    }
  }
  @Override public void onStart() {
    Bundle report = new Bundle();
    SharedRealtime.Engine engine = null;
    boolean success = false;
    try (Peer peer = new Peer()) {
      startActivitySync(new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      await("业务加载失败", () -> { try { return Bootstrap.source() != null; } catch (Exception waiting) { return false; } });
      var runtime = Bootstrap.source().prepared.classLoader().getParent();
      try { Class.forName("kotlin.Unit", false, getTargetContext().getClassLoader()); throw new AssertionError("宿主混入 Kotlin"); }
      catch (ClassNotFoundException expected) {}
      var type = Class.forName("app.luoxianlv.runtime.realtime.RealtimeEngine", true, runtime);
      require(type.getClassLoader() == runtime, "连接不属于共享运行时");
      AtomicBoolean hot = new AtomicBoolean();
      AtomicBoolean callbackThread = new AtomicBoolean(true);
      AtomicInteger tickets = new AtomicInteger();
      AtomicBoolean holdRefresh = new AtomicBoolean();
      AtomicReference<SharedRealtime.Authorization> refresh = new AtomicReference<>();
      SharedRealtime.Host host = new SharedRealtime.Host() {
        @Override public AutoCloseable ticket(SharedRealtime.Authorization callback) {
          tickets.incrementAndGet(); callback.complete("a".repeat(64), 0, 0); return () -> {};
        }
        @Override public AutoCloseable accountGrant(String token, String epoch, SharedRealtime.Authorization callback) {
          if (holdRefresh.get()) {refresh.set(callback);return () -> {};}
          callback.complete("b".repeat(64), 0, 0); return () -> {};
        }
        @Override public void hotChanged(boolean subscribed, String revision, String trust) {
          callbackThread.compareAndSet(true, android.os.Looper.myLooper() == android.os.Looper.getMainLooper());
          hot.set(subscribed);
        }
        @Override public void usageChanged() {}
      };
      engine = (SharedRealtime.Engine) type.getConstructor(Context.class, SharedRealtime.Host.class, String.class)
          .newInstance(getTargetContext().getApplicationContext(), host, "ws://127.0.0.1:" + peer.server.getLocalPort());
      SharedRealtime.Engine selected = engine;
      AtomicInteger observed = new AtomicInteger();
      AutoCloseable observer = engine.observe(new SharedRealtime.Listener() {
        @Override public void changed(Bundle value) { observed.incrementAndGet(); }
        @Override public void message(String topic, String kind, String json) { observed.incrementAndGet(); }
      });
      engine.usage(true);
      await("WS 或热更订阅未完成", () -> selected.state().getBoolean("connected") && hot.get());
      require(peer.live.get() == 1 && tickets.get() == 1, "初始化重复创建连接");
      engine.room(true, false);
      await("四时间戳校时未完成", () -> selected.state().getBoolean("clockReady"));
      engine.account("local-test-token");
      await("账号未授权", () -> "test-account".equals(selected.state().getString("accountId")));
      holdRefresh.set(true);
      engine.account("refreshed-local-token");
      await("未请求更新授权", () -> refresh.get() != null);
      require("test-account".equals(selected.state().getString("accountId")) && peer.cleared.get() == 0,
          "正常刷新令牌撤下了仍有效的合奏授权");
      holdRefresh.set(false);refresh.getAndSet(null).complete("c".repeat(64),0,0);
      await("更新授权未确认", () -> peer.authorized.get() == 2);
      var result = new CountDownLatch(1);
      var accepted = new AtomicBoolean();
      engine.send("test-command", "ensemble", "snapshot", "{}", (ok, json) -> { accepted.set(ok); result.countDown(); });
      require(result.await(10, TimeUnit.SECONDS) && accepted.get(), "命令确认失败");
      String firstEpoch = engine.state().getString("connectionEpoch");
      peer.drop();
      await("断开后未恢复热更订阅", () -> hot.get() && selected.state().getBoolean("connected")
          && !firstEpoch.equals(selected.state().getString("connectionEpoch")));
      require(peer.live.get() == 1, "断线重连留下重复连接");
      await("重连后账号授权未恢复", () -> "test-account".equals(selected.state().getString("accountId")));
      int beforeMaintenance = tickets.get();
      engine.send("drain-test", "ensemble", "testMaintenance", "{}", (ok, json) -> {});
      await("维护通知未进入退避", () -> !selected.state().getBoolean("connected"));
      SystemClock.sleep(3500);
      require(tickets.get() == beforeMaintenance, "维护冷却尚未到期就重新请求票据");
      await("维护冷却后未恢复", () -> selected.state().getBoolean("connected") && hot.get());
      require(peer.live.get() == 1 && tickets.get() == beforeMaintenance + 1, "维护重连不唯一");
      // 仅保留房间不应在无前台通知的后台永久联网。
      engine.usage(false);
      long idleDeadline=SystemClock.elapsedRealtime()+40000;
      while(!"stopped".equals(selected.state().getString("state"))) {
        require(SystemClock.elapsedRealtime()<idleDeadline,"空闲房间在后台未休眠");
        SystemClock.sleep(50);
      }
      await("休眠未关闭旧 Socket",()->peer.live.get()==0 && !hot.get());
      long resetBefore=selected.state().getLong("accountResetEpoch");
      engine.account("");
      await("离线退出未发布身份重置",()->selected.state().getLong("accountResetEpoch")>resetBefore);
      engine.account("new-login-token");
      engine.usage(true);
      await("返回前台未恢复订阅",()->selected.state().getBoolean("connected") && hot.get());
      observer.close();
      runOnMainSync(() -> {});
      int before = observed.get();
      engine.account("");
      await("退出后授权未清理", () -> selected.state().getString("accountId", "").isEmpty());
      SystemClock.sleep(300);
      require(observed.get() == before, "已关闭监听仍回调旧业务");
      require(callbackThread.get(), "宿主事件没有切回主线程");
      engine.close(); engine = null;
      await("关闭未释放 Socket", () -> peer.live.get() == 0);
      require(Bootstrap.source().prepared.classLoader().getParent() == runtime, "实时连接改变业务运行时");
      report.putString("stream", "通过：纯 Java 宿主、Kotlin 运行时连接、热更订阅、校时、授权、命令确认、重连、维护错峰、空闲房间后台休眠及恢复、监听释放及 Socket 关闭。\n");
      success = true;
    } catch (Throwable failure) {
      report.putString("stream", "失败：" + android.util.Log.getStackTraceString(failure));
      if (engine != null) report.putString("connectionState", engine.state().toString());
    } finally {
      if (engine != null) try { engine.close(); } catch (Exception ignored) {}
    }
    finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
  }

  static final class Peer implements AutoCloseable {
    final ServerSocket server;
    final AtomicInteger live = new AtomicInteger(), epochs = new AtomicInteger(), authorized = new AtomicInteger(), cleared = new AtomicInteger();
    final ExecutorService worker = Executors.newFixedThreadPool(3);
    volatile Socket active;
    volatile Exception lastFailure;
    Peer() throws IOException {
      this(new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")));
    }
    Peer(ServerSocket server) {
      this.server = server;
      worker.execute(() -> { while (!server.isClosed()) {
        try { Socket socket = server.accept(); worker.execute(() -> serve(socket)); }
        catch (IOException stopped) { return; }
      } });
    }
    private void serve(Socket socket) {
      live.incrementAndGet(); active = socket;
      try (socket) {
        socket.setSoTimeout(20_000);
        InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        while (header.size() < 8192) {
          int value = input.read(); if (value < 0) return; header.write(value);
          if (header.toString(StandardCharsets.US_ASCII.name()).endsWith("\r\n\r\n")) break;
        }
        String key = "";
        for (String line : header.toString(StandardCharsets.US_ASCII.name()).split("\r\n")) {
          if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) key = line.substring(line.indexOf(':') + 1).trim();
        }
        require(!key.isEmpty(), "握手缺少 key");
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
        String epoch = Integer.toString(epochs.incrementAndGet());
        send(output, epoch, "hello", "system", "hello", new JSONObject().put("clockEpoch", "test-clock")
            .put("capabilities", new org.json.JSONArray().put("hot-hints-v1").put("ensemble-v1")));
        while (true) {
          String text = read(input); if (text == null) return;
          JSONObject request = new JSONObject(text), data = new JSONObject();
          String topic = request.getString("topic"), kind = request.getString("type");
          if (topic.equals("system")) {
            String time = Long.toString(SystemClock.elapsedRealtimeNanos() / 1000 + 1_000_000);
            data.put("receivedUs", time).put("sentUs", time).put("clockEpoch", "test-clock").put("clockHealthy", true);
          } else if (topic.equals("hot")) data.put("subscribed", true).put("channelRevision", "2").put("trustVersion", "1");
          else if (topic.equals("account") && kind.equals("authorize")) {authorized.incrementAndGet();data.put("accountId", "test-account");}
          else if (topic.equals("account") && kind.equals("clear")) cleared.incrementAndGet();
          send(output, epoch, request.getString("messageId"), topic, "result", data);
          if (kind.equals("testMaintenance")) {
            send(output, epoch, "maintenance", "system", "maintenance", new JSONObject().put("retryAfterMs", 5000));
            return;
          }
        }
      } catch (Exception ended) { lastFailure = ended; }
      finally { live.decrementAndGet(); }
    }
    private static String read(InputStream input) throws IOException {
      int first = input.read(); if (first < 0 || (first & 15) == 8) return null;
      int second = input.read(); if (second < 0) return null;
      int size = second & 127;
      if (size == 126) size = (input.read() << 8) | input.read();
      if (size > 16384 || size == 127 || (second & 128) == 0) throw new IOException("frame");
      byte[] mask = bytes(input, 4), body = bytes(input, size);
      for (int i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
      return new String(body, StandardCharsets.UTF_8);
    }
    private static byte[] bytes(InputStream input, int size) throws IOException {
      byte[] result = new byte[size]; new DataInputStream(input).readFully(result); return result;
    }
    private static void send(OutputStream output, String epoch, String id, String topic, String kind, JSONObject data) throws Exception {
      byte[] body = new JSONObject().put("protocolVersion", 1).put("connectionEpoch", epoch).put("messageId", id)
          .put("topic", topic).put("type", kind).put("data", data).toString().getBytes(StandardCharsets.UTF_8);
      output.write(129);
      if (body.length < 126) output.write(body.length);
      else { output.write(126); output.write(body.length >> 8); output.write(body.length & 255); }
      output.write(body); output.flush();
    }
    void drop() throws IOException { if (active != null) active.close(); }
    @Override public void close() throws IOException { server.close(); drop(); worker.shutdownNow(); }
  }
}
