package app.luoxianlv.hot.contract;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 业务仅传递请求及进度，宿主保留普通 APK 更新的信任、任务和安装职责。 */
public final class SharedUpdate {
  private static final AtomicReference<Bridge> CURRENT = new AtomicReference<>();

  private SharedUpdate() {}

  public static Bridge current() {
    return CURRENT.get();
  }

  public static AutoCloseable connect(Bridge bridge) {
    CURRENT.set(Objects.requireNonNull(bridge));
    return () -> CURRENT.compareAndSet(bridge, null);
  }

  /** 演奏重新开始时暂停当前任务；已认证断点保留，业务可在之后继续。 */
  public static final class DeferredException extends java.io.IOException {
    public DeferredException() {
      super("演奏优先，更新已暂停，可稍后继续");
    }
  }

  public interface Bridge {
    default boolean supportsIncremental() {
      return false;
    }

    /** 后台只读发现已经认证的待续任务，不启动下载；旧宿主默认没有待续任务。 */
    default Pending pending() throws Exception {
      return null;
    }

    /** 后台同步调用，直到任务真正退出才释放业务生命周期租约。 */
    Result prepare(Request request, Progress progress) throws Exception;

    void cancel();

    /** 从持久化的签名请求恢复，宿主仍须重新认证、检查基线和最终 APK。 */
    Result resume(Progress progress) throws Exception;
  }

  /** 目标名称来自签名说明，业务可据此显示显式的继续下载入口。 */
  public static final class Pending {
    public final Request request;
    public final String versionName;

    public Pending(Request request, String versionName) {
      this.request = Objects.requireNonNull(request);
      this.versionName = Objects.requireNonNull(versionName);
    }
  }

  public interface Progress {
    void onProgress(String phase, long completed, long total, long downloadedBytes, String detail);
  }

  public static final class Request {
    public final String signedEnvelope;
    public final List<String> fullUrls;
    public final long versionCode, size;
    public final String sha256;

    public Request(
        String signedEnvelope, List<String> fullUrls, long versionCode, String sha256, long size) {
      this.signedEnvelope = Objects.requireNonNull(signedEnvelope);
      this.fullUrls = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(fullUrls));
      this.versionCode = versionCode;
      this.sha256 = sha256;
      this.size = size;
    }
  }

  public static final class Result {
    public final File file;
    public final long versionCode, downloadedBytes;
    public final boolean usedDelta;
    public final String fallbackReason;

    public Result(
        File file,
        long versionCode,
        long downloadedBytes,
        boolean usedDelta,
        String fallbackReason) {
      this.file = file;
      this.versionCode = versionCode;
      this.downloadedBytes = downloadedBytes;
      this.usedDelta = usedDelta;
      this.fallbackReason = fallbackReason;
    }
  }
}
