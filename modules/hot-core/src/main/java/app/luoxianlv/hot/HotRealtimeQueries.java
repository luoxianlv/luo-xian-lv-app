package app.luoxianlv.hot;

import app.luoxianlv.hot.contract.SharedRealtime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 只迁移热更查询；正文和时间继续进入原来的严格解析与签名验证。 */
final class HotRealtimeQueries {
  static boolean available(HotApiClient api) {
    return available(api, SharedRealtime.current());
  }
  static boolean registered(HotApiClient api) {
    SharedRealtime.Bridge bridge = SharedRealtime.current();
    return available(api, bridge) && api.installation.id.equals(bridge.state().getString("installationId"));
  }
  private static boolean available(HotApiClient api, SharedRealtime.Bridge bridge) {
    if (bridge == null || !bridge.state().getBoolean("hotQueriesReady")) return false;
    try {
      java.net.URI origin = java.net.URI.create(bridge.state().getString("apiOrigin", ""));
      java.net.URI expected = api.origin();
      int port = origin.getPort() >= 0 ? origin.getPort() : (origin.getScheme().equals("https") ? 443 : 80);
      int targetPort = expected.getPort() >= 0 ? expected.getPort() : (expected.getScheme().equals("https") ? 443 : 80);
      return origin.getScheme().equals(expected.getScheme()) && origin.getHost().equalsIgnoreCase(expected.getHost()) && port == targetPort;
    } catch (Exception invalid) { return false; }
  }
  static HotApiClient.Response query(HotApiClient api, String route, Map<String, ?> body,
      UpdateCancellation cancellation, int timeoutMillis) throws Exception {
    SharedRealtime.Bridge bridge = SharedRealtime.current();
    if (!available(api, bridge)) return null;
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<String> result = new AtomicReference<>();
    String json = new String(JsonWire.encode(JsonWire.fields("method", "POST", "path", "/api/hot/v2" + route,
        "authorization", api.installation.credential(), "body", body)), java.nio.charset.StandardCharsets.UTF_8);
    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 15_000) return null;
    try (AutoCloseable request = bridge.send("query:" + UUID.randomUUID(), "query", "request", json,
        (success, value) -> { result.set(value); done.countDown(); });
        UpdateCancellation.Registration owned = cancellation.register(request)) {
      long until = System.nanoTime() + timeoutMillis * 1_000_000L;
      while (!done.await(50, TimeUnit.MILLISECONDS)) {
        cancellation.check();
        if (System.nanoTime() >= until) return null;
      }
      cancellation.check();
      StrictJson.Obj envelope = StrictJson.queryEnvelope(result.get().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (!envelope.has("body")) return null;
      int status = (int) envelope.number("status");
      byte[] raw = envelope.string("body").getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (status < 200 || status >= 300) {
        String code = StrictJson.envelope(raw).optionalString("code");
        throw new HotApiClient.Failure(status, code.isEmpty() ? "request_failed" : code, envelope.number("retryAfterMillis"));
      }
      String stamp = envelope.string("time");
      StrictJson.require(stamp.matches("[0-9]{1,12}"), "WS 查询未返回服务端时间");
      return new HotApiClient.Response(StrictJson.envelope(raw), java.time.Instant.ofEpochSecond(Long.parseLong(stamp)));
    }
  }
}
