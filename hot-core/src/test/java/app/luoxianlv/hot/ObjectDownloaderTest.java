package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ObjectDownloaderTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  final class Fixture {
    final byte[] bytes;
    final String hash, content;
    final DownloadBudget budget = new DownloadBudget(directory.newFolder());
    final ObjectDownloader downloader = new ObjectDownloader(directory.newFolder(), budget);
    final AtomicBoolean metered = new AtomicBoolean(true), cancelled = new AtomicBoolean();

    Fixture(int length) throws Exception {
      bytes = new byte[length];
      new Random(40).nextBytes(bytes);
      hash = HotSignatures.hash(bytes);
      content = HotSignatures.hash(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    long remaining() {
      return bytes.length - downloader.partial(hash).length();
    }

    ObjectDownloader.Response response(long offset, InputStream input) {
      return new ObjectDownloader.Response(
          offset, bytes.length, bytes.length - offset, input, () -> {});
    }

    File download(ObjectDownloader.Source source) throws Exception {
      return downloader.download(
          content, hash, bytes.length, source, metered::get, cancelled::get, this::remaining);
    }
  }

  @Test
  public void interruptedTransferResumesExactPrefixAndChargesRetries() throws Exception {
    Fixture f = new Fixture((2 << 20) + 777);
    int cut = 350000;
    InputStream failing =
        new InputStream() {
          int count;

          @Override
          public int read() throws IOException {
            throw new IOException("测试只使用块读取");
          }

          @Override
          public int read(byte[] bytes, int offset, int length) throws IOException {
            if (count == cut) throw new IOException("测试断线");
            int n = Math.min(length, cut - count);
            System.arraycopy(f.bytes, count, bytes, offset, n);
            count += n;
            return n;
          }
        };
    assertThrows(IOException.class, () -> f.download(offset -> f.response(offset, failing)));
    assertEquals(cut, f.downloader.partial(f.hash).length());
    AtomicLong resumed = new AtomicLong(-1);
    File complete =
        f.download(
            offset -> {
              resumed.set(offset);
              return f.response(
                  offset,
                  new ByteArrayInputStream(f.bytes, (int) offset, f.bytes.length - (int) offset));
            });
    assertEquals(cut, resumed.get());
    assertArrayEquals(f.bytes, Files.readAllBytes(complete.toPath()));
    assertEquals((1L << 20) + f.bytes.length - cut, f.budget.used(f.content));
    f.download(
        offset -> {
          throw new AssertionError("完整对象不应重新联网");
        });
  }

  @Test
  public void wrongRangeAndAlteredCompleteFileNeverBecomeExecutableObjects() throws Exception {
    Fixture f = new Fixture(1000);
    Files.write(f.downloader.partial(f.hash).toPath(), new byte[] {1, 2, 3});
    assertThrows(
        IllegalArgumentException.class,
        () -> f.download(offset -> f.response(0, new ByteArrayInputStream(f.bytes))));
    assertEquals(3, f.downloader.partial(f.hash).length());
    Files.write(f.downloader.partial(f.hash).toPath(), new byte[1000]);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            f.download(
                offset -> {
                  throw new AssertionError("损坏完整暂存应先被拒绝");
                }));
  }

  @Test
  public void wholeCandidateBudgetAndPlaybackCancellationPreventOpeningNetwork() throws Exception {
    Fixture f = new Fixture(1024);
    AtomicInteger calls = new AtomicInteger();
    ObjectDownloader.Source source =
        offset -> {
          calls.incrementAndGet();
          return f.response(offset, new ByteArrayInputStream(f.bytes));
        };
    assertThrows(
        DownloadBudget.Deferred.class,
        () ->
            f.downloader.download(
                f.content,
                f.hash,
                f.bytes.length,
                source,
                () -> true,
                () -> false,
                () -> 21L << 20));
    assertEquals(0, calls.get());
    f.cancelled.set(true);
    assertThrows(InterruptedIOException.class, () -> f.download(source));
    assertEquals(0, calls.get());
  }

  @Test
  public void changingToMeteredNetworkRechecksRemainingWholeCandidate() throws Exception {
    Fixture f = new Fixture(100000);
    f.metered.set(false);
    InputStream switchNetwork =
        new ByteArrayInputStream(f.bytes) {
          @Override
          public synchronized int read(byte[] bytes, int offset, int length) {
            int n = super.read(bytes, offset, length);
            f.metered.set(true);
            return n;
          }
        };
    assertThrows(
        DownloadBudget.Deferred.class,
        () ->
            f.downloader.download(
                f.content,
                f.hash,
                f.bytes.length,
                offset -> f.response(offset, switchNetwork),
                f.metered::get,
                () -> false,
                () -> 21L << 20));
    assertEquals(32768, f.downloader.partial(f.hash).length());
    assertEquals(0, f.budget.used(f.content));
  }
}
