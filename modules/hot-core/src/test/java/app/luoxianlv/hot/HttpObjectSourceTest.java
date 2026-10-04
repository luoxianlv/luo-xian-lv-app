package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class HttpObjectSourceTest {
  @Test
  public void realHttpRangeHasExactIdentityEncodingAndOwnedCredential() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>(),
        range = new AtomicReference<>(),
        encoding = new AtomicReference<>();
    try (LoopbackHttp server =
        new LoopbackHttp(
            request -> {
              authorization.set(request.headers.get("authorization"));
              range.set(request.headers.get("range"));
              encoding.set(request.headers.get("accept-encoding"));
              return LoopbackHttp.reply(
                  206, "Content-Range: bytes 2-5/6\r\n", new byte[] {2, 3, 4, 5});
            })) {
      URI origin = server.origin();
      HttpObjectSource source =
          new HttpObjectSource(origin.resolve("/object"), 6, origin, "test-installation", true);
      try (ObjectDownloader.Response response = source.open(2)) {
        assertEquals(2, response.offset);
        assertEquals(6, response.total);
        assertEquals(4, response.length);
        assertArrayEquals(new byte[] {2, 3, 4, 5}, response.body.readAllBytes());
      }
      assertEquals("Bearer test-installation", authorization.get());
      assertEquals("bytes=2-", range.get());
      assertEquals("identity", encoding.get());
    }
  }

  @Test
  public void redirectNeverForwardsCredentialsAndMissingRangeIsRejected() throws Exception {
    AtomicInteger leaked = new AtomicInteger();
    try (LoopbackHttp server =
        new LoopbackHttp(
            request -> {
              if (request.path.equals("/redirect"))
                return LoopbackHttp.reply(302, "Location: /target\r\n", new byte[0]);
              leaked.incrementAndGet();
              return LoopbackHttp.reply(200, "", new byte[6]);
            })) {
      URI origin = server.origin();
      HttpObjectSource redirected =
          new HttpObjectSource(origin.resolve("/redirect"), 6, origin, "test-token", true);
      HttpObjectSource.Failure failure =
          assertThrows(HttpObjectSource.Failure.class, () -> redirected.open(0));
      assertEquals(302, failure.status);
      assertEquals(0, leaked.get());
      HttpObjectSource ignoredRange =
          new HttpObjectSource(origin.resolve("/target"), 6, origin, "test-token", true);
      assertThrows(IllegalArgumentException.class, () -> ignoredRange.open(2));
    }
  }

  @Test
  public void remoteHttpAndCrossOriginCredentialsAreRejectedBeforeConnecting() {
    URI api = URI.create("https://api.example.test");
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpObjectSource(URI.create("http://cdn.example.test/a"), 1, null, "", true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HttpObjectSource(
                URI.create("https://cdn.example.test/a"), 1, api, "credential", false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpObjectSource(URI.create("http://127.0.0.1/a"), 1, null, "", false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HttpObjectSource(
                URI.create("https://user:password@api.example.test/a"), 1, api, "", false));
  }
}
