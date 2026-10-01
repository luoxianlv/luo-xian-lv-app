package app.luoxianlv.host;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import app.luoxianlv.hot.StrictJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** 独立测试的文件握手与只读系统网络探针；不改变系统设置或宿主状态。 */
final class NativeNetworkChecks {
  private NativeNetworkChecks() {}

  static final class Deadline {
    private final LongSupplier clock;
    final long started, expires;
    Deadline(LongSupplier clock) {
      this.clock = clock;
      started = clock.getAsLong();
      // 为最终窗口恢复预留15s；全部主动观察最多285s。
      expires = Math.addExact(started, 285000);
    }
    long remaining(long maximum) {
      require(maximum > 0, "无效观察期限");
      long remaining = expires - clock.getAsLong();
      require(remaining > 0, "网络握手总观察期限已用尽");
      return Math.min(maximum, remaining);
    }
    void check() { remaining(1); }
    void complete() { require(clock.getAsLong() - started <= 300000, "网络检查及收尾超过5分钟"); }
  }

  static long offlineDeadline(long entered, long due) {
    return Math.max(Math.addExact(entered, 75000), Math.addExact(due, 3000));
  }

  static final class Session {
    final String runId;
    final Path directory, control, acknowledgement, report;
    private Session(String runId, Path directory) {
      this.runId = runId; this.directory = directory;
      control = directory.resolve("control.json");
      acknowledgement = directory.resolve("ack.json");
      report = directory.resolve("report.json");
    }
    static Session open(Path files, String runId) throws Exception {
      require(runId != null && runId.matches("[a-f0-9]{32}"), "网络握手runId必须为32位小写hex");
      require(!Files.isSymbolicLink(files) && Files.isDirectory(files, LinkOption.NOFOLLOW_LINKS), "内部files目录不可用");
      Path root = files.toRealPath().resolve("native-host-network-checks");
      if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root);
      require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "握手目录类型无效");
      Path directory = root.resolve(runId);
      Files.createDirectory(directory); // 已存在的run永不重用、删除或覆盖。
      return new Session(runId, directory);
    }
    String relativeControl() { return "files/native-host-network-checks/" + runId + "/control.json"; }
    void request(String phase, long at, long expires, String source) throws Exception {
      require(phase.equals("disconnect") || phase.equals("reconnect") || phase.equals("complete") || phase.equals("failed"), "握手阶段无效");
      require(source != null && source.matches("[a-z0-9:]+"), "来源身份不可编码");
      String content = "{\"schema\":1,\"runId\":\"" + runId + "\",\"phase\":\"" + phase
          + "\",\"requestedElapsedMs\":" + at + ",\"deadlineElapsedMs\":" + expires
          + ",\"sourceIdentity\":\"" + source + "\",\"systemNetworkChangedByHelper\":false}";
      write(control, content);
    }
    boolean acknowledged(String phase) throws Exception {
      require(phase.equals("disconnect") || phase.equals("reconnect"), "握手确认阶段无效");
      safeDirectory();
      if (!Files.exists(acknowledgement, LinkOption.NOFOLLOW_LINKS)) return false;
      require(!Files.isSymbolicLink(acknowledgement) && Files.isRegularFile(acknowledgement, LinkOption.NOFOLLOW_LINKS)
          && Files.size(acknowledgement) <= 4096, "网络ACK类型或大小无效");
      byte[] bytes;
      try (var input = Files.newInputStream(acknowledgement, LinkOption.NOFOLLOW_LINKS)) {
        var output = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        for (int count; (count = input.read(chunk)) != -1;) {
          require(output.size() + count <= 4096, "网络ACK读取超限");
          output.write(chunk, 0, count);
        }
        bytes = output.toByteArray();
      }
      var ack = StrictJson.object(bytes).only("runId", "phase");
      String acknowledgedPhase = ack.string("phase");
      require(acknowledgedPhase.equals("disconnect") || acknowledgedPhase.equals("reconnect"), "网络ACK阶段无效");
      return runId.equals(ack.string("runId")) && phase.equals(acknowledgedPhase);
    }
    void writeReport(String content) throws Exception { write(report, content); }
    private void safeDirectory() throws Exception {
      require(!Files.isSymbolicLink(directory.getParent()) && !Files.isSymbolicLink(directory)
          && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "握手目录已改变");
    }
    private void write(Path path, String content) throws Exception {
      safeDirectory();
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      require(bytes.length <= 1 << 20, "网络测试回执超限");
      Path temporary = Files.createTempFile(directory, ".native-network-", ".tmp");
      try {
        Files.write(temporary, bytes);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      } finally { Files.deleteIfExists(temporary); }
    }
  }

  /** 本测试自己的真实系统callback，只观察；不能据此排除宿主的一秒状态probe。 */
  static final class Probe implements AutoCloseable {
    private final ConnectivityManager connectivity;
    private long callbacks, internetCallbacks, internetAt = -1, lostAt = -1;
    private String internetNetwork = "";
    private boolean closed;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
      @Override public void onAvailable(Network network) { callbacks++; }
      @Override public void onLost(Network network) { callbacks++; lostAt = SystemClock.elapsedRealtime(); }
      @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
        callbacks++;
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
          internetCallbacks++; internetAt = SystemClock.elapsedRealtime(); internetNetwork = network.toString();
        }
      }
    };
    Probe(Context context) {
      require(Looper.myLooper() == Looper.getMainLooper(), "网络探针须在主线程注册");
      connectivity = context.getSystemService(ConnectivityManager.class);
      require(connectivity != null, "系统ConnectivityManager不可用");
      connectivity.registerDefaultNetworkCallback(callback, new Handler(Looper.getMainLooper()));
    }
    Snapshot read() {
      require(!closed && Looper.myLooper() == Looper.getMainLooper(), "网络探针须在有效主线程读取");
      Network network = connectivity.getActiveNetwork();
      NetworkCapabilities caps = network == null ? null : connectivity.getNetworkCapabilities(network);
      Snapshot result = new Snapshot();
      result.at = SystemClock.elapsedRealtime();
      result.network = network == null ? "" : network.toString();
      result.internet = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
      result.validated = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
      result.callbacks = callbacks; result.internetCallbacks = internetCallbacks;
      result.internetAt = internetAt; result.internetNetwork = internetNetwork; result.lostAt = lostAt;
      return result;
    }
    @Override public void close() {
      require(Looper.myLooper() == Looper.getMainLooper(), "网络探针须在主线程注销");
      if (closed) return;
      connectivity.unregisterNetworkCallback(callback);
      closed = true;
    }
  }

  static final class Snapshot {
    long at, callbacks, internetCallbacks, internetAt, lostAt;
    boolean internet, validated;
    String network, internetNetwork;
    JSONObject json() throws Exception {
      return new JSONObject().put("elapsedMs", at).put("activeNetwork", network)
          .put("internet", internet).put("validated", validated).put("systemCallbackCount", callbacks)
          .put("internetCallbackCount", internetCallbacks).put("lastInternetCallbackElapsedMs", internetAt)
          .put("lastInternetCallbackNetwork", internetNetwork).put("lastLostElapsedMs", lostAt);
    }
  }

  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
