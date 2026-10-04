package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** 真正回环HTTP在请求头或响应体阻塞；取消必须关闭当前连接，不靠缩短原超时。 */
public final class UpdateCancellationTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private ExecutorService worker;

  @Before
  public void createWorker() {
    worker = Executors.newSingleThreadExecutor();
  }

  @After
  public void closeWorker() {
    worker.shutdownNow();
  }

  private static final class SlowServer implements AutoCloseable {
    final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    final CountDownLatch requested = new CountDownLatch(1), bodySent = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final Thread thread;
    volatile Socket socket;

    SlowServer(boolean headers, byte[] prefix, long size) throws Exception {
      thread =
          new Thread(
              () -> {
                try (Socket peer = listener.accept()) {
                  socket = peer;
                  peer.setSoTimeout(5000);
                  InputStream input = peer.getInputStream();
                  ByteArrayOutputStream raw = new ByteArrayOutputStream();
                  int matched = 0;
                  while (matched != 4) {
                    int value = input.read();
                    if (value < 0) throw new EOFException("请求提前关闭");
                    raw.write(value);
                    matched =
                        value == "\r\n\r\n".charAt(matched) ? matched + 1 : value == 13 ? 1 : 0;
                  }
                  String request = raw.toString(StandardCharsets.US_ASCII);
                  int length = 0;
                  for (String line : request.split("\r\n"))
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                      length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                  if (input.readNBytes(length).length != length) throw new EOFException("请求体不完整");
                  requested.countDown();
                  if (headers) {
                    peer.getOutputStream()
                        .write(
                            ("HTTP/1.1 200 OK\r\nContent-Length: "
                                    + size
                                    + "\r\nX-Lxhot-Time: 1790635392\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                    peer.getOutputStream().write(prefix);
                    peer.getOutputStream().flush();
                    bodySent.countDown();
                  }
                  assertEquals("取消必须让服务端观察到客户端关闭", -1, input.read());
                } catch (Throwable failure) {
                  if (!listener.isClosed()) error.set(failure);
                } finally {
                  closed.countDown();
                }
              },
              "test-slow-update-http");
      thread.setDaemon(true);
      thread.start();
    }

    URI origin() {
      return URI.create("http://127.0.0.1:" + listener.getLocalPort());
    }

    @Override
    public void close() throws Exception {
      listener.close();
      if (socket != null) socket.close();
      thread.join(5000);
      assertFalse("回环测试线程未退出", thread.isAlive());
      if (error.get() != null) throw new AssertionError(error.get());
    }
  }

  private static Throwable failure(Future<?> result) throws Exception {
    try {
      result.get(2500, TimeUnit.MILLISECONDS);
      throw new AssertionError("操作未取消");
    } catch (ExecutionException failure) {
      assertTrue(
          failure.getCause().toString(),
          failure.getCause() instanceof UpdateCancellation.Cancelled);
      return failure.getCause();
    }
  }

  private static void cancelFast(UpdateCancellation token) {
    long start = System.nanoTime();
    token.cancel();
    assertTrue("调用取消不能同步等待网络读取", System.nanoTime() - start < 200_000_000L);
  }

  @Test
  public void blockedObjectHeadersCloseWithoutWaitingFifteenSecondTimeout() throws Exception {
    try (var server = new SlowServer(false, new byte[0], 4096)) {
      var token = new UpdateCancellation(() -> false);
      var source =
          new HttpObjectSource(
              server.origin().resolve("/object?sig=public-cancel-sentinel"), 4096, null, "", true);
      Future<?> result = worker.submit(() -> source.open(0, token));
      assertTrue(server.requested.await(3, TimeUnit.SECONDS));
      cancelFast(token);
      Throwable rejected = failure(result);
      assertFalse(rejected.toString().contains("public-cancel-sentinel"));
      assertTrue(server.closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, token.objectRequests());
      assertEquals(0, token.objectBytes());
    }
  }

  @Test
  public void blockedObjectBodyClosesAndResumesExactPartialWithoutResettingBudget()
      throws Exception {
    byte[] bytes = new byte[131072];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
    String hash = HotSignatures.hash(bytes), content = HotSignatures.hash(new byte[] {77});
    var budget = new DownloadBudget(temporary.newFolder());
    var downloads = new ObjectDownloader(temporary.newFolder(), budget, temporary.newFolder());
    File partial = downloads.partial(hash);
    try (var server = new SlowServer(true, java.util.Arrays.copyOf(bytes, 4096), bytes.length)) {
      var token = new UpdateCancellation(() -> false);
      var source =
          new HttpObjectSource(server.origin().resolve("/object"), bytes.length, null, "", true);
      Future<?> result =
          worker.submit(
              () ->
                  downloads.download(
                      content,
                      hash,
                      bytes.length,
                      source,
                      () -> true,
                      token,
                      () -> bytes.length - partial.length()));
      assertTrue(server.bodySent.await(3, TimeUnit.SECONDS));
      long until = System.nanoTime() + 3_000_000_000L;
      while (partial.length() != 4096 && System.nanoTime() < until) Thread.sleep(10);
      assertEquals(4096, partial.length());
      cancelFast(token);
      failure(result);
      assertTrue(server.closed.await(3, TimeUnit.SECONDS));
      assertEquals(4096, downloads.receivedBytes());
      assertEquals(4096, token.objectBytes());
      assertEquals(1, token.objectRequests());
      assertEquals(bytes.length, budget.used(content));
      assertArrayEquals(java.util.Arrays.copyOf(bytes, 4096), Files.readAllBytes(partial.toPath()));
    }
    long before = budget.used(content);
    try (var resumed =
        new LoopbackHttp(
            request -> {
              assertEquals("bytes=4096-", request.headers.get("range"));
              return LoopbackHttp.reply(
                  206,
                  "Content-Range: bytes 4096-131071/131072\r\n",
                  java.util.Arrays.copyOfRange(bytes, 4096, bytes.length));
            })) {
      var token = new UpdateCancellation(() -> false);
      File result =
          downloads.download(
              content,
              hash,
              bytes.length,
              new HttpObjectSource(
                  resumed.origin().resolve("/object"), bytes.length, null, "", true),
              () -> true,
              token,
              () -> bytes.length - partial.length());
      assertEquals(hash, HotSignatures.hash(Files.readAllBytes(result.toPath())));
      assertEquals(bytes.length, downloads.receivedBytes());
      assertEquals(bytes.length - 4096, token.objectBytes());
      assertTrue("取消的保守预留不能退还", budget.used(content) > before);
    }
  }

  private void apiCancellation(boolean headers) throws Exception {
    try (var server = new SlowServer(headers, new byte[] {'{'}, 8192)) {
      var identity =
          InstallationIdentity.open(temporary.newFolder(), "app.luoxianlv.debug", "test");
      var client = new HotApiClient(server.origin(), identity, 1, "a".repeat(64), true);
      var token = new UpdateCancellation(() -> false);
      Future<?> result = worker.submit(() -> client.check("", 1, token));
      assertTrue(server.requested.await(3, TimeUnit.SECONDS));
      if (headers) assertTrue(server.bodySent.await(3, TimeUnit.SECONDS));
      cancelFast(token);
      failure(result);
      assertTrue(server.closed.await(3, TimeUnit.SECONDS));
      var registered = HotApiClient.class.getDeclaredField("registered");
      registered.setAccessible(true);
      assertFalse("取消注册不能标记已完成", registered.getBoolean(client));
    }
  }

  @Test
  public void blockedRegistrationHeadersAreCancelled() throws Exception {
    apiCancellation(false);
  }

  @Test
  public void blockedRegistrationBodyIsCancelled() throws Exception {
    apiCancellation(true);
  }

  @Test
  public void cancelledTokenClosesLateRegistrationOnceAndNeverBecomesReusable() throws Exception {
    var token = new UpdateCancellation(() -> false);
    var closed = new AtomicInteger();
    var done = new CountDownLatch(1);
    token.cancel();
    assertThrows(
        UpdateCancellation.Cancelled.class,
        () ->
            token.register(
                () -> {
                  closed.incrementAndGet();
                  done.countDown();
                }));
    assertTrue(done.await(2, TimeUnit.SECONDS));
    token.cancel();
    assertEquals(1, closed.get());
    assertThrows(UpdateCancellation.Cancelled.class, token::check);
  }

  @Test
  public void intermittentFixedObjectBodyRetriesExactRangesAndReservesBudgetOnce()
      throws Exception {
    byte[] bytes = new byte[12288];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 7);
    var ranges = new java.util.ArrayList<String>();
    var failure = new AtomicReference<Throwable>();
    try (ServerSocket listener = new ServerSocket(0, 3, InetAddress.getLoopbackAddress())) {
      Thread server =
          new Thread(
              () -> {
                try {
                  for (int offset = 0; offset < bytes.length; offset += 4096) {
                    try (Socket socket = listener.accept()) {
                      socket.setSoTimeout(4000);
                      String request = headers(socket.getInputStream());
                      String expected = offset == 0 ? null : "bytes=" + offset + "-";
                      String range = header(request, "range");
                      ranges.add(range);
                      assertEquals(expected, range);
                      int remaining = bytes.length - offset;
                      String response =
                          "HTTP/1.1 "
                              + (offset == 0 ? "200 OK" : "206 Partial")
                              + "\r\nConnection: close\r\nContent-Length: "
                              + remaining
                              + "\r\n"
                              + (offset == 0
                                  ? ""
                                  : "Content-Range: bytes " + offset + "-12287/12288\r\n")
                              + "\r\n";
                      socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                      socket.getOutputStream().write(bytes, offset, 4096);
                      socket.getOutputStream().flush();
                      if (remaining > 4096) assertEquals(-1, socket.getInputStream().read());
                    }
                  }
                } catch (Throwable error) {
                  failure.set(error);
                }
              },
              "test-range-resume");
      server.setDaemon(true);
      server.start();
      var budget = new DownloadBudget(temporary.newFolder());
      var downloads = new ObjectDownloader(temporary.newFolder(), budget);
      String hash = HotSignatures.hash(bytes), content = HotSignatures.hash(new byte[] {81});
      var token = new UpdateCancellation(() -> false);
      File result =
          downloads.download(
              content,
              hash,
              bytes.length,
              new HttpObjectSource(
                  URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/object"),
                  bytes.length,
                  null,
                  "",
                  true),
              () -> true,
              token,
              () -> bytes.length - downloads.partial(hash).length());
      server.join(4000);
      assertFalse(server.isAlive());
      assertNull(failure.get());
      assertEquals(3, ranges.size());
      assertEquals(3, token.objectRequests());
      assertEquals(bytes.length, token.objectBytes());
      assertEquals(bytes.length, downloads.receivedBytes());
      assertEquals("超时续传保留同一预留额度", bytes.length, budget.used(content));
      assertArrayEquals(bytes, Files.readAllBytes(result.toPath()));
    }
  }

  private static String headers(InputStream input) throws Exception {
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    int matched = 0;
    while (matched != 4) {
      int value = input.read();
      if (value < 0) throw new EOFException();
      raw.write(value);
      matched = value == "\r\n\r\n".charAt(matched) ? matched + 1 : value == 13 ? 1 : 0;
    }
    return raw.toString(StandardCharsets.US_ASCII);
  }

  private static String header(String request, String name) {
    for (String line : request.split("\r\n"))
      if (line.toLowerCase(java.util.Locale.ROOT).startsWith(name + ":"))
        return line.substring(line.indexOf(':') + 1).trim();
    return null;
  }

  private void delayedApi(boolean cold) throws Exception {
    var failure = new AtomicReference<Throwable>();
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread server =
          new Thread(
              () -> {
                try (Socket socket = listener.accept()) {
                  String request = headers(socket.getInputStream());
                  int size = Integer.parseInt(header(request, "content-length"));
                  String id =
                      StrictJson.object(socket.getInputStream().readNBytes(size))
                          .string("installationId");
                  byte[] body =
                      JsonWire.encode(
                          JsonWire.fields(
                              "installationId",
                              id,
                              "credentialFormat",
                              "Bearer <installationId>.<secret>",
                              "scope",
                              "installation"));
                  socket
                      .getOutputStream()
                      .write(
                          ("HTTP/1.1 200 OK\r\nContent-Length: "
                                  + body.length
                                  + "\r\nX-Lxhot-Time: 1790635392\r\nConnection: close\r\n\r\n")
                              .getBytes(StandardCharsets.US_ASCII));
                  socket.getOutputStream().write(body, 0, 5);
                  socket.getOutputStream().flush();
                  Thread.sleep(1200);
                  socket.getOutputStream().write(body, 5, body.length - 5);
                  socket.getOutputStream().flush();
                } catch (SocketException expectedColdClose) {
                  if (!cold) failure.set(expectedColdClose);
                } catch (Throwable error) {
                  failure.set(error);
                }
              },
              "test-delayed-api-body");
      server.setDaemon(true);
      server.start();
      var identity =
          InstallationIdentity.open(temporary.newFolder(), "app.luoxianlv.debug", "test");
      var api =
          new HotApiClient(
              URI.create("http://127.0.0.1:" + listener.getLocalPort()),
              identity,
              1,
              "a".repeat(64),
              true);
      if (cold) api.timeouts(750, 750);
      long started = System.nanoTime();
      if (cold)
        assertThrows(
            SocketTimeoutException.class, () -> api.register(new UpdateCancellation(() -> false)));
      else api.register(new UpdateCancellation(() -> false));
      long elapsed = System.nanoTime() - started;
      assertTrue(cold ? elapsed < 1_600_000_000L : elapsed >= 1_100_000_000L);
      server.join(3000);
      assertFalse(server.isAlive());
      assertNull(failure.get());
    }
  }

  @Test
  public void ordinaryFixedApiBodyKeepsFifteenSecondToleranceAcrossReadSlices() throws Exception {
    delayedApi(false);
  }

  @Test
  public void coldApiNeverExpandsToOrdinaryFifteenSecondWait() throws Exception {
    delayedApi(true);
  }

  @Test
  public void objectRejectsChunkedTransferEvenWhenContentLengthIsAlsoPresent() throws Exception {
    try (LoopbackHttp server =
        new LoopbackHttp(
            request -> LoopbackHttp.reply(200, "Transfer-Encoding: chunked\r\n", new byte[8]))) {
      var source = new HttpObjectSource(server.origin().resolve("/object"), 8, null, "", true);
      assertThrows(
          IllegalArgumentException.class,
          () -> source.open(0, new UpdateCancellation(() -> false)));
    }
  }
}
