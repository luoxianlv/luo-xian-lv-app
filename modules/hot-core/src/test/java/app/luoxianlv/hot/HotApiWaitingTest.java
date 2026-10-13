package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class HotApiWaitingTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  @Test public void slowHeadersStayOnOriginalRequest() throws Exception {
    var calls = new AtomicInteger();
    try (var server = new LoopbackHttp(request -> {
      calls.incrementAndGet();
      Thread.sleep(900);
      return LoopbackHttp.reply(200, "X-Lxhot-Time: 1791842981\r\n", "{\"ok\":true,\"data\":{\"done\":true}}".getBytes(StandardCharsets.UTF_8));
    })) {
      var identity = InstallationIdentity.open(temporary.newFolder(), "app.luoxianlv.debug", "test");
      var client = new HotApiClient(server.origin(), identity, 1, "a".repeat(64), true);
      client.timeouts(1000, 2500);
      client.post("/events", Map.of(), "stable-id", false);
      assertEquals(1, calls.get());
    }
  }

  @Test public void timedOutPostDoesNotReplay() throws Exception {
    var calls = new AtomicInteger();
    try (var server = new LoopbackHttp(request -> {
      calls.incrementAndGet();
      Thread.sleep(1200);
      return LoopbackHttp.reply(200, "X-Lxhot-Time: 1791842981\r\n", "{\"ok\":true,\"data\":{}}".getBytes(StandardCharsets.UTF_8));
    })) {
      var identity = InstallationIdentity.open(temporary.newFolder(), "app.luoxianlv.debug", "test");
      var client = new HotApiClient(server.origin(), identity, 1, "a".repeat(64), true);
      client.timeouts(1000, 700);
      assertThrows(java.net.SocketTimeoutException.class, () -> client.post("/events", Map.of(), "stable-id", false));
      assertEquals(1, calls.get());
    }
  }
}
