package app.luoxianlv.hot;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** JVM/设备测试共用的回环响应器，不依赖 Android 之外的 JDK HTTP 模块。 */
final class LoopbackHttp implements AutoCloseable {
  interface Handler {
    byte[] reply(Request request) throws Exception;
  }

  static final class Request {
    final String method, path;
    byte[] body = new byte[0];
    final Map<String, String> headers = new LinkedHashMap<>();

    Request(String method, String path) {
      this.method = method;
      this.path = path;
    }
  }

  private final ServerSocket server;
  private final Thread worker;
  private final AtomicReference<Throwable> error = new AtomicReference<>();

  LoopbackHttp(Handler handler) throws Exception {
    server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
    worker =
        new Thread(
            () -> {
              try {
                while (!server.isClosed()) {
                  try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                    ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
                    int ending = 0;
                    byte[] separator = new byte[] {13, 10, 13, 10};
                    while (ending < separator.length) {
                      int value = input.read();
                      if (value < 0 || requestBytes.size() >= 16384)
                        throw new IllegalArgumentException("测试请求头不完整或超限");
                      requestBytes.write(value);
                      ending = value == separator[ending] ? ending + 1 : value == 13 ? 1 : 0;
                    }
                    String[] lines =
                        new String(requestBytes.toByteArray(), StandardCharsets.US_ASCII)
                            .split("\r\n");
                    String line = lines[0];
                    if (!line.startsWith("GET ") && !line.startsWith("POST "))
                      throw new IllegalArgumentException("测试只接受 GET/POST");
                    Request request = new Request(line.split(" ")[0], line.split(" ")[1]);
                    for (int index = 1; index < lines.length; index++) {
                      line = lines[index];
                      int split = line.indexOf(':');
                      if (split < 0) throw new IllegalArgumentException("测试请求头无效");
                      request.headers.put(
                          line.substring(0, split).toLowerCase(Locale.ROOT),
                          line.substring(split + 1).trim());
                    }
                    int length =
                        Integer.parseInt(request.headers.getOrDefault("content-length", "0"));
                    if (length < 0 || length > StrictJson.MAX_BYTES)
                      throw new IllegalArgumentException("测试请求体超限");
                    request.body = new byte[length];
                    int received = 0;
                    while (received < length) {
                      int n = input.read(request.body, received, length - received);
                      if (n < 1) throw new IllegalArgumentException("测试请求体不完整");
                      received += n;
                    }
                    socket.getOutputStream().write(handler.reply(request));
                    socket.getOutputStream().flush();
                    socket.shutdownOutput();
                  }
                }
              } catch (Throwable failure) {
                if (!server.isClosed()) error.set(failure);
              }
            },
            "hot-test-http");
    worker.setDaemon(true);
    worker.start();
  }

  URI origin() {
    return URI.create("http://127.0.0.1:" + server.getLocalPort());
  }

  static byte[] reply(int status, String headers, byte[] body) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.write(
        ("HTTP/1.1 "
                + status
                + " Test\r\nConnection: close\r\nContent-Length: "
                + body.length
                + "\r\n"
                + headers
                + "\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    bytes.write(body);
    return bytes.toByteArray();
  }

  @Override
  public void close() throws Exception {
    server.close();
    worker.join(4000);
    if (worker.isAlive()) throw new AssertionError("测试连接未释放");
    if (error.get() != null) throw new AssertionError("本机 HTTP 测试失败", error.get());
  }
}
