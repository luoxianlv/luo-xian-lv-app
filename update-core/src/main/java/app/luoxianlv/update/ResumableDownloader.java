package app.luoxianlv.update;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 限定大小的流式下载。部分文件身份由调用方专用的摘要目录确定，完成时重新校验全文件。 */
public final class ResumableDownloader {
  private static final Pattern RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
  public interface UrlSource { String get() throws IOException; }
  public long download(UrlSource source, File partial, long size, String digest,
      Cancellation cancellation, Progress progress) throws IOException {
    return download(source, partial, size, digest, cancellation, progress, new AtomicLong());
  }
  public long download(UrlSource source, File partial, long size, String digest,
      Cancellation cancellation, Progress progress, AtomicLong networkBytes) throws IOException {
    if (size <= 0 || size > 2L * 1024 * 1024 * 1024 || !digest.matches("[0-9a-f]{64}")) throw new IOException("下载身份无效");
    SafeFiles.rejectLink(partial);
    SafeFiles.directory(partial.getParentFile());
    if (!partial.getParentFile().isDirectory() && !partial.getParentFile().mkdirs()) throw new IOException("无法创建下载目录");
    if (partial.isFile() && partial.length() == size) {
      try { ArtifactVerifier.verify(partial, size, digest, cancellation); return networkBytes.get(); }
      catch (Cancellation.CancelledException cancelled) { throw cancelled; }
      catch (IOException corrupted) { truncate(partial); }
    } else if (partial.length() > size) truncate(partial);
    cancellation.check();
    long offset = partial.length();
    HttpURLConnection connection = open(source.get(), offset, cancellation);
    try (AutoCloseable ignored = cancellation.onCancel(connection::disconnect)) {
      int status = connection.getResponseCode();
      if (status == 200 && offset != 0) { truncate(partial); offset = 0; }
      if (status != 200 && status != 206) throw new IOException("下载响应异常：" + status);
      if (status == 206) {
        Matcher range = RANGE.matcher(String.valueOf(connection.getHeaderField("Content-Range")));
        if (!range.matches() || Long.parseLong(range.group(1)) != offset
            || Long.parseLong(range.group(2)) != size - 1 || Long.parseLong(range.group(3)) != size)
          throw new IOException("断点下载范围不匹配");
      }
      String encoding = connection.getHeaderField("Content-Encoding");
      if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("下载使用了不允许的传输编码");
      long expected = size - offset;
      long announced = connection.getContentLengthLong();
      if (announced >= 0 && announced != expected) throw new IOException("下载长度不匹配");
      if (partial.getParentFile().getUsableSpace() < expected + 1024 * 1024) throw new IOException("可用空间不足");
      try (InputStream input = connection.getInputStream(); RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
        output.seek(offset);
        byte[] buffer = new byte[65536];
        long completed = offset;
        int count;
        try {
          while ((count = input.read(buffer)) != -1) {
            networkBytes.addAndGet(count);
            cancellation.check();
            if (count > size - completed) throw new IOException("下载内容超过声明大小");
            output.write(buffer, 0, count); completed += count;
            progress.onProgress("downloading", completed, size, networkBytes.get(), "正在下载更新");
          }
        } finally { output.getFD().sync(); }
        if (completed != size) throw new IOException("下载尚未完成，可稍后继续");
      }
      ArtifactVerifier.verify(partial, size, digest, cancellation);
      return networkBytes.get();
    } catch (Cancellation.CancelledException cancelled) { throw cancelled; }
    catch (IOException failure) { cancellation.check(); throw failure; }
    catch (Exception failure) { cancellation.check(); throw new IOException("无法下载更新", failure); }
    finally { connection.disconnect(); cancellation.awaitClosures(); }
  }
  private HttpURLConnection open(String value, long offset, Cancellation cancellation) throws IOException {
    URL url = validate(value);
    for (int redirects = 0; redirects <= 5; redirects++) {
      cancellation.check();
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("Accept-Encoding", "identity");
      if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
      try (AutoCloseable ignored = cancellation.onCancel(connection::disconnect)) {
        int status = connection.getResponseCode();
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return connection;
        String location = connection.getHeaderField("Location");
        if (location == null) throw new IOException("下载跳转缺少地址");
        URL next = validate(new URL(url, location).toString());
        if ("https".equals(url.getProtocol()) && !"https".equals(next.getProtocol())) throw new IOException("下载地址降级");
        connection.disconnect(); url = next;
      } catch (IOException failure) { connection.disconnect(); cancellation.check(); throw failure; }
      catch (Exception failure) { connection.disconnect(); throw new IOException("下载连接失败", failure); }
    }
    throw new IOException("下载跳转过多");
  }
  static URL validate(String value) throws IOException {
    try {
      URI uri = new URI(value);
      String host = uri.getHost();
      boolean testLoopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
      if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && testLoopback)
          || host == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IOException("下载地址必须使用 HTTPS");
      return uri.toURL();
    } catch (java.net.URISyntaxException failure) { throw new IOException("下载地址无效", failure); }
  }
  private static void truncate(File file) throws IOException {
    try (RandomAccessFile output = new RandomAccessFile(file, "rw")) { output.setLength(0); output.getFD().sync(); }
  }
}
