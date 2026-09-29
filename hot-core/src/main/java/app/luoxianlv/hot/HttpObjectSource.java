package app.luoxianlv.hot;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 下载连接不跟随跳转，不把安装凭据送往 OSS/CDN；错误只报告状态码，不输出签名地址。 */
public final class HttpObjectSource implements ObjectDownloader.Source {
  private static final Pattern RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
  private final URI url;
  private final long size;
  private final String credential;
  private final boolean localConnection;

  public static final class Failure extends IOException {
    public final int status;
    public final long retryAfterMillis;

    Failure(int status, long retryAfterMillis) {
      super("下载服务暂未提供对象，HTTP " + status);
      this.status = status;
      this.retryAfterMillis = retryAfterMillis;
    }
  }

  /** credential 非空时只能连接明确指定的 API 同源地址；CDN 签名链接必须传空凭据。 */
  public HttpObjectSource(URI url, long size, URI apiOrigin, String credential, boolean localTest) {
    validateUrl(url, localTest);
    StrictJson.require(size > 0 && size <= HotManifest.MAX_EXPANDED, "下载对象大小无效");
    if (!credential.isEmpty()) {
      validateUrl(apiOrigin, localTest);
      StrictJson.require(
          sameOrigin(url, apiOrigin) && credential.matches("[A-Za-z0-9._-]{1,256}"),
          "安装凭据不能用于其他来源或包含非法字符");
    }
    this.url = url;
    this.size = size;
    this.credential = credential;
    localConnection = localTest && isLocal(url.getHost());
  }

  @Override
  public ObjectDownloader.Response open(long offset) throws Exception {
    StrictJson.require(offset >= 0 && offset < size, "下载偏移量无效");
    // 模拟器可能设置了开发代理；本机测试服务器不能被代理到宿主机的另一个回环端口。
    HttpURLConnection connection = connect(url, localConnection);
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(15000);
      connection.setUseCaches(false);
      connection.setRequestProperty("Accept-Encoding", "identity");
      if (!credential.isEmpty())
        connection.setRequestProperty("Authorization", "Bearer " + credential);
      if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
      int status = connection.getResponseCode();
      if (status != 200 && status != 206)
        throw new Failure(status, retryAfter(connection.getHeaderField("Retry-After")));
      StrictJson.require(
          connection.getHeaderField("Content-Encoding") == null
              || connection.getHeaderField("Content-Encoding").equalsIgnoreCase("identity"),
          "对象下载不接受透明压缩");
      long start = 0, end = size - 1, total = size;
      if (status == 206) {
        String value = connection.getHeaderField("Content-Range");
        Matcher range = RANGE.matcher(value == null ? "" : value);
        StrictJson.require(range.matches(), "下载服务未给出明确的续传范围");
        start = Long.parseLong(range.group(1));
        end = Long.parseLong(range.group(2));
        total = Long.parseLong(range.group(3));
      }
      StrictJson.require(start == offset && end == size - 1 && total == size, "下载服务忽略续传或对象大小改变");
      long length = connection.getHeaderFieldLong("Content-Length", -1);
      StrictJson.require(length == size - offset, "下载响应长度与签名目标不一致");
      long started = System.nanoTime();
      InputStream bounded =
          new FilterInputStream(connection.getInputStream()) {
            private void deadline() throws SocketTimeoutException {
              if (System.nanoTime() - started > 300_000_000_000L)
                throw new SocketTimeoutException("单次对象下载等待超过五分钟");
            }

            @Override
            public int read(byte[] bytes, int from, int count) throws IOException {
              deadline();
              int n = in.read(bytes, from, count);
              deadline();
              return n;
            }

            @Override
            public int read() throws IOException {
              deadline();
              int n = in.read();
              deadline();
              return n;
            }
          };
      return new ObjectDownloader.Response(start, total, length, bounded, connection::disconnect);
    } catch (Exception | Error failure) {
      connection.disconnect();
      throw failure;
    }
  }

  static void validateUrl(URI uri, boolean localTest) {
    StrictJson.require(
        uri != null
            && !uri.isOpaque()
            && uri.getHost() != null
            && uri.getRawUserInfo() == null
            && uri.getRawFragment() == null
            && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535),
        "下载地址格式无效");
    String host = uri.getHost();
    boolean loopback = isLocal(host);
    StrictJson.require(
        "https".equals(uri.getScheme()) || localTest && loopback && "http".equals(uri.getScheme()),
        "下载必须使用 HTTPS，本机联调地址需显式启用测试模式");
  }

  static HttpURLConnection connect(URI url, boolean localTest) throws IOException {
    validateUrl(url, localTest);
    return (HttpURLConnection)
        (localTest && isLocal(url.getHost())
            ? url.toURL().openConnection(java.net.Proxy.NO_PROXY)
            : url.toURL().openConnection());
  }

  private static boolean isLocal(String host) {
    return host.equals("127.0.0.1")
        || host.equals("localhost")
        || host.equals("[::1]")
        || host.equals("10.0.2.2");
  }

  private static boolean sameOrigin(URI a, URI b) {
    return a.getScheme().equals(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && port(a) == port(b);
  }

  private static int port(URI url) {
    return url.getPort() != -1 ? url.getPort() : "https".equals(url.getScheme()) ? 443 : 80;
  }

  static long retryAfter(String value) {
    if (value == null || !value.matches("[0-9]{1,9}")) return 60000;
    return Math.min(1800000, Long.parseLong(value) * 1000);
  }
}
